package kyo.internal.transport

import kyo.*
import scala.annotation.tailrec

/** Fixed-size pool of I/O drivers for parallel event processing. Channels are assigned to drivers via round-robin. Each driver runs its own
  * event loop.
  */
final private[kyo] class IoDriverPool[Handle] private (
    private val drivers: Array[IoDriver[Handle]],
    private val fibers: Array[Maybe[Fiber.Unsafe[Unit, Any]]],
    private val counter: java.util.concurrent.atomic.AtomicLong
):

    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** Return the next driver via round-robin.
      *
      * Thread-safe. Uses an atomic counter to distribute handles evenly across drivers.
      */
    def next()(using AllowUnsafe): IoDriver[Handle] =
        val idx = (counter.getAndIncrement() % drivers.length).toInt
        drivers(Math.abs(idx))

    /** Start all event loops. Stores fiber references for shutdown.
      *
      * Must be called exactly once after construction, before any calls to next(). If a driver fails to start, its fiber slot is skipped
      * and start continues with the remaining drivers.
      */
    def start()(using AllowUnsafe, Frame): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < drivers.length then
                try fibers(i) = Maybe(drivers(i).start())
                catch
                    case t: Throwable =>
                        Log.live.unsafe.error(s"IoDriverPool: driver $i failed to start", t)
                end try
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
            new java.util.concurrent.atomic.AtomicLong(initialCounter)
        )
    end init

end IoDriverPool
