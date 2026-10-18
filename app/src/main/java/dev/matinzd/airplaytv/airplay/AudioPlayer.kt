package dev.matinzd.airplaytv.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
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
 * Everything runs on one audio thread so the network threads never block.
 */
class AudioPlayer {
    private val thread = HandlerThread("airplay-audio", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val queued = AtomicInteger()

    private var track: AudioTrack? = null
    private var aac: MediaCodec? = null
    private var aacPts = 0L
    private var samplesPerFrame = 1024
    private var gain = 1f
    private val info = MediaCodec.BufferInfo()
    private var pcmScratch = ByteArray(0)

    fun configure(ct: Int, samplesPerFrame: Int, lowLatency: Boolean) = post {
        releaseLocked()
        this.samplesPerFrame = if (samplesPerFrame > 0) samplesPerFrame else 1024
        track = createTrack(lowLatency)
        if (ct == AirPlayNative.AUDIO_AAC_ELD || ct == AirPlayNative.AUDIO_AAC_LC) {
            aac = createAacDecoder(eld = ct == AirPlayNative.AUDIO_AAC_ELD)
        }
    }

    fun playPcm(data: ByteArray) = postData { track?.write(data, 0, data.size) }

    fun playAac(data: ByteArray) = postData { decodeAac(data) }

    fun flush() = post {
        track?.apply { pause(); flush(); play() }
        runCatching { aac?.flush() }
        aacPts = 0
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

    private fun createTrack(lowLatency: Boolean): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
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
            .setBufferSizeInBytes(if (lowLatency) minBuffer * 2 else maxOf(minBuffer * 4, SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
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

    private fun decodeAac(data: ByteArray) {
        val codec = aac ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.getInputBuffer(inIndex)?.apply { clear(); put(data) }
                codec.queueInputBuffer(inIndex, 0, data.size, aacPts, 0)
                aacPts += samplesPerFrame * 1_000_000L / SAMPLE_RATE
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
                    track?.write(pcmScratch, 0, info.size)
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
        aacPts = 0
    }

    companion object {
        /** AirPlay sends -30..0 dB; -30 and below (and -144) mean mute. */
        fun airPlayGain(db: Float): Float = if (db <= -30f) 0f else 10f.pow(db / 20f)

        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val MAX_QUEUED = 100
    }
}
