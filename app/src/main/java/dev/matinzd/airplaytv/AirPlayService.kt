package dev.matinzd.airplaytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import dev.matinzd.airplaytv.airplay.Dmap
import dev.matinzd.airplaytv.airplay.MdnsAdvertiser
import dev.matinzd.airplaytv.airplay.VideoDecoder
import java.io.File
import java.security.SecureRandom
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

    private val audio = AudioPlayer()
    private val video = VideoDecoder { width, height ->
        AirPlaySession.update { it.copy(videoWidth = width, videoHeight = height) }
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

        AirPlaySession.videoDecoder = video
        advertiser = MdnsAdvertiser(this)
        worker.execute(::startServer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        worker.execute {
            advertiser.unregister()
            AirPlayNative.nativeStop()
        }
        worker.shutdown()
        AirPlaySession.videoDecoder = null
        video.release()
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
        val rc = AirPlayNative.nativeStart(this, name, hardwareAddress(), keyFile, 1920, 1080, 60, debug)
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
        TvWaker.wake(this)
    }

    override fun onConnectionCount(count: Int) {
        AirPlaySession.update { if (count == 0) it.endSession().copy(connections = 0) else it.copy(connections = count) }
        if (count == 0) {
            audio.stop()
            video.reset()
            TvWaker.cancelNotification(this)
        }
    }

    override fun onConnectionLost(reason: Int) {
        Log.w(TAG, "connection lost (reason $reason)")
    }

    override fun onVideoData(data: ByteArray, ntpUs: Long) {
        if (!AirPlaySession.state.mirroring) AirPlaySession.update { it.copy(mirroring = true, audioOnly = false) }
        video.onFrame(data)
    }

    override fun onVideoReset(type: Int) {
        video.reset()
        AirPlaySession.update {
            // Mirroring stopped: end the session unless audio-only AirPlay is still playing.
            it.copy(mirroring = false, videoWidth = 0, videoHeight = 0, clientName = if (it.audioOnly) it.clientName else null)
        }
    }

    override fun onVideoFlush() = Unit

    override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean) {
        audio.configure(ct, samplesPerFrame, lowLatency = usingScreen)
        AirPlaySession.update { it.copy(audioOnly = !usingScreen && !it.mirroring) }
    }

    override fun onAudioPcm(data: ByteArray) = audio.playPcm(data)

    override fun onAudioAac(data: ByteArray) = audio.playAac(data)

    override fun onAudioFlush() = audio.flush()

    override fun onVolume(db: Float) = audio.setVolumeDb(db)

    override fun onCoverArt(jpeg: ByteArray?) {
        val bitmap = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        AirPlaySession.update { it.copy(coverArt = bitmap) }
    }

    override fun onMetadata(dmap: ByteArray?) {
        val track = dmap?.let(Dmap::parse) ?: return
        AirPlaySession.update { it.copy(title = track.title, artist = track.artist, album = track.album) }
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay_receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AirPlayService::class.java))
        }
    }
}
