package kyo.internal

import kyo.*
import scala.annotation.tailrec

// JVM/Native only: requires OS threads (java.lang.Thread) and Runtime.addShutdownHook,
// both absent on JS/Wasm (single-threaded).
// Requires multithreaded Scala Native runtime (same assumption as kyo-scheduler itself).
private[kyo] trait LogPlatformSpecific extends LogShared:

    // The lazily-initialized global daemon. Forced on the first async emit when async is enabled.
    // The lazy val is double-checked and thread-safe by the JVM/Native language spec, so concurrent
    // first callers observe exactly one daemon.
    private lazy val daemon: LogDaemon.Daemon =
        LogDaemon.start()

    /** Enqueues an event to the daemon, or writes it synchronously when async is disabled or
      * overflowed.
      *
      * Reads the resolve-once `kyo.Log.asyncLogging` flag. When false, writes inline. When true,
      * forces the daemon and offers the event; on a full channel the daemon's resolved overflow
      * policy decides (`SyncFallback` writes the overflowing event inline without dropping;
      * `DropBelow` drops strictly below its level). The `Result[Closed, Boolean]` from offer is
      * absorbed here, so no `Abort[Closed]` reaches the call-site row.
      */
    private[kyo] def emit(event: Log.Event)(using allow: AllowUnsafe, frame: Frame): Unit =
        if !Log.asyncLogging() then write(event)
        else
            // Unsafe: the bounded channel offer is non-blocking and returns a value, satisfying
            // the Unit < Sync call-site guarantee.
            enqueueWith(event, daemon.channel, daemon.overflow)

    /** Suspends until the daemon has dispatched every currently-enqueued event.
      *
      * Registers a waiter the drain completes when it next observes the channel empty (every event
      * the caller enqueued before this call has drained by then), then offers a fence to wake an
      * idle drain so it re-checks emptiness. The fence is best-effort: if the channel is full the
      * offer is dropped, but the waiter still fires because a busy drain reaches empty on its own.
      * Returns `Unit` immediately when async is disabled, since all writes are already inline.
      */
    private[kyo] def flushDaemon(using Frame): Unit < Async =
        if !Log.asyncLogging() then ()
        else
            // Unsafe: Promise.Unsafe.init and channel.offer are non-suspending; defer provides
            // AllowUnsafe so p.safe.get suspends in the Async row.
            Sync.Unsafe.defer {
                val p = Promise.Unsafe.init[Unit, Any]()
                daemon.flushWaiters.add(p)
                // Wake the drain so it re-checks emptiness even if idle. A dropped offer on a full
                // channel is harmless: the busy drain reaches empty on its own and completes p.
                discard(daemon.channel.offer(LogShared.fence))
                p.safe.get
            }

    /** The number of times the global daemon has been initialized: 1 once forced, 0 while cold. */
    private[kyo] def daemonInitCount(using AllowUnsafe): Int =
        LogDaemon.initCount.get()

end LogPlatformSpecific

private[kyo] object LogDaemon:

    final class Daemon(
        val channel: Channel.Unsafe[Log.Event],
        val flushWaiters: Queue.Unbounded.Unsafe[Promise.Unsafe[Unit, Any]],
        val overflow: Log.Overflow
    )

    // Unsafe: object-init singleton (Category B): no construction site, initialized once
    // outside any effect; AllowUnsafe.embrace.danger is the one sanctioned site per rules.md.
    private[kyo] val initCount: AtomicInt.Unsafe =
        import AllowUnsafe.embrace.danger
        AtomicInt.Unsafe.init(0)

    def start(): Daemon =
        // Unsafe: start() is invoked from a lazy val initializer; no AllowUnsafe can propagate
        // from that boundary, so this is the singleton-init embrace.danger site.
        import AllowUnsafe.embrace.danger
        given Frame      = Frame.internal
        val channel      = Channel.Unsafe.init[Log.Event](Math.max(1, Log.asyncLogging.capacity()), Access.MultiProducerSingleConsumer)
        val flushWaiters = Queue.Unbounded.Unsafe.init[Promise.Unsafe[Unit, Any]](Access.MultiProducerMultiConsumer)
        val overflow     = Log.asyncLogging.overflow()
        // Spawn the single detached drain fiber. Fiber.Unsafe.init runs the `< Async` drain body to
        // completion inside a fresh fire-and-forget fiber.
        discard(Fiber.Unsafe.init(runDrain(channel, flushWaiters)))
        // Drain-on-exit: close the channel and dispatch the remainder before the process exits.
        // A hard kill (SIGKILL) can still lose the tail; this is inherent to async logging.
        java.lang.Runtime.getRuntime.addShutdownHook(new Thread(() =>
            channel.close() match
                case Present(remaining) =>
                    // Unsafe: shutdown hook runs in a raw JVM Thread outside the kyo effect system;
                    // AllowUnsafe cannot be propagated from any kyo caller at this boundary.
                    given AllowUnsafe = AllowUnsafe.embrace.danger
                    given Frame       = Frame.internal
                    remaining.foreach { e =>
                        if e ne LogShared.fence then
                            // Contain ANY throw (not just NonFatal): shutdown hook must not propagate.
                            try LogShared.dispatch(e)
                            catch case t: Throwable => reportDrainFailure(e, t)
                    }
                case Absent => ()
        ))
        discard(initCount.incrementAndGet())
        new Daemon(channel, flushWaiters, overflow)
    end start

    /** The single drain fiber's body: stream the channel until closed, dispatching each non-fence
      * event to its carried sink and completing pending flush waiters whenever the channel reaches
      * empty.
      *
      * A failing sink cannot kill the drain: each dispatch is a containment boundary that reports
      * any throw off the Log path (direct stderr, never `Log.*`, never re-thrown) and continues to
      * the next event. Flush waiters complete on channel-empty rather than on a dequeued fence, so a
      * fence dropped on a full channel does not strand a waiter; the fence only wakes an idle drain.
      * The channel is drained one event per pull so that channel-empty is an accurate "every
      * enqueued event has been dispatched" signal for the flush contract.
      */
    private[kyo] def runDrain(
        channel: Channel.Unsafe[Log.Event],
        flushWaiters: Queue.Unbounded.Unsafe[Promise.Unsafe[Unit, Any]]
    )(using Frame): Unit < Async =
        // Drain one event per pull (maxChunkSize = 1). A greedy multi-element chunk removes every
        // queued event from the channel before any is dispatched, so the per-event channel.empty()
        // check below would observe empty after the first dispatch and complete a flush waiter while
        // later events are still undispatched. One event at a time keeps empty() accurate.
        channel.safe.streamUntilClosed(maxChunkSize = 1).foreach { event =>
            Sync.Unsafe.defer {
                if event ne LogShared.fence then
                    // Contain ANY throw (not just NonFatal): the drain must survive a failing sink
                    // (a broken appender, an NPE, a serialization fault). The throw is reported off
                    // the Log path and the drain continues with the next event.
                    try LogShared.dispatch(event)
                    catch
                        case t: Throwable => reportDrainFailure(event, t)
                end if
                // After dispatching (or skipping a fence), complete pending flush waiters once the
                // channel has drained to empty. Every event a flush caller enqueued before
                // registering its waiter has been dispatched by the time the channel is observed
                // empty, so the waiter's flush contract holds.
                if channel.empty().contains(true) then drainFlushWaiters(flushWaiters)
            }
        }
    end runDrain

    @tailrec
    private def drainFlushWaiters(q: Queue.Unbounded.Unsafe[Promise.Unsafe[Unit, Any]])(using AllowUnsafe, Frame): Unit =
        q.poll() match
            case Result.Success(Present(w)) =>
                w.completeUnitDiscard()
                drainFlushWaiters(q)
            case _ => ()
    end drainFlushWaiters

    // Off-daemon failure report: write INLINE to Log.live's console sink via emit (the terminal writeLine
    // path) -- a fixed sink, never the failing event.sink, and never the async dispatcher. reportDrainFailure
    // runs ON the drain fiber, so routing through Log.live.unsafe.error (async by default since the unsafe
    // tier now dispatches) would re-enqueue the failure into the drain's own channel; emit() writes
    // synchronously and cannot recurse. Contain ANY throw: the reporter runs inside a catch handler whose
    // escape would kill the drain fiber, and emit/the clock read can themselves throw (a broken stderr, a
    // Throwable whose printStackTrace throws), so the whole call is wrapped and any throw absorbed.
    // event.message is the already-forced message string, so reading it is safe.
    private def reportDrainFailure(event: Log.Event, error: Throwable)(using Frame, AllowUnsafe): Unit =
        try
            // Reuse the failing event's own timestamp rather than reading the clock: this is an error-path
            // report tied to that event, so a fresh clock read (and the Sync.Unsafe.evalOrThrow bridge it
            // would need here) is not warranted.
            Log.live.unsafe.emit(
                Log.Event(
                    Log.Level.error,
                    Log.live.unsafe,
                    "Log drain failed for '" + event.message + "'",
                    Present(error),
                    summon[Frame],
                    event.timestamp
                )
            )
        catch case _: Throwable => ()

end LogDaemon
