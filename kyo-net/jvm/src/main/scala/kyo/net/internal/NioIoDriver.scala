package kyo.net.internal

import java.io.IOException
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLEngineResult
import kyo.*
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
  * For TLS connections, reads are dispatched through `dispatchReadTls`, which feeds raw ciphertext from the kernel into the `SSLEngine` and
  * delivers decrypted plaintext. Writes go through `writeTls`, which wraps plaintext into TLS records before writing to the channel.
  *
  * Note: `tryUnwrapBuffered` is called at the start of every TLS read to drain any application data the JDK SSLEngine already unwrapped (e.g.
  * from the last handshake record or a coalesced TCP segment). Without this, the selector may never fire again because the kernel buffer is
  * empty even though decrypted bytes are available.
  */
final private[kyo] class NioIoDriver private (@volatile private[net] var selector: Selector)
    extends IoDriver[NioHandle]:

    // Unsafe: created at driver construction with no ambient AllowUnsafe; the danger bridge builds it here and every get/compareAndSet runs
    // under the caller's AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

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
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, Promise.Unsafe[Unit, Abort[Closed]]]()

    // Pending connect requests: channel -> promise
    private val pendingConnects =
        new java.util.concurrent.ConcurrentHashMap[SocketChannel, Promise.Unsafe[Unit, Abort[Closed]]]()

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
      * The runtime reset plus a single unit mirrors a freshly submitted task: the wall-clock a cycle spends parked in `select` is billed to the
      * task, and carrying it forward would make the chain look long-running and starve it against genuinely short tasks.
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
        // Check for buffered TLS data before registering with selector.
        // After handshake, netInBuf may already contain application data.
        handle.tls match
            case Present(tls) =>
                tryUnwrapBuffered(tls) match
                    case Present(buffered) =>
                        Log.live.unsafe.debug(s"$label awaitRead ${handleLabel(handle)} found buffered TLS data size=${buffered.size}")
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(buffered)))
                    case Absent if tls.peerCleanClose =>
                        // Buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose so the
                        // ReadPump tears down instead of waiting on the selector for ciphertext the peer will never send.
                        promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                    case Absent =>
                        armRead(handle, promise)
            case Absent =>
                armRead(handle, promise)
        end match
    end awaitRead

    // Install a fresh ReadArmCell into the read-arm owner slot and register selector interest. Each arm
    // wraps the caller's promise in a freshly allocated ReadArmCell object; the selector carrier completes
    // only the current owner's promise via a reference-equality CAS on the stored cell. A stale arm holds
    // an older ReadArmCell heap object (distinct from the current arm's object even when both carry the
    // same promise), so its CAS fails.
    private def armRead(handle: NioHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        val newCell = Present(ReadArmCell(promise))
        handle.readArm.set(newCell)
        pendingReads.put(handle.channel, handle)
        Log.live.unsafe.debug(s"$label awaitRead registered ${handleLabel(handle)}")
        if !registerInterest(handle.channel, SelectionKey.OP_READ) then
            discard(handle.readArm.compareAndSet(newCell, Absent))
            discard(pendingReads.remove(handle.channel))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerRead failed for ${handleLabel(handle)}")))
        else if handle.forceReadArmWakeup then
            // First post-STARTTLS read arm (NioHandle.forceReadArmWakeup): registerInterest's cross-carrier OP_READ set can be lost to the
            // selector's own interestOps write and its guarded wakeup can coalesce, so on a selector quiescing between repeated upgrades the
            // reassert backstop never runs and this read strands. Force an UNCONDITIONAL selector.wakeup() (Netty's cross-thread discipline:
            // never rely on a coalesced wakeup for a one-shot arm) so the poll carrier runs one cycle and reassertPendingInterest re-applies
            // OP_READ on the selector carrier. One-shot: cleared here so steady-state reads keep the coalesced wakeup.
            handle.forceReadArmWakeup = false
            discard(selector.wakeup())
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
        if handle.upgrading then stashUpgradeBytes(handle, bytes.toArrayUnsafe)
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
        val producer = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        handle.readArm.set(Present(ReadArmCell(producer)))
        pendingReads.put(handle.channel, handle)
        val registered = registerInterest(handle.channel, SelectionKey.OP_READ)
        if !registered then
            discard(handle.readArm.getAndSet(Absent))
            discard(pendingReads.remove(handle.channel))
            failUpgradeHandoff(handle)
        end if
    end applyUpgradeArm

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
                end if
            else if n < 0 then
                if handle.readArm.compareAndSet(cell, Absent) then failUpgradeHandoff(handle)
            else
                // n == 0: spurious selector wakeup, no data ready. Keep the producer armed (re-register on the selector carrier) for the real edge.
                pendingReads.put(channel, handle)
                discard(registerInterest(channel, SelectionKey.OP_READ))
            end if
        catch
            case _: IOException =>
                if handle.readArm.compareAndSet(cell, Absent) then failUpgradeHandoff(handle)
        end try
    end dispatchUpgradeRead

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
        discard(handle.readArm.getAndSet(Absent))
        discard(pendingReads.remove(handle.channel))
        val key = handle.channel.keyFor(selector)
        if (key ne null) && key.isValid then
            try discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
            catch case _: CancelledKeyException => ()
        end if
    end stopUpgradeProducer

    def awaitWritable(handle: NioHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        pendingWritables.put(handle.channel, promise)
        Log.live.unsafe.debug(s"$label awaitWritable registered ${handleLabel(handle)}")
        if !registerInterest(handle.channel, SelectionKey.OP_WRITE) then
            discard(pendingWritables.remove(handle.channel))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerWrite failed for ${handleLabel(handle)}")))
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

    def awaitConnect(handle: NioHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        Maybe(pendingConnects.putIfAbsent(handle.channel, promise)) match
            case Absent =>
                Log.live.unsafe.debug(s"$label awaitConnect registered ${handleLabel(handle)}")
                if !armConnectInterest(handle.channel) then
                    discard(pendingConnects.remove(handle.channel))
                    promise.completeDiscard(Result.fail(Closed(
                        label,
                        summon[Frame],
                        s"registerConnect failed for ${handleLabel(handle)}"
                    )))
                end if
            case Present(_) =>
                promise.completeDiscard(Result.panic(new IllegalStateException(
                    s"$label duplicate awaitConnect for ${handleLabel(handle)}"
                )))
        end match
    end awaitConnect

    /** IoDriver contract stub for the NIO driver. The NIO driver uses its own private accept seam (ServerSocketChannel-based); callers must
      * use the ServerSocketChannel overload below, not this one. Fails loudly so an accidental caller gets an immediate error rather than
      * silently receiving fd 0 (stdin), which is a latent misuse bug.
      */
    def awaitAccept(handle: NioHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        promise.completeDiscard(Result.fail(Closed(
            label,
            summon[Frame],
            "awaitAccept not supported on NioIoDriver; the Nio transport drives its own accept loop"
        )))

    /** Run a TLS engine op on the NIO driver. The NIO driver's selector-carrier read path (dispatchReadTls) and caller-carrier write path
      * (writeTls) acquire the per-connection engine ownership gate directly at their call sites, so no two carriers touch one connection's
      * JDK SSLEngine concurrently. NEED_TASK delegated tasks run inline inside the held ownership (a Fiber-per-task would stall indefinitely
      * waiting for the selector-carrier to schedule it while the selector-carrier is blocked waiting for the NEED_TASK result).
      *
      * This override exists to document the NIO ownership model. External callers (PosixTransport, tests) that reach NioIoDriver via the
      * IoDriver interface call op() directly; the gate is already held by whichever NIO call site is currently executing.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    def write(handle: NioHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        if data.isEmpty || offset >= data.size then WriteResult.Done
        else
            handle.tls match
                case Present(tls) => writeTls(handle, data, offset, tls)
                case Absent       => writePlain(handle, data, offset)

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

    private def writeTls(handle: NioHandle, data: Span[Byte], offset: Int, tls: NioTlsState)(using AllowUnsafe): WriteResult =
        // Acquire per-connection engine ownership before any SSLEngine call. The selector-carrier
        // unwrap path (dispatchReadTls) and this caller-carrier wrap path both need exclusive access
        // to the JDK SSLEngine; the gate serializes them. The engine op is brief (one wrap + socket
        // write per call), so a spin avoids a park and the associated scheduling overhead.
        @tailrec def spinAcquire(): Unit =
            if !handle.engineGate.compareAndSet(false, true) then spinAcquire()
        spinAcquire()
        try
            // If there is pending ciphertext from a previous partial write, flush it first
            val canProceed =
                if tls.pendingCiphertext then
                    val n = handle.channel.write(tls.netOutBuf)
                    if n < 0 then Left(WriteResult.Error)
                    else if tls.netOutBuf.hasRemaining then
                        // Still can't flush: remain in pending state, return the original span at the current offset
                        Left(WriteResult.Partial(data, offset))
                    else
                        tls.pendingCiphertext = false
                        Right(())
                    end if
                else Right(())
            canProceed match
                case Left(result) => result
                case Right(_)     =>
                    // src starts at offset so the wrap loop consumes only the unsent region [offset, data.size).
                    val src = ByteBuffer.wrap(data.toArrayUnsafe, offset, data.size - offset)
                    // Loop wrapping: SSLEngine wraps one TLS record per call (~16KB),
                    // so large payloads need multiple wrap+flush iterations
                    @tailrec def wrapLoop(): WriteResult =
                        if !src.hasRemaining then WriteResult.Done
                        else
                            tls.netOutBuf.clear()
                            val result = tls.engine.wrap(src, tls.netOutBuf)
                            tls.netOutBuf.flip()
                            if result.getStatus eq javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW then
                                WriteResult.Error
                            else
                                // Try one write: if socket buffer is full, return Partial
                                val n = handle.channel.write(tls.netOutBuf)
                                if n < 0 then WriteResult.Error
                                else if tls.netOutBuf.hasRemaining then
                                    // Socket buffer full: save state and return the original span at the current ByteBuffer position.
                                    // src.position() tracks how many bytes from data[offset..] have been consumed, so the Partial offset is
                                    // src.position() (ByteBuffer positions are absolute within the wrapped array: position() == consumed so far
                                    // from data[offset..], i.e. the new absolute offset into data).
                                    tls.pendingCiphertext = true
                                    WriteResult.Partial(data, src.position())
                                else wrapLoop()
                                end if
                            end if
                    wrapLoop()
            end match
        catch
            case _: IOException => WriteResult.Error
        finally
            handle.engineGate.set(false)
        end try
    end writeTls

    /** Remove pending operations for a channel and fail their promises with Closed. */
    private def cleanupPending(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label cleanupPending ${handleLabel(handle)} reads=${
                if pendingReads.containsKey(handle.channel) then 1 else 0
            } writes=${if pendingWritables.containsKey(handle.channel) then 1 else 0} connects=${
                if pendingConnects.containsKey(handle.channel) then 1 else 0
            }")
        val closed = Closed(label, summon[Frame], s"${handleLabel(handle)} closed")
        Maybe(pendingReads.remove(handle.channel)).foreach { h =>
            h.readArm.getAndSet(Absent).foreach { armCell =>
                armCell.promise.completeDiscard(Result.fail(closed))
            }
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
        Maybe(pendingConnects.remove(handle.channel)).foreach { promise =>
            promise.completeDiscard(Result.fail(closed))
        }
    end cleanupPending

    def cancel(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then
                key.cancel()
                if wakeupPending.compareAndSet(false, true) then
                    discard(selector.wakeup())
            end if
        catch
            case _: CancelledKeyException => ()
        end try
        cleanupPending(handle)
    end cancel

    /** STARTTLS detach that KEEPS the channel's SelectionKey. The same channel is re-driven as a TLS handshake immediately after, so instead of
      * cancelling the key (which marks it for removal at the next select() and makes the subsequent channel.register throw CancelledKeyException
      * and defer, opening the no-live-key window a concurrent handshake arm fails on), this resets the key's interest to 0 and keeps it. The
      * handshake then re-arms OP_READ/OP_WRITE on the SAME live key via registerInterest, with no re-registration race. Pending plaintext promises
      * are still failed (cleanupPending) so the plaintext pumps tear down, exactly as cancel does.
      */
    override def detachForUpgrade(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
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
    end detachForUpgrade

    def closeHandle(handle: NioHandle)(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"$label closeHandle ${handleLabel(handle)}")
        cleanupPending(handle)
        try
            val key = handle.channel.keyFor(selector)
            if (key ne null) && key.isValid then
                key.cancel()
        catch
            case _: CancelledKeyException => ()
        end try
        NioHandle.close(handle)
        // Wake the selector so the cancelled key is deregistered, and on JDK 11+ the channel's fd actually kill()ed, on the next select()
        // pass. channel.close defers both to a selection operation, and the loop parks in an indefinite select() with no timeout, so on an
        // otherwise-idle driver (the last connection closing, or a peer-FIN teardown with nothing else pending) this close would otherwise
        // leave the fd stranded in CLOSE_WAIT until an unrelated event happened to wake the loop. Coalesced via wakeupPending, matching the
        // transport and listener closes that wake the selector for the identical deferred-kill() reason.
        wakeup()
    end closeHandle

    /** Remove a pending accept entry for a server channel and fail its promise with Closed. */
    def cleanupAccept(serverChannel: ServerSocketChannel)(using AllowUnsafe, Frame): Unit =
        val closed = Closed(label, summon[Frame], s"server channel=${serverChannel.hashCode()} closed")
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

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            Log.live.unsafe.debug(
                s"$label closing driver, failing ${pendingReads.size()} reads, ${pendingWritables.size()} writes, ${pendingConnects.size()} connects, ${pendingAccepts.size()} accepts"
            )
            val closed = Closed(label, summon[Frame], "driver closed")
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
            pendingConnects.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingConnects.clear()
            pendingAccepts.forEach { (_, promise) =>
                promise.completeDiscard(Result.fail(closed))
            }
            pendingAccepts.clear()
            // Drop any handles awaiting deferred registration: the driver is gone, so the poll carrier will never drain them. Their downstream
            // awaitX promises (if any were armed during the deferred window) are already failed by the pending-op-map cleanup above; the channels
            // are owned and closed by the caller (the upgrade teardown). Clearing prevents a stranded queue entry from outliving the driver.
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
            try selector.close()
            catch case _: IOException => ()
        end if
    end close

    /** Register a channel with this driver's selector. Must be called before awaitRead/awaitWritable. */
    def registerChannel(handle: NioHandle)(using AllowUnsafe): Boolean =
        try
            if wakeupPending.compareAndSet(false, true) then
                discard(selector.wakeup())
            discard(handle.channel.register(selector, 0))
            given Frame = Frame.internal
            Log.live.unsafe.debug(s"$label registerChannel ${handleLabel(handle)}")
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

    /** Register a server channel for accept operations. */
    def registerServerChannel(serverChannel: ServerSocketChannel)(using AllowUnsafe): Boolean =
        try
            if wakeupPending.compareAndSet(false, true) then
                discard(selector.wakeup())
            discard(serverChannel.register(selector, 0))
            true
        catch
            case _: java.nio.channels.ClosedChannelException       => false
            case _: java.nio.channels.ClosedSelectorException      => false
            case _: java.nio.channels.IllegalBlockingModeException => false

    /** Wait for server channel to have pending connections. Promise completes when accept() will not block. */
    def awaitAccept(serverChannel: ServerSocketChannel, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        pendingAccepts.put(serverChannel, promise)
        Log.live.unsafe.debug(s"$label awaitAccept registered server=${serverChannel.hashCode()}")
        if !registerServerInterest(serverChannel, SelectionKey.OP_ACCEPT) then
            discard(pendingAccepts.remove(serverChannel))
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"registerAccept failed")))
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
            // Apply any deferred STARTTLS demand-driven upgrade arm on this (poll) carrier, so its OP_READ registration is selector-confined and
            // never a cross-carrier interestOps read-modify-write. Done after drainPendingRegistrations (the channel's key already
            // exists, kept live by detachForUpgrade) and before reassert so the freshly armed read is reasserted on this same cycle if needed.
            drainUpgradeArms()
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
      * however, isolated from caller carriers: `selector` is `@volatile` and is READ by caller-carrier paths (registerChannel, registerInterest,
      * registerServerChannel) that call `channel.register(selector, ...)` / `selector.wakeup()` concurrently with this swap. A caller that read the old
      * selector just before this `selector.close()` runs gets a `ClosedSelectorException`; those paths handle it by deferring the registration to
      * `pendingRegistrations` (re-registered on the live selector with interest reconstructed from the pending-op maps) rather than failing the op, so
      * the swap is correct under that race. The `@volatile` makes the new selector visible to subsequent caller-carrier reads.
      */
    private[net] def rebuildSelector()(using AllowUnsafe, Frame): Unit =
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
    end rebuildSelector

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
                        handle.tls match
                            case Present(tls) => dispatchReadTls(channel, cell, armCell.promise, handle, tls)
                            case Absent       =>
                                // During the STARTTLS handshake phase the producer read delivers the peer flight into the upgrade handoff slot for the
                                // parked handshake waiter, never to this cell's promise; the plaintext phase (and non-upgrade reads) read normally.
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
                    // cleanupPending so the pump tears down without re-arming.
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
                pendingReads.put(channel, handle)
                discard(registerInterest(channel, SelectionKey.OP_READ))
            end if
        catch
            case _: IOException =>
                if handle.readArm.compareAndSet(cell, Absent) then
                    promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        end try
    end dispatchReadPlain

    private def dispatchReadTls(
        channel: SocketChannel,
        cell: Maybe[ReadArmCell],
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        handle: NioHandle,
        tls: NioTlsState
    )(using AllowUnsafe): Unit =
        // All compareAndSet calls pass the SAME cell object read in dispatchRead: AtomicReference.compareAndSet
        // uses reference equality (eq), so the exact ReadArmCell reference is the CAS key.
        // Acquire per-connection engine ownership before any SSLEngine call so the selector-carrier
        // unwrap path (here) and the caller-carrier wrap path (writeTls) never touch the SSLEngine
        // concurrently. If the write path holds the gate, re-arm for the next selector wakeup rather
        // than spinning on the selector carrier (which would block other connections' read events).
        if !handle.engineGate.compareAndSet(false, true) then
            pendingReads.put(channel, handle)
            discard(registerInterest(channel, SelectionKey.OP_READ))
        else
            // Collect the completion thunk; the gate is released in finally BEFORE the thunk runs so
            // synchronous teardown callbacks (ReadPump.onComplete -> closeHandle -> NioHandle.close ->
            // spinAcquire) never try to re-acquire the gate while the selector carrier still holds it.
            var complete: () => Unit = () => ()
            try
                // Check for buffered data from a previous read (e.g. post-handshake leftover)
                tryUnwrapBuffered(tls) match
                    case Present(buffered) =>
                        given Frame = Frame.internal
                        Log.live.unsafe.debug(s"$label dispatchReadTls ${handleLabel(handle)} buffered plaintext=${buffered.size}")
                        if handle.readArm.compareAndSet(cell, Absent) then
                            complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(buffered)))
                    case Absent if tls.peerCleanClose =>
                        // The buffered records ended in the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose so the
                        // ReadPump tears down, rather than re-arming for ciphertext the peer will never send.
                        if handle.readArm.compareAndSet(cell, Absent) then
                            complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                    case Absent =>
                        val buf = handle.readBuffer
                        buf.clear()
                        val n = channel.read(buf)
                        if n < 0 then
                            // Peer ended the TCP stream. If a close_notify was already consumed (peerCleanClose set by tryUnwrapBuffered) this FIN
                            // follows an orderly close; otherwise it is a bare FIN with no close_notify, the truncation-attack condition (RFC 8446
                            // 6.1). Record peerEof for the bare-FIN case so status reports Truncated; do not overwrite an already-observed clean
                            // close. Mirrors the engine path's recv == 0 -> peerEof handling (PollerIoDriver / IoUringDriver).
                            if !tls.peerCleanClose then tls.peerEof = true
                            if handle.readArm.compareAndSet(cell, Absent) then
                                complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                        else if n == 0 then
                            // Spurious wakeup: no data ready. Restore entry and re-arm.
                            pendingReads.put(channel, handle)
                            discard(registerInterest(channel, SelectionKey.OP_READ))
                        else
                            buf.flip()
                            // Grow netInBuf if needed to hold existing data + new data
                            val needed = tls.netInBuf.position() + buf.remaining()
                            if needed > tls.netInBuf.capacity() then
                                val grown = ByteBuffer.allocate(needed)
                                tls.netInBuf.flip()
                                grown.put(tls.netInBuf)
                                tls.netInBuf = grown
                            end if
                            tls.netInBuf.put(buf)
                            // Try to unwrap the newly fed ciphertext
                            tryUnwrapBuffered(tls) match
                                case Present(plaintext) =>
                                    given Frame = Frame.internal
                                    Log.live.unsafe.debug(s"$label dispatchReadTls ${handleLabel(handle)} plaintext=${plaintext.size}")
                                    if handle.readArm.compareAndSet(cell, Absent) then
                                        complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(plaintext)))
                                case Absent if tls.peerCleanClose =>
                                    // The newly fed ciphertext was the peer's close_notify (orderly close, RFC 8446 6.1): deliver CleanClose
                                    // immediately so the ReadPump tears down, rather than re-arming for ciphertext the peer will never send.
                                    if handle.readArm.compareAndSet(cell, Absent) then
                                        complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                                case Absent =>
                                    // Got ciphertext but no complete TLS record yet: need more data, re-arm.
                                    pendingReads.put(channel, handle)
                                    discard(registerInterest(channel, SelectionKey.OP_READ))
                            end match
                        end if
                end match
            catch
                case _: IOException =>
                    // A read IOException (e.g. a TCP RST) ends the inbound stream abruptly with no close_notify: a truncation, not an orderly close.
                    // Record peerEof unless a close_notify was already consumed, so status reports Truncated rather than Active.
                    if !tls.peerCleanClose then tls.peerEof = true
                    if handle.readArm.compareAndSet(cell, Absent) then
                        complete = () => promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
            finally
                handle.engineGate.set(false)
            end try
            // Run the completion thunk after the gate is released. Synchronous callbacks on the promise
            // (e.g. ReadPump.onComplete -> teardownHandle -> NioHandle.close -> spinAcquire) are now
            // safe: the gate is free when they run on the selector carrier.
            complete()
        end if
    end dispatchReadTls

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
            case Present(promise) =>
                try
                    if channel.finishConnect() then
                        Log.live.unsafe.debug(s"$label dispatchConnect channel=${channel.hashCode()} connected")
                        promise.completeDiscard(Result.succeed(()))
                    else
                        // Not ready (a real partial: finishConnect returned false): re-arm OP_CONNECT with a definite poll cycle (armConnectInterest's
                        // unconditional wakeup), so the re-arm's readiness can never be coalesced away. This branch fires only on an actual
                        // finishConnect=false, so the re-arm is bounded (one per genuine partial), not a self-sustaining spin.
                        pendingConnects.put(channel, promise)
                        discard(armConnectInterest(channel))
                catch
                    case e: IOException =>
                        promise.completeDiscard(Result.fail(Closed(
                            label,
                            summon[Frame],
                            s"finishConnect failed for channel=${channel.hashCode()}: ${e.getMessage}"
                        )))
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

    /** Try to unwrap any buffered ciphertext already sitting in tls.netInBuf.
      *
      * After a TLS handshake, the last socket read may have contained both the final handshake record AND application data. The application
      * data remains in netInBuf. If we only wait for the selector, the kernel buffer is empty (data already read), so the selector never
      * fires and the connection hangs.
      *
      * Returns Present(plaintext) if any application data was unwrapped, Absent otherwise.
      */
    private[kyo] def tryUnwrapBuffered(tls: NioTlsState)(using AllowUnsafe): Maybe[Span[Byte]] =
        tls.netInBuf.flip()
        if !tls.netInBuf.hasRemaining then
            tls.netInBuf.compact()
            Absent
        else
            val plaintext = tls.decryptAcc
            plaintext.reset()
            @tailrec def unwrapLoop(): Unit =
                tls.appInBuf.clear()
                val result = tls.engine.unwrap(tls.netInBuf, tls.appInBuf)
                val status = result.getStatus
                if status eq SSLEngineResult.Status.OK then
                    tls.appInBuf.flip()
                    val n = tls.appInBuf.remaining()
                    if n > 0 then
                        // Bulk get: ensureCapacityFor before reading array (growth may reallocate arr), then direct ByteBuffer.get.
                        plaintext.ensureCapacityFor(n)
                        tls.appInBuf.get(plaintext.array, plaintext.size, n)
                        plaintext.advance(n)
                    end if
                    unwrapLoop()
                else if status eq SSLEngineResult.Status.CLOSED then
                    // The unwrap that consumed the peer's close_notify record reports CLOSED and makes the inbound side done (RFC 8446 6.1
                    // orderly close). Record it so the connection's status reports CleanClose rather than Truncated: this is the orderly
                    // counterpart to a bare TCP FIN. Mirrors JdkSslEngine.readPlain's Status.CLOSED / isInboundDone -> -3 clean-close return,
                    // converging the inline NIO path with the engine-driver path. The loop stops here: a close_notify is the last record on the
                    // inbound stream, so there is nothing further to drain.
                    tls.peerCleanClose = true
                end if
            end unwrapLoop
            unwrapLoop()
            // Belt-and-suspenders: a close_notify could be consumed by an unwrap that also surfaced as a non-CLOSED status (e.g. delivered
            // coalesced behind the last app record). isInboundDone becomes true once the peer's close_notify has been processed, so checking it
            // after the loop catches the clean close in every position. Mirrors JdkSslEngine.readPlain's isInboundDone clean-close detection.
            if tls.engine.isInboundDone then tls.peerCleanClose = true
            tls.netInBuf.compact()
            if plaintext.size > 0 then
                Present(Span.fromUnsafe(plaintext.toByteArray))
            else
                Absent
            end if
        end if
    end tryUnwrapBuffered

end NioIoDriver

private[kyo] object NioIoDriver:

    /** Number of consecutive zero-key `select()` returns that trigger a selector rebuild. */
    private[net] val SelectorRebuildThreshold: Int = 512

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
