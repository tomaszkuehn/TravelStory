package com.travel.slideshow.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** Beats fall at [offsetMs] + k·[periodMs] from the start of the track (constant tempo assumed). */
data class BeatGrid(val periodMs: Double, val offsetMs: Double)

/**
 * Rough tempo and phase detection: decodes the first [ANALYZE_SEC] seconds of a track, builds an onset
 * envelope (rises in loudness), picks the beat period by autocorrelation (biased towards ~120 BPM) and
 * refines period and phase with a comb over the whole excerpt. Tracks without a clear beat get no grid.
 * Results are cached in memory and in preferences, so each file is analysed once.
 */
object BeatAnalyzer {
    private const val ANALYZE_SEC = 45
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val PREFS = "beat_grids"
    private val NONE = BeatGrid(0.0, 0.0)
    private val grids = ConcurrentHashMap<String, BeatGrid>()
    private val busy = ConcurrentHashMap.newKeySet<String>()

    fun gridFor(uri: Uri): BeatGrid? = grids[uri.toString()]?.takeIf { it.periodMs > 0 }

    /** Blocking (decodes audio); call off the main thread. */
    fun analyze(context: Context, uri: Uri) {
        val key = uri.toString()
        if (grids.containsKey(key) || !busy.add(key)) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(key, null)
            if (saved != null) {
                grids[key] = parse(saved)
                return
            }
            // The generated fallback ambience has no steady beat.
            val g = if (uri.scheme == "file" && uri.path?.contains("/ambient/") == true) null
            else runCatching { detect(context, uri) }.getOrNull()
            grids[key] = g ?: NONE
            prefs.edit().putString(key, g?.let { "${it.periodMs};${it.offsetMs}" } ?: "-").apply()
        } finally {
            busy.remove(key)
        }
    }

    private fun parse(s: String): BeatGrid {
        val parts = s.split(';')
        if (parts.size != 2) return NONE
        val p = parts[0].toDoubleOrNull() ?: return NONE
        val o = parts[1].toDoubleOrNull() ?: return NONE
        return BeatGrid(p, o)
    }

    private class Envelope(val energy: DoubleArray, val startMs: Double, val frameMs: Double)

    private fun detect(context: Context, uri: Uri): BeatGrid? {
        val env = decodeEnergy(context, uri) ?: return null
        val frameMs = env.frameMs
        val n = env.energy.size
        if (n * frameMs < 10_000) return null

        // Onset strength: rise in log energy, minus its local mean so steady loudness does not count.
        val rise = DoubleArray(n)
        var prev = ln(env.energy[0] + 1e-10)
        for (i in 1 until n) {
            val cur = ln(env.energy[i] + 1e-10)
            rise[i] = max(0.0, cur - prev)
            prev = cur
        }
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + rise[i]
        val win = (250 / frameMs).toInt().coerceAtLeast(1)
        val onset = DoubleArray(n) { i ->
            val a = maxOf(0, i - win)
            val b = minOf(n, i + win + 1)
            max(0.0, rise[i] - (prefix[b] - prefix[a]) / (b - a))
        }
        val mean = onset.average()
        if (mean <= 0.0) return null

        // Coarse beat period: autocorrelation peak, weighted towards common tempos.
        val minLag = (60_000.0 / MAX_BPM / frameMs).toInt().coerceAtLeast(2)
        val maxLag = (60_000.0 / MIN_BPM / frameMs).toInt() + 1
        if (maxLag + 2 >= n) return null
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until n) s += onset[i] * onset[i - lag]
            s /= (n - lag)
            val bpm = 60_000.0 / (lag * frameMs)
            val octaves = ln(bpm / 120.0) / ln(2.0)
            val score = s * exp(-0.5 * (octaves / 0.8) * (octaves / 0.8))
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null

        // Fine period and phase: the comb that collects the most onset energy over the excerpt.
        var bestP = bestLag.toDouble()
        var bestPhase = 0.0
        var bestComb = -1.0
        var p = bestLag - 1.0
        while (p <= bestLag + 1.0) {
            var ph = 0.0
            while (ph < p) {
                var s = 0.0
                var cnt = 0
                var t = ph
                while (t < n - 1) {
                    val i = t.roundToInt()
                    s += maxOf(onset[i], 0.5 * onset[maxOf(0, i - 1)], 0.5 * onset[minOf(n - 1, i + 1)])
                    cnt++
                    t += p
                }
                if (cnt > 0 && s / cnt > bestComb) {
                    bestComb = s / cnt
                    bestP = p
                    bestPhase = ph
                }
                ph += 1.0
            }
            p += 0.02
        }
        // No clear pulse: beats would land at random, so leave the timing alone.
        if (bestComb < mean * 1.6) return null
        return BeatGrid(bestP * frameMs, env.startMs + bestPhase * frameMs)
    }

    /** Mean square loudness of ~10 ms frames, mono mix. */
    private fun decodeEnergy(context: Context, uri: Uri): Envelope? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val out = ArrayList<Double>(ANALYZE_SEC * 100)
            var acc = 0.0
            var accN = 0
            var startMs = -1.0
            var frameMs = 10.0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var idle = 0
            val limitUs = ANALYZE_SEC * 1_000_000L
            while (true) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || ex.sampleTime > limitUs) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    idle = 0
                    if (startMs < 0 && info.size > 0) startMs = info.presentationTimeUs / 1000.0
                    val hop = maxOf(1, rate / 100)
                    frameMs = hop * 1000.0 / rate
                    val ch = maxOf(1, channels)
                    val bb = c.getOutputBuffer(oi)!!
                    bb.position(info.offset)
                    bb.limit(info.offset + info.size)
                    val ordered = bb.order(ByteOrder.nativeOrder())
                    if (floatPcm) {
                        val fb = ordered.asFloatBuffer()
                        while (fb.remaining() >= ch) {
                            var s = 0.0
                            repeat(ch) { s += fb.get() }
                            s /= ch
                            acc += s * s
                            if (++accN >= hop) { out.add(acc / accN); acc = 0.0; accN = 0 }
                        }
                    } else {
                        val sb = ordered.asShortBuffer()
                        while (sb.remaining() >= ch) {
                            var s = 0.0
                            repeat(ch) { s += sb.get() }
                            s /= ch * 32768.0
                            acc += s * s
                            if (++accN >= hop) { out.add(acc / accN); acc = 0.0; accN = 0 }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (out.size * frameMs >= ANALYZE_SEC * 1000.0) break
                } else if (inDone && ++idle > 200) {
                    break
                }
            }
            if (out.isEmpty()) return null
            return Envelope(out.toDoubleArray(), max(0.0, startMs), frameMs)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
