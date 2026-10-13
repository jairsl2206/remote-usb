package com.jslsystems.usbip.core

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Callbacks from the server; invoked on server threads. */
interface ServerEvents {
    fun log(level: LogLevel, message: String)
    fun sessionsChanged() {}
}

/**
 * Preallocated direct buffers for in-flight URBs, bucketed by power-of-two capacity, so the
 * steady state of a wheel (a few interrupt URBs in flight) allocates nothing.
 */
class BufferPool(private val maxPerBucket: Int = 32, private val maxPooledSize: Int = 64 * 1024) {
    private val buckets = HashMap<Int, ArrayDeque<ByteBuffer>>()

    fun acquire(size: Int): ByteBuffer {
        val capacity = bucketFor(size)
        val buf = if (capacity <= maxPooledSize) {
            synchronized(buckets) { buckets[capacity]?.pollFirst() }
        } else {
            null
        } ?: ByteBuffer.allocateDirect(capacity)
        buf.clear()
        buf.limit(size)
        return buf
    }

    fun release(buf: ByteBuffer) {
        val capacity = buf.capacity()
        if (capacity > maxPooledSize || Integer.bitCount(capacity) != 1) return
        synchronized(buckets) {
            val q = buckets.getOrPut(capacity) { ArrayDeque() }
            if (q.size < maxPerBucket) q.addFirst(buf)
        }
    }

    private fun bucketFor(size: Int): Int {
        if (size <= 64) return 64
        return Integer.highestOneBit(size - 1) shl 1
    }
}

/** Counters shown in the app's status screen (F4: latency and throughput). */
class SessionStats {
    val urbsSubmitted = AtomicLong()
    val urbsCompleted = AtomicLong()
    val unlinks = AtomicLong()
    val errors = AtomicLong()
    val bytesToClient = AtomicLong()
    val bytesFromClient = AtomicLong()
    val controlCount = AtomicLong()
    val controlTotalMicros = AtomicLong()
    val controlMaxMicros = AtomicLong()
    val sendMaxMicros = AtomicLong()

    fun recordControl(micros: Long) {
        controlCount.incrementAndGet()
        controlTotalMicros.addAndGet(micros)
        controlMaxMicros.accumulateAndGet(micros, ::maxOf)
    }

    fun recordSend(micros: Long) {
        sendMaxMicros.accumulateAndGet(micros, ::maxOf)
    }

    fun snapshot() = Snapshot(
        urbsSubmitted = urbsSubmitted.get(),
        urbsCompleted = urbsCompleted.get(),
        unlinks = unlinks.get(),
        errors = errors.get(),
        bytesToClient = bytesToClient.get(),
        bytesFromClient = bytesFromClient.get(),
        controlAvgMicros = controlCount.get().let { if (it == 0L) 0 else controlTotalMicros.get() / it },
        controlMaxMicros = controlMaxMicros.get(),
        sendMaxMicros = sendMaxMicros.get(),
    )

    data class Snapshot(
        val urbsSubmitted: Long,
        val urbsCompleted: Long,
        val unlinks: Long,
        val errors: Long,
        val bytesToClient: Long,
        val bytesFromClient: Long,
        val controlAvgMicros: Long,
        val controlMaxMicros: Long,
        val sendMaxMicros: Long,
    )
}

/**
 * Hands out USB/IP bus IDs ("1-N") that survive re-enumeration: when a device disappears and a
 * device from the same vendor shows up (e.g. a Logitech wheel switching modes and coming back with
 * another product ID), it inherits the old bus ID, so the PC's *Auto* re-attach finds it again.
 */
class BusIdRegistry {
    private data class Slot(val busId: String, val vendorId: Int)

    private val assigned = LinkedHashMap<String, Slot>()     // device key -> slot
    private val vacated = ArrayList<Slot>()                  // most recent last

    @Synchronized
    fun assign(deviceKey: String, vendorId: Int): String {
        assigned[deviceKey]?.let { return it.busId }
        val reuse = vacated.lastOrNull { it.vendorId == vendorId }
        val slot = if (reuse != null) {
            vacated.remove(reuse)
            reuse
        } else {
            val used = (assigned.values + vacated).map { it.busId }.toSet()
            val n = generateSequence(1) { it + 1 }.first { "1-$it" !in used }
            Slot("1-$n", vendorId)
        }
        assigned[deviceKey] = slot
        return slot.busId
    }

    @Synchronized
    fun release(deviceKey: String) {
        assigned.remove(deviceKey)?.let { vacated += it }
    }

    @Synchronized
    fun busIdOf(deviceKey: String): String? = assigned[deviceKey]?.busId

    @Synchronized
    fun deviceKeyOf(busId: String): String? = assigned.entries.firstOrNull { it.value.busId == busId }?.key
}
