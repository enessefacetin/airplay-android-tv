package dev.matinzd.airplaytv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Advertises the _raop._tcp and _airplay._tcp services through the system mDNS
 * responder. Because the system (not this app) answers queries, the Streamer
 * stays visible in the iPhone's AirPlay list while it's in standby.
 */
class MdnsAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var active = false

    fun register() {
        unregister()
        active = true
        register(AirPlayNative.SERVICE_RAOP, "_raop._tcp")
        register(AirPlayNative.SERVICE_AIRPLAY, "_airplay._tcp")
    }

    private fun register(service: Int, type: String) {
        val name = AirPlayNative.nativeServiceName(service) ?: return
        val port = AirPlayNative.nativeServicePort(service)
        val txt = AirPlayNative.nativeServiceTxt(service) ?: ByteArray(0)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            parseTxt(txt).forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered $type '${info.serviceName}' on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.e(TAG, "registration of $type failed: $error; retrying in 10 s")
                synchronized(registrations) { registrations.remove(this) }
                main.postDelayed({ if (active) register(service, type) }, 10_000)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        synchronized(registrations) { registrations += listener }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        active = false
        main.removeCallbacksAndMessages(null)
        synchronized(registrations) {
            registrations.forEach { runCatching { nsd.unregisterService(it) } }
            registrations.clear()
        }
    }

    companion object {
        private const val TAG = "MdnsAdvertiser"

        /** DNS TXT record: a sequence of length-prefixed "key=value" strings. */
        fun parseTxt(txt: ByteArray): List<Pair<String, String>> {
            val entries = mutableListOf<Pair<String, String>>()
            var i = 0
            while (i < txt.size) {
                val len = txt[i].toInt() and 0xff
                val end = minOf(txt.size, i + 1 + len)
                val entry = String(txt, i + 1, end - i - 1, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) entries += entry.substring(0, eq) to entry.substring(eq + 1)
                i = end
            }
            return entries
        }
    }
}
