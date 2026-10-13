package com.jslsystems.usbip.core

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** In-memory wheel: answers GET_DESCRIPTOR and serves interrupt IN reports pushed by the test. */
class FakeBackend : UsbBackend {
    val info = UsbDeviceInfo(
        path = "/dev/bus/usb/001/002", busId = "1-1", busNum = 1, devNum = 2,
        speed = UsbIp.Speed.FULL, vendorId = 0x046d, productId = 0xc24f, bcdDevice = 0x8900,
        deviceClass = 0, deviceSubClass = 0, deviceProtocol = 0,
        configurationValue = 1, numConfigurations = 1,
        interfaces = listOf(UsbInterfaceInfo(3, 0, 0)),
    )
    val handle = FakeHandle(info)

    override fun exportableDevices() = listOf(info)
    override fun open(busId: String): UsbDeviceHandle? = if (busId == info.busId) handle.also { it.closed = false } else null
}

class FakeHandle(override val info: UsbDeviceInfo) : UsbDeviceHandle {
    @Volatile var closed = false
    val configurationsSet = CopyOnWriteArrayList<Int>()
    val outReports = CopyOnWriteArrayList<ByteArray>()
    private val inReports = LinkedBlockingQueue<ByteArray>()
    private val waitingIn = LinkedBlockingQueue<Pair<ByteBuffer, Any>>()
    private val completions = LinkedBlockingQueue<Completion>()
    private val cancelled = ConcurrentHashMap.newKeySet<Any>()
    @Volatile var gone = false

    val deviceDescriptor = byteArrayOf(18, 1, 0x00, 0x02, 0, 0, 0, 64, 0x6d, 0x04, 0x4f, 0xc2.toByte(), 0, 0x89.toByte(), 1, 2, 0, 1)

    fun pushInReport(report: ByteArray) {
        inReports.put(report)
        pump()
    }

    @Synchronized
    private fun pump() {
        while (inReports.isNotEmpty() && waitingIn.isNotEmpty()) {
            val (buf, tag) = waitingIn.poll()
            val report = inReports.poll()
            val n = minOf(report.size, buf.remaining())
            buf.put(report, 0, n)
            completions.put(Completion(tag, n, 0))
        }
    }

    override fun control(setup: SetupPacket, buffer: ByteArray, timeoutMs: Int): Int {
        if (setup.requestType == 0x80 && setup.request == 6 && setup.value == 0x0100) {
            val n = minOf(buffer.size, deviceDescriptor.size)
            deviceDescriptor.copyInto(buffer, 0, 0, n)
            return n
        }
        return UsbIp.Errno.EPIPE
    }

    override fun setConfiguration(value: Int): Boolean {
        configurationsSet += value
        return value == 1
    }

    override fun setInterface(interfaceNumber: Int, alternateSetting: Int) = alternateSetting == 0
    override fun hasEndpoint(endpointAddress: Int) = endpointAddress == 0x81 || endpointAddress == 0x01

    override fun submit(endpointAddress: Int, buffer: ByteBuffer, tag: Any): Boolean {
        if (endpointAddress == 0x01) {
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            outReports += data
            completions.put(Completion(tag, data.size, 0))
        } else {
            waitingIn.put(buffer to tag)
            pump()
        }
        return true
    }

    @Synchronized
    override fun cancel(tag: Any): Boolean {
        val entry = waitingIn.firstOrNull { it.second === tag } ?: return false
        waitingIn.remove(entry)
        cancelled += tag
        completions.put(Completion(tag, 0, UsbIp.Errno.ECONNRESET))
        return true
    }

    override fun reap(timeoutMs: Long): Completion? {
        if (gone) throw IOException("device gone")
        return completions.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        closed = true
        waitingIn.clear()
    }
}
