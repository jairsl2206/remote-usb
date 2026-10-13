package com.jslsystems.usbip.core

/** Minimal parser for the raw descriptor blob Android returns from `UsbDeviceConnection.getRawDescriptors()`. */
object Descriptors {

    data class Endpoint(val address: Int, val attributes: Int, val maxPacketSize: Int, val interval: Int) {
        val transferType: Int get() = attributes and 0x03
        val isIn: Boolean get() = address and 0x80 != 0
    }

    data class Interface(
        val number: Int,
        val alternateSetting: Int,
        val interfaceClass: Int,
        val interfaceSubClass: Int,
        val interfaceProtocol: Int,
        val endpoints: List<Endpoint>,
    )

    data class Configuration(val value: Int, val interfaces: List<Interface>)

    data class Device(
        val bcdUsb: Int,
        val deviceClass: Int,
        val deviceSubClass: Int,
        val deviceProtocol: Int,
        val maxPacketSize0: Int,
        val vendorId: Int,
        val productId: Int,
        val bcdDevice: Int,
        val numConfigurations: Int,
        val configurations: List<Configuration>,
    )

    const val TYPE_DEVICE = 1
    const val TYPE_CONFIGURATION = 2
    const val TYPE_INTERFACE = 4
    const val TYPE_ENDPOINT = 5

    const val TRANSFER_CONTROL = 0
    const val TRANSFER_ISOCHRONOUS = 1
    const val TRANSFER_BULK = 2
    const val TRANSFER_INTERRUPT = 3

    fun parse(raw: ByteArray): Device? {
        if (raw.size < 18 || u8(raw, 1) != TYPE_DEVICE) return null
        val configs = mutableListOf<Configuration>()
        var pos = u8(raw, 0)
        var configValue = -1
        val interfaces = mutableListOf<Interface>()
        var current: Interface? = null
        val endpoints = mutableListOf<Endpoint>()

        fun flushInterface() {
            current?.let { interfaces += it.copy(endpoints = endpoints.toList()) }
            current = null
            endpoints.clear()
        }

        fun flushConfig() {
            flushInterface()
            if (configValue >= 0) configs += Configuration(configValue, interfaces.toList())
            interfaces.clear()
        }

        while (pos + 2 <= raw.size) {
            val len = u8(raw, pos)
            if (len < 2 || pos + len > raw.size) break
            when (u8(raw, pos + 1)) {
                TYPE_CONFIGURATION -> if (len >= 9) {
                    flushConfig()
                    configValue = u8(raw, pos + 5)
                }
                TYPE_INTERFACE -> if (len >= 9) {
                    flushInterface()
                    current = Interface(
                        number = u8(raw, pos + 2),
                        alternateSetting = u8(raw, pos + 3),
                        interfaceClass = u8(raw, pos + 5),
                        interfaceSubClass = u8(raw, pos + 6),
                        interfaceProtocol = u8(raw, pos + 7),
                        endpoints = emptyList(),
                    )
                }
                TYPE_ENDPOINT -> if (len >= 7 && current != null) {
                    endpoints += Endpoint(
                        address = u8(raw, pos + 2),
                        attributes = u8(raw, pos + 3),
                        maxPacketSize = u16(raw, pos + 4) and 0x7ff,
                        interval = u8(raw, pos + 6),
                    )
                }
            }
            pos += len
        }
        flushConfig()

        return Device(
            bcdUsb = u16(raw, 2),
            deviceClass = u8(raw, 4),
            deviceSubClass = u8(raw, 5),
            deviceProtocol = u8(raw, 6),
            maxPacketSize0 = u8(raw, 7),
            vendorId = u16(raw, 8),
            productId = u16(raw, 10),
            bcdDevice = u16(raw, 12),
            numConfigurations = u8(raw, 17),
            configurations = configs,
        )
    }

    /**
     * Android does not expose the bus speed, so infer it from the descriptors. The client uses the
     * speed to pick a virtual port and to interpret `bInterval`, so a wrong guess matters.
     * Logitech wheels (G29/G920/G923) are full-speed devices.
     */
    fun guessSpeed(device: Device): Int {
        if (device.bcdUsb >= 0x0300) return UsbIp.Speed.SUPER
        val endpoints = device.configurations.flatMap { c -> c.interfaces.flatMap { it.endpoints } }
        val highSpeedOnly = endpoints.any {
            (it.transferType == TRANSFER_BULK && it.maxPacketSize > 64) ||
                (it.transferType == TRANSFER_INTERRUPT && it.maxPacketSize > 64) ||
                (it.transferType == TRANSFER_ISOCHRONOUS && it.maxPacketSize > 1023)
        }
        if (highSpeedOnly) return UsbIp.Speed.HIGH
        if (device.maxPacketSize0 == 8 && device.bcdUsb < 0x0200 &&
            endpoints.all { it.transferType == TRANSFER_INTERRUPT && it.maxPacketSize <= 8 }
        ) {
            return UsbIp.Speed.LOW
        }
        return UsbIp.Speed.FULL
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
}
