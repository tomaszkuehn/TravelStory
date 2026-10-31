package com.travel.slideshow.media

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Records the screen and the app's own sound into an MP4 (H.264 + AAC, what YouTube recommends).
 * The picture comes from a virtual display mirroring the screen, the sound from playback capture
 * limited to this app, so neither the microphone nor other apps end up in the film.
 *
 * Paused stretches are cut out: samples inside them are dropped and later timestamps moved back.
 * All times are microseconds on the System.nanoTime clock, which the encoder surface also uses.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ScreenRecorder(
    private val projection: MediaProjection,
    fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitRate: Int,
    private val captureAudio: Boolean,
) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var display: VirtualDisplay? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // Guarded by lock.
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var wroteVideo = false
    private var startUs = 0L
    private var stopUs = Long.MAX_VALUE
    private val gaps = ArrayList<LongArray>()
    private var pauseFromUs = -1L
    private var needKey = true
    private var lastKeyRequestUs = 0L
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    @Volatile private var stopping = false

    /** True when the app's sound is being recorded too. */
    val hasAudio: Boolean get() = audioCodec != null

    fun start() {
        // The first moment still shows the screen the recording was started from.
        startUs = nowUs() + START_SKIP_US
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen sends no frames; repeating the last one keeps a steady frame rate.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / FPS)
        }
        vc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = vc.createInputSurface()
        vc.start()
        videoCodec = vc
        if (captureAudio) setupAudio()
        display = projection.createVirtualDisplay(
            "SlideshowExport", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )
        videoThread = thread(name = "rec-video") { drainVideo() }
        if (audioCodec != null) audioThread = thread(name = "rec-audio") { runAudio() }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio() {
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(Process.myUid())
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf * 4, RATE * BYTES_PER_FRAME / 4))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
            }
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            rec.startRecording()
            audioRecord = rec
            audioCodec = ac
        } catch (e: Exception) {
            // No permission or no capture support: the film is silent.
            runCatching { audioRecord?.release() }
            audioRecord = null
            audioCodec = null
        }
    }

    fun setPaused(paused: Boolean) = synchronized(lock) {
        val now = nowUs()
        if (paused) {
            if (pauseFromUs < 0) pauseFromUs = now
        } else if (pauseFromUs >= 0) {
            // A little more is cut after resuming, so the vanishing controls stay out of the film.
            gaps.add(longArrayOf(maxOf(pauseFromUs, startUs), now + RESUME_SKIP_US))
            pauseFromUs = -1
            needKey = true
        }
    }

    /** Stops and finishes the file; true when it holds any picture. Blocking. */
    fun stop(): Boolean {
        synchronized(lock) {
            if (stopping) return false
            stopping = true
            stopUs = nowUs()
        }
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { videoThread?.join(4000) }
        runCatching { audioThread?.join(4000) }
        runCatching { display?.release() }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        val ok = synchronized(lock) { muxerStarted && wroteVideo }
        val stopped = runCatching { if (muxerStarted) muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        return ok && stopped
    }

    private fun drainVideo() {
        val c = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val i = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    videoTrack = muxer.addTrack(c.outputFormat)
                    maybeStartMuxer()
                }
            } else if (i >= 0) {
                idle = 0
                val buf = c.getOutputBuffer(i)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null) writeVideo(c, buf, info)
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (stopping && ++idle > 200) {
                break
            }
        }
    }

    private fun writeVideo(c: MediaCodec, buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (!muxerStarted) return
        val out = mapPts(info.presentationTimeUs) ?: return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (needKey && !key) {
            // After a cut the film must continue from a key frame.
            val now = nowUs()
            if (now - lastKeyRequestUs > 300_000) {
                lastKeyRequestUs = now
                runCatching { c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            }
            return
        }
        if (out <= lastVideoPts) return
        needKey = false
        lastVideoPts = out
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val copy = MediaCodec.BufferInfo().apply { set(0, info.size, out, info.flags) }
        muxer.writeSampleData(videoTrack, buf.slice(), copy)
        wroteVideo = true
    }

    private fun runAudio() {
        val rec = audioRecord ?: return
        val c = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        var baseUs = -1L
        var frames = 0L
        var lastPts = 0L
        var eosSent = false
        var idle = 0
        try {
            while (true) {
                if (!eosSent) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        ib.clear()
                        if (stopping) {
                            c.queueInputBuffer(ii, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val n = rec.read(ib, minOf(ib.capacity(), CHUNK_BYTES))
                            if (n > 0) {
                                val got = n / BYTES_PER_FRAME
                                val actual = nowUs() - got * 1_000_000L / RATE
                                if (baseUs < 0) baseUs = actual
                                // Capture can stall when nothing plays; keep the sound in step with the clock.
                                val expected = baseUs + frames * 1_000_000L / RATE
                                if (actual - expected > 100_000) baseUs += actual - expected
                                lastPts = baseUs + frames * 1_000_000L / RATE
                                frames += got
                                c.queueInputBuffer(ii, 0, n, lastPts, 0)
                            } else {
                                c.queueInputBuffer(ii, 0, 0, lastPts, 0)
                            }
                        }
                    }
                }
                var done = false
                while (true) {
                    val oi = c.dequeueOutputBuffer(info, 0)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) {
                            audioTrack = muxer.addTrack(c.outputFormat)
                            maybeStartMuxer()
                        }
                    } else if (oi >= 0) {
                        idle = 0
                        val buf = c.getOutputBuffer(oi)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null) writeAudio(buf, info)
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    } else break
                }
                if (done) break
                if (eosSent && ++idle > 300) break
            }
        } catch (e: IllegalStateException) {
            // Released under us while stopping.
        }
    }

    private fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (!muxerStarted) return
        val out = mapPts(info.presentationTimeUs) ?: return
        if (out <= lastAudioPts) return
        lastAudioPts = out
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val copy = MediaCodec.BufferInfo().apply { set(0, info.size, out, info.flags) }
        muxer.writeSampleData(audioTrack, buf.slice(), copy)
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0 || (audioCodec != null && audioTrack < 0)) return
        muxer.start()
        muxerStarted = true
        needKey = true
    }

    /** Film time for clock time [t], or null when [t] falls before the start, after the stop or in a pause. */
    private fun mapPts(t: Long): Long? {
        if (t < startUs || t > stopUs) return null
        if (pauseFromUs in 0..t) return null
        var shift = 0L
        for (g in gaps) {
            if (t >= g[0] && t < g[1]) return null
            if (t >= g[1]) shift += g[1] - g[0]
        }
        return t - startUs - shift
    }

    private fun nowUs() = System.nanoTime() / 1000

    private companion object {
        const val FPS = 30
        const val RATE = 48_000
        const val BYTES_PER_FRAME = 4
        const val CHUNK_BYTES = 4096
        const val START_SKIP_US = 250_000L
        const val RESUME_SKIP_US = 350_000L
    }
}
