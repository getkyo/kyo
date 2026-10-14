package kyo.net.internal

import java.io.IOException
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.NetConnectionIoException
import kyo.net.NetDriverUnsupportedException
import kyo.net.NetException
import kyo.net.internal.transport.*
import kyo.net.internal.util.*
import kyo.scheduler.InternalClock
import kyo.scheduler.Scheduler
import kyo.scheduler.Task
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** JVM I/O driver backed by a single `java.nio.channels.Selector`.
  *
  * Drives all I/O for a pool of non-blocking `SocketChannel` connections. Callers register interest (read / write / connect / accept) by
  * depositing a `Promise` into the appropriate pending map and calling the corresponding `awaitX` method, which adds the interest bit to
  * the channel's `SelectionKey`. The event loop (started via `start()`) calls `Selector.select()` in a tight loop, dispatches each ready
  * key, and completes the pending promise.
  *
  * For TLS connections every engine operation goes through the handle's [[NioTlsState]] while its [[NioGate]] is held: `dispatchReadTls`
  * reads and unwraps through it, and writes wrap through it. Every TLS read first unwraps what is already buffered (the last handshake
  * flight's coalesced records, a grace probe's staging): that ciphertext is out of the kernel, so the selector would never fire for it.
  */
final private[kyo] class NioIoDriver private (@volatile private[net] var selector: Selector)
    extends IoDriver[NioHandle]:

    // Unsafe: created at driver construction with no ambient AllowUnsafe; the danger bridge builds it here and every get/compareAndSet runs
    // under the caller's AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Raised by rebuildSelector from before its snapshot of the registered channels until after it re-registered them (see registerChannel).
    // Unsafe: construction-time bridge, as closedFlag.
    private val rebuilding = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Unsafe: mirrors closedFlag pattern. Guards selector.wakeup() so it fires only on the false->true transition; the
    // post-select re-check in pollOnce closes the race window where a wakeup request arrives while select() is returning.
    // private[net] so tests in kyo.net.internal can observe the flag state directly.
    private[net] val wakeupPending = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Count of UNCONDITIONAL connect-arm wakeups (armConnectInterest). private[net] test-observability: a deterministic test asserts a connect arm
    // ALWAYS issues a wakeup even when wakeupPending is already set (the coalescing condition that a guarded wakeup would lose), proving the
    // forceReadArmWakeup-class connect fix is load-independent. Not used by production logic.
    private[net] val connectWakeups = new java.util.concurrent.atomic.AtomicInteger(0)

    // Count of consecutive selector.select() calls returning zero keys on the select-loop carrier.
    // Read and written only from pollOnce() on the single select-loop fiber (single-carrier-confined).
    private var zeroKeyReturns: Int = 0

    // Flat array-backed key set installed via reflection; Absent when InaccessibleObjectException blocks the install.
    private val installedKeySet: Maybe[SelectedSelectionKeySet] =
        // Unsafe: reflection install runs at construction under the danger bridge (same pattern as closedFlag).
        installSelectedKeySet(selector)(using AllowUnsafe.embrace.danger)

    // Concurrent-collection audit: the four pending-op maps below are raw java.util.concurrent.ConcurrentHashMap. kyo has no
    // concurrent-map type, and its effect-based collections cannot back this driver's non-parking selector path (these maps are read/written on
    // the selector carrier when arming and on the caller's carrier on cancel/close, with no suspension). The raw type is retained as a documented
    // no-equivalent exception; each map is a channel -> pending-state entry, removed by cleanupPending/closeHandle.
    // Pending read requests: channel -> handle (promise stored on handle.readArm)
    private val pendingReads =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, NioHandle]()

    /** Test-observability seam: whether a pending read is still registered for `handle`'s channel. A handshake teardown that reaps the handle
      * through `closeHandle` removes this entry; a bare channel close leaves it stranded (a pendingReads leak). Read-only, no mutation.
      */
    private[kyo] def hasPendingRead(handle: NioHandle)(using AllowUnsafe): Boolean =
        pendingReads.containsKey(handle.channel)

    // Pending writable requests: channel -> promise
    private val pendingWritables =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, Promise.Unsafe[Unit, Abort[Closed | NetException]]]()

    // Pending connect requests: channel -> (promise, handle). The handle is carried so a driver-side connect failure (dispatchConnect's
    // finishConnect IOException) can name the connection it belongs to and its creation frame, rather than the driver.
    private val pendingConnects =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, (Promise.Unsafe[Unit, Abort[Closed | NetException]], NioHandle)]()

    // Pending accept requests: server channel -> promise
    private val pendingAccepts =
        new java.util.concurrent.ConcurrentHashMap[ServerSocketChannel, Promise.Unsafe[Unit, Abort[Closed]]]()

    // Concurrent-collection audit: registerChannel's deferred path enqueues a handle here for the poll carrier to register on its next cycle.
    // kyo has no concurrent-queue type, so a raw ConcurrentLinkedQueue is the documented no-equivalent exception (same justification as the
    // pending-op maps above): producers are arbitrary caller carriers (offer on the CancelledKeyException path of registerChannel), the single
    // consumer is the poll carrier (drainPendingRegistrations, called at the top of pollOnce AFTER select() has flushed the selector's
    // cancelled-key set). offer is the happens-before barrier the consumer relies on. Entries are NioHandles whose channel.register(selector, 0)
    // must be retried on the poll carrier; see registerChannel for why a non-poll carrier cannot flush the cancelled key itself.
    private val pendingRegistrations =
        new java.util.concurrent.ConcurrentLinkedQueue[NioHandle]()

    // Concurrent-collection audit: STARTTLS demand-driven upgrade-producer arms deferred to the poll carrier. armUpgradeProducerRead runs on the
    // upgrade/scheduler fiber (once per handshake read) and enqueues here; drainPendingRegistrations' sibling drainUpgradeArms (poll carrier) applies
    // the actual read-arm (interestOps OP_READ) so EVERY upgrade arm is selector-confined and no interestOps read-modify-write ever races the selector
    // cross-carrier (the repeated-upgrade lost-update). Same raw-ConcurrentLinkedQueue no-equivalent exception as pendingRegistrations: producers are
    // upgrade/scheduler fibers (offer), the single consumer is the poll carrier (drainUpgradeArms at the top of pollOnce); offer is the happens-before
    // barrier. Entries are NioHandles whose upgrade producer read must be armed on the poll carrier.
    private val pendingUpgradeArms =
        new java.util.concurrent.ConcurrentLinkedQueue[NioHandle]()

    // Concurrent-collection audit: peer-close grace-probe arms deferred to the poll carrier so the probe's OP_READ interestOps write is
    // selector-confined (isPeerClosed enqueues, drainGraceProbeArms drains). Same raw-ConcurrentLinkedQueue no-equivalent exception as
    // pendingUpgradeArms above: single producer, single consumer, offer is the happens-before barrier.
    private val pendingGraceProbeArms =
        new java.util.concurrent.ConcurrentLinkedQueue[NioHandle]()

    // Concurrent-collection audit: handles with work for the poll carrier that a caller carrier or a gate release handed over: staged bytes
    // a pump arm found after installing its cell (armRead's post-arm re-check), buffered ciphertext a read's pre-check could not unwrap
    // with the gate held, and work a gate holder's release hands back (owned ops, a deferred read re-arm). Same raw-ConcurrentLinkedQueue
    // no-equivalent exception as pendingGraceProbeArms above: producers are any carrier, the single consumer is the poll carrier
    // (drainGateWork), and offer is the happens-before barrier for the cross-carrier producers.
    private val pendingGateWork =
        new java.util.concurrent.ConcurrentLinkedQueue[NioHandle]()

    // Concurrent-collection audit: listener releases deferred to the selector's deregistration pass. releaseListener (any carrier) offers the
    // closed server channel with its release promise; the poll carrier drains after each select(), the closing carrier drains once more after
    // selector.close(), and a releasing carrier that observes the selector closed after its offer drains its own entry. More than one consumer,
    // so entries are dequeued with poll() (never the peek-then-poll the single-consumer queues above use) and a still-registered entry is
    // re-offered rather than left at the head: poll is atomic, so no entry is completed twice or dropped. Same raw-ConcurrentLinkedQueue
    // no-equivalent exception as its siblings; offer is the happens-before barrier.
    private val pendingListenerReleases =
        new java.util.concurrent.ConcurrentLinkedQueue[(ServerSocketChannel, Promise.Unsafe[Unit, Any])]()

    // Concurrent-collection audit: server-channel registrations for the poll carrier to apply (registerServerChannel). Same raw
    // ConcurrentLinkedQueue no-equivalent exception as its siblings; producers are listen callers, the consumer is the poll carrier, or the
    // closing carrier once the selector is closed, so entries are dequeued with poll().
    private val pendingServerRegistrations =
        new java.util.concurrent.ConcurrentLinkedQueue[(ServerSocketChannel, Maybe[Exception] => Unit)]()

    // Set right after selector.close() in close(): from then on implCloseSelector has killed every channel, so a queued release is true
    // regardless of isRegistered(). Read after an offer by releaseListener and by the poll carrier's re-offer, paired with close()'s write
    // before its drain, so an entry that misses the closing drain is completed by the carrier that offered it: volatile ordering makes at
    // least one side observe the other.
    // Unsafe: construction-time bridge.
    private val selectorClosed = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // Diagnostics dump so a connection this driver still holds shows up in kyo-test's end-of-run leak report (LeakCheck reads Diagnostics.dumpAll).
    // NIO was the one backend that registered nothing, so a leaked NIO connection was unattributable. The dump surfaces the pending-op maps AND every
    // channel still registered with the selector, by local->remote address (a backpressured connection holds a registered key with no pending op,
    // invisible to the maps alone). Runs on the leak-check thread concurrently with the poll carrier, so the selector.keys() snapshot is guarded and
    // degrades to a note. The processSharedTransport marker exempts the by-design never-closed transport from the stranded-op gate, as on the pollers.
    private val diagRegistration: kyo.internal.Diagnostics.Registration =
        val diagName =
            "NioIoDriver@" + java.lang.System.identityHashCode(this) +
                (if kyo.net.internal.ProcessSharedTransport.isBuilding then " processSharedTransport" else "")
        kyo.internal.Diagnostics.register(diagName)(
            dump = () =>
                // Unsafe: the dump reads only already-safe state (an AtomicBoolean get, ConcurrentHashMap sizes, a best-effort selector
                // snapshot); AllowUnsafe is a compile-time gate and these reads need no runtime evidence. Scoped to the lambda body.
                import kyo.AllowUnsafe.embrace.danger
                val chans = new StringBuilder
                try
                    val it = selector.keys().iterator()
                    while it.hasNext do
                        it.next().channel() match
                            case sc: SocketChannel =>
                                val addr =
                                    try s"${sc.getLocalAddress}->${sc.getRemoteAddress}"
                                    catch case _: Throwable => "<addr unavailable>"
                                discard(chans.append(addr).append(' '))
                            case _ => ()
                    end while
                catch case _: Throwable => discard(chans.append("<keys unavailable>"))
                end try
                s"closed=${closedFlag.get()} selectorOpen=${selector.isOpen} pendingReads=${pendingReads.size()} " +
                    s"pendingWritables=${pendingWritables.size()} pendingConnects=${pendingConnects.size()} " +
                    s"pendingAccepts=${pendingAccepts.size()} pendingRegistrations=${pendingRegistrations.size()} " +
                    s"registeredChannels=[$chans]"
            ,
            probe = () =>
                // Unsafe: same as dump above; reads only safe atomic/map state, AllowUnsafe is a compile-time gate. Scoped to the lambda.
                import kyo.AllowUnsafe.embrace.danger
                kyo.internal.Diagnostics.Probe(
                    closed = closedFlag.get(),
                    cycles = 0L,
                    pending = pendingReads.size() > 0 || pendingWritables.size() > 0 ||
                        pendingConnects.size() > 0 || pendingAccepts.size() > 0
                )
        )
    end diagRegistration

    /** Test-observability seam: number of handles awaiting deferred registration on the poll carrier. A `registerChannel` that hit the
      * cancelled-key race enqueues here and is drained by the poll loop's next `select()` cycle; this count is non-zero only in that window.
      * Read-only, no mutation.
      */
    private[net] def pendingRegistrationCount(using AllowUnsafe): Int =
        pendingRegistrations.size()

    def label: String = s"NioIoDriver[sel=${selector.hashCode()}]"

    def handleLabel(handle: NioHandle): String = s"channel=${handle.channel.hashCode()}"

    private def opsToString(ops: Int): String =
        val parts = new StringBuilder
        if (ops & SelectionKey.OP_READ) != 0 then parts.append("READ ")
        if (ops & SelectionKey.OP_WRITE) != 0 then parts.append("WRITE ")
        if (ops & SelectionKey.OP_ACCEPT) != 0 then parts.append("ACCEPT ")
        if (ops & SelectionKey.OP_CONNECT) != 0 then parts.append("CONNECT ")
        if parts.isEmpty then "NONE" else parts.toString.trim
    end opsToString

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // The selector loop runs on scheduler carriers, one select cycle per activation, never on a thread this driver owns.
        //
        // The shape matters as much as the absence of the thread. A `while` loop on a carrier has no fiber safepoints, so the scheduler cannot
        // preempt it, and a continuation this loop completes inline (a ReadPump byte delivery waking a parked take) can be routed back onto the
        // pinned carrier and strand there. Running exactly ONE cycle per activation returns the carrier so it can run the completions the cycle
        // just produced, and the next wait re-arms onto a different carrier via `scheduleExcludingCurrent`.
        //
        // The indefinite `selector.select()` parks its carrier, which is legitimate here: the scheduler COMPENSATES for a parked carrier
        // (BlockingMonitor observes flat user CPU and marks the worker blocked, after which its queue is drained back and no new work is routed
        // to it) but never RESCUES it, so parking is only correct when the driver owns an unconditional wake path. This one does:
        // `selector.wakeup()` returns the park for every interest change, and `selector.close()` aborts an in-flight select with
        // ClosedSelectorException, which ends the chain through the terminal exit below.
        val donePromise = Promise.Unsafe.init[Unit, Any]()
        Scheduler.get.schedule(newPollTask(donePromise, kyo.net.internal.ProcessSharedTransport.isBuilding))
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed Promise.Unsafe[Unit, Any], even though both erase to the same runtime object; the alias is transparent only
        // inside kyo.Fiber's own defining scope, so exposing donePromise as the locked IoDriver.start return needs this erased-boundary cast.
        // Safe: the promise completes only with the Unit-success/panic values the cycle chain sets below.
        donePromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    /** The ONE task this driver reuses for every cycle, built here so it captures `AllowUnsafe` and `Frame`: `Task.run` supplies neither, and
      * building it once means no task or closure is allocated per cycle.
      */
    private def newPollTask(donePromise: Promise.Unsafe[Unit, Any], processShared: Boolean)(using AllowUnsafe, Frame): Task =
        new Task:
            def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
                if processShared then processSharedTransportCycle(this, donePromise)
                else runCycle(this, donePromise)

    /** Named frame marking a cycle of a process-lifetime transport, whose idle parked carrier is expected to sit armed forever. The end-of-run
      * stranded-op and fiber-leak gates allowlist it by this name, so it must stay on the call path of every such cycle.
      */
    private def processSharedTransportCycle(task: Task, donePromise: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Task.Result =
        runCycle(task, donePromise)

    /** One select cycle, then either re-arm or exit. `pollOnce()` is unchanged and is already exactly one cycle: it selects, drains pending
      * registrations and upgrade arms, reasserts interest, and dispatches, returning false only when the selector has been closed.
      */
    private def runCycle(task: Task, donePromise: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Task.Result =
        try
            if closedFlag.get() then terminal(donePromise, Result.succeed(()))
            else if pollOnce() then reArm(task)
            else terminal(donePromise, Result.succeed(()))
            Task.Done
        catch
            // Containment is mandatory, not defensive: a Throwable escaping `run` goes to the worker's uncaught handler, which returns Done, and
            // the chain is simply gone with every pending promise parked and the selector open. Routing it to the terminal exit below is what
            // makes a crashed loop release its selector.
            case t: Throwable =>
                if !closedFlag.get() then Log.live.unsafe.error(s"$label select cycle crashed", t)
                terminal(donePromise, Result.panic(t))
                Task.Done
        end try
    end runCycle

    /** Re-arm the next cycle onto a DIFFERENT carrier, so the one that just ran the cycle is free to run the continuations it produced.
      *
      * The runtime reset plus a single unit is a RE-BASE, not the key the task ends up with: Worker.runTask bills the cycle's wall-clock after this
      * returns, so the chain enters its next queue at `1 + this cycle's park in select`. The reset keeps that per-cycle rather than letting it
      * accumulate over the driver's life, which is what wraps the key into the preempt bit and stops readiness entirely.
      *
      * Sorting behind queued work is the intended tradeoff, not starvation: `pollOnce` already dispatched this cycle's ready keys before the re-arm,
      * so the key governs only when the next `select` begins. See PollerIoDriver.reArm for the full rationale and for the measured reason the park
      * must NOT be exempted from billing.
      */
    private def reArm(task: Task): Unit =
        task.resetRuntime()
        task.addRuntime(1)
        Scheduler.get.scheduleExcludingCurrent(task)
    end reArm

    /** The single exit for every path: an owner close, a selector closed underneath the loop, and a crashed cycle.
      *
      * Calling `close()` here is what closes the crashed-loop leak: previously a crash completed the done-promise with the selector still open
      * and every pending promise parked forever. `close()` is CAS-guarded and everything it touches is safe from any carrier, so it is a no-op
      * after an owner close and the full teardown after a crash. The done-promise therefore means the same thing on every path: the loop has
      * finished AND its selector is released.
      */
    private def terminal(donePromise: Promise.Unsafe[Unit, Any], result: Result[Nothing, Unit < Any])(using AllowUnsafe, Frame): Unit =
        if closedFlag.get() then Log.live.unsafe.debug(s"$label event loop exited cleanly")
        else Log.live.unsafe.warn(s"$label event loop exited unexpectedly")
        close()
        donePromise.completeDiscard(result)
    end terminal

    def awaitRead(handle: NioHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Stray-arm reject for the STARTTLS handshake window, BEFORE any state is touched: once the handshake owns reads, a pump re-arm
        // would clobber the producer's cell and shared pendingReads entry, disconnecting the handshake from the selector (its demand-driven
        // waiter never retries), so it is failed here first, leaving the producer's state untouched. handshakeReading is the same predicate
        // the dispatch routes on, and it is set only post-detach (armUpgradeProducerRead), strictly after the connection's state CAS to
        // Upgrading, so this can never fire pre-CAS and a pre-detach arm is never spuriously failed: that arm is taken by the detach sweep,
        // or, landing between the sweep and the first producer arm, by applyUpgradeArm's occupant fail. upgrading scopes the reject to the
        // window: it clears at handshake completion, so the upgraded connection's own reads pass.
        if !handle.life.armable then promise.completeDiscard(Result.fail(released(handle)))
        else if handle.upgrading && handle.handshakeReading then
            promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "detached for upgrade")))
        else
            awaitReadAdmitted(handle, promise)
    end awaitRead

    /** The failure of an op parked after the handle's release claim. */
    private def released(handle: NioHandle)(using Frame): Closed =
        Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "released")

    /** Put `handle`'s pendingReads entry for a read armed on it, then re-check the release claim. A release claims before it sweeps the entry,
      * so either that sweep runs after this put or the re-check sees the claim and takes the entry back here: no entry outlives the release.
      * False when the claim is taken.
      */
    private def putPendingRead(channel: SocketChannel, handle: NioHandle)(using AllowUnsafe): Boolean =
        pendingReads.put(channel, handle)
        if handle.life.armable then true
        else
            discard(pendingReads.remove(channel))
            false
        end if
    end putPendingRead

    private def awaitReadAdmitted(handle: NioHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // A TLS read first takes plaintext already decrypted, then unwraps what the handshake or a grace probe left buffered: neither has a
        // socket event left to wake the selector for it.
        // The probe stages every byte it read before it latches the peer's close, so a latch read before the staging is drained means the
        // drain took everything the peer sent. Read after it, a latch can cover bytes staged behind the drain, and ending the read there
        // would lose them.
        val peerClosed = handle.peerClosed
        handle.tls match
            case Present(tls) =>
                tls.takePlaintext() match
                    case Present(plaintext) => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(plaintext))))
                    case Absent             =>
                        handle.gate.tryLocked(tls.unwrapBuffered(handle.graceStaging)) match
                            case Absent =>
                                // Another section holds the engine, with ciphertext of this read possibly buffered: arm, and have the
                                // selector carrier unwrap for it once the engine is free.
                                armRead(handle, promise)
                                offerGateWork(handle)
                            case Present(NioTlsState.Inbound.Decrypted) =>
                                // Another carrier can deliver the carry between the unwrap and this take; then this read waits for more.
                                tls.takePlaintext() match
                                    case Present(plaintext) =>
                                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(plaintext))))
                                    case Absent => armRead(handle, promise)
                            case Present(NioTlsState.Inbound.CleanClose) =>
                                // Buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose so the
                                // ReadPump tears down instead of waiting on the selector for ciphertext the peer will never send.
                                promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                            case Present(NioTlsState.Inbound.PeerFin) =>
                                promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                            case Present(NioTlsState.Inbound.Pending) =>
                                // A grace probe latched a bare FIN (truncation) while backpressured: surface it now rather than arming for
                                // ciphertext the peer will never send (a live consumer must see the end promptly).
                                if peerClosed then promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                                else armRead(handle, promise)
                        end match
                end match
            case Absent =>
                drainGraceStaging(handle) match
                    case Present(staged) =>
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(staged))))
                    case Absent =>
                        if peerClosed then promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                        else armRead(handle, promise)
        end match
    end awaitReadAdmitted

    // Install a fresh ReadArmCell into the read-arm owner slot and register selector interest. Each arm
    // wraps the caller's promise in a freshly allocated ReadArmCell object; the selector carrier completes
    // only the current owner's promise via a reference-equality CAS on the stored cell. A stale arm holds
    // an older ReadArmCell heap object (distinct from the current arm's object even when both carry the
    // same promise), so its CAS fails.
    private[net] def armRead(handle: NioHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.life.read.park(promise, released(handle)) then
            val newCell = Present(ReadArmCell(promise))
            // getAndSet, not set: fail the occupant this arm replaces. The occupant is normally Absent, or a standing grace probe whose promise
            // is a throwaway; the case that matters is a stray pre-upgrade pump cell a stalled carrier left behind after every upgrade-window
            // catcher ran, for which this overwrite is the last owner. Idempotent against occupants already completed elsewhere. Displacing a
            // still-armed live cell surfaces promptly as a typed Closed its owner handles as teardown, rather than as a silent hang.
            handle.readArm.getAndSet(newCell).foreach { previous =>
                previous.promise.completeDiscard(Result.fail(Closed(
                    s"connection ${handleLabel(handle)}",
                    handle.createdAt,
                    "read arm replaced"
                )))
            }
            Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
            if !putPendingRead(handle.channel, handle) then
                discard(handle.readArm.compareAndSet(newCell, Absent))
                promise.completeDiscard(Result.fail(released(handle)))
            else if !registerInterest(handle.channel, SelectionKey.OP_READ) then
                discard(handle.readArm.compareAndSet(newCell, Absent))
                discard(pendingReads.remove(handle.channel))
                promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "registerRead failed")))
            else
                if handle.forceReadArmWakeup then
                    // First post-STARTTLS read arm (NioHandle.forceReadArmWakeup): registerInterest's cross-carrier OP_READ set can be lost to the
                    // selector's own interestOps write and its guarded wakeup can coalesce, so on a selector quiescing between repeated upgrades
                    // the reassert backstop never runs and this read strands. Force an UNCONDITIONAL selector.wakeup() (Netty's cross-thread
                    // discipline: never rely on a coalesced wakeup for a one-shot arm) so the poll carrier runs one cycle and
                    // reassertPendingInterest re-applies OP_READ on the selector carrier. One-shot: cleared here so steady-state reads keep the
                    // coalesced wakeup.
                    handle.forceReadArmWakeup = false
                    discard(selector.wakeup())
                end if
                // Post-arm staging re-check (pump side of the staging handoff). awaitRead's staging pre-check and this arm are not atomic
                // against the probe dispatch: a probe holding OP_READ can consume fresh socket bytes into graceStaging between the pre-check
                // (which saw Absent) and the readArm.set above, after which nothing fires (the socket is empty) and the staged bytes would
                // strand against this armed read forever. Re-checking AFTER the arm closes that window from this side: the probe stashes then
                // reads the arm slot (dispatchGraceProbe's tail deliverStaged), this carrier arms then re-reads staging, so one of the two
                // always observes the other. Delivery is deferred to the poll carrier (selector-confined, like the probe arms themselves) with an
                // UNCONDITIONAL wakeup (offerGateWork): there is no socket readiness to ride, so a coalesced wakeup lost to an in-flight select
                // would strand the delivery.
                //
                // The poll carrier takes staged or carried bytes and returns them when no read holds the slot, all under the gate
                // (claimPumpRead), so the gate is read first: a section in progress may have looked at the slot before this cell was set and
                // not yet returned what it took, so this re-check waits it out through the gate. With the gate free here, a section still to
                // come sees this cell, and one that already ran returned what it took before the staging is read below.
                if handle.gate.isHeld then
                    if handle.gate.lockedOrDefer(stagedDeliveryDue(handle)).contains(true) then offerGateWork(handle)
                else if stagedDeliveryDue(handle) then offerGateWork(handle)
                end if
            end if
        end if
    end armRead

    /** Append `arr` to the handle's STARTTLS salvage buffer (poll-carrier side of the handoff). A CAS loop keeps it lock-free; the buffer is
      * drained by the handshake via [[drainUpgradeSalvage]].
      */
    private def stashUpgradeBytes(handle: NioHandle, arr: Array[Byte])(using AllowUnsafe): Unit =
        @tailrec def loop(): Unit =
            val cur = handle.upgradeSalvage.get()
            if !handle.upgradeSalvage.compareAndSet(cur, cur.append(arr)) then loop()
        loop()
    end stashUpgradeBytes

    /** Atomically take and concatenate the handle's STARTTLS salvage into one byte array, or `Absent` when empty. The getAndSet take makes the
      * salvage feed exactly-once into the handshake (drained by startTlsHandshake before the first handshake read).
      */
    private[net] def drainUpgradeSalvage(handle: NioHandle)(using AllowUnsafe): Maybe[Array[Byte]] =
        val taken = handle.upgradeSalvage.getAndSet(Chunk.empty)
        if taken.isEmpty then Absent
        else
            val total = taken.foldLeft(0)(_ + _.length)
            val out   = new Array[Byte](total)
            var pos   = 0
            taken.foreach { a =>
                // System.arraycopy: no kyo equivalent for a bulk primitive-array copy; fully qualified so kyo.System does not shadow it.
                java.lang.System.arraycopy(a, 0, out, pos, a.length)
                pos += a.length
            }
            Present(out)
        end if
    end drainUpgradeSalvage

    /** STARTTLS handoff (NIO override): the plaintext ReadPump pulled `bytes` off the socket but the inbound channel is already closed. If the
      * handle is upgrading, the close that failed the offer is `detachForUpgrade`'s inbound close (it happens-before this call, so `upgrading` is
      * visible), so these bytes are the peer's first TLS flight; SALVAGE them for the handshake to replay (`startTlsHandshake` feeds the salvage
      * into the engine) rather than dropping. A non-upgrade close (an ordinary teardown) leaves `upgrading` false and the bytes are discarded.
      */
    override def onInboundClosedDuringRead(handle: NioHandle, bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        if handle.upgrading then
            stashUpgradeBytes(handle, bytes.toArrayUnsafe)
            // Salvage grace-staged bytes after the parked span (parked-then-staged order). detachForUpgrade backstops the resume case, where the pump
            // unparked before a probe staged and this hook never fires.
            drainGraceStaging(handle).foreach(staged => stashUpgradeBytes(handle, staged))
    end onInboundClosedDuringRead

    /** STARTTLS upgrade confinement: make the SELECTOR carrier the sole reader and OP_READ owner for the upgrade. DEMAND-DRIVEN: the handshake (on the
      * upgrade fiber for the first read, then on the scheduler carrier that resumes each waiter) calls this ONCE PER read it needs, from its NEED_UNWRAP
      * park; the producer then reads exactly one peer flight and stops (it never self-re-arms, so it cannot over-read past the handshake's last read).
      * Every arm is DEFERRED to the poll carrier rather than performed here: this method only marks the handle as reading, enqueues it on
      * [[pendingUpgradeArms]], and wakes the selector. The poll carrier's [[drainUpgradeArms]] then runs [[applyUpgradeArm]] (the actual read-arm cell +
      * OP_READ registration) ON the selector carrier. Confining EVERY arm this way removes the cross-carrier `interestOps` read-modify-write of the
      * upgrade entirely: an upgrade/scheduler-fiber `| OP_READ` racing the selector's own `& ~OP_READ` clears was a lost-update that stranded the
      * handshake on a quiescing selector (the repeated-upgrade residual). The `offer` happens-before the UNCONDITIONAL `selector.wakeup()`, and the
      * single-consumer queue's linearizability guarantees the drain on that `select()` return observes the enqueued handle, so the deferred arm is
      * never lost. The JDK selector is level-triggered, so a demand arm on a socket that already has the flight buffered still reports ready and
      * dispatches; no speculative read is needed.
      */
    override def armUpgradeProducerRead(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        handle.handshakeReading = true
        discard(pendingUpgradeArms.offer(handle))
        discard(selector.wakeup())
    end armUpgradeProducerRead

    /** Drain the deferred STARTTLS upgrade-arm queue on the poll carrier: apply each enqueued bootstrap arm here so its `interestOps` write is
      * selector-confined, never a cross-carrier read-modify-write. Called from [[pollOnce]] after `select()` returns. Single-carrier-confined consumer.
      */
    private def drainUpgradeArms()(using AllowUnsafe): Unit =
        var handle = pendingUpgradeArms.poll()
        while handle ne null do
            applyUpgradeArm(handle)
            handle = pendingUpgradeArms.poll()
        end while
    end drainUpgradeArms

    /** Install one demand-driven STARTTLS upgrade producer read-arm on the SELECTOR carrier (deferred here by [[armUpgradeProducerRead]], one per
      * handshake read). Installs a fresh producer cell (so a readiness dispatch routes to [[dispatchUpgradeRead]], which delivers the peer flight into
      * the handle's [[NioHandle.upgradeHandoff]] slot rather than completing this cell's promise), a `pendingReads` entry, and OP_READ interest. If the
      * channel's key is already gone (the connection closed between the arm enqueue and this drain), the arm cannot be installed; unwind the cell +
      * `pendingReads` entry and fail any parked handshake waiter Closed so the handshake tears down rather than stranding. Poll-carrier-only.
      */
    private def applyUpgradeArm(handle: NioHandle)(using AllowUnsafe): Unit =
        given Frame  = Frame.internal
        val producer = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        // getAndSet, not set: fail the occupant this producer arm replaces. A stray pump re-arm admitted between the detach sweep and this
        // first producer arm escaped the sweep (awaitRead's reject fires only once handshakeReading is set), so this swap is the last point
        // that can complete it. A probe occupant's promise is a throwaway; failing it is harmless.
        handle.readArm.getAndSet(Present(ReadArmCell(producer))).foreach { previous =>
            previous.promise.completeDiscard(Result.fail(Closed(
                s"connection ${handleLabel(handle)}",
                handle.createdAt,
                "detached for upgrade"
            )))
        }
        val registered = putPendingRead(handle.channel, handle) && registerInterest(handle.channel, SelectionKey.OP_READ)
        if !registered then
            discard(handle.readArm.getAndSet(Absent))
            discard(pendingReads.remove(handle.channel))
            failUpgradeHandoff(handle)
        end if
    end applyUpgradeArm

    /** Whether the peer has closed, for the ReadPump's grace poll. NIO has no `POLLRDHUP` equivalent, so the only FIN observation is a consuming
      * read, which must stay selector-carrier-confined; detection cannot happen inline. Reads the [[NioHandle.peerClosed]] latch a PRIOR probe set,
      * and otherwise best-effort defers arming the next probe to the poll carrier (enqueue + unconditional wakeup, never a cross-carrier
      * `interestOps` write). `false` here can mean "not observed yet". Arming is skipped while a STARTTLS upgrade owns the socket, once staged bytes
      * hit the cap, or when a probe is already armed. There is no recycled-handle hazard: NIO keys every map by `SocketChannel` object identity.
      */
    override def isPeerClosed(handle: NioHandle)(using AllowUnsafe, Frame): Boolean =
        if handle.peerClosed then true
        else
            if !handle.upgrading && stagedBytes(handle) < NioIoDriver.GraceProbeStagingCap && !isProbeArmed(handle) then
                discard(pendingGraceProbeArms.offer(handle))
                discard(selector.wakeup())
            end if
            false
    end isPeerClosed

    /** Total bytes the grace probe has staged on `handle` (recomputed from the chunk lengths; no counter field, so no skew race). */
    private[net] def stagedBytes(handle: NioHandle)(using AllowUnsafe): Int =
        handle.graceStaging.get().foldLeft(0)(_ + _.length)

    /** Whether `handle`'s current read-arm cell is a peer-close probe (so [[isPeerClosed]] does not re-enqueue a standing probe). */
    private def isProbeArmed(handle: NioHandle)(using AllowUnsafe): Boolean =
        handle.readArm.get() match
            case Present(cell) => cell.probe
            case Absent        => false

    /** Test-observability seam: the current read-arm slot state as a label ("absent", "probe", or "pump"). Read-only, no mutation. */
    private[net] def readArmState(handle: NioHandle)(using AllowUnsafe): String =
        handle.readArm.get() match
            case Present(cell) => if cell.probe then "probe" else "pump"
            case Absent        => "absent"

    /** Append one probe chunk. Selector-carrier single appender; the CAS loop only guards a concurrent drainer's getAndSet. */
    private def stashGraceBytes(handle: NioHandle, arr: Array[Byte])(using AllowUnsafe): Unit =
        @tailrec def loop(): Unit =
            val cur = handle.graceStaging.get()
            if !handle.graceStaging.compareAndSet(cur, cur.append(arr)) then loop()
        loop()
    end stashGraceBytes

    /** Take and concatenate the staging exactly once (getAndSet: one drainer wins). */
    private def drainGraceStaging(handle: NioHandle)(using AllowUnsafe): Maybe[Array[Byte]] =
        val taken = handle.graceStaging.getAndSet(Chunk.empty)
        if taken.isEmpty then Absent else Present(NioIoDriver.concatChunks(taken))
    end drainGraceStaging

    /** Put drained staging back ahead of anything staged since, so it stays in arrival order. */
    private def returnGraceStaging(handle: NioHandle, staged: Array[Byte])(using AllowUnsafe): Unit =
        @tailrec def loop(): Unit =
            val cur = handle.graceStaging.get()
            if !handle.graceStaging.compareAndSet(cur, Chunk(staged).concat(cur)) then loop()
        loop()
    end returnGraceStaging

    /** Poll-carrier drain of the deferred probe arms (the [[drainUpgradeArms]] twin). */
    private def drainGraceProbeArms()(using AllowUnsafe): Unit =
        var handle = pendingGraceProbeArms.poll()
        while handle ne null do
            applyGraceProbeArm(handle)
            handle = pendingGraceProbeArms.poll()
        end while
    end drainGraceProbeArms

    /** Install one peer-close grace probe read-arm on the SELECTOR carrier (deferred here by [[isPeerClosed]]): a fresh PROBE cell routing a readiness
      * dispatch to [[dispatchGraceProbe]], a `pendingReads` entry, and OP_READ. The cell CAS is against `Absent`: a parked pump leaves the slot Absent;
      * a pump re-arm or upgrade arm that won the slot owns the read, so the CAS fails and the probe yields. The slot alone gates the arm; a
      * `pendingReads` pre-check must NOT: armRead's cell set and entry put are not atomic, so a dispatch that completes a just-set cell can leave the
      * arm's entry behind (dispatch removed the entry before the put landed), and a stale entry blocking probes here silently loses the FIN watch
      * until the entry happens to be consumed. On a dead key (connection closed between kick and drain), unwind.
      */
    private def applyGraceProbeArm(handle: NioHandle)(using AllowUnsafe): Unit =
        if handle.upgrading || handle.peerClosed ||
            stagedBytes(handle) >= NioIoDriver.GraceProbeStagingCap
        then ()
        else
            val probeCell = Present(ReadArmCell(Promise.Unsafe.init[ReadOutcome, Abort[Closed]](), probe = true))
            if handle.readArm.compareAndSet(Absent, probeCell) then
                if !putPendingRead(handle.channel, handle) then discard(handle.readArm.compareAndSet(probeCell, Absent))
                else if !registerInterest(handle.channel, SelectionKey.OP_READ) then
                    discard(handle.readArm.compareAndSet(probeCell, Absent))
                    discard(pendingReads.remove(handle.channel))
                end if
            end if
        end if
    end applyGraceProbeArm

    /** Peer-close grace probe dispatch (selector carrier), routed from [[dispatchRead]] by `ReadArmCell.probe`. Consumes up to
      * [[NioIoDriver.GraceProbeBudgetChunks]] buffers off the socket toward a FIN hidden behind backpressured data, STAGING each chunk so every read
      * path redelivers them in order, and latches [[NioHandle.peerClosed]] on `recv == -1` / IOException. Read outcomes:
      *   - `n > 0`: stage; stop when the per-window budget or the staging cap is reached, since staying armed with data present refires the
      *     level-triggered selector and would drain the whole receive buffer in one window. The next kick continues.
      *   - `n < 0` / IOException: latch peerClosed (+ TLS truncation `peerEof` unless a clean close was already seen).
      *   - `n == 0`: buffer drained, no FIN yet. Stay armed (re-install a fresh probe cell + OP_READ) as a standing FIN watch, so a later FIN fires
      *     the selector and the probe reads -1.
      * All socket reads here are selector-carrier-serialized with every pump dispatch, so the probe never races a pump read for the stream.
      */
    private def dispatchGraceProbe(channel: SocketChannel, cell: Maybe[ReadArmCell], handle: NioHandle)(using AllowUnsafe): Unit =
        // Take ownership. On CAS failure a close/cleanupPending getAndSet or a pump re-arm's set already took the slot; do not touch the socket.
        if handle.readArm.compareAndSet(cell, Absent) then
            try
                var budget = NioIoDriver.GraceProbeBudgetChunks
                var armed  = true
                while armed do
                    val buf = handle.readBuffer
                    buf.clear()
                    val n = channel.read(buf)
                    if n > 0 then
                        buf.flip()
                        val arr = new Array[Byte](n)
                        buf.get(arr)
                        stashGraceBytes(handle, arr)
                        budget -= 1
                        if budget <= 0 || stagedBytes(handle) >= NioIoDriver.GraceProbeStagingCap then armed = false
                    else if n < 0 then
                        handle.peerClosed = true
                        handle.tls.foreach(_.markPeerEof())
                        armed = false
                    else
                        // re-install as a standing FIN watch; CAS against Absent so a pump re-arm that won the slot is not clobbered.
                        val nextCell = Present(ReadArmCell(Promise.Unsafe.init[ReadOutcome, Abort[Closed]](), probe = true))
                        if handle.readArm.compareAndSet(Absent, nextCell) then
                            if putPendingRead(channel, handle) then discard(registerInterest(channel, SelectionKey.OP_READ))
                            else discard(handle.readArm.compareAndSet(nextCell, Absent))
                        armed = false
                    end if
                end while
            catch
                case _: IOException =>
                    handle.peerClosed = true
                    handle.tls.foreach(_.markPeerEof())
            end try
            // Probe side of the staging handoff (see armRead's post-arm re-check): after the last stash, a pump arm that raced this dispatch
            // may own the slot with its staging pre-check already behind it, so hand the staged bytes over now. Same-carrier with every other
            // dispatch, so this cannot race a pump dispatch for the cell.
            if stagedDeliveryDue(handle) then deliverStaged(handle)
        end if
    end dispatchGraceProbe

    /** Hand `handle` to the poll carrier for its gate work ([[deliverStaged]]), with an unconditional wakeup: none of that work rides a
      * socket event, so a wakeup coalesced into a select that already began would leave it queued until an unrelated event.
      */
    private def offerGateWork(handle: NioHandle)(using AllowUnsafe): Unit =
        discard(pendingGateWork.offer(handle))
        discard(selector.wakeup())
        // A closed selector runs no further cycle; close() drains once after it sets the flag, and an offer after that drain drains here.
        if selectorClosed.get() then drainGateWork()
    end offerGateWork

    /** Poll-carrier drain of the handles with gate work. It polls until empty: a handle comes back while this runs only through a gate
      * release, one per release, never through a retry, so a writer holding a gate cannot make the drain spin.
      */
    /** Test-observability seam: the handles queued for the poll carrier's gate work. Read-only, no mutation. */
    private[net] def pendingGateWorkCount(using AllowUnsafe): Int = pendingGateWork.size()

    private[net] def drainGateWork()(using AllowUnsafe): Unit =
        var handle = pendingGateWork.poll()
        while handle ne null do
            deliverStaged(handle)
            handle = pendingGateWork.poll()
        end while
    end drainGateWork

    /** Whether there are staged or carried bytes to hand to an armed read. The other carriers can drain them or arm a read between this
      * and [[deliverStaged]].
      */
    private[net] def stagedDeliveryDue(handle: NioHandle)(using AllowUnsafe): Boolean =
        !handle.upgrading && (!handle.graceStaging.get().isEmpty || handle.tls.exists(_.hasPlaintext))

    /** The poll carrier's gate work for `handle`, acting on whatever the slot, the staging and the gate's queue hold now. SELECTOR-CARRIER
      * ONLY, so it never races a dispatch for the cell; the only concurrent slot writer is a close/cancel cleanupPending, which the
      * ownership CAS settles.
      *
      * Under the gate, unless a section holds it, in which case that section's release hands the handle back: the ops queued by
      * [[runOwned]], the re-arm of a TLS read whose dispatch found the gate held ([[rearmDeferredRead]]), and, while a pump read holds the
      * slot, the unwrap of staged and buffered ciphertext into the carry. A partial record stays buffered for the next socket read to
      * extend.
      *
      * Then, with the gate released so the read's callbacks can take it, the carry, or the end the unwrap reached, goes to the pump read
      * holding the slot. Plaintext goes to whichever read holds the slot now, so a read that lost the slot while the engine decrypted never
      * takes plaintext the engine cannot produce again.
      *
      * This is the delivery half of the staging handoff: staging is stashed exclusively on this carrier, the pump arms on caller carriers,
      * and both sides act-then-check (stash then read the slot here, arm then re-read staging in armRead), so staged bytes present while a
      * pump cell is armed always reach exactly one of the two checks. Without it they strand: the probe consumed the socket, so no readiness
      * ever fires for the armed cell.
      */
    private[net] def deliverStaged(handle: NioHandle)(using AllowUnsafe): Unit =
        handle.gate.lockedOrDefer {
            handle.gate.runQueued()
            if handle.upgrading || !pumpArmed(handle) then Absent
            else
                handle.tls match
                    case Absent       => claimStagedPlain(handle)
                    case Present(tls) =>
                        rearmDeferredRead(handle)
                        tls.unwrapBuffered(handle.graceStaging) match
                            case NioTlsState.Inbound.CleanClose => claimTls(handle, tls, Present(ReadOutcome.CleanClose))
                            case NioTlsState.Inbound.PeerFin    => claimTls(handle, tls, Present(ReadOutcome.PeerFin))
                            case _                              => claimTls(handle, tls, Absent)
                        end match
                end match
            end if
        }.flatten.foreach(_.complete())
    end deliverStaged

    /** Plain staged delivery: drain the staging, then claim the pump read holding the slot for it. The staging is drained first because a
      * read's own pre-check drains it too, on its caller's carrier: by the time the slot is read, the bytes may be gone and the read in the
      * slot the next one, armed after that drain. With no pump read to take them the bytes go back to the staging, or to the upgrade
      * salvage when an upgrade took the slot.
      */
    private def claimStagedPlain(handle: NioHandle)(using AllowUnsafe, NioGate.Held): Maybe[NioIoDriver.Delivery] =
        drainGraceStaging(handle).flatMap { staged =>
            if handle.upgrading then
                stashUpgradeBytes(handle, staged)
                Absent
            else
                claimPumpRead(handle) match
                    case Present(read) => Present(NioIoDriver.Delivery(read, Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(staged)))))
                    case Absent        =>
                        returnGraceStaging(handle, staged)
                        if handle.upgrading then drainGraceStaging(handle).foreach(stashUpgradeBytes(handle, _))
                        Absent
            end if
        }
    end claimStagedPlain

    /** Claim the pump read holding the slot for the decrypted plaintext waiting in `tls`, or, with none waiting, for `end`. Plaintext no
      * read can take goes back to the carry.
      */
    private def claimTls(handle: NioHandle, tls: NioTlsState, end: Maybe[ReadOutcome])(using
        AllowUnsafe,
        NioGate.Held
    ): Maybe[NioIoDriver.Delivery] =
        tls.takePlaintext() match
            case Present(plaintext) =>
                claimPumpRead(handle) match
                    case Present(read) => Present(NioIoDriver.Delivery(read, Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(plaintext)))))
                    case Absent        =>
                        tls.returnPlaintext(plaintext)
                        Absent
            case Absent => end.flatMap(outcome => claimPumpRead(handle).map(NioIoDriver.Delivery(_, Result.succeed(outcome))))
    end claimTls

    /** Take the pump read holding the slot, with its pendingReads entry. Under the gate, so taking the staging or the carry, claiming the read
      * that receives it, and returning what no read could take are one step against armRead's re-check: a take that is returned later
      * would otherwise hide the bytes from a read armed in between.
      */
    @tailrec private def claimPumpRead(handle: NioHandle)(using
        AllowUnsafe,
        NioGate.Held
    ): Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] =
        val cell = handle.readArm.get()
        cell match
            case Present(armCell) if !armCell.probe =>
                if handle.readArm.compareAndSet(cell, Absent) then
                    discard(pendingReads.remove(handle.channel))
                    Present(armCell.promise)
                else claimPumpRead(handle)
            case _ => Absent
        end match
    end claimPumpRead

    private def pumpArmed(handle: NioHandle)(using AllowUnsafe): Boolean =
        handle.readArm.get() match
            case Present(cell) => !cell.probe
            case Absent        => false

    /** Re-arm a TLS pump read whose dispatch found the gate held and left it in its cell with no pendingReads entry (see dispatchReadTls).
      * An arm that is between its cell set and its entry put is re-armed twice, which is idempotent. SELECTOR-CARRIER ONLY.
      */
    private def rearmDeferredRead(handle: NioHandle)(using AllowUnsafe): Unit =
        handle.readArm.get() match
            case Present(cell) if !cell.probe && handle.tls.nonEmpty && !pendingReads.containsKey(handle.channel) =>
                if putPendingRead(handle.channel, handle) then discard(registerInterest(handle.channel, SelectionKey.OP_READ))
            case _ => ()
    end rearmDeferredRead

    /** STARTTLS upgrade producer (selector carrier): read at most one buffer of peer ciphertext and hand it to the handshake through the handle's
      * [[NioHandle.upgradeHandoff]] slot, so the handshake fiber never reads the socket itself. The producer read-arm cell is CAS-cleared (the orphan
      * guard); the bytes go to the slot, never to the cell's promise. DEMAND-DRIVEN: the producer reads exactly ONE peer flight per arm and does NOT
      * re-arm itself. The handshake's next [[NioTransport.driveHandshake]] NEED_UNWRAP park re-arms it via [[armUpgradeProducerRead]] (deferred to the
      * poll carrier through [[pendingUpgradeArms]], never a cross-carrier interestOps RMW). This is what keeps the producer from over-reading past the
      * handshake's last read: once the handshake reaches FINISHED it stops parking, so no further arm is issued and the upgraded connection's ReadPump
      * cleanly owns every post-FINISHED read. A spurious empty read (n==0) re-arms the same cell in place (the demand is still outstanding).
      */
    private def dispatchUpgradeRead(channel: SocketChannel, cell: Maybe[ReadArmCell], handle: NioHandle)(using AllowUnsafe): Unit =
        try
            val buf = handle.readBuffer
            buf.clear()
            val n = channel.read(buf)
            if n > 0 then
                buf.flip()
                val arr = new Array[Byte](n)
                buf.get(arr)
                if handle.readArm.compareAndSet(cell, Absent) then
                    deliverToUpgradeHandoff(handle, arr)
                    // Demand-driven: the producer read exactly one flight and stops here. It does NOT re-arm itself; the handshake's next NEED_UNWRAP
                    // park re-arms it via armUpgradeProducerRead. Not self-re-arming is what prevents the over-read past FINISHED: the handshake stops
                    // parking once it completes, so no arm is issued after the last handshake read and the upgraded ReadPump owns post-FINISHED reads.
                    failConsumedUpgradeRead(handle, cell)
                end if
            else if n < 0 then
                if handle.readArm.compareAndSet(cell, Absent) then
                    failUpgradeHandoff(handle)
                    failConsumedUpgradeRead(handle, cell)
            else
                // n == 0: spurious selector wakeup, no data ready. Keep the producer armed (re-register on the selector carrier) for the real edge.
                if putPendingRead(channel, handle) then discard(registerInterest(channel, SelectionKey.OP_READ))
                else if handle.readArm.compareAndSet(cell, Absent) then
                    failUpgradeHandoff(handle)
                    failConsumedUpgradeRead(handle, cell)
                end if
            end if
        catch
            case _: IOException =>
                if handle.readArm.compareAndSet(cell, Absent) then
                    failUpgradeHandoff(handle)
                    failConsumedUpgradeRead(handle, cell)
        end try
    end dispatchUpgradeRead

    /** Fail the promise of the cell an upgrade-producer dispatch consumed. When a pump read armed before the upgrade window is the cell the
      * dispatch took (the pump's last plaintext arm, with the handshake already owning reads), its bytes were delivered into the upgrade
      * handoff above and the detach sweep can no longer see the cell, so this dispatch is the last owner that can complete the promise.
      * Failing it is the pump's teardown signal, not data loss. For the handshake's own producer vehicle this is a harmless completion
      * nobody observes. The poller dual is `PollerIoDriver.failConsumedUpgradeRead`.
      */
    private def failConsumedUpgradeRead(handle: NioHandle, cell: Maybe[ReadArmCell])(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        cell.foreach(_.promise.completeDiscard(Result.fail(Closed(
            s"connection ${handleLabel(handle)}",
            handle.createdAt,
            "detached for upgrade"
        ))))
    end failConsumedUpgradeRead

    /** Deliver `arr` (one peer ciphertext flight read on the selector carrier) into the handle's [[NioHandle.upgradeHandoff]] slot: fulfil a parked
      * handshake waiter, or stage a Carryover the handshake's next read consumes. Demand-driven, the producer arms only after the park has already
      * CAS-installed its Waiter, so the producer normally finds a parked Waiter and the Carryover branch is a backstop. When a Carryover IS already
      * staged, the new bytes are APPENDED rather than dropped (a single-slot overwrite would lose every segment after the first). The CAS loop keeps
      * exactly one winner per transition; the producer is the sole stager (selector-confined) so the Carryover-append never contends. Mirrors the posix
      * `deliverToUpgradeHandoff`.
      */
    @tailrec
    private def deliverToUpgradeHandoff(handle: NioHandle, arr: Array[Byte])(using AllowUnsafe): Unit =
        import NioHandle.UpgradeHandoff
        handle.upgradeHandoff.get() match
            case parked: UpgradeHandoff.Waiter =>
                if handle.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle) then
                    parked.promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
                else deliverToUpgradeHandoff(handle, arr)
            case staged: UpgradeHandoff.Carryover =>
                val combined = new Array[Byte](staged.bytes.length + arr.length)
                // System.arraycopy: no kyo equivalent for the bulk carryover-merge copy; fully qualified so kyo.System does not shadow it.
                java.lang.System.arraycopy(staged.bytes, 0, combined, 0, staged.bytes.length)
                java.lang.System.arraycopy(arr, 0, combined, staged.bytes.length, arr.length)
                if !handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Carryover(combined)) then
                    deliverToUpgradeHandoff(handle, arr)
            case _ =>
                // Idle: stage the first Carryover. A CAS loss means the handshake parked a Waiter or the producer staged a Carryover concurrently; re-run.
                if !handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, UpgradeHandoff.Carryover(arr)) then
                    deliverToUpgradeHandoff(handle, arr)
        end match
    end deliverToUpgradeHandoff

    /** Fail a STARTTLS handshake parked on the upgrade handoff when the producer read hit EOF or a hard error: complete the waiter with an empty Span
      * (the handshake renders it as a peer close). A no-op when no waiter is parked (the handshake's own next read observes the closed channel).
      */
    private def failUpgradeHandoff(handle: NioHandle)(using AllowUnsafe): Unit =
        import NioHandle.UpgradeHandoff
        handle.upgradeHandoff.get() match
            case parked: UpgradeHandoff.Waiter =>
                discard(handle.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle))
                parked.promise.completeDiscard(Result.succeed(Span.empty[Byte]))
            case _ => ()
        end match
    end failUpgradeHandoff

    /** Retire the standing STARTTLS upgrade producer at handshake completion: CAS the producer read-arm cell to Absent and drop OP_READ + the
      * pendingReads entry, so no post-FINISHED readiness dispatch routes the upgraded connection's first application bytes to the dead producer
      * cell. The pendingReads removal is the authoritative gate (dispatchRead keys off pendingReads), so even if the interestOps clear is lost on a
      * cross-carrier completion the next dispatch is a no-op and the bytes wait in the socket for the ReadPump's own arm in connection.start().
      */
    override def stopUpgradeProducer(handle: NioHandle)(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        // Fail the retired occupant instead of discarding it: normally the producer's throwaway vehicle (a harmless completion nobody
        // observes), but a stray pump arm whose carrier stalled across the whole handshake can be the occupant here, and this retire is the
        // last catcher on that ordering. Runs at FINISHED before completeConnect starts the upgraded pump, so the occupant is never the new
        // connection's arm.
        handle.readArm.getAndSet(Absent).foreach { cell =>
            cell.promise.completeDiscard(Result.fail(Closed(
                s"connection ${handleLabel(handle)}",
                handle.createdAt,
                "detached for upgrade"
            )))
        }
        discard(pendingReads.remove(handle.channel))
        val key = handle.channel.keyFor(selector)
        if (key ne null) && key.isValid then
            try discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
            catch case _: CancelledKeyException => ()
        end if
    end stopUpgradeProducer

    def awaitWritable(handle: NioHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        if handle.life.writable.park(promise, released(handle)) then
            pendingWritables.put(handle.channel, promise)
            Log.live.unsafe.debug(s"$label awaitWritable registered ${handleLabel(handle)}")
            if !registerInterest(handle.channel, SelectionKey.OP_WRITE) then
                discard(pendingWritables.remove(handle.channel))
                promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "registerWrite failed")))
            end if
        end if
    end awaitWritable

    /** Arm (or re-arm) OP_CONNECT for `channel` and force a DEFINITE poll cycle with an UNCONDITIONAL `selector.wakeup()`, the connect-arm analog
      * of the read path's `forceReadArmWakeup` (armRead). The JDK selector is level-triggered for OP_CONNECT, so the only way a pending connect's
      * readiness is missed is `select()` blocking past it while the arm's wakeup was coalesced away (the guarded `wakeupPending` CAS loses to an
      * in-flight wakeup, the selector returns for the other event, clears the flag, and re-blocks before this arm's interest is observed). A connect
      * burst makes that window common. The unconditional wakeup guarantees one poll cycle in which `reassertPendingInterest` re-applies OP_CONNECT
      * and `select()` observes the (level-triggered) connect readiness, so a connect arm can never be coalesced away. Returns registerInterest's
      * result so the caller fails the promise on a hard register failure. Bounded: scoped to the OP_CONNECT arm/re-arm sites only (awaitConnect, the
      * dispatchConnect partial re-arm), never the steady-state read/write paths, so it cannot become a self-sustaining wakeup storm.
      */
    private def armConnectInterest(channel: SocketChannel)(using AllowUnsafe): Boolean =
        val registered = registerInterest(channel, SelectionKey.OP_CONNECT)
        if registered then
            discard(connectWakeups.incrementAndGet()) // test-observability: count the unconditional connect-arm wakeups
            discard(selector.wakeup())
        registered
    end armConnectInterest

    def awaitConnect(handle: NioHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        if !handle.life.armable then promise.completeDiscard(Result.fail(released(handle)))
        else
            Maybe(pendingConnects.putIfAbsent(handle.channel, (promise, handle))) match
                case Absent =>
                    Log.live.unsafe.debug(s"$label awaitConnect registered ${handleLabel(handle)}")
                    if !handle.life.connect.park(promise, released(handle)) then discard(pendingConnects.remove(handle.channel))
                    else if !armConnectInterest(handle.channel) then
                        discard(pendingConnects.remove(handle.channel))
                        promise.completeDiscard(Result.fail(Closed(
                            s"connection ${handleLabel(handle)}",
                            handle.createdAt,
                            "registerConnect failed"
                        )))
                    end if
                case Present(_) =>
                    // NoStackTrace: constructed on the driver carrier, whose stack crosses C poller
                    // frames the Scala Native unwinder cannot step through on arm64; the trace would
                    // record driver internals anyway, not the misusing caller on its own fiber.
                    promise.completeDiscard(Result.panic(new IllegalStateException(
                        s"$label duplicate awaitConnect for ${handleLabel(handle)}"
                    ) with scala.util.control.NoStackTrace))
            end match
    end awaitConnect

    /** IoDriver contract stub for the NIO driver. The NIO driver uses its own private accept seam (ServerSocketChannel-based); callers must
      * use the ServerSocketChannel overload below, not this one. Fails loudly so an accidental caller gets an immediate error rather than
      * silently receiving fd 0 (stdin), which is a latent misuse bug.
      */
    def awaitAccept(handle: NioHandle, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        promise.completeDiscard(Result.Panic(NetDriverUnsupportedException(label, "awaitAccept")))

    /** The NIO driver never submits an engine op: its JDK SSLEngine is reached only through [[NioTlsState]] with the handle's [[NioGate]]
      * held. A caller that reaches NioIoDriver through the IoDriver interface gets `op` run directly.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    /** Under the handle's [[NioGate]]: inline when it is free, after the current section when this thread holds it, and otherwise on the
      * selector carrier once the holder releases it.
      */
    def runOwned(handle: NioHandle)(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        handle.gate.enqueue(op)
        discard(handle.gate.lockedOrDefer(handle.gate.runQueued()))
    end runOwned

    def write(handle: NioHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        handle.tls match
            // No early Done at the span's end: the TLS write returns Partial at offset == data.size when its last record only partly fit the
            // socket, and only it flushes that record's held ciphertext on the retry.
            case Present(tls) => handle.gate.locked(tls.write(handle.channel, data, offset))
            case Absent       =>
                if data.isEmpty || offset >= data.size then WriteResult.Done
                else handle.gate.locked(writePlain(handle, data, offset))

    private def writePlain(handle: NioHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        try
            val arr = data.toArrayUnsafe
            val len = arr.length - offset
            // Wrap with offset so no arr.drop(n) allocation is needed on partial writes.
            val buf = ByteBuffer.wrap(arr, offset, len)
            val n   = handle.channel.write(buf)
            if n < 0 then
                WriteResult.Error
            else if n >= len then
                WriteResult.Done
            else
                WriteResult.Partial(data, offset + n) // same span, advanced offset, no arr.drop
            end if
        catch
            case _: IOException => WriteResult.Error

    /** Remove pending operations for a channel and fail their promises with Closed. */
    private def cleanupPending(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label cleanupPending ${handleLabel(handle)} reads=${
                if pendingReads.containsKey(handle.channel) then 1 else 0
            } writes=${if pendingWritables.containsKey(handle.channel) then 1 else 0} connects=${
                if pendingConnects.containsKey(handle.channel) then 1 else 0
            }")
        val closed = Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "closed")
        // The read sweep is slot-first, never keyed on the pendingReads entry: a dispatch that consumed the entry without completing the
        // promise (the STARTTLS salvage branch) or an arm whose entry put raced this remove leaves a live cell with no entry, and a
        // map-keyed sweep misses it, stranding a promise nothing else ever completes. getAndSet is exactly-once against every completer
        // (they all CAS on their read cell); taking a probe cell fails only its throwaway promise. stopUpgradeProducer is the in-file
        // precedent for slot-first sweeping.
        discard(pendingReads.remove(handle.channel))
        handle.readArm.getAndSet(Absent).foreach { armCell =>
            armCell.promise.completeDiscard(Result.fail(closed))
        }
        // Fail a STARTTLS handshake parked on the upgrade handoff. A no-op during detachForUpgrade (the slot is Idle: the handshake parks only after
        // detach) and for non-upgrade teardowns; on a real close mid-upgrade it releases the parked waiter so the handshake tears down instead of
        // hanging. Mirrors PosixHandle.freeResources' upgrade-slot handling.
        handle.upgradeHandoff.getAndSet(NioHandle.UpgradeHandoff.Idle) match
            case NioHandle.UpgradeHandoff.Waiter(p, _) => p.completeDiscard(Result.fail(closed))
            case _                                     => ()
        end match
        Maybe(pendingWritables.remove(handle.channel)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
        Maybe(pendingConnects.remove(handle.channel)).foreach { case (promise, _) =>
            promise.completeDiscard(Result.fail(closed))
        }
        handle.life.cancel(closed)
    end cleanupPending

    // NIO keys every registration by its channel object, never by fd number, so a recycled number cannot reach a closed handle's state.
    def releaseFd(handle: NioHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit = closeFd()

    def closeListener(handle: NioHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(handle.life.claimRelease())
        try
            cancelKey(handle)
            cleanupPending(handle)
        finally releaseFd(handle, closeFd)
        end try
    end closeListener

    private def cancelKey(handle: NioHandle)(using AllowUnsafe): Unit =
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then key.cancel()
        catch
            case _: CancelledKeyException => ()
        end try
    end cancelKey

    /** Fails every op parked on the handle and withdraws its interest, but KEEPS the channel's SelectionKey: cancel takes no release claim, so
      * an op parked after it is live and re-arms interest on the SAME key via registerInterest. Cancelling the key instead would mark it for
      * removal at the next select() and make every later arm on the channel fail. Only the release cancels the key.
      */
    def cancel(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then
                discard(key.interestOps(0))
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
            end if
        catch
            case _: CancelledKeyException => ()
        end try
        cleanupPending(handle)
    end cancel

    /** STARTTLS detach: a [[cancel]], so the same channel is re-driven as a TLS handshake on the same live key, plus the staging salvage. */
    override def detachForUpgrade(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        cancel(handle)
        // Backstop for the resume-then-probe window: onInboundClosedDuringRead salvages graceStaging only when the pump parked, but a probe can stage
        // the peer's flight after the pump resumed. Salvage it here so the handshake sees it; a no-op (empty) when the inline hook already drained it.
        drainGraceStaging(handle).foreach(staged => stashUpgradeBytes(handle, staged))
    end detachForUpgrade

    /** The handle's one release: takes the release claim before the sweep, so a repeat call is a no-op and no op is parked or re-armed after
      * it, then, on the handle's owner, cancels the key and closes the channel, so the close never lands inside a write or an engine
      * operation in progress.
      */
    def closeHandle(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        if handle.life.claimRelease() then runOwned(handle)(() => release(handle))

    private def release(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label closeHandle ${handleLabel(handle)}")
        cleanupPending(handle)
        cancelKey(handle)
        NioHandle.close(handle)
        // Wake the selector so the cancelled key is deregistered, and on JDK 11+ the channel's fd actually kill()ed, on the next select()
        // pass. channel.close defers both to a selection operation, and the loop parks in an indefinite select() with no timeout, so on an
        // otherwise-idle driver (the last connection closing, or a peer-FIN teardown with nothing else pending) this close would otherwise
        // leave the fd stranded in CLOSE_WAIT until an unrelated event happened to wake the loop. Coalesced via wakeupPending, matching the
        // transport and listener closes that wake the selector for the identical deferred-kill() reason.
        wakeup()
    end release

    /** Remove a pending accept entry for a server channel and fail its promise with Closed. */
    def cleanupAccept(serverChannel: ServerSocketChannel, createdAt: Frame)(using AllowUnsafe, Frame): Unit =
        val closed = Closed(s"listener channel=${serverChannel.hashCode()}", createdAt, "closed")
        Maybe(pendingAccepts.remove(serverChannel)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
    end cleanupAccept

    /** Wake the selector so a deferred deregistration/close is processed on the next `select()` cycle even when the loop would otherwise park
      * with no timeout. A closed channel's `SelectionKey` is only deregistered (and, on JDK 11+, its fd actually `kill()`ed) during a `select()`
      * pass; nothing else wakes the selector on a listener close, so an idle driver would leave the cancelled key, and the listening socket,
      * pending indefinitely. Mirrors the wakeup coalescing the interest-change paths use (`wakeupPending` is cleared post-select).
      */
    def wakeup()(using AllowUnsafe): Unit =
        if wakeupPending.compareAndSet(false, true) then
            discard(selector.wakeup())
    end wakeup

    /** Arm the release of a closed listener's descriptor. `serverChannel.close()` has already cancelled the channel's SelectionKey, but the fd is
      * closed (`kill()`ed) only inside a later selector pass that deregisters the cancelled key. The entry is queued for the poll carrier and the
      * selector is woken unconditionally rather than through the coalescing `wakeup()`: a wakeup coalesced into a pass that began before the
      * cancel landed would leave an idle driver parked with the descriptor open. The promise completes once a pass has observed the channel
      * deregistered, or at once when the driver is already closed, since `implCloseSelector` killed every channel. On a driver whose loop has
      * not started it completes on the loop's first pass or in `close()`, whichever comes first, and never earlier.
      */
    def releaseListener(serverChannel: ServerSocketChannel, released: Promise.Unsafe[Unit, Any])(using AllowUnsafe): Unit =
        discard(pendingListenerReleases.offer((serverChannel, released)))
        discard(selector.wakeup())
        if selectorClosed.get() then drainListenerReleases()
    end releaseListener

    /** Complete every queued listener release whose channel the selector has deregistered, re-queuing the rest for the next pass.
      *
      * `isRegistered()` is the observation: `SelectorImpl.processDeregisterQueue` decrements the channel's key count one statement before it
      * calls `kill()`, so a false read after `select()` returned means the fd is closed. A channel still registered cancelled its key after that
      * pass's deregistration ran; the wakeup armed here makes the next pass run its deregistration before parking and return at once. An entry
      * is re-queued at most twice: once per selector, the second time only when a rebuild re-registers a listener that is closed while the
      * rebuild runs, so its key is cancelled on the new selector after that selector's first pass. The extra zero-key returns therefore cannot
      * accumulate toward the rebuild threshold. Bounded by the size snapshot so a re-queued entry is not revisited within the pass. After a re-queue the closed flag is re-read: a `close()` that ran between
      * the registration check and the re-offer drained a queue this entry was absent from, and this carrier then completes it itself.
      */
    private def drainListenerReleases()(using AllowUnsafe): Unit =
        var remaining = pendingListenerReleases.size()
        var requeued  = false
        while remaining > 0 do
            remaining -= 1
            val entry = pendingListenerReleases.poll()
            if entry ne null then
                if selectorClosed.get() || !entry._1.isRegistered() then entry._2.completeUnitDiscard()
                else
                    discard(pendingListenerReleases.offer(entry))
                    discard(selector.wakeup())
                    requeued = true
            end if
        end while
        if requeued && selectorClosed.get() then drainListenerReleases()
    end drainListenerReleases

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            Log.live.unsafe.debug(
                s"$label closing driver, failing ${pendingReads.size()} reads, ${pendingWritables.size()} writes, ${pendingConnects.size()} connects, ${pendingAccepts.size()} accepts"
            )
            // Failing a promise runs its callbacks inline, so the selector teardown is in a `finally`: a throw from one of them must not
            // leave the selector open or the queued listener releases pending. The flag is set before the drain so a
            // release armed after this drain completes itself.
            try failPendingOps()
            finally
                try selector.close()
                catch case _: IOException => ()
                selectorClosed.set(true)
                drainListenerReleases()
                drainServerRegistrations()
                // owned ops a gate release handed back run once more here, since no selector cycle follows (see offerGateWork)
                drainGateWork()
                diagRegistration.close()
            end try
        end if
    end close

    private def failPendingOps()(using AllowUnsafe, Frame): Unit =
        val closed = Closed(label, Frame.internal, "driver closed")
        // This sweep iterates the pendingReads map, unlike cleanupPending's slot-first per-handle sweep: an armed cell whose entry a
        // dispatch consumed (the STARTTLS salvage shape) is invisible here. Covered in practice because the transport's close cancels
        // every connection (cleanupPending) before the driver close runs; this map walk is the backstop for entries those closes missed.
        pendingReads.forEach { (_, h) =>
            h.readArm.getAndSet(Absent).foreach { armCell =>
                armCell.promise.completeDiscard(Result.fail(closed))
            }
        }
        pendingReads.clear()
        pendingWritables.forEach { (_, promise) =>
            promise.completeDiscard(Result.fail(closed))
        }
        pendingWritables.clear()
        pendingConnects.forEach { (_, entry) =>
            entry._1.completeDiscard(Result.fail(closed))
        }
        pendingConnects.clear()
        pendingAccepts.forEach { (_, promise) =>
            promise.completeDiscard(Result.fail(closed))
        }
        pendingAccepts.clear()
        // Drop any handles awaiting deferred registration: the driver is gone, so the poll carrier will never drain them. Their downstream
        // awaitX promises (if any were armed during the deferred window) are already failed by the pending-op-map cleanup above; the channels
        // are owned and closed by the caller (the upgrade teardown).
        pendingRegistrations.clear()
        // Fail any STARTTLS upgrade whose bootstrap arm was enqueued but not yet applied: the driver is gone, so drainUpgradeArms will never run.
        // Such a handle is not yet in pendingReads (applyUpgradeArm puts it), so the loop above did not fail its handshake waiter; the waiter
        // parked on the upgrade handoff slot by driveHandshake right after the bootstrap enqueue is failed here so the handshake tears down.
        var pendingArm = pendingUpgradeArms.poll()
        while pendingArm ne null do
            pendingArm.upgradeHandoff.getAndSet(NioHandle.UpgradeHandoff.Idle) match
                case NioHandle.UpgradeHandoff.Waiter(p, _) => p.completeDiscard(Result.fail(closed))
                case _                                     => ()
            pendingArm = pendingUpgradeArms.poll()
        end while
    end failPendingOps

    /** Register a channel with this driver's selector. Must be called before awaitRead/awaitWritable. */
    def registerChannel(handle: NioHandle)(using AllowUnsafe): Boolean =
        handle.gate.bind(() => offerGateWork(handle))
        try
            if wakeupPending.compareAndSet(false, true) then
                discard(selector.wakeup())
            val target = selector
            discard(handle.channel.register(target, 0))
            given Frame = Frame.internal
            // A rebuild that took its snapshot of the selector's channels before this registration landed moves every channel but this one,
            // then closes the selector this one is on. It raises `rebuilding` before the snapshot and swaps `selector` before lowering it, so a
            // registration that raced it observes one or the other here and hands itself to the poll carrier, which registers it on the live
            // selector with its interest rebuilt from the pending-op maps.
            if rebuilding.get() || (selector ne target) then
                discard(pendingRegistrations.offer(handle))
                discard(selector.wakeup())
                Log.live.unsafe.debug(s"$label registerChannel raced a selector rebuild, deferred ${handleLabel(handle)}")
            else Log.live.unsafe.debug(s"$label registerChannel ${handleLabel(handle)}")
            end if
            true
        catch
            case _: java.nio.channels.CancelledKeyException =>
                // A cancelled key for this channel is still pending in the selector's cancelled-key set. The set is flushed only by the poll
                // carrier at the start of each `select()`, and `channel.register` throws `CancelledKeyException` until then. Retrying inline
                // would force this caller carrier to wait for the flush: either a `selectNow()` (which deadlocks against the poll carrier that
                // holds the SelectorImpl monitor for the whole native poll, with unfair JVM locks giving it no progress guarantee) or an
                // OS-thread park (the forbidden block). The only sanctioned OS-thread block in the module is the poll carrier's `select()`
                // head-of-line, so neither is allowed here.
                //
                // Instead, hand the registration to the selector's owner: enqueue the handle, wake the poll carrier, and report success. The
                // poll carrier drains `pendingRegistrations` at the top of `pollOnce`, AFTER `select()` has flushed the cancelled-key set, and
                // completes the `channel.register(selector, 0)` there. The registration is therefore deferred but guaranteed: no inline park, no
                // spin. Triggers via Postgres SSLRequest upgrade (`NioTransport.upgradeToTls` re-registers the same channel after the plaintext
                // pump's `detachForUpgrade` cancelled its key).
                discard(pendingRegistrations.offer(handle))
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label registerChannel deferred to poll carrier ${handleLabel(handle)}")
                true
            case _: java.nio.channels.ClosedSelectorException if !closedFlag.get() =>
                // The poll carrier's rebuildSelector closed the old selector between this caller carrier's read of `selector` and its
                // channel.register (the selector-spin rebuild a concurrent connect burst induces). The rebuild installs a fresh live selector
                // and loops straight back into pollOnce; hand the registration to it exactly as the CancelledKeyException path does, rather than
                // returning false and failing the connect with an empty-cause NetConnectException. drainPendingRegistrations re-registers the
                // channel on the live selector with interest reconstructed from the pending-op maps, so the registration is deferred but
                // guaranteed. selector is @volatile so the wakeup targets the live selector once the swap is visible. Gated on !closedFlag: a
                // ClosedSelectorException during driver shutdown (closedFlag set) is a genuine terminal failure, so it still returns false; only
                // a rebuild-window close (driver still live) defers.
                discard(pendingRegistrations.offer(handle))
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
                true
            case _: java.nio.channels.ClosedSelectorException      => false
            case _: java.nio.channels.ClosedChannelException       => false
            case _: java.nio.channels.IllegalBlockingModeException => false
        end try
    end registerChannel

    /** Register a server channel for accept operations, on the poll carrier, the carrier that rebuilds the selector. A registration applied on
      * any other carrier can land on the selector a rebuild just closed, or on the old selector after the rebuild took its snapshot of the
      * channels it moves, and the listener is then never registered. `onRegistered` receives the registration's failure, `Absent` once the
      * channel is registered, on the carrier that applied it.
      */
    def registerServerChannel(serverChannel: ServerSocketChannel)(onRegistered: Maybe[Exception] => Unit)(using AllowUnsafe): Unit =
        discard(pendingServerRegistrations.offer((serverChannel, onRegistered)))
        discard(selector.wakeup())
        // A closed selector runs no further pass; close() drains once after it sets the flag, and an offer after that drain drains here.
        if selectorClosed.get() then drainServerRegistrations()
    end registerServerChannel

    /** Apply every queued server-channel registration. Poll-carrier only while the loop runs, and on the closing carrier once the selector is
      * closed, where each fails with the selector's `ClosedSelectorException`. Dequeued with poll(), so an entry is applied once whichever
      * carrier takes it.
      */
    private[net] def drainServerRegistrations()(using AllowUnsafe): Unit =
        var entry = pendingServerRegistrations.poll()
        while entry ne null do
            val (serverChannel, onRegistered) = entry
            val failure: Maybe[Exception]     =
                try
                    discard(serverChannel.register(selector, 0))
                    Absent
                catch
                    case e: java.nio.channels.ClosedChannelException       => Present(e)
                    case e: java.nio.channels.ClosedSelectorException      => Present(e)
                    case e: java.nio.channels.IllegalBlockingModeException => Present(e)
            onRegistered(failure)
            entry = pendingServerRegistrations.poll()
        end while
    end drainServerRegistrations

    /** Wait for server channel to have pending connections. Promise completes when accept() will not block. */
    def awaitAccept(serverChannel: ServerSocketChannel, promise: Promise.Unsafe[Unit, Abort[Closed]], createdAt: Frame)(using
        AllowUnsafe,
        Frame
    ): Unit =
        pendingAccepts.put(serverChannel, promise)
        Log.live.unsafe.debug(s"$label awaitAccept registered server=${serverChannel.hashCode()}")
        if !registerServerInterest(serverChannel, SelectionKey.OP_ACCEPT) then
            discard(pendingAccepts.remove(serverChannel))
            promise.completeDiscard(Result.fail(Closed(
                s"listener channel=${serverChannel.hashCode()}",
                createdAt,
                "registerAccept failed"
            )))
        end if
    end awaitAccept

    private def registerServerInterest(channel: ServerSocketChannel, ops: Int)(using AllowUnsafe): Boolean =
        try
            val key = channel.keyFor(selector)
            if (key ne null) && key.isValid then
                val current = key.interestOps()
                val newOps  = current | ops
                if newOps != current then
                    discard(key.interestOps(newOps))
                    if wakeupPending.compareAndSet(false, true) then
                        discard(selector.wakeup())
                end if
                given Frame = Frame.internal
                Log.live.unsafe.debug(
                    s"$label registerServerInterest channel=${channel.hashCode()} ops=${opsToString(ops)} newOps=${opsToString(newOps)}"
                )
                true
            else
                false
            end if
        catch
            case _: CancelledKeyException => false

    /** True while `channel` is still in the deferred-registration queue: its `registerChannel` hit the cancelled-key race and the poll carrier
      * has not yet completed the registration. In this window the channel has no `SelectionKey`, so an `awaitX` arming interest cannot touch a
      * key; the interest is held in the pending-op map and applied by `drainPendingRegistrations` when it reconstructs interest at registration.
      */
    private def isPendingRegistration(channel: java.nio.channels.SelectableChannel): Boolean =
        val iter  = pendingRegistrations.iterator()
        var found = false
        while !found && iter.hasNext do
            if iter.next().channel eq channel then found = true
        found
    end isPendingRegistration

    private def registerInterest(channel: SocketChannel, ops: Int)(using AllowUnsafe): Boolean =
        try
            val key = channel.keyFor(selector)
            if (key ne null) && key.isValid then
                val current = key.interestOps()
                val newOps  = current | ops
                if newOps != current then
                    discard(key.interestOps(newOps))
                    if wakeupPending.compareAndSet(false, true) then
                        discard(selector.wakeup())
                end if
                given Frame = Frame.internal
                Log.live.unsafe.debug(
                    s"$label registerInterest channel=${channel.hashCode()} ops=${opsToString(ops)} newOps=${opsToString(newOps)}"
                )
                true
            else if isPendingRegistration(channel) then
                // Deferred registration in flight: the channel has no key yet. The interest is already in the pending-op map (the calling awaitX
                // put it there before this call), and drainPendingRegistrations reconstructs interest from those maps when it registers the
                // channel on the poll carrier, so report success here. Wake the poll carrier so it drains promptly. Mirrors the rebuildSelector
                // interest-reconstruction invariant: the pending-op maps are the source of truth for what is armed.
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
                given Frame = Frame.internal
                Log.live.unsafe.debug(
                    s"$label registerInterest deferred channel=${channel.hashCode()} ops=${opsToString(ops)}"
                )
                true
            else
                false
            end if
        catch
            case _: CancelledKeyException                                          => false
            case _: java.nio.channels.ClosedSelectorException if !closedFlag.get() =>
                // The poll carrier's rebuildSelector closed the selector under this caller-carrier interest set (the same selector-spin rebuild
                // a connect burst induces). The interest is already recorded in the pending-op map (the calling awaitX put it there before this
                // call), and rebuildSelector reconstructs every channel's interest from those maps on the new selector, so report success rather
                // than failing the op. Mirrors the isPendingRegistration deferred branch above: the pending-op maps are the source of truth. Gated
                // on !closedFlag: a ClosedSelectorException during driver shutdown is terminal and still returns false.
                true
            case _: java.nio.channels.ClosedSelectorException => false

    /** Re-assert each pending op's interest bit on its channel's key, deriving from the pending-op maps (the source of truth, the same maps
      * rebuildSelector reconstructs from). Add-only: a fired op clears its own bit in dispatchReadyKeys, so this only restores a bit that a
      * cross-carrier interestOps race or a coalesced/lost wakeup dropped while the op is still pending. Poll-carrier-only (called from pollOnce).
      */
    private def reassertPendingInterest()(using AllowUnsafe): Unit =
        reassertOp(pendingReads.keySet().iterator(), SelectionKey.OP_READ)
        reassertOp(pendingWritables.keySet().iterator(), SelectionKey.OP_WRITE)
        reassertOp(pendingConnects.keySet().iterator(), SelectionKey.OP_CONNECT)
    end reassertPendingInterest

    private def reassertOp(it: java.util.Iterator[SocketChannel], op: Int)(using AllowUnsafe): Unit =
        while it.hasNext do
            val ch  = it.next()
            val key = ch.keyFor(selector)
            if (key ne null) && key.isValid then
                try
                    val cur = key.interestOps()
                    if (cur & op) == 0 then discard(key.interestOps(cur | op))
                catch case _: CancelledKeyException => ()
            end if
        end while
    end reassertOp

    private def pollOnce()(using AllowUnsafe): Boolean =
        try
            // Indefinite select: blocks until an event arrives or selector.wakeup() is called. The select()
            // returns early when any key becomes ready, when close() closes the selector (ClosedSelectorException),
            // or when wakeup() is called (e.g. by armInterest after enqueuing a registration). The
            // reassertPendingInterest() call below is the liveness backstop for lost interest bits: any armed op
            // whose bit was cleared by a cross-carrier race is re-asserted on this cycle. This mirrors the
            // epoll/kqueue poller's indefinite kevent + wake.
            // Hand off everything this carrier is holding BEFORE parking in the wait below. The park pins this worker for the whole
            // duration of the wait, and a task sitting in its local queue cannot run while it is pinned: nothing else frees a parked
            // worker's queue, since a steal is opportunistic and preemption is deliberately withheld from a worker whose task is
            // parked in a syscall rather than burning a time slice. That deadlocks outright when the queued task is what would
            // produce the event this wait is about to block on. flush() re-schedules those tasks onto other workers (it excludes
            // this one) and is a no-op off a worker thread. It cannot close the window on its own, since a task can still land
            // here after the flush and before the wait returns; Worker.checkAvailability drains that residue once the blocking
            // monitor flags this worker.
            Scheduler.get.flush()
            val n = selector.select()
            // Post-select re-check: clear the pending flag now that select() has returned.
            discard(wakeupPending.compareAndSet(true, false))
            // Drain deferred registrations now that select() has flushed the selector's cancelled-key set: any
            // channel whose register() raced a lingering cancelled key (the STARTTLS upgrade re-registration) can
            // now be registered cleanly. Done before key dispatch so a freshly registered channel can have its
            // interest armed (awaitX) and reported on the next cycle.
            drainPendingRegistrations()
            // Listener registrations apply here, on the carrier that rebuilds the selector, so none can land on a selector being replaced.
            drainServerRegistrations()
            // Complete the releases of listeners closed since the previous pass: the select() that just returned ran the deregistration that
            // kills a cancelled listener channel. Before dispatch, so a continuation awaiting a release runs among this cycle's completions.
            drainListenerReleases()
            // Apply any deferred STARTTLS demand-driven upgrade arm on this (poll) carrier, so its OP_READ registration is selector-confined and
            // never a cross-carrier interestOps read-modify-write. Done after drainPendingRegistrations (the channel's key already
            // exists, kept live by detachForUpgrade) and before reassert so the freshly armed read is reasserted on this same cycle if needed.
            drainUpgradeArms()
            // selector-confined like drainUpgradeArms above
            drainGraceProbeArms()
            // staged deliveries, owned ops and deferred read re-arms handed to this carrier (selector-confined like the probe arms)
            drainGateWork()
            // Re-assert armed interest from the pending-op maps (the source of truth): restores any OP_READ/OP_WRITE/OP_CONNECT bit dropped by a
            // cross-carrier interestOps race (a dispatch-clear racing an arm) or a coalesced/lost wakeup. This is the liveness backstop:
            // an armed op whose interest bit was lost is re-armed within one cycle so it is visible on the next select().
            reassertPendingInterest()
            if n > 0 then
                zeroKeyReturns = 0
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label select returned $n ready keys")
                dispatchReadyKeys()
            else
                // All zero-key returns from an indefinite select() are wakeup-driven (readiness change, wakeup() call, or spurious return).
                // Every zero-key return counts toward the spin-rebuild heuristic so a stuck selector is detected regardless of elapsed time.
                zeroKeyReturns += 1
                if shouldRebuild(zeroKeyReturns) then
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label selector spin detected ($zeroKeyReturns zero-key returns), rebuilding selector")
                    rebuildSelector()
                    zeroKeyReturns = 0
                end if
            end if
            true
        catch
            case _: java.nio.channels.ClosedSelectorException => false

    /** Drain the deferred-registration queue on the poll carrier. Each enqueued handle hit the cancelled-key race in `registerChannel`; the
      * `select()` that just returned flushed the selector's cancelled-key set, so its `channel.register` can now succeed, registered with the
      * interest reconstructed from the pending-op maps. A registration that STILL throws `CancelledKeyException` (the key has not been flushed
      * yet, e.g. its cancel landed after this `select()` began) leaves the handle at the queue head and stops the drain for this cycle, arming a
      * wakeup so the next `select()` flushes the key and the retry happens promptly rather than blocking until an unrelated event. A closed
      * channel / selector is dropped (the upgrade or driver is gone; the caller already observed success, and the downstream `awaitX` fails the
      * parked promise with `Closed`). Called only from the poll carrier (single-carrier-confined consumer).
      */
    private[net] def drainPendingRegistrations()(using AllowUnsafe): Unit =
        // The poll carrier is the only consumer, so peek-then-poll is a safe atomic dequeue here: a handle is removed from the queue ONLY after
        // its register() has created the SelectionKey (or it is being dropped). This ordering closes the race with a concurrent registerInterest on
        // a caller carrier: once a channel is absent from the queue, its key already exists, so registerInterest never sees the (no-key AND
        // not-pending) gap that would fail the awaitX. A still-cancelled key (register throws CancelledKeyException again) stops the drain with the
        // handle left at the head, retried on the next select() cycle (which re-flushes the cancelled-key set); a wakeup is armed so that cycle
        // comes promptly. The loop is bounded by the snapshot size so it never spins on a re-enqueued or un-flushable head within one cycle.
        var remaining  = pendingRegistrations.size()
        var keyBlocked = false
        while remaining > 0 && !keyBlocked do
            remaining -= 1
            val handle = pendingRegistrations.peek()
            if handle ne null then
                try
                    // Reconstruct armed interest from the pending-op maps, exactly as rebuildSelector does: an awaitX issued during the deferred
                    // window recorded its interest in the map (registerInterest returned the deferred-success path), so registering with interest 0
                    // here would drop it and the operation's promise would never complete. Connect is the upgrade path's relevant op; read/write
                    // are included for completeness and to mirror the rebuild invariant.
                    val sc  = handle.channel
                    var ops = 0
                    if pendingReads.containsKey(sc) then ops = ops | SelectionKey.OP_READ
                    if pendingWritables.containsKey(sc) then ops = ops | SelectionKey.OP_WRITE
                    if pendingConnects.containsKey(sc) then ops = ops | SelectionKey.OP_CONNECT
                    discard(sc.register(selector, ops))
                    // Registration succeeded (the key now exists): only now remove the handle from the queue.
                    discard(pendingRegistrations.poll())
                    given Frame = Frame.internal
                    Log.live.unsafe.debug(s"$label registerChannel (deferred) ${handleLabel(handle)} ops=${opsToString(ops)}")
                    // Edge recovery for a deferred CONNECT: this channel's registration was deferred across the cancelled-key / rebuild window, so
                    // its OP_CONNECT was registered on this (possibly freshly rebuilt) selector only now. If the OS connect completed DURING that
                    // deferral window, the channel is already connect-ready and the selector does not re-surface OP_CONNECT for an interest registered
                    // after the readiness occurred, so the standing arm never dispatches and the connect strands to its deadline (the
                    // deferred-connect-after-rebuild TIMEOUT). Force one dispatchConnect probe now: if finishConnect succeeds it completes the promise
                    // immediately, otherwise it is a harmless no-op that re-arms OP_CONNECT for the real edge. Mirrors the read path's missedReads
                    // force-dispatch recovery (a deferred OP_READ already gets a speculative read; connect lacked the analogue).
                    if (ops & SelectionKey.OP_CONNECT) != 0 then dispatchConnect(sc)
                catch
                    case _: java.nio.channels.CancelledKeyException =>
                        // Key not flushed yet: leave the handle at the queue head and stop draining this cycle. Wake the poll loop so the next
                        // select() flushes the cancelled-key set and the retry happens promptly rather than blocking until an unrelated event.
                        keyBlocked = true
                        if wakeupPending.compareAndSet(false, true) then
                            discard(selector.wakeup())
                    case _: java.nio.channels.ClosedChannelException =>
                        discard(pendingRegistrations.poll())
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label registerChannel (deferred) dropped closed channel ${handleLabel(handle)}")
                    case _: java.nio.channels.ClosedSelectorException =>
                        discard(pendingRegistrations.poll())
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label registerChannel (deferred) dropped on closed selector ${handleLabel(handle)}")
                    case _: java.nio.channels.IllegalBlockingModeException =>
                        discard(pendingRegistrations.poll())
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label registerChannel (deferred) dropped blocking-mode channel ${handleLabel(handle)}")
                end try
            end if
        end while
    end drainPendingRegistrations

    private def dispatchReadyKeys()(using AllowUnsafe): Unit =
        installedKeySet match
            case Present(flatSet) =>
                val arr   = flatSet.filledKeys
                val total = flatSet.size()
                var i     = 0
                while i < total do
                    val key = arr(i)
                    i += 1
                    if key ne null then
                        try
                            if key.isValid then
                                val ready = key.readyOps()
                                if (ready & SelectionKey.OP_ACCEPT) != 0 then
                                    discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_ACCEPT))
                                    // Safe: an OP_ACCEPT-ready key was registered by a ServerSocketChannel, the only channel type the driver registers for accept.
                                    dispatchAccept(key.channel().asInstanceOf[ServerSocketChannel])
                                else
                                    // Safe: a non-accept key (connect/read/write) was registered by a SocketChannel, the only channel type the driver registers for those ops.
                                    val channel = key.channel().asInstanceOf[SocketChannel]
                                    if (ready & SelectionKey.OP_CONNECT) != 0 then
                                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT))
                                        dispatchConnect(channel)
                                    end if
                                    if (ready & SelectionKey.OP_READ) != 0 then
                                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
                                        dispatchRead(channel)
                                    end if
                                    if (ready & SelectionKey.OP_WRITE) != 0 then
                                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE))
                                        dispatchWritable(channel)
                                    end if
                                end if
                            end if
                        catch
                            case _: CancelledKeyException => ()
                        end try
                    end if
                end while
                flatSet.reset()
            case Absent =>
                val keys = selector.selectedKeys()
                val iter = keys.iterator()
                dispatchKeysLoop(iter)
    end dispatchReadyKeys

    @tailrec
    private def dispatchKeysLoop(iter: java.util.Iterator[SelectionKey])(using AllowUnsafe): Unit =
        if iter.hasNext then
            val key = iter.next()
            iter.remove()
            try
                if key.isValid then
                    val ready = key.readyOps()
                    if (ready & SelectionKey.OP_ACCEPT) != 0 then
                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_ACCEPT))
                        // Safe: an OP_ACCEPT-ready key was registered by a ServerSocketChannel, the only channel type the driver registers for accept.
                        dispatchAccept(key.channel().asInstanceOf[ServerSocketChannel])
                    else
                        // Safe: a non-accept key (connect/read/write) was registered by a SocketChannel, the only channel type the driver registers for those ops.
                        val channel = key.channel().asInstanceOf[SocketChannel]
                        if (ready & SelectionKey.OP_CONNECT) != 0 then
                            discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT))
                            dispatchConnect(channel)
                        end if
                        if (ready & SelectionKey.OP_READ) != 0 then
                            discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
                            dispatchRead(channel)
                        end if
                        if (ready & SelectionKey.OP_WRITE) != 0 then
                            discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE))
                            dispatchWritable(channel)
                        end if
                    end if
                end if
            catch
                case _: CancelledKeyException => ()
            end try
            dispatchKeysLoop(iter)
        end if
    end dispatchKeysLoop

    /** Pure predicate: true when the accumulated consecutive zero-key returns exceed the rebuild threshold.
      *
      * `private[net]` so tests in `kyo.net.internal` can call it directly with concrete inputs.
      */
    private[net] def shouldRebuild(consecutiveZeroReturns: Int): Boolean =
        consecutiveZeroReturns >= NioIoDriver.SelectorRebuildThreshold

    /** Test-observability seam: the interest ops currently registered for `channel` on the live selector, or -1
      * when `channel` has no valid key. After a selector rebuild this reflects the new selector, so a test can
      * assert that an in-flight operation's armed interest survived the rebuild.
      */
    private[net] def interestOpsFor(channel: java.nio.channels.SelectableChannel)(using AllowUnsafe): Int =
        val key = channel.keyFor(selector)
        if (key ne null) && key.isValid then key.interestOps() else -1

    /** Rebuild the selector by snapshotting registered channels, opening a fresh selector, closing the old one,
      * and re-registering every channel with its in-flight interest reconstructed from the pending-op maps.
      * Resets the zero-key counter.
      *
      * The fresh selector is opened BEFORE the old one is closed: if `Selector.open()` fails (for example on
      * file-descriptor exhaustion) the old selector stays live and the rebuild is skipped this cycle, rather
      * than tearing down the only selector the driver has and stranding every channel.
      *
      * Interest is reconstructed from `pendingReads` / `pendingWritables` / `pendingConnects` / `pendingAccepts`,
      * the source of truth for what is armed: a channel re-registered with interest 0 would never report
      * readiness for an operation already pending at rebuild time, so that operation's promise would hang forever.
      *
      * `private[net]` so the rebuild path is directly exercisable by tests in `kyo.net.internal`. Concurrency contract: rebuildSelector itself runs
      * ONLY on the select-loop carrier (or before that loop starts), so its own snapshot/close/swap/re-register sequence is single-carrier. It is NOT,
      * however, isolated from caller carriers: `selector` is `@volatile` and is READ by caller-carrier paths (registerChannel, registerInterest)
      * that call `channel.register(selector, ...)` / `selector.wakeup()` concurrently with this swap. A caller that read the old selector just
      * before this `selector.close()` runs gets a `ClosedSelectorException`, and one whose registration landed on the old selector after the
      * snapshot below sees `rebuilding` raised or the selector swapped; both paths defer the registration to `pendingRegistrations` (re-registered
      * on the live selector with interest reconstructed from the pending-op maps) rather than failing the op, so the swap is correct under that
      * race. Server channels register only on this carrier (registerServerChannel). The `@volatile` makes the new selector visible to subsequent
      * caller-carrier reads.
      */
    private[net] def rebuildSelector()(using AllowUnsafe, Frame): Unit =
        rebuilding.set(true)
        try swapSelector()
        finally rebuilding.set(false)
    end rebuildSelector

    private def swapSelector()(using AllowUnsafe, Frame): Unit =
        // Snapshot channel references BEFORE closing the old selector (closing cancels all keys,
        // after which key.channel() may still work but the key set is no longer reliable).
        val channels = selector.keys().iterator().asScala
            .flatMap { k =>
                val ch = k.channel()
                if ch.isOpen then Some(ch) else None
            }
            .toArray

        val opened: Maybe[Selector] =
            try Present(Selector.open())
            catch case _: IOException => Absent

        opened match
            case Absent =>
                // Open failed: keep the current selector and retry at the next threshold rather than
                // leaving the driver with no selector at all.
                zeroKeyReturns = 0
                Log.live.unsafe.warn(s"$label selector rebuild skipped: Selector.open() failed, keeping the current selector")
            case Present(newSelector) =>
                try selector.close()
                catch case _: IOException => ()

                // Re-install the flat key set on the new selector if the original install succeeded.
                installedKeySet match
                    case Present(flatSet) => installFlatSetInto(newSelector, flatSet)
                    case Absent           => ()

                selector = newSelector
                zeroKeyReturns = 0
                // The swap invalidates any wakeup a concurrent offer-then-wakeup producer aimed at the old selector (a closed selector's
                // wakeup is a silent no-op), and a coalesced wakeup lost that way would leave wakeupPending latched true with no select
                // return to clear it, suppressing every future guarded wakeup. Reset the gate and issue one compensating sticky wakeup on
                // the new selector, so its first select() returns immediately and pollOnce runs every deferred drain once post-swap. That
                // drain necessarily sees any offer whose wakeup was lost: the offer precedes the wakeup's selector read, which preceded the
                // swap in the volatile synchronization order.
                wakeupPending.set(false)
                discard(newSelector.wakeup())

                // A close() racing this swap may have read and closed the old selector. It sets closedFlag before it reads `selector`,
                // and this reads closedFlag after writing `selector`, so one of the two always sees the other: either close() closes the
                // new selector, or this does. Without it the new selector is closed by nobody, the channels re-registered below are
                // never killed, and a listener release would be reported for a descriptor that is still open.
                if closedFlag.get() then
                    try newSelector.close()
                    catch case e: IOException => Log.live.unsafe.error(s"$label could not close the selector a closing driver rebuilt", e)
                end if

                // Re-register every channel on the new selector, restoring its armed interest from the
                // pending-op maps so an in-flight read, write, connect, or accept survives the rebuild.
                var i = 0
                while i < channels.length do
                    val ch = channels(i)
                    i += 1
                    try
                        ch match
                            case sc: SocketChannel =>
                                var ops = 0
                                if pendingReads.containsKey(sc) then ops = ops | SelectionKey.OP_READ
                                if pendingWritables.containsKey(sc) then ops = ops | SelectionKey.OP_WRITE
                                if pendingConnects.containsKey(sc) then ops = ops | SelectionKey.OP_CONNECT
                                discard(sc.register(newSelector, ops))
                            case ssc: ServerSocketChannel =>
                                val ops = if pendingAccepts.containsKey(ssc) then SelectionKey.OP_ACCEPT else 0
                                discard(ssc.register(newSelector, ops))
                            case _ => ()
                    catch
                        case _: java.nio.channels.ClosedChannelException  => ()
                        case _: java.nio.channels.ClosedSelectorException => ()
                    end try
                end while
        end match
    end swapSelector

    /** Install `flatSet` into the `SelectorImpl.selectedKeys` and `SelectorImpl.publicSelectedKeys` fields of `sel`.
      * Used both at initial construction and when rebuilding the selector.
      */
    private def installFlatSetInto(sel: Selector, flatSet: SelectedSelectionKeySet)(using AllowUnsafe): Unit =
        try
            val implClass = Class.forName("sun.nio.ch.SelectorImpl")
            val skField   = implClass.getDeclaredField("selectedKeys")
            skField.setAccessible(true)
            val pskField = implClass.getDeclaredField("publicSelectedKeys")
            pskField.setAccessible(true)
            skField.set(sel, flatSet)
            pskField.set(sel, flatSet)
        catch
            // InaccessibleObjectException (JDK 9+) extends RuntimeException, not ReflectiveOperationException.
            case _: ReflectiveOperationException                  => () // graceful: no-op if reflection fails on the new selector
            case _: java.lang.reflect.InaccessibleObjectException => ()

    /** Try to install a flat `SelectedSelectionKeySet` into `selector` via reflection.
      *
      * Returns `Present(set)` when the reflection succeeds; returns `Absent` when an `InaccessibleObjectException` or any other reflection
      * failure is thrown. Callers must treat `Absent` as a graceful fallback to the default `HashSet`-backed path.
      *
      * `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` is required for the reflection to succeed on JDK 17+. The build's test JVM options
      * include that flag; production JVMs may not, so the `Absent` path must remain fully functional.
      */
    private def installSelectedKeySet(sel: Selector)(using AllowUnsafe): Maybe[SelectedSelectionKeySet] =
        try
            val implClass = Class.forName("sun.nio.ch.SelectorImpl")
            val skField   = implClass.getDeclaredField("selectedKeys")
            skField.setAccessible(true)
            val pskField = implClass.getDeclaredField("publicSelectedKeys")
            pskField.setAccessible(true)
            val flatSet = new SelectedSelectionKeySet()
            skField.set(sel, flatSet)
            pskField.set(sel, flatSet)
            Present(flatSet)
        catch
            // InaccessibleObjectException (JDK 9+) extends RuntimeException, not ReflectiveOperationException.
            case _: ReflectiveOperationException                  => Absent
            case _: java.lang.reflect.InaccessibleObjectException => Absent

    private def dispatchRead(channel: SocketChannel)(using AllowUnsafe): Unit =
        Maybe(pendingReads.remove(channel)) match
            case Present(handle) =>
                // Read the current owner cell. The cell object IS the identity for the CAS in
                // dispatchReadPlain/dispatchReadTls: AtomicReference.compareAndSet uses reference equality,
                // so only the carrier that read THIS exact ReadArmCell object can CAS-clear it. A stale
                // dispatch from an earlier arm holds a different ReadArmCell instance and its CAS fails.
                // A concurrent cancel/close calls getAndSet(Absent), which races with the CAS; only one
                // side wins (the other's CAS fails or finds Absent and skips the completion).
                val cell = handle.readArm.get()
                cell match
                    case Present(armCell) =>
                        if armCell.probe then
                            // probe arm: routed before the tls/upgrade branches because its bytes never reach a promise.
                            dispatchGraceProbe(channel, cell, handle)
                        else
                            handle.tls match
                                case Present(tls) => dispatchReadTls(channel, cell, armCell.promise, handle, tls)
                                case Absent       =>
                                    // During the STARTTLS handshake phase the producer read delivers the peer flight into the upgrade handoff slot for
                                    // the parked handshake waiter, never to this cell's promise; the plaintext phase (and non-upgrade reads) read
                                    // normally.
                                    if handle.upgrading && handle.handshakeReading then dispatchUpgradeRead(channel, cell, handle)
                                    else dispatchReadPlain(channel, cell, armCell.promise, handle)
                    case Absent =>
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label dispatchRead ${handleLabel(handle)} readArm already cleared (cancelled)")
                end match
            case Absent =>
                given Frame = Frame.internal
                Log.live.unsafe.warn(s"$label dispatchRead for channel=${channel.hashCode()} with no pending promise")
        end match
    end dispatchRead

    private def dispatchReadPlain(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle
    )(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        // Deliver any grace-probe-staged bytes FIRST, with NO socket read: this closes the one ordering race the awaitRead pre-check cannot, where the
        // pump re-arms while a probe dispatch is queued ahead of it (the probe stages chunk A, then the pump would read fresh chunk B past A; here the
        // pump delivers A, B stays in the kernel). Selector-carrier serialization plus drain-first at every pump delivery makes the order total.
        drainGraceStaging(handle) match
            case Present(staged) =>
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(staged))))
                else
                    // A re-arm replaced the cell, or a close took it, since dispatchRead read it: the bytes go to the read that holds the
                    // slot now, and back to the staging when none does.
                    returnGraceStaging(handle, staged)
                    deliverStaged(handle)
                end if
            case Absent =>
                dispatchReadPlainSocket(channel, cell, promise, handle)
        end match
    end dispatchReadPlain

    /** The socket-reading tail of [[dispatchReadPlain]] (split out so the grace-staging pre-check can short-circuit without a socket read). */
    private def dispatchReadPlainSocket(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle
    )(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        try
            // Reuse handle's buffer
            val buf = handle.readBuffer
            buf.clear()
            val n = channel.read(buf)
            if n > 0 then
                buf.flip()
                val arr = new Array[Byte](n)
                buf.get(arr)
                Log.live.unsafe.debug(s"$label dispatchRead ${handleLabel(handle)} bytes=$n")
                if handle.upgrading && !handle.handshakeReading then
                    // STARTTLS plaintext phase: this is the retiring plaintext pump reading the peer's first TLS flight (e.g. the ClientHello) off
                    // the socket while the upgrade is detaching. Completing it would let the pump either offer to the now-closed inbound (DROP) or
                    // re-arm and STEAL the read the handshake is about to issue on the same handle. Instead SALVAGE the bytes for the handshake to
                    // replay (startTlsHandshake feeds the salvage into the engine), and leave the pump's read promise to be failed by detach's
                    // cleanupPending so the pump tears down without re-arming. That handoff only holds because cleanupPending's read sweep is
                    // slot-first: this dispatch has just consumed the pendingReads entry, so a map-keyed sweep would miss the still-armed cell
                    // and strand the promise forever.
                    stashUpgradeBytes(handle, arr)
                    // TOCTOU close (stash-after-drain): the guard above observed handshakeReading=false, but startTls (which sets
                    // handshakeReading BEFORE its one-shot drainUpgradeSalvage) can flip it true and drain in the window between that guard
                    // and this stash write, stranding the just-stashed flight (the ClientHello) in the salvage that startTls already drained,
                    // so the handshake parks forever. Re-check after the stash: if the handshake has taken over reading, re-drain and deliver
                    // it to the upgrade-handoff slot the handshake consumes. Race-free and exactly-once: both startTls's drain and this
                    // re-drain are getAndSet on the same salvage, so the flight reaches the handshake once (startTls wins, it lands in
                    // netInBuf; this re-drain wins, the slot delivers it to the park). Selector-carrier-confined like the stash.
                    if handle.handshakeReading then
                        drainUpgradeSalvage(handle).foreach { salvaged =>
                            deliverToUpgradeHandoff(handle, salvaged)
                        }
                    end if
                else
                    val cas = handle.readArm.compareAndSet(cell, Absent)
                    if cas then
                        // CAS-clear the owner cell before completing. Pass the SAME cell object read in dispatchRead so
                        // AtomicReference.compareAndSet's reference check succeeds.
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                    end if
                end if
            else if n < 0 then
                // Orderly peer EOF (recv == 0 with no local shutdown).
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
            else
                // n == 0: no data ready (selector spurious wakeup). Re-arm: restore the entry and interest.
                if putPendingRead(channel, handle) then discard(registerInterest(channel, SelectionKey.OP_READ))
                else if handle.readArm.compareAndSet(cell, Absent) then promise.completeDiscard(Result.fail(released(handle)))
            end if
        catch
            case _: IOException =>
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        end try
    end dispatchReadPlainSocket

    private def dispatchReadTls(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle,
        tls: NioTlsState
    )(using AllowUnsafe): Unit =
        // When another section holds the gate, the read stays armed in its cell with no pendingReads entry, so neither this dispatch nor
        // the reassert pass re-arms OP_READ, which on a readable socket would fire every cycle for as long as a writer holds the gate. The
        // holder's release hands the handle back and deliverStaged re-arms the read (rearmDeferredRead). The read's promise completes only
        // after the gate is released, so its callbacks can take the gate.
        handle.gate.lockedOrDefer {
            val inbound =
                // carried plaintext precedes anything this dispatch would decrypt; the socket is read on the next arm
                if tls.hasPlaintext then NioTlsState.Inbound.Decrypted
                else tls.unwrapFromSocket(handle.graceStaging, channel, handle.readBuffer)
            inbound match
                case NioTlsState.Inbound.Pending    => rearmTlsRead(channel, cell, promise, handle)
                case NioTlsState.Inbound.Decrypted  => claimTls(handle, tls, Absent)
                case NioTlsState.Inbound.CleanClose => claimTls(handle, tls, Present(ReadOutcome.CleanClose))
                case NioTlsState.Inbound.PeerFin    => claimTls(handle, tls, Present(ReadOutcome.PeerFin))
            end match
        }.flatten.foreach(_.complete())
    end dispatchReadTls

    /** Keep a TLS read armed, or, once the release claim is taken, take its cell and return the failure to complete it with once the gate
      * is released.
      */
    private def rearmTlsRead(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle
    )(using AllowUnsafe): Maybe[NioIoDriver.Delivery] =
        if putPendingRead(channel, handle) then
            discard(registerInterest(channel, SelectionKey.OP_READ))
            Absent
        else if handle.readArm.compareAndSet(cell, Absent) then
            Present(NioIoDriver.Delivery(promise, Result.fail(released(handle)(using Frame.internal))))
        else Absent
    end rearmTlsRead

    private def dispatchWritable(channel: SocketChannel)(using AllowUnsafe): Unit =
        Maybe(pendingWritables.remove(channel)) match
            case Present(promise) =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchWritable channel=${channel.hashCode()}")
                promise.completeDiscard(Result.succeed(()))
            case Absent =>
                () // No pending promise - may have been completed by race condition fix
        end match
    end dispatchWritable

    private def dispatchConnect(channel: SocketChannel)(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        Maybe(pendingConnects.remove(channel)) match
            case Present((promise, handle)) =>
                try
                    if channel.finishConnect() then
                        Log.live.unsafe.debug(s"$label dispatchConnect channel=${channel.hashCode()} connected")
                        promise.completeDiscard(Result.succeed(()))
                    else
                        // Not ready (a real partial: finishConnect returned false): re-arm OP_CONNECT with a definite poll cycle (armConnectInterest's
                        // unconditional wakeup), so the re-arm's readiness can never be coalesced away. This branch fires only on an actual
                        // finishConnect=false, so the re-arm is bounded (one per genuine partial), not a self-sustaining spin.
                        pendingConnects.put(channel, (promise, handle))
                        discard(armConnectInterest(channel))
                catch
                    case e: IOException =>
                        // A driver-side connect I/O failure (e.g. ECONNREFUSED surfaced by finishConnect): a receive/connect failure on THIS
                        // connection, not a closure of the driver. Fail the connect promise with a typed connection I/O error naming the
                        // connection and its creation frame; the transport wraps it into NetConnectException.
                        promise.completeDiscard(Result.fail(NetConnectionIoException(
                            s"connection ${handleLabel(handle)}",
                            NetConnectionIoException.Operation.Connect,
                            e
                        )(using handle.createdAt)))
            case Absent =>
                Log.live.unsafe.debug(s"$label dispatchConnect channel=${channel.hashCode()} no pending promise")
        end match
    end dispatchConnect

    private def dispatchAccept(serverChannel: ServerSocketChannel)(using AllowUnsafe): Unit =
        Maybe(pendingAccepts.remove(serverChannel)) match
            case Present(promise) =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"$label dispatchAccept server=${serverChannel.hashCode()}")
                promise.completeDiscard(Result.succeed(()))
            case Absent =>
                given Frame = Frame.internal
                Log.live.unsafe.warn(s"$label dispatchAccept for serverChannel=${serverChannel.hashCode()} with no pending promise")
        end match
    end dispatchAccept

end NioIoDriver

private[kyo] object NioIoDriver:

    /** A read claimed under the gate with the result it receives, completed once the gate is released. */
    final private case class Delivery(
        read: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        result: Result[Closed, ReadOutcome < Abort[Closed]]
    ):
        def complete()(using AllowUnsafe): Unit = read.completeDiscard(result)
    end Delivery

    private[internal] def concatChunks(chunks: Chunk[Array[Byte]]): Array[Byte] =
        val total = chunks.foldLeft(0)(_ + _.length)
        val out   = new Array[Byte](total)
        var pos   = 0
        chunks.foreach { a =>
            // System.arraycopy: no kyo equivalent for a bulk primitive-array copy; fully qualified so kyo.System does not shadow it.
            java.lang.System.arraycopy(a, 0, out, pos, a.length)
            pos += a.length
        }
        out
    end concatChunks

    /** Number of consecutive zero-key `select()` returns that trigger a selector rebuild. */
    private[net] val SelectorRebuildThreshold: Int = 512

    /** Max `readBuffer`-sized chunks the probe consumes per grace window before disarming: with data present a level-triggered selector refires
      * immediately, so unbudgeted one window drains the whole receive buffer. Default 8 KiB `readBuffer`: up to 128 KiB per window; the next kick continues.
      */
    private[net] val GraceProbeBudgetChunks: Int = 16

    /** Cap on total bytes the peer-close grace probe may stage on one handle (1 MiB). Past it the probe stops consuming, so a FIN behind more than
      * this is never reclaimed (keeps the pre-fix behavior): the deliberate trade that stops a live chatty peer turning the fd fix into a heap leak.
      */
    private[net] val GraceProbeStagingCap: Int = 1 << 20

    /** Retained for diagnostic and test use. The selector loop calls `selector.select()` with no timeout; `reassertPendingInterest()` +
      * `selector.wakeup()` is the liveness mechanism rather than a bounded timeout floor.
      */
    private[net] val SelectTimeoutMs: Long = 100L

    /** Factory for `NioIoDriver`. Opens a fresh `Selector` for each driver instance. */
    def init()(using AllowUnsafe): NioIoDriver =
        new NioIoDriver(Selector.open())

    /** Build a driver over a caller-supplied selector.
      *
      * `private[net]` for the crash-containment test, which needs a selector whose `select()` throws: the constructor is class-private, and the
      * contract under test is that a Throwable escaping a select cycle still reaches the terminal exit and closes the selector, which cannot be
      * provoked through a real one.
      */
    private[net] def forSelector(selector: Selector)(using AllowUnsafe): NioIoDriver =
        new NioIoDriver(selector)
end NioIoDriver
