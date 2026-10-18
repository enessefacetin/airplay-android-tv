package dev.matinzd.airplaytv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Sends play/pause and track commands to the sender (DACP, as an Apple TV remote does).
 *
 * The sender gives its DACP-ID and Active-Remote token with the audio stream and
 * advertises "iTunes_Ctrl_<DACP-ID>" as _dacp._tcp. Commands are plain HTTP GETs to
 * /ctrl-int/1/<command> with the Active-Remote header.
 */
class RemoteControl(context: Context) {
    enum class Command(val path: String) {
        PLAY_PAUSE("playpause"), PLAY("play"), PAUSE("pause"), NEXT("nextitem"), PREVIOUS("previtem")
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var port = 0
    private var discovery: NsdManager.DiscoveryListener? = null

    /** The sender's IDs; repeated calls with the same ID are ignored. */
    fun setSender(newDacpId: String, newActiveRemote: String) {
        activeRemote = newActiveRemote
        if (newDacpId == dacpId) return
        dacpId = newDacpId
        host = null
        startDiscovery()
    }

    fun clear() {
        dacpId = null
        activeRemote = null
        host = null
        stopDiscovery()
    }

    fun send(command: Command) = worker.execute {
        val h = host
        val token = activeRemote
        if (h == null || token == null) {
            Log.i(TAG, "no remote-control target for ${command.path}")
            return@execute
        }
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(h, port), 2000)
                socket.soTimeout = 2000
                socket.getOutputStream().write(
                    ("GET /ctrl-int/1/${command.path} HTTP/1.1\r\n" +
                        "Host: starlight.local\r\nActive-Remote: $token\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                val status = socket.getInputStream().bufferedReader().readLine()
                Log.i(TAG, "${command.path}: $status")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${command.path} failed: ${e.message}")
        }
    }

    private fun startDiscovery() {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val id = dacpId ?: return
                if (!info.serviceName.equals("iTunes_Ctrl_$id", ignoreCase = true)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        host = resolved.host
                        port = resolved.port
                        Log.i(TAG, "remote control at ${resolved.host?.hostAddress}:${resolved.port}")
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                        Log.w(TAG, "resolving ${info.serviceName} failed: $error")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        discovery = listener
        nsd.discoverServices("_dacp._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    private companion object {
        const val TAG = "RemoteControl"
    }
}
