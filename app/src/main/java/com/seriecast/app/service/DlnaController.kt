package com.seriecast.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.seriecast.app.data.MediaLibrary
import com.seriecast.app.dlna.DlnaServer
import com.seriecast.app.dlna.LanInfo
import com.seriecast.app.dlna.LanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Process-wide owner of the running DLNA server.
 *
 * The service is what Android keeps alive; this object is what both the service and the UI observe,
 * which avoids a binder and keeps the UI honest about the real server state.
 */
object DlnaController {

    sealed interface State {
        data object Stopped : State
        data class Running(val info: DlnaServer.Info, val startedAtMillis: Long) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: DlnaServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var context: Context? = null

    @Synchronized
    fun start(rawContext: Context): State {
        val appContext = rawContext.applicationContext
        context = appContext
        server?.let { existing ->
            if (existing.isRunning) {
                return (_state.value as? State.Running) ?: State.Stopped
            }
        }

        val lan = LanInfo.resolve(appContext)
        val binding = when (lan) {
            is LanState.Ready -> lan.binding
            LanState.NoNetwork -> return fail("Sin red. Conectá el teléfono al mismo Wi-Fi que el televisor.")
            is LanState.NotLocalNetwork -> return fail(
                "La conexión activa es ${describe(lan.transport)}. El televisor necesita que uses Wi-Fi o Ethernet en la misma red local.",
            )
            is LanState.NoIpv4 -> return fail("La interfaz ${lan.interfaceName} no tiene dirección IPv4.")
        }

        return try {
            acquireMulticastLock(appContext)
            val dlna = DlnaServer(
                binding = binding,
                friendlyName = friendlyName(),
                uuid = uuid(appContext),
                library = { MediaLibrary(appContext).scan() },
                onLog = { Log.d(TAG, it) },
            )
            val info = dlna.start()
            server = dlna
            val running = State.Running(info, System.currentTimeMillis())
            _state.value = running
            Log.i(TAG, "DLNA server on http://${info.host}:${info.port} with ${info.videos} videos")
            running
        } catch (t: Throwable) {
            Log.e(TAG, "could not start DLNA server", t)
            releaseMulticastLock()
            server = null
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        releaseMulticastLock()
        _state.value = State.Stopped
    }

    fun refreshLibrary() {
        server?.refreshLibrary()
    }

    fun selfTest(): DlnaServer.SelfTest? = server?.selfTest()

    fun friendlyName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank() || model.equals("unknown", true)) "SerieCast" else "SerieCast ($model)"
    }

    private fun fail(reason: String): State {
        val failed = State.Failed(reason)
        _state.value = failed
        return failed
    }

    private fun uuid(context: Context): String {
        val prefs = context.getSharedPreferences("seriecast", Context.MODE_PRIVATE)
        prefs.getString("uuid", null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("uuid", generated).apply()
        return generated
    }

    private fun acquireMulticastLock(context: Context) {
        if (multicastLock != null) return
        val wifi = context.getSystemService(WifiManager::class.java) ?: return
        // Without this lock the kernel drops multicast datagrams while the screen is off and the
        // television stops seeing the device after a few minutes.
        multicastLock = wifi.createMulticastLock("seriecast-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun describe(transport: String): String = when (transport) {
        "cellular" -> "datos móviles"
        "vpn" -> "una VPN"
        "ethernet" -> "Ethernet"
        else -> transport
    }

    private const val TAG = "DlnaController"
}
