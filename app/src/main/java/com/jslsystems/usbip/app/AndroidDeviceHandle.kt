package com.jslsystems.usbip.app

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import com.jslsystems.usbip.core.Completion
import com.jslsystems.usbip.core.LogLevel
import com.jslsystems.usbip.core.ServerEvents
import com.jslsystems.usbip.core.SetupPacket
import com.jslsystems.usbip.core.UsbDeviceHandle
import com.jslsystems.usbip.core.UsbDeviceInfo
import com.jslsystems.usbip.core.UsbIp
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * One opened device. EP0 goes through the blocking `controlTransfer`; interrupt and bulk
 * endpoints use `UsbRequest.queue` and are reaped with `requestWait`.
 */
class AndroidDeviceHandle(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    override val info: UsbDeviceInfo,
    private val events: ServerEvents,
) : UsbDeviceHandle {

    private class InFlight(val request: UsbRequest, val buffer: ByteBuffer) {
        @Volatile var cancelled = false
    }

    private var configuration: UsbConfiguration? =
        (0 until device.configurationCount).map { device.getConfiguration(it) }.firstOrNull { it.id == info.configurationValue }
            ?: device.takeIf { it.configurationCount > 0 }?.getConfiguration(0)

    private val claimed = mutableListOf<UsbInterface>()

    /** Endpoints of the selected alternate setting of each interface, keyed by interface number. */
    private val endpointsByInterface = ConcurrentHashMap<Int, List<UsbEndpoint>>()
    private val inFlight = ConcurrentHashMap<Any, InFlight>()
    private var consecutiveFailures = 0

    /** Claims every interface with force=true, detaching Android's HID driver (no double input). */
    @Synchronized
    fun claimAll(): Boolean {
        val config = configuration ?: return false
        for (intf in interfacesOf(config).filter { it.alternateSetting == 0 }) {
            if (!connection.claimInterface(intf, true)) return false
            claimed += intf
            endpointsByInterface[intf.id] = endpointsOf(intf)
        }
        return true
    }

    @Synchronized
    private fun releaseAll() {
        claimed.forEach { connection.releaseInterface(it) }
        claimed.clear()
        endpointsByInterface.clear()
    }

    override fun control(setup: SetupPacket, buffer: ByteArray, timeoutMs: Int): Int {
        val n = connection.controlTransfer(
            setup.requestType, setup.request, setup.value, setup.index, buffer, buffer.size, timeoutMs,
        )
        return if (n < 0) UsbIp.Errno.EPIPE else n
    }

    @Synchronized
    override fun setConfiguration(value: Int): Boolean {
        if (value == 0 || value == configuration?.id) return true
        val target = (0 until device.configurationCount).map { device.getConfiguration(it) }.firstOrNull { it.id == value }
            ?: return false
        releaseAll()
        if (!connection.setConfiguration(target)) {
            events.log(LogLevel.WARN, "setConfiguration($value) falló")
            claimAll()
            return false
        }
        configuration = target
        events.log(LogLevel.INFO, "Configuración $value activa en ${info.busId}")
        return claimAll()
    }

    @Synchronized
    override fun setInterface(interfaceNumber: Int, alternateSetting: Int): Boolean {
        val config = configuration ?: return false
        val intf = interfacesOf(config).firstOrNull { it.id == interfaceNumber && it.alternateSetting == alternateSetting }
            ?: return false
        if (!connection.setInterface(intf)) return false
        endpointsByInterface[interfaceNumber] = endpointsOf(intf)
        return true
    }

    override fun hasEndpoint(endpointAddress: Int) = findEndpoint(endpointAddress) != null

    private fun findEndpoint(address: Int): UsbEndpoint? =
        endpointsByInterface.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.address == address } }

    override fun submit(endpointAddress: Int, buffer: ByteBuffer, tag: Any): Boolean {
        val endpoint = findEndpoint(endpointAddress) ?: return false
        val request = UsbRequest()
        if (!request.initialize(connection, endpoint)) {
            request.close()
            return false
        }
        request.clientData = tag
        inFlight[tag] = InFlight(request, buffer)
        if (!request.queue(buffer)) {
            inFlight.remove(tag)
            request.close()
            return false
        }
        return true
    }

    override fun cancel(tag: Any): Boolean {
        val entry = inFlight[tag] ?: return false
        entry.cancelled = true
        return entry.request.cancel()
    }

    override fun reap(timeoutMs: Long): Completion? {
        val request = try {
            connection.requestWait(timeoutMs)
        } catch (e: TimeoutException) {
            return null
        } catch (e: BufferOverflowException) {
            events.log(LogLevel.WARN, "El dispositivo envió más datos de los pedidos")
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (request == null) {
            // requestWait returns null on I/O errors, typically because the device was unplugged.
            // Tolerate an isolated failure; a run of them means the device is gone.
            if (++consecutiveFailures >= 3) throw IOException("requestWait falló")
            Thread.sleep(10)
            return null
        }
        consecutiveFailures = 0
        val tag = request.clientData
        val entry = tag?.let { inFlight.remove(it) }
        request.close()
        if (entry == null) return null
        // queue(ByteBuffer) advances the position by the number of bytes transferred.
        val status = if (entry.cancelled) UsbIp.Errno.ECONNRESET else 0
        return Completion(tag, entry.buffer.position(), status)
    }

    override fun close() {
        inFlight.values.forEach { runCatching { it.request.cancel(); it.request.close() } }
        inFlight.clear()
        runCatching { releaseAll() }
        connection.close()
    }

    private fun interfacesOf(config: UsbConfiguration) = (0 until config.interfaceCount).map { config.getInterface(it) }

    private fun endpointsOf(intf: UsbInterface) = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
}
