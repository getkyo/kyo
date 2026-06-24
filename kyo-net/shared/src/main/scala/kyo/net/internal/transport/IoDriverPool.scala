package kyo.net.internal.transport

import kyo.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Fixed-size pool of I/O drivers for parallel event processing. Channels are assigned to drivers via round-robin. Each driver runs its own
  * event loop.
  */
final private[kyo] class IoDriverPool[Handle] private (
    private val drivers: Array[IoDriver[Handle]],
    private val fibers: Array[Maybe[Fiber.Unsafe[Unit, Any]]],
    private val counter: AtomicLong.Unsafe
):

    // Unsafe: created at construction with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the caller's
    // AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    /** Number of drivers in the pool. */
    private[kyo] def size: Int = drivers.length

    /** Return the next driver via round-robin.
      *
      * Thread-safe. Uses an atomic counter to distribute handles evenly across drivers.
      */
    def next()(using AllowUnsafe): IoDriver[Handle] =
        // Mask the sign bit BEFORE the modulo: once the AtomicLong counter wraps past Long.MaxValue it goes negative, and `Math.abs` cannot
        // recover (`Math.abs(Long.MinValue)` is still Long.MinValue, an overflow), so the old `Math.abs(idx)` could index out of bounds or skew
        // the rotation at the wrap. Clearing the top bit yields a non-negative value, so the modulo gives a clean 0..N-1 rotation across the wrap.
        val idx = ((counter.getAndIncrement() & Long.MaxValue) % drivers.length).toInt
        drivers(idx)
    end next

    /** Start all event loops. Stores fiber references for shutdown.
      *
      * Must be called exactly once after construction, before any calls to next(). If any driver fails to start, the already-started drivers
      * are closed, their fibers interrupted, and the failure is rethrown (all-or-nothing).
      */
    def start()(using AllowUnsafe, Frame): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < drivers.length then
                val started =
                    try drivers(i).start()
                    catch
                        case ex: Throwable if NonFatal(ex) =>
                            // All-or-nothing: a partially-started pool is never handed to the transport. Close the already-started
                            // subset (close() skips Absent slots and is CAS-guarded) and rethrow, so the transport build fails atomically
                            // rather than running fewer drivers than ioPoolSize requested. Guarded on NonFatal: on a fatal/control
                            // throwable the process is dying, so the subset-close is moot; let the fatal propagate uncaught.
                            close()
                            throw ex
                    end try
                end started
                fibers(i) = Present(started)
                loop(i + 1)
        loop(0)
    end start

    /** Close all drivers and interrupt event loop fibers.
      *
      * Closes drivers first to stop accepting new work, then interrupts all fibers. Idempotent via AtomicBoolean guard. Each driver's
      * close() is also independently CAS-guarded. Fiber slots that were never started (still Absent) are skipped.
      */
    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            @tailrec def closeLoop(i: Int): Unit =
                if i < drivers.length then
                    drivers(i).close()
                    closeLoop(i + 1)
            closeLoop(0)
            @tailrec def interruptLoop(i: Int): Unit =
                if i < fibers.length then
                    fibers(i).foreach(f => discard(f.interrupt(Result.Panic(new Exception("IoDriverPool closed")))))
                    interruptLoop(i + 1)
            interruptLoop(0)
    end close

end IoDriverPool

private[kyo] object IoDriverPool:

    /** Create a pool wrapping the given drivers.
      *
      * @param drivers
      *   Array of drivers; must have length >= 1
      * @return
      *   Pool ready for start()
      */
    def init[Handle](drivers: Array[IoDriver[Handle]])(using AllowUnsafe): IoDriverPool[Handle] =
        init(drivers, 0L)

    private[kyo] def init[Handle](drivers: Array[IoDriver[Handle]], initialCounter: Long)(using AllowUnsafe): IoDriverPool[Handle] =
        require(drivers.length > 0, "IoDriverPool requires at least one driver")
        new IoDriverPool(
            drivers,
            Array.fill[Maybe[Fiber.Unsafe[Unit, Any]]](drivers.length)(Absent),
            AtomicLong.Unsafe.init(initialCounter)
        )
    end init

end IoDriverPool
