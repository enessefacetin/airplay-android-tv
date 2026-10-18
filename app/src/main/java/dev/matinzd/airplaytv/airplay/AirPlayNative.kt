package dev.matinzd.airplaytv.airplay

/**
 * JNI surface of the native AirPlay server (UxPlay's protocol core).
 * Implemented in app/src/main/cpp/airplay_jni.cpp.
 *
 * Listener callbacks arrive on native network threads, never the main thread.
 */
object AirPlayNative {
    const val SERVICE_RAOP = 0
    const val SERVICE_AIRPLAY = 1

    // reset_type_t in raop.h
    const val RESET_NOHOLD = 0
    const val RESET_RTP_SHUTDOWN = 1

    // Audio "ct" codes from the RTSP SETUP
    const val AUDIO_PCM = 1
    const val AUDIO_ALAC = 2
    const val AUDIO_AAC_LC = 4
    const val AUDIO_AAC_ELD = 8

    init {
        System.loadLibrary("airplay_jni")
    }

    interface Listener {
        /** RTSP SETUP from a client: a session is starting. */
        fun onClientRequest(deviceId: String?, model: String?, name: String?)
        fun onConnectionCount(count: Int)
        /** Clients were dropped (0 = nativeDisconnect, 1 = client stopped responding); the server keeps listening. */
        fun onConnectionLost(reason: Int)

        /** One H.264 access unit in Annex-B format. */
        fun onVideoData(data: ByteArray, ntpUs: Long)
        fun onVideoReset(type: Int)
        fun onVideoFlush()

        fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean)
        /** Decoded 44.1 kHz stereo S16LE (ALAC is decoded natively). */
        fun onAudioPcm(data: ByteArray)
        /** One raw AAC frame (AAC-ELD while mirroring). */
        fun onAudioAac(data: ByteArray)
        fun onAudioFlush()
        /** AirPlay volume in dB: -30..0, or -144 for mute. */
        fun onVolume(db: Float)
        fun onCoverArt(jpeg: ByteArray?)
        fun onMetadata(dmap: ByteArray?)
    }

    @JvmStatic
    external fun nativeStart(
        listener: Listener,
        name: String,
        hwAddr: ByteArray,
        keyFile: String,
        width: Int,
        height: Int,
        fps: Int,
        debug: Boolean,
    ): Int

    @JvmStatic external fun nativeStop()
    /** Drops all client connections; the server keeps listening. */
    @JvmStatic external fun nativeDisconnect()
    @JvmStatic external fun nativeServiceName(service: Int): String?
    @JvmStatic external fun nativeServicePort(service: Int): Int
    @JvmStatic external fun nativeServiceTxt(service: Int): ByteArray?
}
