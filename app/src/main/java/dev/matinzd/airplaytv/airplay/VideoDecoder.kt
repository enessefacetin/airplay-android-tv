package dev.matinzd.airplaytv.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 mirror stream -> MediaCodec -> Surface, rendered as soon as decoded.
 *
 * Frames start arriving before the playback screen (and its Surface) exists,
 * especially when the TV is waking from standby, and the iPhone only sends a
 * keyframe at the start and on rotation. So every frame since the last
 * keyframe is kept and replayed into the decoder once a Surface is attached.
 */
class VideoDecoder(private val onVideoSize: (width: Int, height: Int) -> Unit) {
    private val lock = Any()
    private val thread = HandlerThread("video-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var errorRetries = 0

    private val gop = ArrayList<ByteArray>()      // frames since the last keyframe
    private var gopBytes = 0L
    private var gopOverflow = false
    private val pending = ArrayDeque<ByteArray>() // waiting for a decoder input buffer
    private val freeInputs = ArrayDeque<Int>()

    fun onFrame(data: ByteArray) {
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            var keyframe = false
            for (nal in H264.leadingNals(data)) {
                when (nal.type) {
                    H264.NAL_SPS -> {
                        val newSps = data.copyOfRange(nal.start, nal.end)
                        if (!newSps.contentEquals(sps)) {
                            // New resolution (rotation) or new stream: start a fresh decoder.
                            sps = newSps
                            toRelease = detachCodecLocked()
                        }
                    }
                    H264.NAL_PPS -> pps = data.copyOfRange(nal.start, nal.end)
                    H264.NAL_IDR -> keyframe = true
                }
            }
            if (keyframe) {
                gop.clear()
                gopBytes = 0
                gopOverflow = false
            }
            val decodable = keyframe || gop.isNotEmpty() || (codec != null && gopOverflow)
            if (!decodable) return@synchronized

            if (!gopOverflow) {
                if (gopBytes + data.size > MAX_GOP_BYTES) {
                    // Too long to cache; keep decoding live, cache again from the next keyframe.
                    gop.clear()
                    gopBytes = 0
                    gopOverflow = true
                } else {
                    gop += data
                    gopBytes += data.size
                }
            }

            val c = codec
            if (c != null) {
                pending.addLast(data)
                feedLocked(c)
            } else if (surface != null) {
                startCodecLocked()
            }
        }
        toRelease?.let(::release)
    }

    fun setSurface(newSurface: Surface?) {
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            if (newSurface === surface) return
            toRelease = detachCodecLocked()
            surface = newSurface
            errorRetries = 0
            if (newSurface != null) startCodecLocked()
        }
        toRelease?.let(::release)
    }

    /** The mirror stream ended. */
    fun reset() {
        var toRelease: MediaCodec? = null
        synchronized(lock) {
            toRelease = detachCodecLocked()
            sps = null
            pps = null
            gop.clear()
            gopBytes = 0
            gopOverflow = false
        }
        toRelease?.let(::release)
    }

    fun release() {
        reset()
        thread.quitSafely()
    }

    private fun startCodecLocked() {
        val s = surface ?: return
        val spsBytes = sps ?: return
        val ppsBytes = pps ?: return
        if (gop.isEmpty()) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))
                setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(Callback(), handler)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            freeInputs.clear()
            pending.clear()
            pending.addAll(gop)
            Log.i(TAG, "decoder ${c.name} started, replaying ${gop.size} frames")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start decoder", e)
        }
    }

    private fun feedLocked(c: MediaCodec) {
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val frame = pending.removeFirst()
            try {
                val buffer = c.getInputBuffer(index) ?: continue
                buffer.clear()
                if (frame.size > buffer.remaining()) {
                    Log.w(TAG, "frame of ${frame.size} bytes too large, dropped")
                    c.queueInputBuffer(index, 0, 0, 0, 0)
                    continue
                }
                buffer.put(frame)
                c.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
            } catch (e: IllegalStateException) {
                return // codec is being released
            }
        }
    }

    private fun detachCodecLocked(): MediaCodec? {
        val c = codec
        codec = null
        freeInputs.clear()
        pending.clear()
        return c
    }

    private fun release(c: MediaCodec) {
        try {
            c.stop()
        } catch (_: Exception) {
        }
        c.release()
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (c !== codec) return
                freeInputs.addLast(index)
                feedLocked(c)
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                c.releaseOutputBuffer(index, true) // render immediately: lowest latency
            } catch (_: IllegalStateException) {
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            val width = if (format.containsKey("crop-right")) {
                format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            } else format.getInteger(MediaFormat.KEY_WIDTH)
            val height = if (format.containsKey("crop-bottom")) {
                format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            } else format.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "video size ${width}x$height")
            onVideoSize(width, height)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "decoder error", e)
            var toRelease: MediaCodec? = null
            synchronized(lock) {
                if (c !== codec) return
                toRelease = detachCodecLocked()
                if (errorRetries++ < 3) startCodecLocked()
            }
            toRelease?.let(::release)
        }
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val MAX_GOP_BYTES = 48L * 1024 * 1024
    }
}
