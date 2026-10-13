package com.jslsystems.usbip.core

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * The platform side of the server: enumerates local USB devices and performs transfers.
 * The Android app implements it with `UsbManager`; tests use an in-memory fake.
 */
interface UsbBackend {
    /** Devices that can be exported right now (permission granted, not a hub). */
    fun exportableDevices(): List<UsbDeviceInfo>

    /** Opens and claims the device for exclusive use, or returns null if that fails. */
    fun open(busId: String): UsbDeviceHandle?
}

/** Completion of an asynchronous transfer started with [UsbDeviceHandle.submit]. */
class Completion(val tag: Any, val actualLength: Int, val status: Int)

interface UsbDeviceHandle : Closeable {
    val info: UsbDeviceInfo

    /**
     * Blocking control transfer on endpoint 0. For IN transfers the data is written to [buffer];
     * for OUT transfers [buffer] holds the payload. Returns the transferred length, or a negative
     * errno (see [UsbIp.Errno]).
     */
    fun control(setup: SetupPacket, buffer: ByteArray, timeoutMs: Int): Int

    /** Selects a configuration (intercepted SET_CONFIGURATION). */
    fun setConfiguration(value: Int): Boolean

    /** Selects an alternate setting (intercepted SET_INTERFACE). */
    fun setInterface(interfaceNumber: Int, alternateSetting: Int): Boolean

    /** Whether [endpointAddress] (with direction bit) exists in the active configuration. */
    fun hasEndpoint(endpointAddress: Int): Boolean

    /**
     * Queues an asynchronous bulk/interrupt transfer. For IN transfers [buffer] has room for the
     * data (position 0, limit = requested length); for OUT it holds the payload. Returns false if
     * the transfer could not be queued.
     */
    fun submit(endpointAddress: Int, buffer: ByteBuffer, tag: Any): Boolean

    /** Requests cancellation of a queued transfer. It is still reported by [reap]. */
    fun cancel(tag: Any): Boolean

    /**
     * Waits up to [timeoutMs] for a completed transfer. Returns null on timeout.
     * Throws [java.io.IOException] if the device is gone.
     */
    fun reap(timeoutMs: Long): Completion?
}
