package com.jslsystems.usbip.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Speaks the client side of USB/IP the way the Linux vhci driver and usbip-win2 do. */
class UsbIpServerTest {
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val events = object : ServerEvents {
        override fun log(level: LogLevel, message: String) {
            logs += "$level $message"
        }
    }
    private lateinit var backend: FakeBackend
    private lateinit var server: UsbIpServer

    @BeforeTest
    fun setUp() {
        backend = FakeBackend()
        server = UsbIpServer(backend, events, port = 0)
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop()

    private fun connect() = Socket("127.0.0.1", server.boundPort).apply { soTimeout = 3000 }

    private fun DataOutputStream.opHeader(code: Int) {
        writeShort(UsbIp.VERSION)
        writeShort(code)
        writeInt(0)
    }

    @Test
    fun devlistDescribesTheDevice() {
        connect().use { s ->
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            out.opHeader(UsbIp.OP_REQ_DEVLIST)
            assertEquals(UsbIp.VERSION, inp.readUnsignedShort())
            assertEquals(UsbIp.OP_REP_DEVLIST, inp.readUnsignedShort())
            assertEquals(0, inp.readInt())
            assertEquals(1, inp.readInt())
            val dev = ByteArray(UsbIp.DEVICE_STRUCT_SIZE).also { inp.readFully(it) }
            val b = ByteBuffer.wrap(dev)
            assertEquals("/dev/bus/usb/001/002", cString(dev, 0, 256))
            assertEquals("1-1", cString(dev, 256, 32))
            b.position(288)
            assertEquals(1, b.int)
            assertEquals(2, b.int)
            assertEquals(UsbIp.Speed.FULL, b.int)
            assertEquals(0x046d, b.short.toInt() and 0xffff)
            assertEquals(0xc24f, b.short.toInt() and 0xffff)
            b.position(311)
            assertEquals(1, b.get().toInt()) // bNumInterfaces
            val iface = ByteArray(4).also { inp.readFully(it) }
            assertEquals(3, iface[0].toInt())
        }
    }

    private fun importDevice(s: Socket): Pair<DataOutputStream, DataInputStream> {
        val out = DataOutputStream(s.getOutputStream())
        val inp = DataInputStream(s.getInputStream())
        out.opHeader(UsbIp.OP_REQ_IMPORT)
        out.write("1-1".toByteArray().copyOf(32))
        assertEquals(UsbIp.VERSION, inp.readUnsignedShort())
        assertEquals(UsbIp.OP_REP_IMPORT, inp.readUnsignedShort())
        assertEquals(0, inp.readInt())
        val dev = ByteArray(UsbIp.DEVICE_STRUCT_SIZE).also { inp.readFully(it) }
        assertEquals("1-1", cString(dev, 256, 32))
        return out to inp
    }

    private fun DataOutputStream.submit(
        seq: Int, dir: Int, ep: Int, length: Int, setup: ByteArray = ByteArray(8), data: ByteArray = ByteArray(0),
        packets: Int = -1,
    ) {
        writeInt(UsbIp.CMD_SUBMIT); writeInt(seq); writeInt(0x10002); writeInt(dir); writeInt(ep)
        writeInt(if (dir == UsbIp.DIR_IN) 0x200 else 0); writeInt(length); writeInt(0); writeInt(packets); writeInt(1)
        write(setup)
        write(data)
        flush()
    }

    private fun DataOutputStream.unlink(seq: Int, target: Int) {
        writeInt(UsbIp.CMD_UNLINK); writeInt(seq); writeInt(0x10002); writeInt(0); writeInt(1)
        writeInt(target); write(ByteArray(24))
        flush()
    }

    private class Ret(val command: Int, val seq: Int, val status: Int, val actual: Int, val packets: Int)

    private fun DataInputStream.ret(): Ret {
        val command = readInt(); val seq = readInt()
        readInt(); readInt(); readInt()
        val status = readInt(); val actual = readInt()
        readInt()
        val packets = readInt()
        readInt(); readLong()
        return Ret(command, seq, status, actual, packets)
    }

    @Test
    fun controlInReturnsDescriptor() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            val getDescriptor = byteArrayOf(0x80.toByte(), 6, 0, 1, 0, 0, 18, 0)
            out.submit(seq = 1, dir = UsbIp.DIR_IN, ep = 0, length = 18, setup = getDescriptor)
            val r = inp.ret()
            assertEquals(UsbIp.RET_SUBMIT, r.command)
            assertEquals(1, r.seq)
            assertEquals(0, r.status)
            assertEquals(18, r.actual)
            assertEquals(-1, r.packets, "number_of_packets is echoed")
            val data = ByteArray(18).also { inp.readFully(it) }
            assertContentEquals(backend.handle.deviceDescriptor, data)
        }
    }

    @Test
    fun unsupportedControlStalls() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            out.submit(seq = 5, dir = UsbIp.DIR_IN, ep = 0, length = 4, setup = byteArrayOf(0xc0.toByte(), 1, 0, 0, 0, 0, 4, 0))
            val r = inp.ret()
            assertEquals(UsbIp.Errno.EPIPE, r.status)
            assertEquals(0, r.actual)
        }
    }

    @Test
    fun setConfigurationIsIntercepted() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            out.submit(seq = 2, dir = UsbIp.DIR_OUT, ep = 0, length = 0, setup = byteArrayOf(0, 9, 1, 0, 0, 0, 0, 0))
            assertEquals(0, inp.ret().status)
            assertEquals(listOf(1), backend.handle.configurationsSet)
        }
    }

    @Test
    fun portResetIsAnsweredWithoutResetting() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            out.submit(seq = 3, dir = UsbIp.DIR_OUT, ep = 0, length = 0, setup = byteArrayOf(0x23, 3, 4, 0, 1, 0, 0, 0))
            assertEquals(0, inp.ret().status)
            assertTrue(logs.any { "reset USB" in it })
        }
    }

    @Test
    fun interruptInAndOut() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            out.submit(seq = 10, dir = UsbIp.DIR_IN, ep = 1, length = 64)
            out.submit(seq = 11, dir = UsbIp.DIR_IN, ep = 1, length = 64)
            backend.handle.pushInReport(byteArrayOf(1, 2, 3))
            backend.handle.pushInReport(byteArrayOf(4, 5))
            val r1 = inp.ret()
            assertEquals(10, r1.seq)
            assertEquals(3, r1.actual)
            assertContentEquals(byteArrayOf(1, 2, 3), ByteArray(3).also { inp.readFully(it) })
            val r2 = inp.ret()
            assertEquals(11, r2.seq)
            assertContentEquals(byteArrayOf(4, 5), ByteArray(2).also { inp.readFully(it) })

            // Force feedback goes the other way: interrupt OUT.
            val ff = byteArrayOf(0x11, 0x08, 0x80.toByte(), 0, 0, 0, 0)
            out.submit(seq = 12, dir = UsbIp.DIR_OUT, ep = 1, length = ff.size, data = ff)
            val r3 = inp.ret()
            assertEquals(12, r3.seq)
            assertEquals(ff.size, r3.actual)
            assertContentEquals(ff, backend.handle.outReports.single())
        }
    }

    @Test
    fun unlinkPendingUrb() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            out.submit(seq = 20, dir = UsbIp.DIR_IN, ep = 1, length = 64)
            Thread.sleep(100)
            out.unlink(seq = 21, target = 20)
            val r = inp.ret()
            assertEquals(UsbIp.RET_UNLINK, r.command)
            assertEquals(21, r.seq)
            assertEquals(UsbIp.Errno.ECONNRESET, r.status)
            // The unlinked URB must not get a RET_SUBMIT.
            s.soTimeout = 400
            assertFailsWith<SocketTimeoutException> { inp.readInt() }
        }
    }

    @Test
    fun unlinkCompletedUrbAnswersZero() {
        connect().use { s ->
            val (out, inp) = importDevice(s)
            out.unlink(seq = 31, target = 30)
            val r = inp.ret()
            assertEquals(UsbIp.RET_UNLINK, r.command)
            assertEquals(0, r.status)
        }
    }

    @Test
    fun secondImportIsBusyAndDevlistHidesExportedDevice() {
        connect().use { s ->
            importDevice(s)
            connect().use { s2 ->
                val out = DataOutputStream(s2.getOutputStream())
                val inp = DataInputStream(s2.getInputStream())
                out.opHeader(UsbIp.OP_REQ_IMPORT)
                out.write("1-1".toByteArray().copyOf(32))
                inp.readUnsignedShort(); inp.readUnsignedShort()
                assertEquals(UsbIp.ST_DEV_BUSY, inp.readInt())
            }
            connect().use { s3 ->
                val out = DataOutputStream(s3.getOutputStream())
                val inp = DataInputStream(s3.getInputStream())
                out.opHeader(UsbIp.OP_REQ_DEVLIST)
                inp.readInt(); inp.readInt()
                assertEquals(0, inp.readInt())
            }
        }
    }

    @Test
    fun deviceGoneClosesSessionAndReleasesHandle() {
        connect().use { s ->
            val (_, inp) = importDevice(s)
            backend.handle.gone = true
            assertEquals(-1, inp.read(), "server closes the TCP session")
            waitUntil { backend.handle.closed && server.sessions().isEmpty() }
        }
    }

    @Test
    fun closeSessionDisconnectsClient() {
        connect().use { s ->
            val (_, inp) = importDevice(s)
            waitUntil { server.isExported("1-1") }
            server.closeSession("1-1")
            assertEquals(-1, inp.read())
            waitUntil { backend.handle.closed }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        val end = (off until off + len).firstOrNull { b[it].toInt() == 0 } ?: (off + len)
        return String(b, off, end - off)
    }
}
