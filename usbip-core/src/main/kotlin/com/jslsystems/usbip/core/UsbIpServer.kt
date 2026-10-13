package com.jslsystems.usbip.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB/IP server (TCP 3240): answers OP_REQ_DEVLIST and OP_REQ_IMPORT, then hands the connection
 * to a [UsbIpSession]. One client per device; other connections may still list devices.
 */
class UsbIpServer(
    private val backend: UsbBackend,
    private val events: ServerEvents,
    private val port: Int = UsbIp.DEFAULT_PORT,
) {
    data class SessionInfo(
        val busId: String,
        val device: UsbDeviceInfo,
        val client: String,
        val startedAtMillis: Long,
        val stats: SessionStats.Snapshot,
    )

    private var serverSocket: ServerSocket? = null
    private val sessions = ConcurrentHashMap<String, UsbIpSession>()
    private val reserved = ConcurrentHashMap.newKeySet<String>()
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionCounter = AtomicInteger()

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** The port actually bound (useful when constructed with port 0 in tests). */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        Thread({ acceptLoop(ss) }, "usbip-accept").apply { isDaemon = true }.start()
        events.log(LogLevel.INFO, "Servidor USB/IP escuchando en el puerto ${ss.localPort}")
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        runCatching { ss.close() }
        sessions.values.forEach { it.close() }
        openSockets.forEach { runCatching { it.close() } }
        events.log(LogLevel.INFO, "Servidor detenido")
    }

    fun sessions(): List<SessionInfo> = sessions.map { (busId, s) ->
        SessionInfo(busId, s.device, s.clientName, s.startedAtMillis, s.stats.snapshot())
    }

    fun isExported(busId: String): Boolean = sessions.containsKey(busId)

    /** Closes the session using [busId] so the client sees the disconnect and can re-attach. */
    fun closeSession(busId: String) {
        sessions[busId]?.let {
            events.log(LogLevel.INFO, "Cerrando sesión de $busId")
            it.close()
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (!ss.isClosed) events.log(LogLevel.ERROR, "accept falló: ${e.message}")
                break
            }
            val id = connectionCounter.incrementAndGet()
            Thread({ handleConnection(socket) }, "usbip-conn-$id").apply { isDaemon = true }.start()
        }
    }

    private fun handleConnection(socket: Socket) {
        openSockets += socket
        val client = socket.remoteSocketAddress?.toString()?.removePrefix("/") ?: "?"
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = socket.getOutputStream()
            val header = ByteArray(UsbIp.OP_HEADER_SIZE)
            while (true) {
                try {
                    input.readFully(header)
                } catch (e: java.io.EOFException) {
                    return
                }
                val version = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
                val code = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
                if (version != UsbIp.VERSION) {
                    events.log(LogLevel.WARN, "$client usa la versión USB/IP 0x%04x (se esperaba 0x0111)".format(version))
                }
                when (code) {
                    UsbIp.OP_REQ_DEVLIST -> {
                        val devices = backend.exportableDevices().filterNot { isExported(it.busId) }
                        output.write(UsbIpCodec.encodeDevList(devices))
                        output.flush()
                        events.log(LogLevel.DEBUG, "$client pidió la lista (${devices.size} dispositivos)")
                    }
                    UsbIp.OP_REQ_IMPORT -> {
                        val busId = UsbIpCodec.readBusId(input)
                        importDevice(busId, client, socket, input, output)
                        return
                    }
                    else -> {
                        events.log(LogLevel.WARN, "$client envió una operación desconocida 0x%04x".format(code))
                        return
                    }
                }
            }
        } catch (e: SocketException) {
            // Closed by stop() or by the peer.
        } catch (e: IOException) {
            events.log(LogLevel.WARN, "Conexión con $client: ${e.message}")
        } catch (e: RuntimeException) {
            // Never let a connection thread die with an uncaught exception: on Android that kills the app.
            events.log(LogLevel.ERROR, "Conexión con $client: error inesperado: $e")
        } finally {
            openSockets -= socket
            runCatching { socket.close() }
        }
    }

    private fun importDevice(
        busId: String,
        client: String,
        socket: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
    ) {
        if (!reserved.add(busId)) {
            events.log(LogLevel.WARN, "$client pidió $busId, pero ya está en uso")
            output.write(UsbIpCodec.encodeImportReply(UsbIp.ST_DEV_BUSY, null))
            return
        }
        try {
            val handle = backend.open(busId)
            if (handle == null) {
                events.log(LogLevel.WARN, "$client pidió $busId, que no existe o no se pudo abrir")
                output.write(UsbIpCodec.encodeImportReply(UsbIp.ST_NODEV, null))
                return
            }
            val session = UsbIpSession(socket, input, output, handle, events, client)
            output.write(UsbIpCodec.encodeImportReply(UsbIp.ST_OK, handle.info))
            output.flush()
            sessions[busId] = session
            events.log(LogLevel.INFO, "$client montó ${handle.info.describe()}")
            events.sessionsChanged()
            try {
                session.run()
            } finally {
                sessions.remove(busId)
                events.log(LogLevel.INFO, "$client desmontó $busId")
                events.sessionsChanged()
            }
        } finally {
            reserved.remove(busId)
        }
    }
}
