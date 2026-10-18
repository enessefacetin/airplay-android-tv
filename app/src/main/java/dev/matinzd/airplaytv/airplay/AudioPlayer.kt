package dev.matinzd.airplaytv.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Plays AirPlay audio through an AudioTrack. ALAC/PCM arrive already decoded;
 * AAC (AAC-ELD while mirroring) is decoded with MediaCodec.
 *
 * Every packet is played at `sender timestamp + sync latency` (see [AvSync]):
 * if a packet would be heard too early, silence is inserted first; if it would
 * be heard too late, it's dropped. That keeps audio on the same timeline as the
 * video and absorbs clock drift between the iPhone and the TV.
 *
 * Everything runs on one audio thread so the network threads never block.
 */
class AudioPlayer(private val sync: AvSync) {
    private val thread = HandlerThread("airplay-audio", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val queued = AtomicInteger()

    private var track: AudioTrack? = null
    private var aac: MediaCodec? = null
    private var aacPtsUs = 0L
    private var samplesPerFrame = 1024
    private var gain = 1f
    private val info = MediaCodec.BufferInfo()
    private var pcmScratch = ByteArray(0)

    // Where the AudioTrack is: frames written so far, and its output pipeline latency.
    private var framesWritten = 0L
    private var pipelineNs = -1L
    private val timestamp = AudioTimestamp()
    private val stats = Stats()

    fun configure(ct: Int, samplesPerFrame: Int, lowLatency: Boolean) = post {
        releaseLocked()
        this.samplesPerFrame = if (samplesPerFrame > 0) samplesPerFrame else 1024
        track = createTrack(lowLatency)
        if (ct == AirPlayNative.AUDIO_AAC_ELD || ct == AirPlayNative.AUDIO_AAC_LC) {
            aac = createAacDecoder(eld = ct == AirPlayNative.AUDIO_AAC_ELD)
        }
    }

    /** [ptsNs]: sender timestamp on the System.nanoTime() clock, or 0 if unknown. */
    fun playPcm(data: ByteArray, ptsNs: Long) = postData { writeSynced(data, 0, data.size, ptsNs) }

    fun playAac(data: ByteArray, ptsNs: Long) = postData { decodeAac(data, ptsNs) }

    fun flush() = post {
        track?.apply { pause(); flush(); play() }
        runCatching { aac?.flush() }
        aacPtsUs = 0
        framesWritten = 0
        pipelineNs = -1
    }

    fun setVolume(gain: Float) = post {
        this.gain = gain
        track?.setVolume(gain)
    }

    fun stop() = post { releaseLocked() }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    /** Drops data instead of building up latency if the audio thread falls behind. */
    private fun postData(block: () -> Unit) {
        if (queued.get() > MAX_QUEUED) return
        queued.incrementAndGet()
        handler.post {
            queued.decrementAndGet()
            block()
        }
    }

    private fun writeSynced(pcm: ByteArray, offset: Int, length: Int, ptsNs: Long) {
        val t = track ?: return
        val frames = length / BYTES_PER_FRAME
        if (ptsNs > 0) {
            val now = System.nanoTime()
            val heardAtNs = nextFrameHeardAtNs(t, now)
            if (heardAtNs != null) {
                if (pipelineNs >= 0) sync.reportAudio(now - ptsNs + pipelineNs)
                val error = heardAtNs - (ptsNs + sync.latencyNs) // > 0: late, < 0: early
                when {
                    error > MAX_LATE_NS -> {
                        stats.dropped++
                        return
                    }
                    error < -MAX_EARLY_NS -> {
                        val silence = ((-error) * SAMPLE_RATE / 1_000_000_000L).toInt()
                            .coerceAtMost(SAMPLE_RATE * 3)
                        writeSilence(t, silence)
                        stats.silenceFrames += silence
                    }
                }
                stats.error(error)
            }
        }
        t.write(pcm, offset, length)
        framesWritten += frames
    }

    /**
     * When the next frame written now would be heard, from the track's latest
     * timestamp. Also measures the output pipeline latency once per track, while
     * the queue still holds only what arrived naturally.
     */
    private fun nextFrameHeardAtNs(t: AudioTrack, now: Long): Long? {
        if (!t.getTimestamp(timestamp) || timestamp.framePosition <= 0) return null
        val framesAhead = framesWritten - timestamp.framePosition
        val heardAt = timestamp.nanoTime + framesAhead * 1_000_000_000L / SAMPLE_RATE
        if (pipelineNs < 0) {
            pipelineNs = (heardAt - now).coerceAtLeast(0)
            Log.i(TAG, "audio output pipeline latency ${pipelineNs / AvSync.MS} ms")
        }
        return heardAt
    }

    private fun writeSilence(t: AudioTrack, frames: Int) {
        var remaining = frames * BYTES_PER_FRAME
        val zeros = ByteArray(minOf(remaining, 16 * 1024))
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size)
            val written = t.write(zeros, 0, n)
            if (written <= 0) return
            remaining -= written
        }
        framesWritten += frames
    }

    private fun createTrack(lowLatency: Boolean): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Room to hold audio until its presentation time: up to the sync latency while
        // mirroring, about 2 s for AirPlay music.
        val bufferBytes = if (lowLatency) maxOf(minBuffer * 2, SAMPLE_RATE * BYTES_PER_FRAME / 2)
        else SAMPLE_RATE * BYTES_PER_FRAME * 3
        framesWritten = 0
        pipelineNs = -1
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(gain)
                it.play()
            }
    }

    private fun createAacDecoder(eld: Boolean): MediaCodec? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (eld) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)))
            } else {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
            }
        }
        // Prefer the platform software decoder: hardware AAC decoders often lack AAC-ELD.
        for (create in listOf(
            { MediaCodec.createByCodecName("c2.android.aac.decoder") },
            { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) },
        )) {
            try {
                return create().apply {
                    configure(format, null, null, 0)
                    start()
                    Log.i(TAG, "AAC decoder $name (eld=$eld)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AAC decoder unavailable: ${e.message}")
            }
        }
        return null
    }

    private fun decodeAac(data: ByteArray, ptsNs: Long) {
        val codec = aac ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.getInputBuffer(inIndex)?.apply { clear(); put(data) }
                // The sender timestamp travels through the decoder; fall back to a counter.
                val ptsUs = if (ptsNs > 0) ptsNs / 1000 else aacPtsUs
                codec.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
                aacPtsUs = ptsUs + samplesPerFrame * 1_000_000L / SAMPLE_RATE
            }
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex < 0) break
                val out = codec.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(pcmScratch, 0, info.size)
                    writeSynced(pcmScratch, 0, info.size, if (ptsNs > 0) info.presentationTimeUs * 1000 else 0)
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AAC decode failed", e)
        }
    }

    private fun releaseLocked() {
        aac?.let {
            runCatching { it.stop() }
            it.release()
        }
        aac = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        aacPtsUs = 0
    }

    /** Logs how far audio was from its slot, and the corrections made. */
    private class Stats {
        var dropped = 0
        var silenceFrames = 0L
        private var count = 0
        private var errorSumNs = 0L
        private var windowStart = System.nanoTime()

        fun error(errorNs: Long) {
            count++
            errorSumNs += errorNs
            val elapsed = System.nanoTime() - windowStart
            if (elapsed >= 5_000_000_000L) {
                Log.i(TAG, "sync error avg %d ms, dropped %d packets, inserted %d ms silence".format(
                    errorSumNs / count / AvSync.MS, dropped, silenceFrames * 1000 / SAMPLE_RATE))
                windowStart = System.nanoTime()
                count = 0; errorSumNs = 0; dropped = 0; silenceFrames = 0
            }
        }
    }

    companion object {
        /** AirPlay sends -30..0 dB; -30 and below (and -144) mean mute. */
        fun airPlayGain(db: Float): Float = if (db <= -30f) 0f else 10f.pow(db / 20f)

        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val BYTES_PER_FRAME = 4
        private const val MAX_QUEUED = 400
        private const val MAX_LATE_NS = 25 * AvSync.MS
        private const val MAX_EARLY_NS = 15 * AvSync.MS
    }
}
