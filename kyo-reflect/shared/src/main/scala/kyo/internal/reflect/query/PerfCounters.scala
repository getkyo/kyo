package kyo.internal.reflect.query

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared performance counters for cold-load timing instrumentation.
  *
  * Populated by JvmFileSource (jarOpenCount) and ClasspathOrchestrator (entryReadCount, bytesReadTotal, and per-stage decode counters).
  * Read only when -Dkyo.reflect.timing=true is set; increments happen unconditionally but are cheap.
  *
  * Per-stage decode counters accumulate nanoseconds across all decoder worker threads. Divide by worker count to estimate critical-path
  * contribution.
  *
  * Private to the kyo package so this never appears in public API.
  */
private[kyo] object PerfCounters:
    val jarOpenCount: AtomicInteger    = new AtomicInteger(0)
    val entryReadCount: AtomicInteger  = new AtomicInteger(0)
    val bytesReadTotal: AtomicLong     = new AtomicLong(0L)
    val jarConstructTimeNs: AtomicLong = new AtomicLong(0L)
    val jarReadTimeNs: AtomicLong      = new AtomicLong(0L)

    // Per-stage decode counters: cumulative nanoseconds across all decoder fibers.
    val tastyHeaderTimeNs: AtomicLong        = new AtomicLong(0L)
    val nameUnpicklerTimeNs: AtomicLong      = new AtomicLong(0L)
    val sectionIndexTimeNs: AtomicLong       = new AtomicLong(0L)
    val attributeUnpicklerTimeNs: AtomicLong = new AtomicLong(0L)
    val astPass1TimeNs: AtomicLong           = new AtomicLong(0L)
    val commentsUnpicklerTimeNs: AtomicLong  = new AtomicLong(0L)
    val positionsUnpicklerTimeNs: AtomicLong = new AtomicLong(0L)

    def reset(): Unit =
        jarOpenCount.set(0)
        entryReadCount.set(0)
        bytesReadTotal.set(0L)
        jarConstructTimeNs.set(0L)
        jarReadTimeNs.set(0L)
        tastyHeaderTimeNs.set(0L)
        nameUnpicklerTimeNs.set(0L)
        sectionIndexTimeNs.set(0L)
        attributeUnpicklerTimeNs.set(0L)
        astPass1TimeNs.set(0L)
        commentsUnpicklerTimeNs.set(0L)
        positionsUnpicklerTimeNs.set(0L)
    end reset
end PerfCounters
