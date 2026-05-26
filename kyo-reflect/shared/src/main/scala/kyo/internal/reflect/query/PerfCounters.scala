package kyo.internal.reflect.query

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared performance counters for cold-load timing instrumentation.
  *
  * Populated by JvmFileSource (jarOpenCount) and ClasspathOrchestrator (entryReadCount, bytesReadTotal). Read only when
  * -Dkyo.reflect.timing=true is set; increments happen unconditionally but are cheap.
  *
  * Private to the kyo package so this never appears in public API.
  */
private[kyo] object PerfCounters:
    val jarOpenCount: AtomicInteger    = new AtomicInteger(0)
    val entryReadCount: AtomicInteger  = new AtomicInteger(0)
    val bytesReadTotal: AtomicLong     = new AtomicLong(0L)
    val jarConstructTimeNs: AtomicLong = new AtomicLong(0L)
    val jarReadTimeNs: AtomicLong      = new AtomicLong(0L)

    def reset(): Unit =
        jarOpenCount.set(0)
        entryReadCount.set(0)
        bytesReadTotal.set(0L)
        jarConstructTimeNs.set(0L)
        jarReadTimeNs.set(0L)
    end reset
end PerfCounters
