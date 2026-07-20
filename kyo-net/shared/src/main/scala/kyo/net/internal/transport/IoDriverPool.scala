package kyo.net.internal.transport

import kyo.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Fixed-size pool of I/O drivers for parallel event processing. Channels are assigned to drivers via round-robin. Each driver runs its own
  * event loop.
  */
final private[kyo] class IoDriverPool[Handle] private (
    private val drivers: Array[IoDriver[Handle]],
    private val counter: AtomicLong.Unsafe
):

    /** Number of drivers in the pool. */
    private[kyo] def size: Int = drivers.length

    /** Return the next driver via round-robin.
      *
      * Thread-safe. Uses an atomic counter to distribute handles evenly across drivers.
      */
    def next()(using AllowUnsafe): IoDriver[Handle] =
        // Mask the sign bit BEFORE the modulo: once the AtomicLong counter wraps past Long.MaxValue it goes negative, and `Math.abs` cannot
        // recover (`Math.abs(Long.MinValue)` is still Long.MinValue, an overflow), so a plain `Math.abs(idx)` would index out of bounds or skew
        // the rotation at the wrap. Clearing the top bit yields a non-negative value, so the modulo gives a clean 0..N-1 rotation across the wrap.
        val idx = ((counter.getAndIncrement() & Long.MaxValue) % drivers.length).toInt
        drivers(idx)
    end next

    /** Start all event loops.
      *
      * Must be called exactly once after construction, before any calls to next(). If any driver fails to start, the already-started drivers
      * are closed and the failure is rethrown (all-or-nothing).
      */
    def start()(using AllowUnsafe, Frame): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < drivers.length then
                try discard(drivers(i).start())
                catch
                    case ex: Throwable if NonFatal(ex) =>
                        // All-or-nothing: a partially-started pool is never handed to a transport. Tear the already-started prefix down and
                        // rethrow, so the build fails atomically rather than running fewer drivers than ioPoolSize requested. This is the ONE
                        // place a driver is closed from outside its own terminal exit: a transport is process-lifetime and never shuts down, so
                        // a pool that was successfully handed over is never torn down. Guarded on NonFatal: on a fatal throwable the process is
                        // dying and the rollback is moot, so let it propagate uncaught.
                        rollback(i)
                        throw ex
                end try
                loop(i + 1)
        loop(0)
    end start

    /** Close the first `startedCount` drivers after a failed start. Each driver's own close is CAS-guarded, and the pool is discarded
      * immediately afterwards, so this neither needs nor waits for a teardown signal.
      */
    private def rollback(startedCount: Int)(using AllowUnsafe, Frame): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < startedCount then
                discard(drivers(i).close())
                loop(i + 1)
        loop(0)
    end rollback

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
        new IoDriverPool(drivers, AtomicLong.Unsafe.init(initialCounter))
    end init

end IoDriverPool
