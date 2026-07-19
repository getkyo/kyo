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

    // Unsafe: created at construction with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the caller's
    // AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Each driver's terminal-teardown signal, as returned by its own start(). Retained rather than discarded so close() can hand back a real
    // "the fds are gone" fiber instead of a bare request. Written once by start() before the pool is handed to a transport, and only read
    // afterwards, so a plain field is sufficient: the publication happens-before any close() a caller can reach.
    @volatile private var doneFibers: Array[Fiber.Unsafe[Unit, Any]] = null

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
        val started = new Array[Fiber.Unsafe[Unit, Any]](drivers.length)
        @tailrec def loop(i: Int): Unit =
            if i < drivers.length then
                try started(i) = drivers(i).start()
                catch
                    case ex: Throwable if NonFatal(ex) =>
                        // All-or-nothing: a partially-started pool is never handed to the transport. Close the already-started
                        // subset (close() skips Absent slots and is CAS-guarded) and rethrow, so the transport build fails atomically
                        // rather than running fewer drivers than ioPoolSize requested. Guarded on NonFatal: on a fatal/control
                        // throwable the process is dying, so the subset-close is moot; let the fatal propagate uncaught.
                        // Publish only the prefix that actually started: the remaining slots are still null, and handing those to the
                        // close path would NPE while it chained completions, masking the real start failure being rethrown here.
                        doneFibers = started.take(i)
                        discard(close())
                        throw ex
                end try
                loop(i + 1)
        loop(0)
        // Publish only after every driver started, so close() either sees the complete set or (on the failure path above) the
        // subset assigned there. Each entry completes when that driver's loop has finished its terminal teardown — that is, after
        // its poller fd is closed and its scratch freed — which is what makes the close below a real release signal rather than
        // just a request.
        doneFibers = started
    end start

    /** Close all drivers.
      *
      * Idempotent via AtomicBoolean guard. Each driver's close() is also independently CAS-guarded, so calling it here is safe even if a
      * driver was already closed directly.
      *
      * Does NOT interrupt each driver's event-loop fiber: every driver's own `close()` is responsible for bringing its loop down (NIO closes
      * its selector directly, which aborts a blocked `select()`; the posix io_uring/poller drivers wake their loop and let it observe the
      * close signal on its own carrier). For io_uring and the poller that self-teardown is deferred to the loop's own carrier (their pending-op
      * bookkeeping is carrier-confined and cannot be swept from here), so an unconditional fiber interrupt issued right after signaling close
      * could abort the loop before it reaches that deferred teardown, permanently stranding a handle whose close was mid-flight: exactly
      * the fd leak an unconditional interrupt would cause. Trust each driver's own close() contract instead of racing it.
      */
    def close()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        if closedFlag.compareAndSet(false, true) then
            @tailrec def closeLoop(i: Int): Unit =
                if i < drivers.length then
                    drivers(i).close()
                    closeLoop(i + 1)
            closeLoop(0)
        end if
        awaitTornDown()
    end close

    /** A fiber completing once every started driver has finished its terminal teardown, so the pool's poller/ring fds are released and its
      * scratch freed.
      *
      * This is what gives [[close]] backpressure. Each driver's `start()` hands back exactly this signal and the pool retains it; a caller
      * that awaits the returned fiber knows the descriptors are gone, while one that does not must `discard` it explicitly. Without the
      * await, `close()` only REQUESTS teardown — the driver's terminal hop is a scheduled activation, so the fds outlive the call, and a
      * caller that closes one transport and immediately opens another transiently holds both.
      *
      * Completes immediately when the pool never started (no driver is running, and `close()` on a never-started driver releases its scratch
      * directly), and is safe to call repeatedly: it only observes the retained promises, never mutates them.
      *
      * Never blocks: completion is chained through `onComplete`, so awaiting it suspends the calling fiber and frees its carrier for the
      * driver's own terminal activation to run on.
      */
    private def awaitTornDown()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Every entry is non-null: start() publishes either the full set or, on its failure path, only the prefix that actually started.
        // That trim matters — handing this loop a half-filled array would NPE here and mask the start failure being rethrown.
        val fibers  = doneFibers
        val promise = Promise.Unsafe.init[Unit, Any]()
        if (fibers eq null) || fibers.isEmpty then promise.completeDiscard(Result.succeed(()))
        else
            val remaining = AtomicInt.Unsafe.init(fibers.length)
            var i         = 0
            while i < fibers.length do
                // Ignore each driver's own outcome: what this pool needs to observe is only that the loop FINISHED, not how.
                //
                // Whether finishing also released the driver's fds is the driver's own contract, and it is NOT uniform today.
                // PollerIoDriver routes a crashed cycle through the same terminal exit as a clean one, so its poller fd and scratch are
                // released either way. IoUringDriver and NioIoDriver instead complete this promise straight from their thread wrapper
                // without running their teardown, so a crashed loop there leaks its ring/selector. That gap is the drivers', not the
                // pool's; converting them to the single-terminal-exit shape closes it.
                fibers(i).onComplete(_ => if remaining.decrementAndGet() == 0 then promise.completeDiscard(Result.succeed(())))
                i += 1
            end while
        end if
        promise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end awaitTornDown

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
