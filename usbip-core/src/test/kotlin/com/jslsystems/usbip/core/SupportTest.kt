package com.jslsystems.usbip.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SupportTest {

    @Test
    fun reenumeratedWheelKeepsItsBusId() {
        val r = BusIdRegistry()
        assertEquals("1-1", r.assign("/dev/bus/usb/001/002", 0x046d))
        assertEquals("1-2", r.assign("/dev/bus/usb/001/003", 0x0bda))
        r.release("/dev/bus/usb/001/002")
        // Same vendor, new device node (wheel switched mode): inherits 1-1.
        assertEquals("1-1", r.assign("/dev/bus/usb/001/004", 0x046d))
        // Other vendors never take a vacated Logitech slot.
        r.release("/dev/bus/usb/001/004")
        assertEquals("1-3", r.assign("/dev/bus/usb/001/005", 0x1234))
        assertEquals("/dev/bus/usb/001/003", r.deviceKeyOf("1-2"))
    }

    @Test
    fun wireSizesMatchTheKernelStructs() {
        val info = FakeBackend().info
        assertEquals(312, UsbIp.DEVICE_STRUCT_SIZE)
        assertEquals(8 + 4 + 312 + 4, UsbIpCodec.encodeDevList(listOf(info)).size)
        assertEquals(8 + 312, UsbIpCodec.encodeImportReply(UsbIp.ST_OK, info).size)
        assertEquals(8, UsbIpCodec.encodeImportReply(UsbIp.ST_NODEV, null).size)
    }

    @Test
    fun bufferPoolReusesBuffers() {
        val pool = BufferPool()
        val a = pool.acquire(64)
        assertEquals(64, a.limit())
        pool.release(a)
        assertSame(a, pool.acquire(10))
        val big = pool.acquire(100)
        assertEquals(128, big.capacity())
        assertEquals(100, big.limit())
        assertNotSame(a, big)
    }

    @Test
    fun parsesG29StyleDescriptors() {
        val device = byteArrayOf(18, 1, 0x00, 0x02, 0, 0, 0, 64, 0x6d, 0x04, 0x4f, 0xc2.toByte(), 0, 0x89.toByte(), 1, 2, 0, 1)
        val config = byteArrayOf(
            9, 2, 41, 0, 1, 1, 0, 0x80.toByte(), 50,
            9, 4, 0, 0, 2, 3, 0, 0, 0,
            9, 0x21, 0x11, 1, 0, 1, 0x22, 0xa0.toByte(), 0,
            7, 5, 0x81.toByte(), 3, 64, 0, 1,
            7, 5, 0x01, 3, 64, 0, 1,
        )
        val parsed = assertNotNull(Descriptors.parse(device + config))
        assertEquals(0x046d, parsed.vendorId)
        assertEquals(0xc24f, parsed.productId)
        val iface = parsed.configurations.single().interfaces.single()
        assertEquals(3, iface.interfaceClass)
        assertEquals(listOf(0x81, 0x01), iface.endpoints.map { it.address })
        assertEquals(UsbIp.Speed.FULL, Descriptors.guessSpeed(parsed))
    }

    @Test
    fun highSpeedBulkDeviceIsDetected() {
        val device = byteArrayOf(18, 1, 0x00, 0x02, 0, 0, 0, 64, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1)
        val config = byteArrayOf(
            9, 2, 32, 0, 1, 1, 0, 0x80.toByte(), 50,
            9, 4, 0, 0, 2, 8, 6, 0x50, 0,
            7, 5, 0x81.toByte(), 2, 0, 2, 0,
            7, 5, 0x02, 2, 0, 2, 0,
        )
        assertEquals(UsbIp.Speed.HIGH, Descriptors.guessSpeed(Descriptors.parse(device + config)!!))
    }
}
