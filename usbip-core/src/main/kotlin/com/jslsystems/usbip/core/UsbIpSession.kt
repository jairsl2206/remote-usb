package com.jslsystems.usbip.core

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * URB phase of an imported device: forwards CMD_SUBMIT / CMD_UNLINK from the client to the
 * device and sends RET_SUBMIT / RET_UNLINK back.
 *
 * Threads: the caller's thread reads commands; EP0 transfers run one at a time on a control
 * thread (they are blocking on Android); bulk/interrupt transfers are queued asynchronously and a
 * single reaper thread sends their completions. Whoever removes a URB from [pending] owns its
 * reply, which makes unlink-vs-completion races safe.
 */
class UsbIpSession(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val handle: UsbDeviceHandle,
    private val events: ServerEvents,
    val clientName: String,
    private val controlTimeoutMs: Int = 5000,
) {
    val stats = SessionStats()
    val device: UsbDeviceInfo get() = handle.info
    val startedAtMillis = System.currentTimeMillis()

    private class PendingUrb(val cmd: UrbCommand.Submit, val buffer: ByteBuffer?)

    private val pending = HashMap<Int, PendingUrb>()
    private val inFlight = AtomicInteger()
    private val pool = BufferPool()
    private val writeLock = Any()
    private var writeBuffer: ByteBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN)
    private val control: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbip-ep0-${device.busId}").apply { isDaemon = true }
    }

    @Volatile private var running = true

    /** Runs until the client disconnects, the device goes away or [close] is called. */
    fun run() {
        val reaper = Thread(::reapLoop, "usbip-reaper-${device.busId}").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        val header = ByteArray(UsbIp.HEADER_SIZE)
        try {
            while (running) {
                val cmd = UsbIpCodec.readCommand(input, header) ?: break
                when (cmd) {
                    is UrbCommand.Submit -> handleSubmit(cmd)
                    is UrbCommand.Unlink -> handleUnlink(cmd)
                }
            }
        } catch (e: IOException) {
            if (running) events.log(LogLevel.INFO, "Sesión ${device.busId}: conexión cerrada (${e.message})")
        } catch (e: RuntimeException) {
            events.log(LogLevel.ERROR, "Sesión ${device.busId}: error inesperado: $e")
        } finally {
            shutdown(reaper)
        }
    }

    /** Ends the session from another thread (device detached, server stopping). */
    fun close() {
        running = false
        runCatching { socket.close() }
    }

    private fun handleSubmit(cmd: UrbCommand.Submit) {
        stats.urbsSubmitted.incrementAndGet()
        stats.bytesFromClient.addAndGet(cmd.data.size.toLong())
        when {
            cmd.isIsochronous -> {
                events.log(LogLevel.WARN, "URB isócrona en ep ${cmd.endpoint}: no soportada")
                sendRetSubmit(cmd, UsbIp.Errno.EPIPE, 0, null)
            }
            cmd.endpoint == 0 -> {
                synchronized(pending) { pending[cmd.seqNum] = PendingUrb(cmd, null) }
                control.execute { runControl(cmd) }
            }
            else -> submitAsync(cmd)
        }
    }

    private fun submitAsync(cmd: UrbCommand.Submit) {
        val address = cmd.endpoint or (if (cmd.isIn) 0x80 else 0)
        if (!handle.hasEndpoint(address)) {
            sendRetSubmit(cmd, UsbIp.Errno.EPIPE, 0, null)
            return
        }
        val buffer = pool.acquire(cmd.transferBufferLength)
        if (!cmd.isIn) {
            buffer.put(cmd.data)
            buffer.flip()
        }
        val urb = PendingUrb(cmd, buffer)
        synchronized(pending) { pending[cmd.seqNum] = urb }
        inFlight.incrementAndGet()
        if (!handle.submit(address, buffer, urb)) {
            inFlight.decrementAndGet()
            pool.release(buffer)
            stats.errors.incrementAndGet()
            if (take(cmd.seqNum, urb)) sendRetSubmit(cmd, UsbIp.Errno.EPIPE, 0, null)
        }
    }

    private fun runControl(cmd: UrbCommand.Submit) {
        try {
            executeControl(cmd)
        } catch (e: RuntimeException) {
            events.log(LogLevel.ERROR, "Error en transferencia de control: $e")
            synchronized(pending) { pending.remove(cmd.seqNum) }?.let { sendRetSubmit(it.cmd, UsbIp.Errno.EPIPE, 0, null) }
        }
    }

    private fun executeControl(cmd: UrbCommand.Submit) {
        val setup = SetupPacket.parse(cmd.setup)
        val start = System.nanoTime()
        var data: ByteArray? = null
        val result: Int = when {
            // SET_CONFIGURATION: usbfs needs its own call, a raw control transfer would desync the kernel.
            setup.requestType == 0x00 && setup.request == 9 -> {
                if (handle.setConfiguration(setup.value)) 0 else UsbIp.Errno.EPIPE
            }
            // SET_INTERFACE
            setup.requestType == 0x01 && setup.request == 11 -> {
                if (handle.setInterface(setup.index, setup.value)) 0 else UsbIp.Errno.EPIPE
            }
            // SET_FEATURE(PORT_RESET): Android does not let normal apps reset a device.
            setup.requestType == 0x23 && setup.request == 3 && setup.value == 4 -> {
                events.log(
                    LogLevel.WARN,
                    "El cliente pidió reset USB de ${device.busId}; Android no lo permite, se responde OK sin reset",
                )
                0
            }
            cmd.isIn -> {
                val buf = ByteArray(minOf(setup.length, cmd.transferBufferLength))
                val n = handle.control(setup, buf, controlTimeoutMs)
                data = buf
                n
            }
            else -> handle.control(setup, cmd.data, controlTimeoutMs)
        }
        stats.recordControl((System.nanoTime() - start) / 1000)
        val status = if (result < 0) result else 0
        val actual = when {
            result < 0 -> 0
            cmd.isIn -> result
            else -> cmd.data.size
        }
        if (status != 0) {
            stats.errors.incrementAndGet()
            events.log(
                LogLevel.DEBUG,
                "Control %02x %02x %04x %04x falló (%d)".format(
                    setup.requestType, setup.request, setup.value, setup.index, status,
                ),
            )
        }
        val urb = synchronized(pending) { pending.remove(cmd.seqNum) } ?: return // unlinked meanwhile
        sendRetSubmit(urb.cmd, status, actual, data?.let { ByteBuffer.wrap(it, 0, actual) })
    }

    private fun handleUnlink(cmd: UrbCommand.Unlink) {
        stats.unlinks.incrementAndGet()
        val urb = synchronized(pending) { pending.remove(cmd.unlinkSeqNum) }
        if (urb == null) {
            // Already completed and answered: RET_UNLINK with status 0, as the Linux stub does.
            sendRetUnlink(cmd.seqNum, 0)
            return
        }
        if (urb.buffer != null) handle.cancel(urb)
        sendRetUnlink(cmd.seqNum, UsbIp.Errno.ECONNRESET)
    }

    private fun reapLoop() {
        try {
            reapUntilDone()
        } catch (e: RuntimeException) {
            events.log(LogLevel.ERROR, "Error al recibir del dispositivo: $e")
            close()
        }
    }

    private fun reapUntilDone() {
        while (running || inFlight.get() > 0) {
            val completion = try {
                handle.reap(if (running) 100 else 20)
            } catch (e: IOException) {
                if (running) {
                    events.log(LogLevel.WARN, "Dispositivo ${device.busId} desconectado: ${e.message}")
                    close()
                }
                return
            }
            if (completion == null) {
                if (!running && System.nanoTime() > drainDeadline) return
                continue
            }
            inFlight.decrementAndGet()
            val urb = completion.tag as PendingUrb
            val buffer = urb.buffer!!
            if (take(urb.cmd.seqNum, urb)) {
                stats.urbsCompleted.incrementAndGet()
                if (completion.status != 0) stats.errors.incrementAndGet()
                val actual = completion.actualLength.coerceIn(0, urb.cmd.transferBufferLength)
                val inData = if (urb.cmd.isIn && actual > 0) {
                    buffer.duplicate().apply {
                        position(0)
                        limit(actual)
                    }
                } else {
                    null
                }
                sendRetSubmit(urb.cmd, completion.status, actual, inData)
            }
            pool.release(buffer)
        }
    }

    @Volatile private var drainDeadline = Long.MAX_VALUE

    private fun take(seqNum: Int, urb: PendingUrb): Boolean = synchronized(pending) {
        if (pending[seqNum] === urb) {
            pending.remove(seqNum)
            true
        } else {
            false
        }
    }

    private fun sendRetSubmit(cmd: UrbCommand.Submit, status: Int, actualLength: Int, inData: ByteBuffer?) {
        val payload = inData?.remaining() ?: 0
        send(UsbIp.HEADER_SIZE + payload) { out ->
            UsbIpCodec.encodeRetSubmitHeader(
                out, cmd.seqNum, status, if (cmd.isIn) payload else actualLength, cmd.numberOfPackets,
            )
            if (inData != null) out.put(inData)
        }
        stats.bytesToClient.addAndGet(payload.toLong())
    }

    private fun sendRetUnlink(seqNum: Int, status: Int) {
        send(UsbIp.HEADER_SIZE) { out -> UsbIpCodec.encodeRetUnlink(out, seqNum, status) }
    }

    /** Serializes replies; each one goes out in a single write so TCP_NODELAY sends one segment. */
    private inline fun send(size: Int, encode: (ByteBuffer) -> Unit) {
        synchronized(writeLock) {
            if (writeBuffer.capacity() < size) {
                writeBuffer = ByteBuffer.allocate(Integer.highestOneBit(size) shl 1).order(ByteOrder.BIG_ENDIAN)
            }
            encode(writeBuffer)
            val start = System.nanoTime()
            try {
                output.write(writeBuffer.array(), 0, writeBuffer.position())
                output.flush()
            } catch (e: IOException) {
                close()
                return
            }
            stats.recordSend((System.nanoTime() - start) / 1000)
        }
    }

    private fun shutdown(reaper: Thread) {
        running = false
        runCatching { socket.close() }
        val outstanding = synchronized(pending) {
            pending.values.toList().also { pending.clear() }
        }
        outstanding.filter { it.buffer != null }.forEach { handle.cancel(it) }
        // Give cancelled transfers a moment to be reaped before the connection is closed.
        drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        reaper.join(1500)
        control.shutdownNow()
        control.awaitTermination(controlTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        runCatching { handle.close() }
    }
}
