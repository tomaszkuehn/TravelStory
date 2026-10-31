package com.travel.slideshow.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

sealed interface ExportState {
    data object Idle : ExportState
    data object Recording : ExportState
    data object Saving : ExportState
    data class Done(val uri: Uri, val name: String, val silent: Boolean) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Film export: the show is recorded from the screen while it plays and saved as an MP4 in
 * Movies/Podróżnicze Slideshow, ready to share to YouTube.
 */
object VideoExport {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state

    /** Screen capture with app sound needs Android 10. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val main = Handler(Looper.getMainLooper())
    private var recorder: ScreenRecorder? = null
    private var projection: MediaProjection? = null
    private var pfd: ParcelFileDescriptor? = null
    private var target: Uri? = null
    private var name = ""
    private var service: Service? = null

    val isRecording: Boolean get() = _state.value is ExportState.Recording

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun begin(svc: Service, resultCode: Int, data: Intent, title: String) {
        if (recorder != null) return
        service = svc
        val cr = svc.contentResolver
        try {
            val mpm = svc.getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("brak zgody")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                // Stopped from the system (status bar chip, lock screen): keep what was recorded.
                override fun onStop() { main.post { stop() } }
            }, main)

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            svc.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
            val long = maxOf(dm.widthPixels, dm.heightPixels)
            val short = minOf(dm.widthPixels, dm.heightPixels)
            // Full HD at most, keeping the screen's shape (YouTube handles any aspect ratio).
            val scale = minOf(1.0, 1920.0 / long, 1080.0 / short)
            val w = align16(dm.widthPixels * scale)
            val h = align16(dm.heightPixels * scale)
            val bitRate = (12_000_000L * w * h / (1920 * 1080)).toInt().coerceAtLeast(4_000_000)

            name = fileName(title)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("nie można utworzyć pliku")
            target = uri
            val fd = cr.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("nie można otworzyć pliku")
            pfd = fd
            val audio = ContextCompat.checkSelfPermission(svc, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val rec = ScreenRecorder(proj, fd.fileDescriptor, w, h, dm.densityDpi, bitRate, audio)
            rec.start()
            recorder = rec
            _state.value = ExportState.Recording
        } catch (e: Exception) {
            cleanup(svc, keep = false)
            _state.value = ExportState.Failed("Nie udało się rozpocząć nagrywania: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun setPaused(paused: Boolean) {
        recorder?.setPaused(paused)
    }

    /** Ends the recording and saves the film in the background. */
    fun stop() {
        val rec = recorder ?: return
        recorder = null
        val svc = service ?: return
        _state.value = ExportState.Saving
        val silent = !rec.hasAudio
        thread(name = "rec-finish") {
            val ok = rec.stop()
            val uri = target
            cleanup(svc, keep = ok)
            _state.value = if (ok && uri != null) ExportState.Done(uri, name, silent)
            else ExportState.Failed("Nagranie jest puste — nic nie zapisano.")
        }
    }

    /** Back to idle after the result was shown. */
    fun dismiss() {
        val s = _state.value
        if (s is ExportState.Done || s is ExportState.Failed) _state.value = ExportState.Idle
    }

    private fun cleanup(svc: Service, keep: Boolean) {
        runCatching { projection?.stop() }
        projection = null
        runCatching { pfd?.close() }
        pfd = null
        target?.let { uri ->
            val cr = svc.contentResolver
            runCatching {
                if (keep) cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else cr.delete(uri, null, null)
            }
        }
        target = null
        main.post {
            @Suppress("DEPRECATION")
            runCatching { svc.stopForeground(true) }
            svc.stopSelf()
        }
        service = null
    }

    private fun align16(v: Double): Int = maxOf(16, (v / 16).roundToInt() * 16)

    private fun fileName(title: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().replace(Regex("\\s+"), " ").take(60)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return (if (clean.isEmpty()) "Pokaz" else clean) + " " + stamp + ".mp4"
    }

    const val FOLDER = "Podróżnicze Slideshow"
}

/** Foreground service required while the screen is captured. */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VideoExport.stop()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        VideoExport.begin(this, intent.getIntExtra(EXTRA_CODE, 0), data, intent.getStringExtra(EXTRA_TITLE) ?: "")
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nagrywanie wideo", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Nagrywanie pokazu do wideo")
            .setContentText("Zapis zakończy się sam na końcu pokazu")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Zakończ i zapisz", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "video_export"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, resultCode: Int, data: Intent, title: String) {
            val i = Intent(context, RecordService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
