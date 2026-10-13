package com.jslsystems.usbip.core

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB/IP wire format (protocol version 1.1.1), as implemented by the Linux kernel
 * (drivers/usb/usbip) and by usbip-win2. All integers are big-endian.
 */
object UsbIp {
    const val VERSION = 0x0111
    const val DEFAULT_PORT = 3240

    // Operation codes (connection setup phase).
    const val OP_REQ_DEVLIST = 0x8005
    const val OP_REP_DEVLIST = 0x0005
    const val OP_REQ_IMPORT = 0x8003
    const val OP_REP_IMPORT = 0x0003

    const val ST_OK = 0
    const val ST_NA = 1        // device not available / busy
    const val ST_DEV_BUSY = 2
    const val ST_DEV_ERR = 3
    const val ST_NODEV = 4
    const val ST_ERROR = 5

    // URB phase commands.
    const val CMD_SUBMIT = 1
    const val CMD_UNLINK = 2
    const val RET_SUBMIT = 3
    const val RET_UNLINK = 4

    const val DIR_OUT = 0
    const val DIR_IN = 1

    const val HEADER_SIZE = 48
    const val OP_HEADER_SIZE = 8
    const val BUSID_SIZE = 32
    const val PATH_SIZE = 256
    const val DEVICE_STRUCT_SIZE = PATH_SIZE + BUSID_SIZE + 12 + 6 + 6 // 312
    const val ISO_DESCRIPTOR_SIZE = 16

    /** Linux errno values carried as negative `status` in RET_SUBMIT / RET_UNLINK. */
    object Errno {
        const val ENOENT = -2
        const val EPIPE = -32
        const val ETIME = -62
        const val EPROTO = -71
        const val EOVERFLOW = -75
        const val ECONNRESET = -104
        const val ESHUTDOWN = -108
        const val EXDEV = -18
    }

    /** `enum usb_device_speed` from the Linux kernel. */
    object Speed {
        const val UNKNOWN = 0
        const val LOW = 1
        const val FULL = 2
        const val HIGH = 3
        const val WIRELESS = 4
        const val SUPER = 5
        const val SUPER_PLUS = 6
    }
}

data class UsbInterfaceInfo(val interfaceClass: Int, val interfaceSubClass: Int, val interfaceProtocol: Int)

/** What a USB/IP server announces about an exported device (`struct usbip_usb_device`). */
data class UsbDeviceInfo(
    val path: String,
    val busId: String,
    val busNum: Int,
    val devNum: Int,
    val speed: Int,
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val configurationValue: Int,
    val numConfigurations: Int,
    val interfaces: List<UsbInterfaceInfo>,
) {
    val devId: Int get() = (busNum shl 16) or devNum

    fun writeTo(buf: ByteBuffer, withInterfaces: Boolean) {
        putFixedString(buf, path, UsbIp.PATH_SIZE)
        putFixedString(buf, busId, UsbIp.BUSID_SIZE)
        buf.putInt(busNum)
        buf.putInt(devNum)
        buf.putInt(speed)
        buf.putShort(vendorId.toShort())
        buf.putShort(productId.toShort())
        buf.putShort(bcdDevice.toShort())
        buf.put(deviceClass.toByte())
        buf.put(deviceSubClass.toByte())
        buf.put(deviceProtocol.toByte())
        buf.put(configurationValue.toByte())
        buf.put(numConfigurations.toByte())
        buf.put(interfaces.size.toByte())
        if (withInterfaces) {
            for (i in interfaces) {
                buf.put(i.interfaceClass.toByte())
                buf.put(i.interfaceSubClass.toByte())
                buf.put(i.interfaceProtocol.toByte())
                buf.put(0) // padding
            }
        }
    }

    fun describe(): String = "%04x:%04x busid=%s".format(vendorId, productId, busId)
}

/** The 8-byte SETUP packet of a control transfer. */
data class SetupPacket(val requestType: Int, val request: Int, val value: Int, val index: Int, val length: Int) {
    val isDeviceToHost: Boolean get() = requestType and 0x80 != 0

    companion object {
        /** Setup packets are little-endian, unlike the rest of the USB/IP header. */
        fun parse(bytes: ByteArray): SetupPacket = SetupPacket(
            requestType = bytes[0].toInt() and 0xff,
            request = bytes[1].toInt() and 0xff,
            value = (bytes[2].toInt() and 0xff) or ((bytes[3].toInt() and 0xff) shl 8),
            index = (bytes[4].toInt() and 0xff) or ((bytes[5].toInt() and 0xff) shl 8),
            length = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8),
        )
    }
}

sealed class UrbCommand {
    abstract val seqNum: Int
    abstract val devId: Int
    abstract val direction: Int
    abstract val endpoint: Int

    data class Submit(
        override val seqNum: Int,
        override val devId: Int,
        override val direction: Int,
        override val endpoint: Int,
        val transferFlags: Int,
        val transferBufferLength: Int,
        val startFrame: Int,
        val numberOfPackets: Int,
        val interval: Int,
        val setup: ByteArray,
        /** OUT payload; empty for IN transfers. */
        val data: ByteArray,
    ) : UrbCommand() {
        val isIn: Boolean get() = direction == UsbIp.DIR_IN

        /** Non-isochronous URBs carry 0 (Linux) or 0xffffffff (usbip-win2) here. */
        val isIsochronous: Boolean get() = numberOfPackets != 0 && numberOfPackets != -1
    }

    data class Unlink(
        override val seqNum: Int,
        override val devId: Int,
        override val direction: Int,
        override val endpoint: Int,
        val unlinkSeqNum: Int,
    ) : UrbCommand()
}

object UsbIpCodec {

    class ProtocolException(message: String) : java.io.IOException(message)

    /** Upper bound for a single transfer; protects against garbage lengths from a broken peer. */
    const val MAX_TRANSFER = 1 shl 20

    fun opHeader(code: Int, status: Int): ByteBuffer =
        ByteBuffer.allocate(UsbIp.OP_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(UsbIp.VERSION.toShort())
            putShort(code.toShort())
            putInt(status)
        }

    fun encodeDevList(devices: List<UsbDeviceInfo>): ByteArray {
        val size = UsbIp.OP_HEADER_SIZE + 4 +
            devices.sumOf { UsbIp.DEVICE_STRUCT_SIZE + 4 * it.interfaces.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.put(opHeader(UsbIp.OP_REP_DEVLIST, UsbIp.ST_OK).array())
        buf.putInt(devices.size)
        devices.forEach { it.writeTo(buf, withInterfaces = true) }
        return buf.array()
    }

    fun encodeImportReply(status: Int, device: UsbDeviceInfo?): ByteArray {
        if (status != UsbIp.ST_OK || device == null) {
            return opHeader(UsbIp.OP_REP_IMPORT, if (status == UsbIp.ST_OK) UsbIp.ST_ERROR else status).array()
        }
        val buf = ByteBuffer.allocate(UsbIp.OP_HEADER_SIZE + UsbIp.DEVICE_STRUCT_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(opHeader(UsbIp.OP_REP_IMPORT, UsbIp.ST_OK).array())
        device.writeTo(buf, withInterfaces = false)
        return buf.array()
    }

    /**
     * Reads one URB-phase command. Returns null on a clean end of stream.
     * [header] must be a reusable [UsbIp.HEADER_SIZE]-byte array.
     */
    fun readCommand(input: DataInputStream, header: ByteArray): UrbCommand? {
        try {
            input.readFully(header, 0, 4)
        } catch (e: java.io.EOFException) {
            return null
        }
        input.readFully(header, 4, UsbIp.HEADER_SIZE - 4)
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val command = b.getInt()
        val seqNum = b.getInt()
        val devId = b.getInt()
        val direction = b.getInt()
        val endpoint = b.getInt()
        return when (command) {
            UsbIp.CMD_SUBMIT -> {
                val flags = b.getInt()
                val length = b.getInt()
                val startFrame = b.getInt()
                val packets = b.getInt()
                val interval = b.getInt()
                val setup = ByteArray(8).also { b.get(it) }
                if (length < 0 || length > MAX_TRANSFER) throw ProtocolException("bad transfer length $length")
                val data = if (direction == UsbIp.DIR_OUT && length > 0) {
                    ByteArray(length).also { input.readFully(it) }
                } else {
                    EMPTY
                }
                val submit = UrbCommand.Submit(
                    seqNum, devId, direction, endpoint, flags, length, startFrame, packets, interval, setup, data,
                )
                if (submit.isIsochronous) {
                    if (packets < 0 || packets > 1024) throw ProtocolException("bad packet count $packets")
                    // Consume the ISO descriptors so the stream stays in sync; isochronous is unsupported.
                    input.readFully(ByteArray(packets * UsbIp.ISO_DESCRIPTOR_SIZE))
                }
                submit
            }
            UsbIp.CMD_UNLINK -> UrbCommand.Unlink(seqNum, devId, direction, endpoint, unlinkSeqNum = b.getInt())
            else -> throw ProtocolException("unknown command 0x%08x".format(command))
        }
    }

    /**
     * Writes the RET_SUBMIT header into [out]; the caller appends the IN payload, if any.
     * Like the Linux stub driver, devid/direction/ep are zero; clients match replies by seqnum.
     */
    fun encodeRetSubmitHeader(out: ByteBuffer, seqNum: Int, status: Int, actualLength: Int, numberOfPackets: Int) {
        out.clear()
        out.putInt(UsbIp.RET_SUBMIT)
        out.putInt(seqNum)
        out.putInt(0)
        out.putInt(0)
        out.putInt(0)
        out.putInt(status)
        out.putInt(actualLength)
        out.putInt(0) // start_frame
        out.putInt(numberOfPackets)
        out.putInt(0) // error_count
        out.putLong(0) // padding
    }

    fun encodeRetUnlink(out: ByteBuffer, seqNum: Int, status: Int) {
        out.clear()
        out.putInt(UsbIp.RET_UNLINK)
        out.putInt(seqNum)
        out.putInt(0)
        out.putInt(0)
        out.putInt(0)
        out.putInt(status)
        repeat(6) { out.putInt(0) }
    }

    fun readBusId(input: DataInputStream): String {
        val raw = ByteArray(UsbIp.BUSID_SIZE)
        input.readFully(raw)
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }

    private val EMPTY = ByteArray(0)
}

internal fun putFixedString(buf: ByteBuffer, value: String, size: Int) {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    val n = minOf(bytes.size, size - 1)
    buf.put(bytes, 0, n)
    repeat(size - n) { buf.put(0) }
}
