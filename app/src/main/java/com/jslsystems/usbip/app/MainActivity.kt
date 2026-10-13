package com.jslsystems.usbip.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.jslsystems.usbip.core.UsbIp
import java.net.Inet4Address
import java.net.NetworkInterface

/** Status screen, navigable with the remote's D-pad (F4). */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: TextView
    private lateinit var sessions: TextView
    private lateinit var log: TextView
    private lateinit var toggleServer: Button
    private lateinit var autostart: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        devices = findViewById(R.id.devices)
        sessions = findViewById(R.id.sessions)
        log = findViewById(R.id.log)
        toggleServer = findViewById(R.id.toggle_server)
        autostart = findViewById(R.id.autostart)

        toggleServer.setOnClickListener {
            val running = UsbIpService.instance?.server?.isRunning == true
            UsbIpService.send(this, if (running) UsbIpService.ACTION_STOP else UsbIpService.ACTION_START)
        }
        findViewById<Button>(R.id.request_permission).setOnClickListener {
            UsbIpService.send(this, UsbIpService.ACTION_REQUEST_PERMISSIONS)
        }
        autostart.setOnClickListener {
            Prefs.setAutostart(this, !Prefs.autostart(this))
            render()
        }
        findViewById<Button>(R.id.debug_log).setOnClickListener {
            AppState.showDebug = !AppState.showDebug
            render()
        }

        if (Prefs.autostart(this) && UsbIpService.instance == null) {
            UsbIpService.send(this, UsbIpService.ACTION_START)
        }
        toggleServer.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        AppState.addListener(listener)
        handler.post(refresh)
    }

    override fun onStop() {
        AppState.removeListener(listener)
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun render() {
        val service = UsbIpService.instance
        val server = service?.server
        val running = server?.isRunning == true

        status.text = if (running) {
            val ips = localAddresses().ifEmpty { listOf("sin red") }
            getString(R.string.status_running, ips.joinToString("  "), UsbIp.DEFAULT_PORT)
        } else {
            getString(R.string.status_stopped)
        }
        toggleServer.setText(if (running) R.string.stop_server else R.string.start_server)
        autostart.text = getString(R.string.autostart, getString(if (Prefs.autostart(this)) R.string.yes else R.string.no))
        findViewById<Button>(R.id.debug_log).text =
            getString(R.string.debug_log, getString(if (AppState.showDebug) R.string.yes else R.string.no))

        devices.text = service?.let { s ->
            val list = s.backend.attachedDevices()
            if (list.isEmpty()) {
                getString(R.string.no_devices)
            } else {
                list.joinToString("\n") { d ->
                    val busId = s.backend.busIdOf(d)
                    val state = when {
                        server?.isExported(busId) == true -> "montado en la PC"
                        s.backend.hasPermission(d) -> "disponible"
                        else -> "sin permiso"
                    }
                    "$busId  ${d.label()}  · $state"
                }
            }
        } ?: getString(R.string.no_devices)

        val active = server?.sessions().orEmpty()
        sessions.text = if (active.isEmpty()) {
            getString(R.string.no_sessions)
        } else {
            active.joinToString("\n\n") { s ->
                val st = s.stats
                val seconds = (System.currentTimeMillis() - s.startedAtMillis) / 1000
                "${s.busId} ← ${s.client}  (${seconds}s)\n" +
                    "URBs ${st.urbsSubmitted} enviadas · ${st.urbsCompleted} completadas · ${st.unlinks} unlink · ${st.errors} errores\n" +
                    "Control prom ${st.controlAvgMicros / 1000.0} ms · máx ${st.controlMaxMicros / 1000.0} ms · " +
                    "envío TCP máx ${st.sendMaxMicros / 1000.0} ms\n" +
                    "${st.bytesToClient / 1024} KiB a la PC · ${st.bytesFromClient / 1024} KiB desde la PC"
            }
        }

        log.text = AppState.logLines().asReversed().joinToString("\n")
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nif.name})" } }
    }.getOrDefault(emptyList())
}
