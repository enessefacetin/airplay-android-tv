package dev.matinzd.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dev.matinzd.airplaytv.airplay.AirPlayNative
import dev.matinzd.airplaytv.airplay.AudioPlayer
import dev.matinzd.airplaytv.airplay.AvSync
import dev.matinzd.airplaytv.airplay.Dmap
import dev.matinzd.airplaytv.airplay.HlsPlayer
import dev.matinzd.airplaytv.airplay.MdnsAdvertiser
import dev.matinzd.airplaytv.airplay.VideoDecoder
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on AirPlay receiver. Runs as a foreground service with a partial wake
 * lock so the listener and the mDNS advertisement survive standby, and wakes
 * the TV when an iPhone starts a session.
 */
class AirPlayService : Service(), AirPlayNative.Listener {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var advertiser: MdnsAdvertiser
    private lateinit var cpuLock: PowerManager.WakeLock
    private var wifiLock: WifiManager.WifiLock? = null

    private val sync = AvSync()
    private val audio = AudioPlayer(sync)
    private val video = VideoDecoder(sync) { width, height ->
        AirPlaySession.update { it.copy(videoWidth = width, videoHeight = height) }
    }
    private lateinit var hls: HlsPlayer

    /** Turning the TV off ends the AirPlay session, like it does on an Apple TV. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AirPlaySession.state.connections > 0) {
                Log.i(TAG, "screen turned off, ending the AirPlay session")
                worker.execute { AirPlayNative.nativeDisconnect() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplaytv:server")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplaytv:server")
            ?.apply { setReferenceCounted(false); acquire() }

        hls = HlsPlayer(
            this,
            onVideoSize = { width, height -> AirPlaySession.update { it.copy(videoWidth = width, videoHeight = height) } },
            onEnded = { AirPlaySession.update { it.copy(hlsVideo = false) } },
        )
        // Mirroring and AirPlay video never run at the same time; both draw on the playback screen.
        AirPlaySession.surfaceSink = { surface ->
            video.setSurface(surface)
            hls.setSurface(surface)
        }
        advertiser = MdnsAdvertiser(this)
        KeepAliveJob.schedule(this)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        worker.execute(::startServer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        worker.execute {
            advertiser.unregister()
            AirPlayNative.nativeStop()
        }
        worker.shutdown()
        AirPlaySession.surfaceSink = null
        video.release()
        hls.stop()
        audio.release()
        wifiLock?.release()
        cpuLock.release()
        AirPlaySession.update { AirPlaySession.State() }
        super.onDestroy()
    }

    private fun startServer() {
        val name = deviceName()
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val keyFile = File(filesDir, "airplay-key.pem").absolutePath
        val language = Locale.getDefault().toLanguageTag()
        val rc = AirPlayNative.nativeStart(this, name, hardwareAddress(), keyFile, 1920, 1080, 60, language, debug)
        if (rc != 0) {
            Log.e(TAG, "AirPlay server failed to start: $rc")
            AirPlaySession.update { it.copy(serverRunning = false, error = "Server failed to start ($rc)") }
            return
        }
        advertiser.register()
        val port = AirPlayNative.nativeServicePort(AirPlayNative.SERVICE_AIRPLAY)
        AirPlaySession.update { it.copy(serverRunning = true, serverName = name, port = port, error = null) }
    }

    /** The name shown in the iPhone's AirPlay list: the TV's own device name. */
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /**
     * AirPlay identifies receivers by a MAC-style device ID, and iPhones remember
     * pairings by it. Android hides the real MAC, so use a stable random one.
     */
    private fun hardwareAddress(): ByteArray {
        val prefs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
        prefs.getString("hw_addr", null)?.let { saved ->
            return saved.split(":").map { it.toInt(16).toByte() }.toByteArray()
        }
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() and 0xfe) or 0x02).toByte() // unicast, locally administered
        prefs.edit().putString("hw_addr", bytes.joinToString(":") { "%02x".format(it) }).apply()
        return bytes
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirPlay receiver", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("AirPlay receiver")
            .setContentText("Ready for AirPlay")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- AirPlayNative.Listener (native threads) ----

    override fun onClientRequest(deviceId: String?, model: String?, name: String?) {
        Log.i(TAG, "AirPlay session from $name ($model)")
        AirPlaySession.update { it.copy(clientName = name ?: "iPhone", clientModel = model) }
        sync.reset()
        TvWaker.wake(this)
    }

    override fun onConnectionCount(count: Int) {
        AirPlaySession.update { if (count == 0) it.endSession().copy(connections = 0) else it.copy(connections = count) }
        if (count == 0) {
            sync.reset()
            audio.stop()
            video.reset()
            hls.stop()
            TvWaker.cancelNotification(this)
        }
    }

    override fun onConnectionLost(reason: Int) {
        Log.w(TAG, "connection lost (reason $reason)")
    }

    override fun onVideoData(data: ByteArray, ptsNs: Long) {
        val state = AirPlaySession.state
        if (state.hlsVideo) {
            // The iPhone went back to mirroring without stopping the video first:
            // free the Surface before the mirror decoder needs it.
            Log.i(TAG, "mirroring resumed while AirPlay video was active; stopping the video")
            hls.stopAndWait()
        }
        if (!state.mirroring || state.hlsVideo) {
            AirPlaySession.update { it.copy(mirroring = true, hlsVideo = false, audioOnly = false) }
        }
        video.onFrame(data, ptsNs)
    }

    override fun onVideoReset(type: Int) {
        when (type) {
            AirPlayNative.RESET_HLS_SHUTDOWN, AirPlayNative.RESET_HLS_EOS -> {
                hls.stop()
                AirPlaySession.update { endVideo(it.copy(hlsVideo = false)) }
            }
            AirPlayNative.RESET_NOHOLD -> {
                video.reset()
                hls.stop()
                AirPlaySession.update { endVideo(it.copy(mirroring = false, hlsVideo = false)) }
            }
            AirPlayNative.RESET_RTP_TO_HLS_TEARDOWN -> { // mirroring hands over to AirPlay video
                video.reset()
                AirPlaySession.update { it.copy(mirroring = false) }
            }
            else -> { // mirroring stopped
                video.reset()
                AirPlaySession.update { endVideo(it.copy(mirroring = false)) }
            }
        }
    }

    /** When no video is left, end the session unless audio-only AirPlay is still playing. */
    private fun endVideo(state: AirPlaySession.State) =
        if (state.showsVideo) state
        else state.copy(videoWidth = 0, videoHeight = 0, clientName = if (state.audioOnly) state.clientName else null)

    override fun onVideoFlush() = Unit

    override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean) {
        sync.audioOnly = !usingScreen
        audio.configure(ct, samplesPerFrame, lowLatency = usingScreen)
        AirPlaySession.update { it.copy(audioOnly = !usingScreen && !it.showsVideo) }
    }

    override fun onAudioPcm(data: ByteArray, ptsNs: Long) = audio.playPcm(data, ptsNs)

    override fun onAudioAac(data: ByteArray, ptsNs: Long) = audio.playAac(data, ptsNs)

    override fun onAudioFlush() = audio.flush()

    override fun onVolume(db: Float) {
        val gain = AudioPlayer.airPlayGain(db)
        audio.setVolume(gain)
        hls.setVolume(gain)
    }

    override fun onCoverArt(jpeg: ByteArray?) {
        val bitmap = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        AirPlaySession.update { it.copy(coverArt = bitmap) }
    }

    override fun onMetadata(dmap: ByteArray?) {
        val track = dmap?.let(Dmap::parse) ?: return
        AirPlaySession.update { it.copy(title = track.title, artist = track.artist, album = track.album) }
    }

    override fun onVideoPlay(url: String, startSec: Float) {
        // AirPlay video doesn't go through RTSP SETUP, so wake the TV here too.
        AirPlaySession.update {
            it.copy(hlsVideo = true, mirroring = false, audioOnly = false, clientName = it.clientName ?: "iPhone")
        }
        TvWaker.wake(this)
        hls.play(url, startSec)
    }

    override fun onVideoScrub(positionSec: Float) = hls.seek(positionSec)

    override fun onVideoRate(rate: Float) = hls.setRate(rate)

    override fun onVideoStop() {
        hls.stop()
        AirPlaySession.update { endVideo(it.copy(hlsVideo = false)) }
    }

    override fun onVideoPlaylistRemove(): Float = hls.pauseAndGetPosition()

    override fun onVideoPlaybackInfo(): DoubleArray = hls.playbackInfo()

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay_receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AirPlayService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: not allowed from the background
                // right now (e.g. battery optimisation exemption missing). KeepAliveJob retries.
                Log.w(TAG, "could not start the receiver: ${e.message}")
            }
        }
    }
}
