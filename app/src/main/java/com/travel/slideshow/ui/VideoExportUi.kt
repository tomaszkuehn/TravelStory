package com.travel.slideshow.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.travel.slideshow.media.ExportState
import com.travel.slideshow.media.RecordService
import com.travel.slideshow.media.VideoExport

/**
 * Starts the film export: asks for the sound permission (once), then for screen capture, then hands
 * over to the recording service. Null on Android versions that cannot record app sound.
 */
@Composable
fun rememberVideoExport(title: String): (() -> Unit)? {
    if (!VideoExport.supported) return null
    val context = LocalContext.current
    val currentTitle by rememberUpdatedState(title)
    val mpm = context.getSystemService(MediaProjectionManager::class.java)
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) RecordService.start(context, r.resultCode, data, currentTitle)
    }
    // Without the permission the film is just silent.
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        capture.launch(mpm.createScreenCaptureIntent())
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            capture.launch(mpm.createScreenCaptureIntent())
        } else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** Keeps the screen orientation while recording and reports how saving went. */
@Composable
fun VideoExportStatus() {
    val state by VideoExport.state.collectAsState()
    val context = LocalContext.current
    val recording = state is ExportState.Recording
    DisposableEffect(recording) {
        val activity = context as? Activity
        // Turning the device mid-recording would squeeze the picture.
        if (recording) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { if (recording) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    when (val s = state) {
        is ExportState.Saving -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Zapisywanie wideo…") },
            text = { Text("Chwilę to potrwa.") },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = { VideoExport.dismiss() },
            title = { Text("Wideo zapisane") },
            text = {
                Text(
                    "„${s.name}” w folderze Filmy/${VideoExport.FOLDER}. Format MP4 (H.264 + AAC) — gotowy do wysłania na YouTube." +
                        if (s.silent) "\n\nBez dźwięku — brak zgody na nagrywanie dźwięku." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, s.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Udostępnij wideo"))
                    VideoExport.dismiss()
                }) { Text("Udostępnij (YouTube…)") }
            },
            dismissButton = { TextButton(onClick = { VideoExport.dismiss() }) { Text("OK") } },
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = { VideoExport.dismiss() },
            title = { Text("Eksport wideo") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { VideoExport.dismiss() }) { Text("OK") } },
        )
        else -> {}
    }
}
