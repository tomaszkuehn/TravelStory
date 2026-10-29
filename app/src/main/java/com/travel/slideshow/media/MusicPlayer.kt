package com.travel.slideshow.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

class MusicPlayer(private val context: Context) {

    private var playlist: List<Uri> = emptyList()
    private var idx = 0
    private var currentPlayer: MediaPlayer? = null
    private var volumeFraction = 0.8f
    private var paused = false

    fun play(tracks: List<Uri>) {
        stop()
        if (tracks.isEmpty()) return
        playlist = tracks
        idx = 0
        startCurrent()
    }

    private fun startCurrent() {
        if (playlist.isEmpty()) return
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(context, playlist[idx])
            p.isLooping = false
            p.setOnCompletionListener {
                if (playlist.isEmpty()) return@setOnCompletionListener
                idx = (idx + 1) % playlist.size
                startCurrent()
            }
            p.prepare()
            val f = volumeFraction
            p.setVolume(f, f)
            p.start()
            currentPlayer = p
            paused = false
        } catch (e: Exception) {
            if (idx + 1 < playlist.size) {
                idx++
                startCurrent()
            }
        }
    }

    fun pause() {
        runCatching { currentPlayer?.pause() }
        paused = true
    }

    fun resume() {
        runCatching { currentPlayer?.start() }
        paused = false
    }

    fun setVolume(fraction: Float) {
        volumeFraction = fraction
        runCatching { currentPlayer?.setVolume(fraction, fraction) }
    }

    fun stop() {
        currentPlayer?.let { p ->
            runCatching {
                p.setOnCompletionListener(null)
                p.stop()
                p.release()
            }
        }
        currentPlayer = null
        playlist = emptyList()
        idx = 0
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
        val out = File(dir, "amb_$safe.wav")
        if (out.exists() && out.length() > 10_000) return out
        val seed = (iso3 + countryName).hashCode().toLong()
        writeWav(out, generate(seed, DURATION_SEC))
        return out
    }

    internal fun generate(seed: Long, seconds: Int): FloatArray {
        val rng = java.util.Random(seed)
        val n = seconds * RATE
        val out = FloatArray(n)
        val rootMidi = 41 + rng.nextInt(12)
        val minor = rng.nextBoolean()
        val scale = if (minor) intArrayOf(0, 3, 5, 7, 10) else intArrayOf(0, 2, 4, 7, 9)
        val chords = listOf(
            intArrayOf(0, 2, 4), intArrayOf(3, 5, 0), intArrayOf(4, 1, 3), intArrayOf(0, 4, 2)
        )
        val chordLen = 12.0
        val chordCount = (seconds / chordLen).toInt() + 1
        val chordSeq = Array(chordCount) { chords[rng.nextInt(chords.size)] }

        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val ci = (t / chordLen).toInt()
            val ct = t - ci * chordLen
            val chord = chordSeq[ci]
            var v = 0.0
            for (k in chord.indices) {
                val midi = rootMidi + scale[chord[k] % scale.size] - 12
                val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                val env = kotlin.math.exp(-(0.06 / chordLen) * ct) * (0.55 - 0.27 * ci)
                v += 0.11 * env * kotlin.math.sin(2 * Math.PI * f * t)
            }
            out[i] = v.toFloat()
        }
        var pitchIdx = 2
        var tNext = rng.nextDouble() * 1.2
        while (tNext < seconds - 1.0) {
            val step = scale[rng.nextInt(scale.size)] + if (rng.nextDouble() < 0.25) 7 else 0
            pitchIdx = ((pitchIdx + step) % scale.size + scale.size) % scale.size
            val midi = rootMidi + scale[pitchIdx] + if (rng.nextDouble() < 0.5) 12 else 0
            val f = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val start = (tNext * RATE).toInt()
            val len = (0.9 * RATE).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                if (start + i >= n) break
                val env = kotlin.math.exp(-5.5 * t) * (1 - kotlin.math.exp(-140 * t))
                val s = 0.24 * env * kotlin.math.sin(2 * Math.PI * f * t)
                out[start + i] = ((out[start + i] + s) / 1.6).toFloat()
            }
            tNext += 0.55 + rng.nextDouble() * 1.6
        }
        val fade = 3 * RATE
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