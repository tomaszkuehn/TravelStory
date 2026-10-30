package com.travel.slideshow.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/**
 * Must be used from the main thread only: MediaPlayer is not thread-safe and its
 * callbacks are delivered on the thread that created it.
 */
class MusicPlayer(private val context: Context) {

    private var playlist: List<Uri> = emptyList()
    private var idx = 0
    private var currentPlayer: MediaPlayer? = null
    private var prepared = false
    private var failures = 0
    private var volumeFraction = 0.8f
    private var paused = false

    // Effective volume = volumeFraction * duckLevel * gain; ramps are advanced by [tick].
    private var gain = 1f
    private var fadingPlayer: MediaPlayer? = null
    private var fadingGain = 0f
    private var duckLevel = 1f
    private var duckTarget = 1f
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tickRunnable = Runnable { tick() }

    /** Starts [tracks]; with [crossfade] the previous music fades out while the new one fades in. */
    fun play(tracks: List<Uri>, crossfade: Boolean = true) {
        val old = currentPlayer
        if (crossfade && old != null && prepared) {
            releaseFading()
            fadingPlayer = old.also { detach(it) }
            fadingGain = gain
            currentPlayer = null
            prepared = false
        }
        stopPlaylist()
        if (tracks.isEmpty()) return
        playlist = tracks
        idx = 0
        failures = 0
        startCurrent(fadeIn = crossfade)
        ensureTicking()
    }

    /** Lowers music under a video clip's own sound. */
    fun setDucked(ducked: Boolean) {
        duckTarget = if (ducked) DUCK_LEVEL else 1f
        ensureTicking()
    }

    private fun ensureTicking() {
        if (ticking || paused) return
        ticking = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun tick() {
        ticking = false
        if (paused) return
        var busy = false
        if (duckLevel != duckTarget) {
            val step = TICK_MS.toFloat() / DUCK_MS
            duckLevel = if (duckLevel < duckTarget) minOf(duckTarget, duckLevel + step) else maxOf(duckTarget, duckLevel - step)
            busy = true
        }
        if (prepared && gain < 1f) {
            gain = minOf(1f, gain + TICK_MS.toFloat() / FADE_MS)
            busy = true
        } else if (!prepared && currentPlayer != null && gain < 1f) {
            busy = true // still preparing; keep the clock alive for the fade-in
        }
        fadingPlayer?.let {
            fadingGain -= TICK_MS.toFloat() / FADE_MS
            if (fadingGain <= 0f) releaseFading() else busy = true
        }
        applyVolumes()
        if (busy) ensureTicking()
    }

    private fun applyVolumes() {
        val base = volumeFraction * duckLevel
        if (prepared) runCatching { currentPlayer?.setVolume(base * gain, base * gain) }
        fadingPlayer?.let { p -> runCatching { p.setVolume(base * fadingGain, base * fadingGain) } }
    }

    private fun startCurrent(fadeIn: Boolean = false) {
        releaseCurrent()
        if (playlist.isEmpty()) return
        gain = if (fadeIn) 0f else 1f
        val p = MediaPlayer()
        currentPlayer = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(context, playlist[idx])
            p.isLooping = playlist.size <= 1
            p.setOnPreparedListener { mp ->
                if (mp !== currentPlayer) return@setOnPreparedListener
                prepared = true
                failures = 0
                applyVolumes()
                if (!paused) mp.start()
                ensureTicking()
            }
            p.setOnCompletionListener { mp ->
                if (mp !== currentPlayer || playlist.isEmpty()) return@setOnCompletionListener
                idx = (idx + 1) % playlist.size
                startCurrent()
            }
            p.setOnErrorListener { mp, _, _ ->
                if (mp === currentPlayer) skipBroken()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            skipBroken()
        }
    }

    private fun skipBroken() {
        failures++
        if (failures >= playlist.size) {
            stop()
            return
        }
        idx = (idx + 1) % playlist.size
        startCurrent()
    }

    private fun detach(p: MediaPlayer) {
        runCatching {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
        }
    }

    private fun releaseCurrent() {
        currentPlayer?.let { p ->
            detach(p)
            runCatching { p.release() }
        }
        currentPlayer = null
        prepared = false
    }

    private fun releaseFading() {
        fadingPlayer?.let { p -> runCatching { p.release() } }
        fadingPlayer = null
    }

    /** Pause state survives [play]/[stop], so a track started while the show is paused waits for [resume]. */
    fun pause() {
        paused = true
        // A half-finished fade-out is not worth resuming later.
        releaseFading()
        if (prepared) runCatching { currentPlayer?.pause() }
    }

    fun resume() {
        paused = false
        if (prepared) runCatching { currentPlayer?.start() }
        ensureTicking()
    }

    fun setVolume(fraction: Float) {
        volumeFraction = fraction
        applyVolumes()
    }

    private fun stopPlaylist() {
        releaseCurrent()
        playlist = emptyList()
        idx = 0
    }

    fun stop() {
        releaseFading()
        stopPlaylist()
    }

    companion object {
        private const val TICK_MS = 40L
        private const val FADE_MS = 1800
        private const val DUCK_MS = 600
        private const val DUCK_LEVEL = 0.2f
    }
}

class MusicResolver(private val context: Context) {

    fun tracksFor(iso3: String, countryName: String, musicRoot: Uri?): List<Uri> {
        val root = musicRoot ?: return fallbackFor(iso3, countryName)
        val wanted = buildSet {
            val iso2 = com.travel.slideshow.data.geo.CountryInfo.iso2(iso3)
            add(iso3.lowercase(Locale.US))
            if (iso2.isNotEmpty()) add(iso2.lowercase(Locale.US))
            add(countryName.lowercase(Locale.US))
        }
        val audioDirs = mutableListOf<String?>()
        try {
            val treeId = DocumentsContract.getTreeDocumentId(root)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, treeId)
            context.contentResolver.query(children, DIR_PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)?.lowercase(Locale.US) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (!isDir) continue
                    if (name in wanted) audioDirs.add(id)
                }
            }
        } catch (e: Exception) {
            return fallbackFor(iso3, countryName)
        }
        val tracks = mutableListOf<Uri>()
        for (dirId in audioDirs) if (dirId != null) tracks.addAll(audioFiles(root, dirId))
        if (tracks.isEmpty()) {
            try {
                val treeId = DocumentsContract.getTreeDocumentId(root)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, treeId)
                context.contentResolver.query(children, DIR_PROJECTION, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1)?.lowercase(Locale.US) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && name in setOf("_default", "default")) {
                            tracks.addAll(audioFiles(root, id))
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        tracks.shuffle()
        return if (tracks.isNotEmpty()) tracks else fallbackFor(iso3, countryName)
    }

    private fun audioFiles(root: Uri, dirId: String): List<Uri> {
        val out = mutableListOf<Uri>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, dirId)
            context.contentResolver.query(children, FILE_PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val low = name.lowercase(Locale.US)
                    if (AUDIO_EXT.any { low.endsWith(it) }) {
                        out.add(DocumentsContract.buildDocumentUriUsingTree(root, id))
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun fallbackFor(iso3: String, countryName: String): List<Uri> {
        return try {
            val file = AmbienceSynth.ensureTrack(context, iso3, countryName)
            listOf(Uri.fromFile(file))
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private val AUDIO_EXT = listOf(".mp3", ".ogg", ".wav", ".m4a", ".flac", ".aac")
        private val DIR_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        private val FILE_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

object AmbienceSynth {

    private const val RATE = 22050
    private const val DURATION_SEC = 96

    fun ensureTrack(context: Context, iso3: String, countryName: String): File {
        val dir = File(context.filesDir, "ambient").apply { mkdirs() }
        val safe = (iso3 + "_" + countryName)
            .replace(Regex("[^A-Za-z0-9]"), "_")
            .lowercase(Locale.US)
            .take(40)
        val out = File(dir, "amb2_$safe.wav")
        if (out.exists() && out.length() > 10_000) return out
        val seed = (iso3 + countryName).hashCode().toLong()
        writeWav(out, generate(seed, DURATION_SEC))
        return out
    }

    internal fun generate(seed: Long, seconds: Int): FloatArray {
        val rng = java.util.Random(seed)
        val n = seconds * RATE
        val out = FloatArray(n)

        val rootMidi = 45 + rng.nextInt(10)
        val minor = rng.nextBoolean()
        val scale = if (minor) intArrayOf(0, 3, 5, 7, 10) else intArrayOf(0, 2, 4, 7, 9)
        val ext = IntArray(10) { if (it < 5) scale[it] else scale[it - 5] + 12 }
        val progressions = listOf(
            intArrayOf(0, 3, 1, 4), intArrayOf(0, 2, 3, 1), intArrayOf(0, 1, 3, 2)
        )
        val prog = progressions[rng.nextInt(progressions.size)]
        val chordLen = 12.0
        val fadeIn = 2.0
        val fadeOut = 2.0
        val chordCount = (seconds / chordLen).toInt() + 1

        fun chordTone(midi: Int, t: Double, ct: Double, cl: Double, amp: Double): Double {
            val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val att = if (ct < fadeIn) 0.5 * (1 - kotlin.math.cos(Math.PI * ct / fadeIn)) else 1.0
            val rel = if (cl - ct < fadeOut) 0.5 * (1 - kotlin.math.cos(Math.PI * (cl - ct) / fadeOut)) else 1.0
            val env = amp * att * rel
            val wob = 1.0 + 0.0012 * Math.sin(2 * Math.PI * 0.19 * t)
            return env * (kotlin.math.sin(2 * Math.PI * f * wob * t) +
                0.28 * kotlin.math.sin(2 * Math.PI * 2 * f * wob * t))
        }

        for (ci in 0 until chordCount) {
            val startS = ci * chordLen
            val endS = minOf(seconds.toDouble(), startS + chordLen)
            if (startS >= seconds) break
            val chordDeg = ext[prog[ci % prog.size]]
            val startI = (startS * RATE).toInt()
            val endI = (endS * RATE).toInt()
            val ctLen = endS - startS
            for (i in startI until endI) {
                val t = i.toDouble() / RATE
                val ct = t - startS
                var v = 0.0
                v += chordTone(rootMidi - 12, t, ct, ctLen, 0.05)
                v += chordTone(rootMidi + chordDeg, t, ct, ctLen, 0.075)
                v += chordTone(rootMidi + ext[(prog[ci % prog.size] + 2) % ext.size] + 12, t, ct, ctLen, 0.045)
                out[i] = (out[i] + v).toFloat()
            }
        }

        var deg = 2
        var beat = 1.2 + rng.nextDouble() * 0.8
        var tNext = 1.5 + rng.nextDouble()
        while (tNext < seconds - 2.0) {
            if (rng.nextDouble() > 0.32) {
                deg = (deg + (rng.nextInt(3) - 1)).coerceIn(0, ext.size - 1)
                val midi = rootMidi + ext[deg] + 12
                val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                val start = (tNext * RATE).toInt()
                val len = (2.2 * RATE).toInt()
                for (i in 0 until len) {
                    if (start + i >= n) break
                    val dt = i.toDouble() / RATE
                    val env = kotlin.math.exp(-3.4 * dt) * (1 - kotlin.math.exp(-55 * dt))
                    val s = 0.14 * env * (
                        kotlin.math.sin(2 * Math.PI * f * dt) +
                            0.15 * kotlin.math.sin(2 * Math.PI * 2 * f * dt)
                        )
                    out[start + i] = out[start + i] + s.toFloat()
                }
            }
            tNext += beat * (1.0 + (rng.nextInt(3) - 1) * 0.5)
        }

        var peak = 1e-6
        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(out[i].toDouble()))
        val gain = (0.72 / peak).coerceAtMost(4.0)
        for (i in 0 until n) {
            out[i] = (out[i] * gain).coerceIn(-0.98, 0.98).toFloat()
        }
        val fade = (1.5 * RATE).toInt()
        for (i in 0 until fade) {
            val g = i.toDouble() / fade
            out[i] = (out[i] * g).toFloat()
            out[n - 1 - i] = (out[n - 1 - i] * g).toFloat()
        }
        return out
    }

    private fun writeWav(file: File, samples: FloatArray) {
        java.io.DataOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(file))
        ).use { d ->
            val dataLen = samples.size * 2
            d.writeBytes("RIFF"); writeLE(d, 36 + dataLen); d.writeBytes("WAVE")
            d.writeBytes("fmt "); writeLE(d, 16)
            d.write(byteArrayOf(1, 0, 1, 0))
            writeLE(d, RATE); writeLE(d, RATE * 2)
            d.write(byteArrayOf(2, 0, 16, 0))
            d.writeBytes("data"); writeLE(d, dataLen)
            val buf = ByteArray(2)
            for (s in samples) {
                val v = s.coerceIn(-0.999f, 0.999f)
                val iv = (v * Short.MAX_VALUE).toInt()
                buf[0] = (iv and 0xFF).toByte()
                buf[1] = ((iv shr 8) and 0xFF).toByte()
                d.write(buf)
            }
        }
    }

    private fun writeLE(d: java.io.DataOutputStream, v: Int) {
        d.write(v and 0xFF)
        d.write((v shr 8) and 0xFF)
        d.write((v shr 16) and 0xFF)
        d.write((v shr 24) and 0xFF)
    }
}