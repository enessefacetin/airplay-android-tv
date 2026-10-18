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

    /** A command pressed before the sender was found; sent once it is. */
    @Volatile private var pending: Command? = null
    private var lastLookupNs = 0L

    /** The sender's IDs; repeated calls with the same ID are ignored. */
    fun setSender(newDacpId: String, newActiveRemote: String) {
        activeRemote = newActiveRemote
        if (newDacpId == dacpId) return
        Log.i(TAG, "sender DACP-ID $newDacpId")
        dacpId = newDacpId
        host = null
        startDiscovery()
    }

    fun clear() {
        dacpId = null
        activeRemote = null
        host = null
        pending = null
        stopDiscovery()
    }

    fun send(command: Command) = worker.execute {
        val h = host
        val token = activeRemote
        if (h == null || token == null) {
            Log.i(TAG, "no remote-control target for ${command.path}")
            // The lookup can miss the sender's announcement: look again, send when found.
            if (dacpId != null) {
                pending = command
                if (System.nanoTime() - lastLookupNs > 3_000_000_000L) startDiscovery()
            }
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

    @Synchronized
    private fun startDiscovery() {
        stopDiscovery()
        lastLookupNs = System.nanoTime()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val id = dacpId ?: return
                if (!isSenderService(info.serviceName, id)) {
                    Log.i(TAG, "ignoring ${info.serviceName} (want iTunes_Ctrl_$id)")
                    return
                }
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        host = resolved.host
                        port = resolved.port
                        Log.i(TAG, "remote control at ${resolved.host?.hostAddress}:${resolved.port}")
                        pending?.let { pending = null; send(it) }
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                        Log.w(TAG, "resolving ${info.serviceName} failed: $error")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.w(TAG, "discovery failed: $error")
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        discovery = listener
        nsd.discoverServices("_dacp._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    companion object {
        private const val TAG = "RemoteControl"

        /**
         * The DACP-ID header is a 64-bit hex number without leading zeros
         * ("ED089007A70EE1B"); the service name has all 16 digits ("iTunes_Ctrl_0ED0...").
         */
        fun isSenderService(serviceName: String, dacpId: String): Boolean {
            val prefix = "iTunes_Ctrl_"
            if (!serviceName.startsWith(prefix, ignoreCase = true)) return false
            return serviceName.substring(prefix.length).trimStart('0').equals(dacpId.trimStart('0'), ignoreCase = true)
        }
    }
}
