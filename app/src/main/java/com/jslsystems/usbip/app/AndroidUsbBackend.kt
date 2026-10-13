package com.jslsystems.usbip.app

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.jslsystems.usbip.core.BusIdRegistry
import com.jslsystems.usbip.core.Descriptors
import com.jslsystems.usbip.core.LogLevel
import com.jslsystems.usbip.core.ServerEvents
import com.jslsystems.usbip.core.UsbBackend
import com.jslsystems.usbip.core.UsbDeviceHandle
import com.jslsystems.usbip.core.UsbDeviceInfo
import com.jslsystems.usbip.core.UsbInterfaceInfo
import java.util.concurrent.ConcurrentHashMap

/** Exposes the devices attached to the TV's USB host port to the USB/IP server. */
class AndroidUsbBackend(context: Context, private val events: ServerEvents) : UsbBackend {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val registry = BusIdRegistry()
    private val infoCache = ConcurrentHashMap<String, UsbDeviceInfo>() // device name -> info

    /** Every attached device that is not a hub, with or without permission. */
    fun attachedDevices(): List<UsbDevice> =
        usb.deviceList.values.filter { it.deviceClass != UsbConstants.USB_CLASS_HUB }.sortedBy { it.deviceName }

    fun hasPermission(device: UsbDevice) = usb.hasPermission(device)

    fun busIdOf(device: UsbDevice): String = registry.assign(device.deviceName, device.vendorId)

    /** Forgets a detached device; its bus ID stays reserved for the next device from the same vendor. */
    fun onDetached(device: UsbDevice) {
        infoCache.remove(device.deviceName)
        registry.release(device.deviceName)
    }

    override fun exportableDevices(): List<UsbDeviceInfo> =
        attachedDevices().filter { usb.hasPermission(it) }.mapNotNull { infoFor(it, null) }

    override fun open(busId: String): UsbDeviceHandle? {
        val key = registry.deviceKeyOf(busId) ?: return null
        val device = usb.deviceList[key] ?: return null
        if (!usb.hasPermission(device)) {
            events.log(LogLevel.WARN, "Sin permiso USB para ${device.productName ?: key}")
            return null
        }
        val connection = usb.openDevice(device) ?: return null
        val info = infoFor(device, connection)
        if (info == null) {
            connection.close()
            return null
        }
        val handle = AndroidDeviceHandle(device, connection, info, events)
        if (!handle.claimAll()) {
            events.log(LogLevel.ERROR, "No se pudieron reclamar las interfaces de ${info.describe()}")
            handle.close()
            return null
        }
        return handle
    }

    private fun infoFor(device: UsbDevice, open: UsbDeviceConnection?): UsbDeviceInfo? {
        infoCache[device.deviceName]?.let { if (open == null) return it }
        val connection = open ?: usb.openDevice(device) ?: return null
        try {
            val parsed = connection.rawDescriptors?.let { Descriptors.parse(it) } ?: return null
            val active = currentConfiguration(connection)
                ?.takeIf { v -> parsed.configurations.any { it.value == v } }
                ?: parsed.configurations.firstOrNull()?.value
                ?: 1
            val interfaces = parsed.configurations.firstOrNull { it.value == active }?.interfaces.orEmpty()
                .filter { it.alternateSetting == 0 }
                .map { UsbInterfaceInfo(it.interfaceClass, it.interfaceSubClass, it.interfaceProtocol) }
            val (bus, dev) = busAndDevice(device)
            val info = UsbDeviceInfo(
                path = device.deviceName,
                busId = busIdOf(device),
                busNum = bus,
                devNum = dev,
                speed = Descriptors.guessSpeed(parsed),
                vendorId = parsed.vendorId,
                productId = parsed.productId,
                bcdDevice = parsed.bcdDevice,
                deviceClass = parsed.deviceClass,
                deviceSubClass = parsed.deviceSubClass,
                deviceProtocol = parsed.deviceProtocol,
                configurationValue = active,
                numConfigurations = parsed.numConfigurations,
                interfaces = interfaces,
            )
            infoCache[device.deviceName] = info
            return info
        } finally {
            if (open == null) connection.close()
        }
    }

    /** GET_CONFIGURATION; Android has no getter for the active configuration. */
    private fun currentConfiguration(connection: UsbDeviceConnection): Int? {
        val buf = ByteArray(1)
        val n = connection.controlTransfer(0x80, 8, 0, 0, buf, 1, 1000)
        return if (n == 1) buf[0].toInt() and 0xff else null
    }

    /** "/dev/bus/usb/001/005" -> (1, 5). */
    private fun busAndDevice(device: UsbDevice): Pair<Int, Int> {
        val parts = device.deviceName.split('/').takeLast(2).mapNotNull { it.toIntOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else (device.deviceId / 1000) to (device.deviceId % 1000)
    }
}
