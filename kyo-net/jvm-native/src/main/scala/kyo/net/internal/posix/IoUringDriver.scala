package kyo.net.internal.posix

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetBackendUnavailableException
import kyo.net.NetConnectionClosedException
import kyo.net.NetConnectionClosedException.Operation
import kyo.net.internal.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import kyo.net.internal.util.writeFromBuffer
import kyo.scheduler.InternalClock
import kyo.scheduler.Scheduler
import kyo.scheduler.Task

/** Completion-native I/O driver over io_uring (Linux >= 5.6), unified onto [[PosixHandle]] and the kyo-ffi [[IoUringBindings]].
  *
  * Unlike the readiness-based [[PollerIoDriver]] (epoll/kqueue: "fd is ready", then the caller reads), io_uring is already
  * completion-based: each operation is one self-identifying SQE submitted to the ring and keyed by a dense `user_data` value, and a
  * single reap loop drains the completion queue and fulfils the keyed promise. It presents the unchanged completion
  * `IoDriver[PosixHandle]` contract, identical to the poller arm from the caller's perspective.
  *
  * #### Reap loop
  *
  * The reap loop runs on kyo scheduler carriers, never on a thread this driver owns: one reusable `Task` performs exactly ONE cycle per
  * activation and re-arms the next before returning, so the carrier is released between cycles. Each cycle uses
  * `kyo_uring_submit_and_wait_timeout` to flush accumulated SQEs AND wait for the next CQE in a single `io_uring_enter` syscall (indefinite
  * when the wake eventfd multishot is armed; bounded to `ReapTimeoutNs` during the re-arm-retry window), then drains every already-ready CQE
  * with `kyo_uring_peek_cqe`. Whether the wait fiber completes inline (JVM/Native) or is genuinely pending (JS), the successor is the same
  * re-arm, so neither platform grows the stack. A timeout or transient rc is a normal empty turn, not an error. Every exit, including a
  * crashed cycle, routes through one terminal that tears the ring down and completes the done-fiber.
  *
  * #### UAF-safe close
  *
  * A buffer submitted to io_uring is kernel-owned until its CQE arrives: the kernel may write into a read buffer or read from a send
  * buffer at any time before completion. So `cancel`/`closeHandle` must NOT free that memory while an SQE is in flight. The handshake:
  *
  *   - Every in-flight op increments a per-handle in-flight count; reaping its CQE decrements it and releases the op's pinned memory
  *     (`PendingOp.releaseBuffer`).
  *   - `cancel(h)` fails every pending promise for `h` immediately (the caller stops waiting), but does NOT remove the pending entries
  *     and does NOT free any buffer: the kernel still owns them. The entries stay so their CQEs are still reaped and their memory
  *     released in order.
  *   - `closeHandle(h)` calls `cancel(h)`, then defers `PosixHandle.close(h)` (which frees the handle's `readBuffer`) until the
  *     handle's in-flight count reaches zero. If there is nothing in flight it closes immediately; otherwise the close runs when the
  *     last CQE for the handle is reaped.
  *
  * Buffers use the shared arena (`Buffer.alloc` / `Buffer.fromArray`), never `allocConfined`: submission and reaping run on different
  * scheduler carriers, so a confined arena would throw on the cross-carrier reap.
  */
final private[net] class IoUringDriver private[posix] (
    uring: IoUringBindings,
    ring: Buffer[Byte],
    sockets: SocketBindings,
    closedFlag: AtomicBoolean.Unsafe,
    // The ring and cqePtr are touched by the reap carrier inside its in-flight kyo_uring_submit_and_wait_timeout (indefinitely when wake-armed,
    // or for up to ReapTimeoutNs during the re-arm window) and by user carriers in kyo_uring_get_sqe. Their teardown must therefore be
    // SINGLE-OWNER: when the reap loop was started, only the reap carrier (on its own exit, after the wait has returned) frees them; close()
    // then merely signals via closedFlag.
    // `started` records whether a reap carrier exists to own that teardown; `teardownDone` makes the actual free idempotent across the two
    // paths (reap-loop exit when started, close() inline when never started).
    //
    // `teardownDone` is a CLAIM, not a completion signal: its CAS succeeding means teardownRing's body has STARTED, not that
    // io_uring_queue_exit / cqePtr.close() have actually run. [[ringExited]] is the true completion flag (set after cqePtr.close()); the
    // two are deliberately different fields: closeHandle's post-teardown self-close and closeNow's fd/buffer reclaim are only
    // UAF-safe once queue_exit has released the kernel's hold on every recv buffer, which `teardownDone` alone does not prove.
    started: AtomicBoolean.Unsafe,
    teardownDone: AtomicBoolean.Unsafe,
    // Set AFTER cqePtr.close() at the tail of teardownRing: the true "the ring is gone and every kernel-held buffer is released" signal,
    // distinct from `teardownDone` (which is set as teardownRing's FIRST step, a claim, not a completion). closeHandle's inline self-close
    // and engineFreeSink's inline-vs-submit decision gate on THIS flag, not `teardownDone`: closeNow's unconditional fd close + buffer free
    // (no in-flight check) is UAF-safe only once the kernel has released its hold on the handle's recv buffer, which happens at
    // io_uring_queue_exit, strictly after `teardownDone`'s CAS but strictly before this flag is set.
    ringExited: AtomicBoolean.Unsafe,
    // Set when the reap loop has exited (or never ran). The ring is touched by BOTH the reap carrier (wait/peek/seen) and the engine-FIFO
    // worker (flushTls/flushRaw get_sqe); io_uring_queue_exit frees the ring, so teardown must wait for BOTH to be done with it, not just the
    // reap carrier alone. `tryTeardown` fires the exactly-once teardown when reapExited AND the FIFO worker is idle.
    reapExited: AtomicBoolean.Unsafe,
    keyGen: AtomicLong.Unsafe, // dense user_data keys (NOT fds: SQEs self-identify)
    pendingSubmits: AtomicLong.Unsafe,
    // The cqe pointer scratch is owned solely by the reap carrier (runCycle and drainReady both run on it), allocated once for the driver
    // lifetime. Both methods are called sequentially on the same carrier: runCycle calls drainReady inline after the wait, then re-arms; the two
    // sites never overlap.
    cqePtr: Buffer[Long],
    // Reap-loop wakeup (mirrors the poller's eventfd wake). The reap carrier is the SINGLE producer of SQEs, so a cross-carrier submission
    // (submitEngineOp: an app fiber arming a recv, a write, a close) cannot enter the SQ itself; it enqueues the op and writes `wakeFd`. A
    // multishot IORING_OP_POLL_ADD armed on `wakeFd` then fires a CQE, returning the parked submit_and_wait so the reap loop drains the queue.
    // This is what lets the reap wait block INDEFINITELY (like NIO's selector.select() + wakeup()) instead of polling a bounded timeout to
    // discover queued submissions, which head-of-line-blocked them behind the wait under load. `wakeFd` creation is a liveness requirement:
    // `init` throws when eventfd creation fails (the indefinite park has no signal to return without it; symmetric with the poller's
    // `registerWake` fatal). The eventfd write is unconditional (the counter coalesces
    // concurrent writes in the kernel); a coalescing gate is deliberately NOT used, because it could suppress a write at the instant the loop is
    // parking and lose the wake. `wakeGuard` makes the eventfd close (at teardown) mutually exclusive with any in-flight write (mirrors the
    // poller's WakeHolder guard: the lazyFdDelete cross-fd recycling hazard).
    wakeFd: Int,
    wakeGuard: AtomicInt.Unsafe,
    // True when IORING_FEAT_NODROP (bit 1, kernel >= 5.5) was confirmed at ring init: the kernel guarantees no CQE is dropped,
    // applying backpressure on submit instead. When true, the wake eventfd's multishot CQE is non-loseable by kernel contract,
    // making the indefinite park safe. When false (kernel < 5.5), fall back to the bounded ReapTimeoutNs floor so a lost CQE
    // can never hang the loop indefinitely.
    nodropAvailable: Boolean
) extends IoDriver[PosixHandle], TlsEngineIo:

    // Whether the wake multishot POLL_ADD is currently armed on the ring. Reap-carrier-only (armWake runs in runCycle and completeMultishot, both
    // on the reap carrier), so a plain var is single-owner. The reap wait parks indefinitely only when this is true, NODROP is confirmed, and no
    // ops are stalled; a re-arm has not yet succeeded (SQ-full) leaves it false, and the loop uses the bounded park until the arm lands.
    private var wakePollArmed = false

    // Concurrent-collection audit: the raw java.util.concurrent maps and the ConcurrentLinkedQueue below are retained
    // deliberately. kyo has no concurrent-map type, and its effect-based Queue/Channel cannot back these non-parking hot syscall paths (the
    // user_data->op maps are touched without suspension on the reap and engine-FIFO carriers; the engineQueue is a lock-free single-consumer FIFO
    // drained inline at the top of each reap cycle). The per-field comments name each field's owning carrier; no raw type is shared unsafely.
    private val pending = new ConcurrentHashMap[Long, PendingOp]()

    // Cross-carrier submission handoff: every SQ operation (get_sqe + prep + submit) and every TLS engine op for every connection on this driver
    // runs on the single reap carrier, which drains this queue at the top of each reap cycle (see [[submitEngineOp]] / [[runCycle]]). One producer
    // for the io_uring submission ring; no two engine ops overlap on the same engine because one carrier runs them in FIFO order.
    private val engineQueue = new ConcurrentLinkedQueue[() => Unit]()

    // handle id -> count of in-flight (submitted, not yet reaped) ops for that handle. A handle whose id appears in
    // `closeAfterDrain` is closed by the reap loop once this count drops to 0 (close runs only after the kernel releases the buffers).
    // That count can never reach 0 for an op the kernel never completes and nothing else cancels (an IORING_OP_CONNECT against an
    // unresponsive peer): teardownRing's post-exit sweep force-discharges any entry still here once the ring itself has exited, since by
    // then the kernel has released its hold on every buffer/SQE regardless of what inFlight still reads.
    private[posix] val inFlight = new ConcurrentHashMap[Long, Long]()
    private val closeAfterDrain = new ConcurrentHashMap[Long, PosixHandle]()
    // Every handle whose close has been REQUESTED (closeHandle entered) but not yet completed (closeNow run). At ring teardown any still-pending
    // requested close is force-completed so its fd is reclaimed: a connection close is async (engine FIFO / closeAfterDrain), and the last in-flight
    // closes lose the race against a per-driver teardown, leaking the fd (an idle peer leaves it ESTABLISHED, a peer-FIN'd one CLOSE_WAIT). Only
    // close-REQUESTED handles are tracked here, so teardown never force-closes a live connection or a STARTTLS-detached fd (those never enter
    // closeHandle), which is what made the unconditional teardown-close break TLS round-trips.
    private val pendingCloses = new ConcurrentHashMap[Long, PosixHandle]()

    // Handles whose pending raw tail could not flush because the SUBMISSION queue was full (flushRaw's get_sqe returned Absent), so the remainder
    // is buffered with no send in flight. SQ-full has no per-handle send CQE to re-drive the flush, so the reap loop re-flushes these (on the
    // engine FIFO worker) after each CQE batch, which freed at least one SQ slot. Backpressures rather than busy-spinning: at most one re-flush per
    // drained batch per handle, and a re-flush that hits SQ-full again re-adds the handle. A set so a handle re-flushed once is not queued twice.
    private val stalledRaw = ConcurrentHashMap.newKeySet[PosixHandle]()

    // Promise-bearing arms (recv, accept, connect) that could not submit because the SUBMISSION queue was full (their get_sqe returned Absent): no
    // SQE is in flight, so no CQE will re-drive them. The reap loop re-arms these after each CQE batch frees an SQ slot (reArmStalledSubmits in
    // drainReady), mirroring stalledRaw for sends: a transient SQ-full BACKPRESSURES the operation (its promise stays pending) instead of failing
    // it. A failed accept would wedge the accept loop (its onComplete reads Failure as "listener closed" and stops re-arming), and a failed connect
    // would drop a connection that would otherwise succeed; parking removes both. Each entry is the PendingOp (Read/Accept/Connect) carrying the
    // fields to re-submit; it is NOT registered (no key) while parked here, and a parked Accept keeps its addr/len buffers alive for the re-submit.
    // Single-owner on the reap carrier (the submit helpers run there via submitEngineOp; reArmStalledSubmits runs in drainReady), so a plain
    // ArrayDeque is safe. Bounded: each batch is snapshotted and the queue cleared, so a re-arm that hits SQ-full again is retried only on the NEXT batch.
    private val stalledSubmits = new java.util.ArrayDeque[PendingOp]()

    // Per-handle count of consecutive `-EINTR` send CQE completions for that handle's outstanding send (TLS or raw). A send CQE that reaps
    // `-EINTR` (a signal interrupted the send before any byte moved; POSIX send(2) says to retry) re-flushes the SAME unsent region instead of
    // discarding the tail, bounded by `maxTransientIoRetries`: once a send completes non-`-EINTR` (a real partial / full send, or a genuine
    // error) the entry is cleared, so the bound counts only an uninterrupted EINTR storm and cannot spin. Keyed by handle id, mutated only on
    // the engine FIFO worker (onTlsSendComplete / onRawSendComplete), so a plain count under ConcurrentHashMap is single-owner here.
    private val sendEintrRetries = new ConcurrentHashMap[Long, Int]()

    // Reap-loop liveness counter, incremented once per reap-loop iteration and exposed through the Diagnostics dump: a frozen
    // value when a leaf hangs means the reap loop is dead/stuck; an advancing value means it is live but a pending completion is not delivered.
    @volatile private var diagReapCycles: Long = 0L

    // This driver's Diagnostics registration, held from start() and closed from close() so a per-test driver's dumper/probe does not
    // outlive it (Diagnostics is a process-global registry; every driver ever built registers now, not just the process-shared
    // singleton, so an unclosed registration would accumulate for the life of the process). Null until start() registers it; start()
    // runs on the construction carrier before this driver is exposed to a concurrent close(), so no synchronization guards the write
    // itself, but the field is @volatile so close() (on any carrier) observes it.
    @volatile private var diagRegistration: kyo.internal.Diagnostics.Registration | Null = null

    // Bound on consecutive `-EINTR` retries for one logical read or send. POSIX recv(2)/send(2): a non-blocking call interrupted by a signal
    // before any byte is transferred completes with `-EINTR` and MUST be retried (no data was moved, the socket is unchanged), exactly as
    // PollerIoDriver retries EINTR in place, and as the accept path already retries it. The retry is bounded so a pathological
    // EINTR storm cannot spin the reap loop: past the bound the last `-EINTR` falls through to the existing hard-error branch (fail Closed for a
    // read, discard the tail for a send). 8 matches PollerIoDriver.maxTransientIoRetries and PosixTransport.maxTransientAcceptRetries.
    private val maxTransientIoRetries = 8

    def label: String = "IoUringDriver"

    def handleLabel(handle: PosixHandle): String = s"fd=${handle.readFd}/${handle.writeFd}"

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // STARTTLS single-recv gate. While `upgradeActive` is set the handshake owns the fd's read side exclusively: the only recv that may be in
        // flight is the one the plaintext ReadPump armed BEFORE the upgrade (kernel-owned, uncancellable, carrying the peer's first post-signal
        // flight), which the reap routes through `upgradeHandoff`. Any recv arm REQUESTED while the flag is set is a stray plaintext-ReadPump re-arm
        // racing the upgrade (the ReadPump's last plaintext read re-armed after detachForUpgrade cancelled it, on its reused callback-less promise);
        // arming it would put a second recv on the fd that wins the peer's flight and drops the bytes onto the dead promise, stranding the handshake
        // (the STARTTLS upgrade stall). Drop it at the source: the ReadPump is being torn down, so its promise never needing to complete is correct.
        // The handshake's own ciphertext recv never enters here: it arms via awaitReadHandshake (which does not gate). False on the pollers
        // (upgradeActive is never set there: synchronous reads hold no kernel-owned recv), so this is io_uring-only.
        if handle.upgradeActive then ()
        else submitDeferredRecv(handle, promise, handshakeOwned = false)
    end awaitRead

    /** The STARTTLS handshake's own ciphertext recv. Never gated by `upgradeActive` (it is the legitimate read owner, not a stray plaintext-pump
      * re-arm), and tagged `handshakeOwned` so the reap exempts it from the upgrade-window handoff routing and feeds it to the engine.
      */
    override def awaitReadHandshake(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
        AllowUnsafe,
        Frame
    ): Unit =
        submitDeferredRecv(handle, promise, handshakeOwned = true)

    /** io_uring STARTTLS upgrade producer (single-source): arm the handshake's own ciphertext recv as a producer whose CQE the reap routes to the
      * handle's [[PosixHandle.upgradeHandoff]] slot (the reap's `upgradeActive` branch, which stays set for the whole upgrade), fulfilling the waiter
      * `driveUpgradeRead` parked. The handshake never feeds a recv promise directly, so there is no second read source to orphan a staged Carryover.
      * `producer` is a vehicle to hold the recv armed and be failed on close; the handshake observes only the slot. handshakeOwned so the single-recv
      * `upgradeActive` gate in [[awaitRead]] does not drop it. Demand-driven: `driveUpgradeRead` calls this once per read it needs.
      */
    override def armUpgradeProducerRead(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        val producer = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        awaitReadHandshake(handle, producer)
    end armUpgradeProducerRead

    /** Arm the recv on the reap carrier (via the engine queue) so get_sqe has a single producer. The closedFlag check runs there too: a submit on a
      * ring being torn down is a use-after-free, and once closing, the reap-loop exit drains this op and fails the promise. The reap loop's own
      * -EINTR re-submit calls submitRecv directly, already on the reap carrier.
      */
    private def submitDeferredRecv(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]], handshakeOwned: Boolean)(using
        AllowUnsafe,
        Frame
    ): Unit =
        submitEngineOp { () =>
            if closedFlag.get() then
                promise.completeDiscard(Result.fail(Closed(label, summon[Frame], "driver closed")))
            else
                // Snapshot handle.isUpgraded HERE, on the reap carrier, at the moment this recv is actually armed -- not at the (possibly much
                // earlier, possibly different-carrier) awaitRead/awaitReadHandshake call. See PendingOp.Read's armedPostUpgrade doc.
                submitRecv(handle, promise, eintrRetries = 0, handshakeOwned = handshakeOwned, armedPostUpgrade = handle.isUpgraded)
        }

    /** Submit one recv SQE for `promise`, recording `eintrRetries` (the count of prior re-submissions caused by a `-EINTR` read CQE) on the
      * pending op so the reap can bound the EINTR retry. The public [[awaitRead]] enters at 0; the reap's `-EINTR` re-submit enters at the
      * incremented count.
      */
    private def submitRecv(
        handle: PosixHandle,
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        eintrRetries: Int,
        handshakeOwned: Boolean,
        armedPostUpgrade: Boolean
    )(using
        AllowUnsafe,
        Frame
    ): Unit =
        if handle.readBuffer.isClosed then
            // The handle was closed (its buffers freed) before this deferred arm ran on the reap carrier; never point a recv SQE at freed memory.
            // Fail the read so the caller's pump tears down. closeNow (the buffer free) also runs on the reap carrier, so this arm and the free are
            // serialized: either the arm wins and the close defers behind the recv CQE, or the buffer is already closed and this branch fires.
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"fd=${handle.readFd} closed")))
        else if handle.isUpgraded && hasInFlightRead(handle) then
            // Single-recv-ordering enforcement: this handle went through a STARTTLS upgrade (PosixHandle.isUpgraded) and ANOTHER recv is
            // already kernel-owned and in flight for it right now -- the orphaned handshake-window producer recv the driveUpgradeRead /
            // onFinished TOCTOU can leave outstanding (see PosixHandle.isUpgraded's doc). Queue this request (PosixHandle.queuedRecv) instead
            // of submitting a second SQE: both would target the SAME cached staging buffer (recvStagingFor) and race completion order (a
            // fatal TLS record, or the bad_record_mac corruption shape). `complete`'s `drainQueuedRecv` fires it the moment that in-flight
            // recv's CQE is fully processed, so the two are never simultaneously in flight -- non-blocking, no fiber parked, no deadlock risk
            // (unlike deferring the caller's `onFinished`/`upgraded.start()` itself, which can deadlock: see PosixTransport's
            // onFinished comment). Gated on `isUpgraded` (rare, durable, set only for STARTTLS-upgraded handles) so a normal connection's reads
            // never pay this extra `hasInFlightRead` scan: a well-behaved ReadPump/handshake never has two recvs in flight for one handle
            // outside this one upgrade-transition window in the first place.
            handle.queuedRecv = PosixHandle.QueuedRecv.Queued(promise, handshakeOwned, armedPostUpgrade)
        else if handle.recvInFlight then
            // ALWAYS-ON exclusive-use guard (PosixHandle.recvInFlight): a plain branch, not an elidable `assert` -- this stays live in
            // production. Unreachable for an `isUpgraded` handle (the clause above already intercepts and queues its one legitimate
            // dual-recv window); reaching this means a SECOND recv was requested for this handle while its buffer is still kernel-owned by
            // an SQE that has not reaped yet -- a structural violation of the "arm one, await it, arm the next" discipline every
            // ReadPump/handshake caller is expected to follow. Never guess which recv's bytes belong where: fail this NEW request and abort
            // the whole connection instead of letting two live kernel writes race into one buffer (the same corruption class the queue
            // above exists to prevent for the one known trigger; this is the safety net for any other one).
            val msg =
                s"fd=${handle.readFd} id=${handle.id.packed} exclusive-use violation: a recv was requested while another was already " +
                    "kernel-owned for this handle's buffer"
            Log.live.unsafe.error(s"$label $msg")
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], msg)))
            closeHandle(handle)
        else
            val key = keyGen.getAndIncrement()
            // Cheap snapshot of WHICH buffer this recv is about to target (Present -> recvStagingFor's staging buffer, Absent -> the raw
            // readBuffer): just the tag, not the (lazily-allocating) buffer lookup itself, which stays inside the Present(sqe) branch below so
            // a park on a full SQ never triggers the allocation for a recv that has not actually been armed yet. Carried with the op (see
            // PendingOp.Read's doc) so the feed-time buffer-role check in `complete` can verify the buffer it is about to read from is the SAME
            // one this recv's kernel write actually landed in, regardless of what the handle's CURRENT `tls` state says at reap time.
            val armedForStaging = handle.tls match
                case Present(_) => true
                case Absent     => false
            // ORDERING NOTE: `register` (making `hasInFlightRead` observe this op) runs a few lines BEFORE `handle.recvInFlight = true` below
            // (case Present(sqe) =>, after submitBatched()) -- the two are NOT simultaneous. This gap is safe only because `submitRecv` is the
            // SOLE writer of both, runs to completion on the single reap carrier with no suspension point in between, and cannot be re-entered
            // while executing: no OTHER call on that same carrier can ever observe the intermediate state. If a future change lets some other
            // carrier read `hasInFlightRead` or `recvInFlight` directly (not just via another `submitRecv` call), that read MUST NOT assume the
            // two become true together -- see IoUringDriverTest's "closeHandle defers..." leaf and IoUringExclusiveUseSqFullTest for the exact
            // test-side race this gap can expose under scheduling pressure (surfaces only under concurrency, never in isolation).
            register(key, PendingOp.Read(promise, handle, eintrRetries, handshakeOwned, armedPostUpgrade, armedForStaging))
            uring.kyo_uring_get_sqe(ring) match
                case Present(sqe) =>
                    // For TLS handles: point the recv SQE directly at the per-handle staging buffer so the kernel fills it without an extra copy.
                    // The staging buffer is lazily allocated on the first TLS read (recvStagingFor) and reused across reads. A single TLS recv is
                    // in flight per handle at a time (re-arm happens only inside the engine op after the ciphertext is consumed), so the kernel
                    // never overwrites the staging with a second recv before the engine op feeds the first.
                    // For non-TLS handles: read into the handle's reused readBuffer (the raw path is unchanged).
                    val recvTarget =
                        if armedForStaging then recvStagingFor(handle)
                        else handle.readBuffer
                    // Non-negativity guard at the C trust boundary (CWE-190/195/805): a negative recv length wraps to a huge size_t at the C cast and
                    // becomes an out-of-bounds kernel read. The shim refuses to prepare the SQE and returns non-zero; map that to an OBSERVABLE
                    // rejection (fail the read promise with Closed) rather than letting a bad value pass or dropping the SQE silently (a dropped SQE
                    // would leave this promise waiting on a CQE that never arrives, a hang). readBufferSize is always positive today, so this guards
                    // only a future signedness bug at the boundary.
                    // flags=0: single-shot recv. IORING_RECV_MULTISHOT requires provided buffer rings (IORING_OP_PROVIDE_BUFFERS) to be set up
                    // before submission, or the kernel returns -EINVAL. Provided buffer rings are deferred; each recv re-arms after its CQE.
                    if uring.kyo_uring_prep_recv(sqe, handle.readFd, recvTarget, handle.readBufferSize.toLong, 0) != 0 then
                        unregister(key)
                        promise.completeDiscard(Result.fail(Closed(
                            label,
                            summon[Frame],
                            s"io_uring recv rejected: negative length ${handle.readBufferSize}"
                        )))
                    else
                        uring.kyo_uring_sqe_set_data64(sqe, key)
                        submitBatched()
                        // Claim exclusive use of this handle's buffer (PosixHandle.recvInFlight) now that a real SQE owns it; cleared in
                        // complete() the moment this CQE reaps.
                        handle.recvInFlight = true
                    end if
                case Absent =>
                    // SUBMISSION queue full: no recv SQE is in flight, so no CQE will re-drive this read. Park it (the promise stays pending) and
                    // re-arm after the next CQE batch frees a slot (reArmStalledSubmits), mirroring stalledRaw for sends so a transient SQ-full
                    // backpressures the read instead of failing the connection. unregister first: the key is re-assigned on re-submit. recvInFlight
                    // stays false: nothing kernel-owned touches the buffer yet, so the later re-arm (reArmStalledSubmits, calling submitRecv
                    // directly) must not itself trip the exclusive-use guard above.
                    unregister(key)
                    discard(stalledSubmits.add(PendingOp.Read(
                        promise,
                        handle,
                        eintrRetries,
                        handshakeOwned,
                        armedPostUpgrade,
                        armedForStaging
                    )))
            end match
        end if
    end submitRecv

    /** Fire the recv `submitRecv` queued behind this handle's just-completed one (see [[PosixHandle.queuedRecv]]). Called from [[complete]]
      * immediately after a Read CQE is fully routed (on every completion path, normal or abnormal, except an EINTR retry), so by the time this
      * runs no recv is in flight for the handle: submitting the queued request now can never alias a buffer or race completion order with
      * another recv. A no-op when nothing is queued (the overwhelmingly common case: this only ever populates during the narrow STARTTLS
      * upgrade-transition window, see submitRecv).
      */
    private def drainQueuedRecv(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        handle.queuedRecv match
            case PosixHandle.QueuedRecv.Queued(promise, handshakeOwned, armedPostUpgrade) =>
                handle.queuedRecv = PosixHandle.QueuedRecv.None
                submitRecv(handle, promise, eintrRetries = 0, handshakeOwned = handshakeOwned, armedPostUpgrade = armedPostUpgrade)
            case _ => ()
    end drainQueuedRecv

    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.unsentTailBytes >= PosixHandle.WriteTailLowWater then
            // Tail-bound park (CWE-400): the pump suspended because the write tail reached the high-water mark, not because of a kernel send-buffer
            // limit (io_uring's send is async and self-drains via the in-flight SQE's CQE re-flush). Completing the promise now would busy-loop:
            // the pump would retry, hit the high-water bound again, get Partial, and await again with no progress. Instead,
            // hold the promise on the handle; the CQE re-flush path (onTlsSendComplete / onRawSendComplete) completes it via releaseBackpressureWaiter
            // once the tail falls below the low-water mark. An in-flight send is guaranteed: the tail only reaches the high-water mark through a write
            // whose flush submitted a send SQE (or coalesced behind one), and that SQE's reap re-flushes and re-checks the waiter.
            handle.backpressurePromise = Present(promise)
            // Double-check on the FIFO worker: a CQE re-flush may have drained the tail below the low-water mark between the check above and this
            // registration (the reap runs on the FIFO worker, this runs on the pump carrier). Routing the re-check through the FIFO observes a
            // consistent tail snapshot (no race with onTlsSendComplete) and completes the just-registered waiter if the drain already happened, so
            // the pump is never stranded. A waiter parked while the handle is closing is failed by PosixHandle.freeResources, so the close-vs-park
            // race never strands the pump.
            submitEngineOp(() => handle.releaseBackpressureWaiter())
        else
            // Below the bound: io_uring buffers the unsent bytes in the pending tail, holds AT MOST ONE send SQE in flight per handle, and re-flushes
            // the remainder when that send's CQE reaps, so a write always returns Done and there is no kernel send-buffer to drain before the next
            // write. The completion arm signals writability immediately (no readiness re-arm: the send is already in flight / pending and self-drains).
            promise.completeDiscard(Result.succeed(()))
        end if
    end awaitWritable

    def awaitConnect(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Arm the connect on the reap carrier (single get_sqe producer); see [[submitEngineOp]].
        submitEngineOp(() => submitConnect(promise, handle))
    end awaitConnect

    /** Submit one connect SQE for `promise`, reading the stashed `connectTarget` off the handle. The public [[awaitConnect]] enters via the engine
      * queue; [[reArmStalledSubmits]] re-enters here directly after a CQE batch freed a slot. On a full SQ the connect parks in [[stalledSubmits]]
      * (its promise stays pending) instead of failing, so a transient SQ-full does not drop a connection that would otherwise complete.
      */
    private def submitConnect(promise: Promise.Unsafe[Unit, Abort[Closed]], handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        handle.connectTarget match
            case Present((addr, len)) =>
                val key = keyGen.getAndIncrement()
                register(key, PendingOp.Connect(promise, handle))
                if closedFlag.get() then
                    unregister(key)
                    promise.completeDiscard(Result.fail(Closed(label, summon[Frame], "driver closed")))
                else
                    uring.kyo_uring_get_sqe(ring) match
                        case Present(sqe) =>
                            uring.kyo_uring_prep_connect(sqe, handle.writeFd, addr, len)
                            uring.kyo_uring_sqe_set_data64(sqe, key)
                            submitBatched()
                        case Absent =>
                            // SQ full: nothing in flight to re-drive the connect. Park it and re-arm after the next CQE batch frees a slot
                            // (reArmStalledSubmits), mirroring the recv path so a transient SQ-full backpressures rather than failing the connect.
                            unregister(key)
                            discard(stalledSubmits.add(PendingOp.Connect(promise, handle)))
                    end match
                end if
            case Absent =>
                // No stashed connect target: the handle was not created for a client connect. Fail loudly rather than submit a bad SQE.
                promise.completeDiscard(Result.fail(Closed(label, summon[Frame], "awaitConnect on a handle with no connectTarget")))
        end match
    end submitConnect

    /** Submit a single-shot `IORING_OP_ACCEPT` SQE for the given listen handle. When the CQE arrives, the reap loop completes `promise`
      * with the accepted client fd (>= 0) or a [[Closed]] wrapping the negative errno. The addr/addrlen placeholder buffers are stored
      * in the [[PendingOp.Accept]] entry so they stay alive until the CQE is reaped, then released via `releaseBuffer`. If the SQ is
      * full the accept parks in [[stalledSubmits]] (keeping its buffers) and is re-armed once a slot frees, never failed (a failed accept
      * would wedge the accept loop, whose `onComplete` reads Failure as "listener closed" and stops re-arming).
      */
    def awaitAccept(handle: PosixHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // Placeholder buffers that kyo_uring_prep_accept requires; the kernel may write the peer address into addr, which we discard.
        // The accepted fd comes from the CQE res field. These must stay alive until the CQE is reaped (kept in PendingOp.Accept) and across a
        // park+re-arm, so they are allocated here once and threaded through submitAccept rather than re-allocated per re-arm.
        val noAddr = Buffer.alloc[Byte](SockAddr.inet6Size)
        val noLen  = Buffer.alloc[Int](1)
        noLen.set(0, SockAddr.inet6Size)
        // Arm the accept on the reap carrier (single get_sqe producer); see [[submitEngineOp]].
        submitEngineOp(() => submitAccept(promise, handle, noAddr, noLen))
    end awaitAccept

    /** Submit one accept SQE for `promise` over the supplied (already-allocated) addr/len placeholder buffers. The public [[awaitAccept]]
      * enters via the engine queue; [[reArmStalledSubmits]] re-enters here directly after a CQE batch freed a slot. On a full SQ the accept
      * parks in [[stalledSubmits]] keeping its buffers (re-armed later), never failed, so a transient SQ-full cannot wedge the listener's
      * accept loop.
      */
    private def submitAccept(
        promise: Promise.Unsafe[Int, Abort[Closed]],
        handle: PosixHandle,
        noAddr: Buffer[Byte],
        noLen: Buffer[Int]
    )(using AllowUnsafe, Frame): Unit =
        val key = keyGen.getAndIncrement()
        register(key, PendingOp.Accept(promise, handle, noAddr, noLen))
        if closedFlag.get() then
            unregister(key)
            noAddr.close()
            noLen.close()
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], "driver closed")))
        else if handle.isClosing() then
            // The listener was torn down (closeListener ran requestClose on this carrier before this arm drained): its fd is closed and the
            // number may already name a different socket, so arming would accept on that socket and steal its connections. Reject instead.
            unregister(key)
            noAddr.close()
            noLen.close()
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], "listener closed")))
        else
            uring.kyo_uring_get_sqe(ring) match
                case Present(sqe) =>
                    uring.kyo_uring_prep_accept(sqe, handle.readFd, noAddr, noLen, 0) // single-shot; flags=0
                    uring.kyo_uring_sqe_set_data64(sqe, key)
                    submitBatched()
                case Absent =>
                    // SQ full: park the accept (keeping its buffers) and re-arm after the next CQE batch frees a slot (reArmStalledSubmits) instead
                    // of failing it, which the accept loop would misread as "listener closed" and stop re-arming. unregister first: the key is
                    // re-assigned on re-submit.
                    unregister(key)
                    discard(stalledSubmits.add(PendingOp.Accept(promise, handle, noAddr, noLen)))
            end match
        end if
    end submitAccept

    def write(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        given Frame = Frame.internal
        // Reject a write once the driver is closing: flushRaw/flushTls would get_sqe on a ring the reap carrier is tearing down.
        // beginWrite() guards the per-HANDLE lifecycle but not the driver's; the pump treats Error as teardown, which is correct here.
        if closedFlag.get() then WriteResult.Error
        else if data.isEmpty || offset >= data.size then WriteResult.Done
        else if handle.unsentTailBytes >= PosixHandle.WriteTailHighWater then
            // Write-backpressure bound (CWE-400): the write tail (pendingCipher for TLS, rawPending for raw) has reached the high-water mark because
            // the in-flight send is not draining it fast enough (a slow- or no-read peer). io_uring's write is async and always returns Done after
            // appending, so without this gate the WritePump would keep pulling spans and the tail would grow without limit toward OOM. Report
            // TailPartial so the pump parks on the tail-bound backpressure path (Backpressured state) rather than on socket writability; the data
            // is not consumed (re-presented unchanged on retry), and awaitWritable holds the pump until the in-flight send's CQE re-flush drains
            // the tail below the low-water mark (releaseBackpressureWaiter). Checked before beginWrite so an over-bound write touches no guard.
            WriteResult.TailPartial(data, offset)
        else
            handle.tls match
                case Present(engine) =>
                    if !handle.beginWrite() then
                        // The handle was closed (resources freed) before this write acquired them; bail without touching the engine. The pump
                        // treats Error as teardown, which is correct for a write on a closed handle (the write twin of the recv's !beginDispatch
                        // guard on the poller).
                        WriteResult.Error
                    else writeTls(handle, data, engine)
                case Absent =>
                    if !handle.beginWrite() then WriteResult.Error // handle closed before this write acquired its resources
                    else writeRaw(handle, data, offset)
        end if
    end write

    /** Plaintext send on io_uring, held SINGLE-IN-FLIGHT per handle with reflush-on-partial: the raw twin of [[writeTls]], with identity
      * "encryption" (the plaintext IS the wire bytes). Submits the append-then-flush on the serial engine worker and returns Done immediately,
      * so the write pump always proceeds to the next take.
      *
      * io_uring's `IORING_OP_SEND` is asynchronous and may short-send (`res < len` without MSG_WAITALL), and CQEs need not complete in submission
      * order: two send SQEs on one socket can finish out of order, so issuing the next raw send before the prior CQE reaps corrupts the wire
      * stream (liburing #1102). So the bytes are appended to the handle's pending tail ([[PosixHandle.rawPending]]) and AT MOST ONE raw send SQE
      * is held in flight per handle ([[PosixHandle.rawSendInFlight]], the completion-driver twin of the TLS `sendInFlight`): [[flushRaw]] submits
      * only when no send is in flight; a back-to-back write that runs while one is in flight APPENDS its bytes and does NOT submit (coalescing),
      * and the in-flight send's CQE re-flushes the grown unsent region ([[onRawSendComplete]]). A short send re-submits the unsent remainder.
      * Both the tail and the guard are mutated only on the engine FIFO worker, so they are single-owner and never race.
      */
    private def writeRaw(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe, Frame): WriteResult =
        submitEngineOp { () =>
            // endWrite is called inside this FIFO thunk after the append+flush, keeping the write guard held until they finish: a concurrent
            // closeHandle defers freeResources (which clears the pending tail) until this endWrite fires, so the tail is never cleared while
            // the flush reads it. The send SQE submitted below reaps asynchronously; its CQE re-flush runs as a SEPARATE later engine op.
            try
                appendRaw(handle, data, offset)
                flushRaw(handle)
            finally discard(handle.endWrite())
            end try
        }
        // The pump always sees Done; the actual send runs on the FIFO worker carrier after the append+flush, then self-drains via reap re-flush.
        WriteResult.Done
    end writeRaw

    /** Append the unsent plaintext region `[offset, data.size)` to the handle's pending raw tail. Engine-FIFO-worker-only (single owner), so it
      * never runs concurrently with [[flushRaw]] or [[onRawSendComplete]]. The [[GrowableByteBuffer]] is allocated lazily on the first append, so
      * a write whose payload is sent in one SQE allocates no extra growable buffer. Mirrors [[appendPending]] for the TLS path.
      */
    private def appendRaw(handle: PosixHandle, data: Span[Byte], offset: Int): Unit =
        val buf =
            handle.rawPending match
                case Present(b) => b
                case Absent =>
                    val b = new GrowableByteBuffer
                    handle.rawPending = Present(b)
                    b
        buf.writeBytes(data.toArrayUnsafe, offset, data.size - offset)
    end appendRaw

    /** Submit one send SQE for the unsent region `[rawPendingSent, size)` of the handle's pending raw tail, holding AT MOST ONE raw send SQE in
      * flight per handle ([[PosixHandle.rawSendInFlight]]). Engine-FIFO-worker-only. The raw twin of [[flushTls]].
      *
      * Unlike the TLS flush mirror (a per-handle reused buffer not closed on reap), the unsent region is copied into a FRESH per-write Buffer
      * pinned in [[PendingOp.Write]] and closed on reap: a raw send buffer is per-write so it can be released the moment its CQE reaps, and a
      * reused mirror would be overwritten by the next flush while the kernel is still reading it. If a send is already in flight this returns
      * WITHOUT submitting; the bytes a concurrent write appended stay in the tail and the in-flight send's [[onRawSendComplete]] re-flushes them.
      * If the SQ is full no SQE was submitted; the guard stays clear and the remainder stays pending for the reap loop to re-flush when a slot
      * frees ([[reflushStalledRaw]]).
      */
    private def flushRaw(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Driver closing: never get_sqe on a ring the teardown is reclaiming (mirrors flushTls). A raw flush is normally unreachable during
        // close (writes are rejected at `write` and the reap loop's stalled re-flush has exited), so this is the defensive boundary.
        if closedFlag.get() then ()
        else if handle.rawSendInFlight then
            () // a send SQE is already outstanding; its onRawSendComplete re-flushes any bytes appended meanwhile
        else
            handle.rawPending match
                case Absent => () // nothing buffered
                case Present(buf) =>
                    val unsentLen = buf.size - handle.rawPendingSent
                    if unsentLen <= 0 then
                        // Fully sent already: reset so the next write starts clean.
                        buf.reset()
                        handle.rawPendingSent = 0
                    else
                        // Copy the unsent region into a fresh per-write Buffer (closed on reap), then send it from offset 0. The send is
                        // single-in-flight per handle (rawSendInFlight guard), so the next flush does not run until this send's CQE reaps.
                        val sendBuf = Buffer.fromArray[Byte](
                            java.util.Arrays.copyOfRange(buf.array, handle.rawPendingSent, buf.size)
                        )
                        val key = keyGen.getAndIncrement()
                        register(key, PendingOp.Write(handle, sendBuf, handle.rawPendingSent, unsentLen))
                        uring.kyo_uring_get_sqe(ring) match
                            case Present(sqe) =>
                                // Non-negativity guard at the C trust boundary (CWE-190/195/805): a negative send length wraps to a huge size_t at the
                                // C cast and becomes an out-of-bounds kernel read. The unsentLen > 0 guard above already keeps a negative length out of
                                // this branch today, so the shim return is 0 here; the check is the defensive boundary for a future signedness bug. On a
                                // non-zero return the SQE was NOT prepared, so leave the guard clear, release the per-write buffer, and re-queue the
                                // handle for a later flush (the same not-submitted handling as SQ-full): no silent SQE drop, no byte loss.
                                if uring.kyo_uring_prep_send(
                                        sqe,
                                        handle.writeFd,
                                        sendBuf,
                                        unsentLen.toLong,
                                        PosixConstants.MSG_NOSIGNAL
                                    ) != 0
                                then
                                    unregister(key)
                                    sendBuf.close()
                                    discard(stalledRaw.add(handle))
                                else
                                    uring.kyo_uring_sqe_set_data64(sqe, key)
                                    // Exactly one raw send SQE is now outstanding for this handle; cleared when its CQE reaps (onRawSendComplete).
                                    handle.rawSendInFlight = true
                                    submitBatched()
                            case Absent =>
                                // SQ full: nothing was submitted; the kernel never owned sendBuf. Release it and leave the remainder pending with
                                // the guard clear. The reap loop re-flushes this handle once a CQE frees a SQ slot (reflushStalledRaw), so the
                                // pump never busy-spins and no byte is dropped.
                                unregister(key)
                                sendBuf.close()
                                discard(stalledRaw.add(handle))
                        end match
                    end if
            end match
        end if
    end flushRaw

    /** TLS send on io_uring: submit the encrypt-then-send operation to the serial engine worker and return Done immediately, mirroring
      * [[PollerIoDriver.writeTls]]. The FIFO worker encrypts the whole plaintext through the shared [[encryptPlaintext]] loop (the same engine
      * steps the poller runs), appends the ciphertext to the handle's pending tail, then submits ONE send SQE for the unsent region.
      *
      * Two io_uring-specific points (vs the poller's inline send): the ciphertext buffer the send SQE points at MUST outlive its asynchronous
      * send CQE, so it is pinned in [[PendingOp.TlsWrite]] (the same use-after-free model the raw path uses for [[PendingOp.Write]]); and a
      * partial send is detected at CQE reap (`res < len`) and the unsent remainder is re-submitted on a fresh send SQE rather than re-arming
      * writability (io_uring has no readiness re-arm). The remainder accounting (`pendingCipher` / `pendingCipherSent`) stays on the engine FIFO
      * worker (the reap enqueues the re-flush as an engine op), so the tail is touched by exactly one carrier.
      */
    private def writeTls(handle: PosixHandle, data: Span[Byte], engine: TlsEngine)(using AllowUnsafe, Frame): WriteResult =
        submitEngineOp { () =>
            // endWrite is called inside this FIFO thunk after the engine ops, keeping the write guard held until the engine is done: a
            // concurrent closeHandle defers the engine free until this endWrite fires, so the engine is never freed while writePlain /
            // drainCiphertext run. The send SQE submitted below reaps asynchronously; its CQE re-flush runs as a SEPARATE later engine op.
            try
                val ok = encryptPlaintext(handle, data, engine)((drain, n) => appendPending(handle, drain, n))
                if ok then flushTls(handle)
            finally discard(handle.endWrite())
            end try
        }
        // The pump always sees Done; the actual send runs on the FIFO worker carrier after the engine ops complete.
        WriteResult.Done
    end writeTls

    /** Append freshly-encrypted ciphertext to the handle's pending tail. Engine-FIFO-worker-only (the single owner of every write engine op for
      * this connection), so it never runs concurrently. The [[GrowableByteBuffer]] is allocated lazily on the first append so a write whose
      * ciphertext is sent in one SQE allocates nothing extra. Mirrors [[PollerIoDriver.appendPending]].
      *
      * `drain` is the per-handle reused encryptDrain buffer (never closed here). `len` is the number of bytes written by drainCiphertext.
      * Uses [[GrowableByteBuffer.writeFromBuffer]] for a zero-intermediate-allocation copy from the off-heap drain buffer (direct off-heap
      * to heap copy, no intermediate Array).
      */
    private def appendPending(handle: PosixHandle, drain: Buffer[Byte], len: Int)(using AllowUnsafe): Unit =
        val buf =
            handle.pendingCipher match
                case Present(b) => b
                case Absent =>
                    val b = new GrowableByteBuffer
                    handle.pendingCipher = Present(b)
                    b
        buf.writeFromBuffer(drain, len) // zero intermediate heap array (direct off-heap -> heap copy)
    end appendPending

    /** True when the raw (plaintext / handshake-ciphertext) tail still holds bytes that have not been sent. Used by [[flushTls]] to keep a TLS
      * send strictly after the raw tail across the STARTTLS upgrade: the handshake's final flight is sent via the raw path (writeRaw, while
      * `handle.tls` is still Absent), so a TLS app-data send submitted while raw bytes are still queued (or a raw send is in flight) would put
      * two send SQEs on one fd, which io_uring may reap out of order, reordering the wire bytes. Engine-FIFO-worker-only (same owner as the tails).
      */
    private def rawTailHasUnsent(handle: PosixHandle): Boolean =
        handle.rawPending match
            case Present(buf) => buf.size - handle.rawPendingSent > 0
            case Absent       => false

    /** Submit one send SQE for the unsent region `[pendingCipherSent, size)` of the handle's pending ciphertext tail, holding AT MOST ONE TLS send
      * SQE in flight per handle. Engine-FIFO-worker-only.
      *
      * Unlike the poller's inline-send flush, this does not loop: io_uring's send completes asynchronously, so the unsent region is copied into a
      * pinned `Buffer` and handed to a single send SQE; the partial-send re-flush happens when that SQE's CQE is reaped ([[onTlsSendComplete]]).
      *
      * The [[PosixHandle.sendInFlight]] guard is what keeps the asynchronous send correct: it is the completion-driver twin of the poller's
      * `flushReArmPending` coalesce. If a send is already in flight this returns immediately WITHOUT submitting; the freshly-encrypted ciphertext a
      * concurrent `writeTls` appended stays in `pendingCipher`, and the in-flight send's `onTlsSendComplete` re-flushes it (so a second back-to-back
      * write never re-sends the first write's still-unacknowledged region). When it does submit it sets the guard; the reap clears it. If the SQ is
      * full no SQE was submitted, so the guard stays clear and the buffer is released; the remainder stays pending for a later flush (the next write,
      * or a re-flush re-submits it).
      */
    private def flushTls(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Driver closing: do not get_sqe on a ring the teardown is reclaiming, and do not submit a close_notify send whose CQE the exiting
        // reap loop would never deliver (which would strand the deferred close and leak the engine). Skipping it lets registerDeferredClose
        // see nothing in flight and free the connection now (force-close on shutdown; the peer sees a reset instead of a graceful close_notify).
        if closedFlag.get() then ()
        else if handle.sendInFlight then
            () // a send SQE is already outstanding; its onTlsSendComplete re-flushes any bytes appended meanwhile
        else if handle.rawSendInFlight || rawTailHasUnsent(handle) then
            // Cross-tail send wire-order across the STARTTLS (raw->TLS) transition: the raw tail (the handshake's final flight, sent via
            // writeRaw while handle.tls was Absent) has a send in flight or bytes still queued. Submitting a TLS app-data send here would put
            // two send SQEs on one fd, which io_uring may reap out of order (liburing #1102, see writeRaw), reordering the wire bytes so the
            // peer reads a byte-shifted record and aborts with bad_record_mac. Defer: onRawSendComplete re-flushes this TLS tail once the raw
            // tail fully drains. The transition is one-way (handle.tls becomes Present at onFinished and writes only ever route to writeTls
            // after, so rawPending never grows again), so this defers at most across the handshake's final flight, never indefinitely. The raw
            // and TLS send tails are separate handle fields with this cross-tail ordering between them: keep them separate, since a single
            // undifferentiated send tail loses the ordering and lets the STARTTLS-client bad_record_mac corruption back in.
            ()
        else
            handle.pendingCipher match
                case Absent => () // nothing buffered (e.g. an engine that produced no ciphertext this write)
                case Present(buf) =>
                    val unsentLen = buf.size - handle.pendingCipherSent
                    if unsentLen <= 0 then
                        // Fully sent already: reset so the next write starts clean.
                        buf.reset()
                        handle.pendingCipherSent = 0
                    else
                        // Copy the unsent region element-wise into the per-handle reused flush mirror (grown on demand), then send from
                        // offset 0 of the mirror. The send is single-in-flight per handle (sendInFlight guard), so the mirror is not
                        // refilled until the prior send CQE reaps. The mirror is owned by the engine FIFO worker and freed only in
                        // freeResources; it must NOT be closed on reap (TlsWrite.releaseBuffer is a no-op for that reason).
                        val mirror = flushMirrorFor(handle, unsentLen)
                        var mi     = 0
                        while mi < unsentLen do
                            mirror.set(mi, buf.array(handle.pendingCipherSent + mi))
                            mi += 1
                        end while
                        val key = keyGen.getAndIncrement()
                        register(key, PendingOp.TlsWrite(handle, mirror, unsentLen))
                        uring.kyo_uring_get_sqe(ring) match
                            case Present(sqe) =>
                                // Non-negativity guard at the C trust boundary (CWE-190/195/805): a negative send length wraps to a huge size_t at the
                                // C cast and becomes an out-of-bounds kernel read. The unsentLen > 0 guard above already keeps a negative length out of
                                // this branch today, so the shim return is 0 here; the check is the defensive boundary for a future signedness bug. On a
                                // non-zero return the SQE was NOT prepared, so leave sendInFlight clear (the same not-submitted handling as SQ-full): the
                                // mirror stays allocated and the remainder stays pending for the next flush, no silent SQE drop, no byte loss.
                                if uring.kyo_uring_prep_send(
                                        sqe,
                                        handle.writeFd,
                                        mirror,
                                        unsentLen.toLong,
                                        PosixConstants.MSG_NOSIGNAL
                                    ) != 0
                                then
                                    unregister(key)
                                else
                                    uring.kyo_uring_sqe_set_data64(sqe, key)
                                    // Exactly one TlsWrite SQE is now outstanding for this handle; cleared when its CQE reaps (onTlsSendComplete).
                                    handle.sendInFlight = true
                                    submitBatched()
                            case Absent =>
                                // SQ full: nothing was submitted; the mirror was never handed to the kernel and can be left allocated for
                                // the next flush. Leave sendInFlight clear so the next flush (from the next write or re-flush) can retry.
                                unregister(key)
                        end match
                    end if
            end match
        end if
    end flushTls

    /** Account for a reaped TLS-send CQE on the engine FIFO worker (the reap loop enqueues this so the tail stays single-owner). The reaped SQE was
      * the one outstanding send, so this FIRST clears [[PosixHandle.sendInFlight]] (the next flush may now submit), then advances
      * `pendingCipherSent` by the bytes actually sent and re-flushes the remainder.
      *
      * On a positive `res` it advances `pendingCipherSent` by `res`. If the tail is fully drained it resets. Otherwise bytes remain, either a
      * partial send (`res < submitted len`) OR ciphertext a coalesced back-to-back `writeTls` appended while this send was in flight; either way
      * [[flushTls]] re-submits a send SQE for the new unsent region (which now starts at the advanced `pendingCipherSent` and spans every appended
      * byte). A negative `res` (hard send error / peer reset) discards the tail; the recv side surfaces Closed through the normal teardown path.
      */
    private def onTlsSendComplete(handle: PosixHandle, res: Int)(using AllowUnsafe, Frame): Unit =
        // The one outstanding send has reaped: clear the guard FIRST so the re-flush below (or a later write's flush) can submit the next SQE.
        handle.sendInFlight = false
        handle.pendingCipher match
            case Absent => () // tail already cleared (e.g. by a concurrent close)
            case Present(buf) =>
                if res < 0 && -res == PosixConstants.EINTR && sendEintrCount(handle) < maxTransientIoRetries then
                    // EINTR send CQE: a signal interrupted the send before any byte moved (POSIX send(2)); nothing was transferred, so re-flush the
                    // SAME unsent region instead of discarding the tail and silently losing the outbound ciphertext (the io_uring twin of
                    // PollerIoDriver's sendBlockingWithRetry). pendingCipherSent is NOT advanced (no byte was sent). Bounded by maxTransientIoRetries
                    // via the per-handle count so an EINTR storm cannot spin: past the bound the tail is discarded by the hard-error branch below.
                    bumpSendEintr(handle)
                    flushTls(handle)
                else if res < 0 then
                    Log.live.unsafe.debug(s"io_uring TLS send error fd=${handle.writeFd}; discarding pending ciphertext tail")
                    clearSendEintr(handle)
                    buf.reset()
                    handle.pendingCipherSent = 0
                else
                    // A non-EINTR send completed (a real partial or full send): reset the EINTR retry count so the bound counts only an uninterrupted
                    // EINTR run.
                    clearSendEintr(handle)
                    handle.pendingCipherSent += res
                    if handle.pendingCipherSent >= buf.size then
                        buf.reset()
                        handle.pendingCipherSent = 0
                        // Always-on at-rest invariant (the recvInFlight doctrine, PosixHandle.scala:211-224, applied to the send tail): a
                        // plain branch, not an elidable assert, so it stays live in production. Once the tail is declared fully drained,
                        // both fields must actually be at rest; a future edit that resets one but not the other would otherwise surface
                        // only as a downstream, hard-to-attribute flake in some unrelated suite. Attributes both values here,
                        // at the exact op, in every TLS-writing suite.
                        if buf.size != 0 || handle.pendingCipherSent != 0 then
                            Log.live.unsafe.error(
                                s"$label fd=${handle.writeFd} at-rest violation: TLS send tail declared fully drained but " +
                                    s"bufSize=${buf.size} pendingCipherSent=${handle.pendingCipherSent} after reset"
                            )
                        end if
                    else
                        // Bytes remain (a partial send, or ciphertext a coalesced write appended while this send was in flight): re-submit a send
                        // SQE for the new unsent region. flushTls re-sets sendInFlight when it submits.
                        flushTls(handle)
                    end if
                end if
        end match
        // The tail just advanced (or fully drained / discarded): if a WritePump parked at the high-water bound and the tail has fallen below the
        // low-water mark, release it so the pump retries the deferred write. A no-op when no waiter is parked or the tail is still over the mark.
        handle.releaseBackpressureWaiter()
    end onTlsSendComplete

    /** Account for a reaped RAW (plaintext) send CQE on the engine FIFO worker (the reap loop enqueues this so the tail stays single-owner). The
      * raw twin of [[onTlsSendComplete]]. The reaped SQE was the one outstanding raw send, so this FIRST clears [[PosixHandle.rawSendInFlight]]
      * (the next flush may now submit), then advances `rawPendingSent` by the bytes actually sent and re-flushes the remainder.
      *
      * On a positive `res` it advances `rawPendingSent` by `res`. If the tail is fully drained it resets. Otherwise bytes remain, either a partial
      * send (`res < submitted len`) OR plaintext a coalesced back-to-back `writeRaw` appended while this send was in flight; either way
      * [[flushRaw]] re-submits a send SQE for the new unsent region (which starts at the advanced `rawPendingSent` and spans every appended byte),
      * so no byte is dropped and the wire order is preserved (a single send is ever in flight). A negative `res` (hard send error / peer reset)
      * discards the tail; the recv side surfaces Closed through the normal teardown path, exactly as the TLS path does.
      */
    private def onRawSendComplete(handle: PosixHandle, res: Int)(using AllowUnsafe, Frame): Unit =
        // The one outstanding send has reaped: clear the guard FIRST so the re-flush below can submit the next SQE.
        handle.rawSendInFlight = false
        handle.rawPending match
            case Absent => () // tail already cleared (e.g. by a concurrent close)
            case Present(buf) =>
                if res < 0 && -res == PosixConstants.EINTR && sendEintrCount(handle) < maxTransientIoRetries then
                    // EINTR send CQE: a signal interrupted the send before any byte moved (POSIX send(2)); nothing was transferred, so re-flush the
                    // SAME unsent region instead of discarding the tail and silently losing the outbound bytes (the raw twin of the TLS path above).
                    // rawPendingSent is NOT advanced (no byte was sent). Bounded by maxTransientIoRetries via the per-handle count so an EINTR storm
                    // cannot spin: past the bound the tail is discarded by the hard-error branch below.
                    bumpSendEintr(handle)
                    flushRaw(handle)
                else if res < 0 then
                    Log.live.unsafe.debug(s"io_uring raw send error fd=${handle.writeFd}; discarding pending plaintext tail")
                    clearSendEintr(handle)
                    buf.reset()
                    handle.rawPendingSent = 0
                else
                    // A non-EINTR send completed (a real partial or full send): reset the EINTR retry count so the bound counts only an uninterrupted
                    // EINTR run.
                    clearSendEintr(handle)
                    handle.rawPendingSent += res
                    if handle.rawPendingSent >= buf.size then
                        buf.reset()
                        handle.rawPendingSent = 0
                        // Cross-tail send wire-order: the raw tail is now fully drained. Kick the TLS tail in case a
                        // post-handshake app-data send was deferred in flushTls to keep it strictly after the handshake's final raw
                        // flight (two SQEs on one fd that io_uring can reap out of order reorder the wire bytes -> bad_record_mac).
                        // A no-op when the TLS tail is empty (the common non-STARTTLS raw connection) or a TLS send is already in
                        // flight. The raw and TLS tails are separate handle fields: keep them separate, since folding them into one
                        // undifferentiated tail loses this kick and lets the STARTTLS-client corruption back in.
                        flushTls(handle)
                    else
                        // Bytes remain (a partial send, or plaintext a coalesced write appended while this send was in flight): re-submit a send
                        // SQE for the new unsent region. flushRaw re-sets rawSendInFlight when it submits.
                        flushRaw(handle)
                    end if
                end if
        end match
        // The tail just advanced (or fully drained / discarded): if a WritePump parked at the high-water bound and the tail has fallen below the
        // low-water mark, release it so the pump retries the deferred write. A no-op when no waiter is parked or the tail is still over the mark.
        handle.releaseBackpressureWaiter()
    end onRawSendComplete

    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Fail every pending promise for `handle` immediately so the caller stops waiting, but do NOT remove the pending entries and do NOT
        // free any buffer: the SQEs are still in flight and the kernel still owns their memory. Their CQEs are still reaped, which
        // is what decrements the in-flight count and releases the per-op memory in order.
        val closed = Closed(label, summon[Frame], s"fd=${handle.readFd}/${handle.writeFd} canceled")
        pending.forEach { (_, op) =>
            if op.handle.id.packed == handle.id.packed then op.failPromise(closed)
        }
        // Fail any WritePump promise parked at the write-backpressure high-water bound. It is held on the handle (a tail-bound park, not a pending
        // SQE), so it is not in `pending`; releasing it with Closed lets the pump tear down rather than hang on a tail that will never drain.
        handle.backpressurePromise.foreach(_.completeDiscard(Result.fail(closed)))
        handle.backpressurePromise = Absent
    end cancel

    /** Listener teardown, sequenced on the reap carrier so a queued accept arm can never outlive the listen fd (the ghost-accept hazard the
      * [[IoDriver.closeListener]] contract describes). [[awaitAccept]] only ENQUEUES the accept arm; a `cancel` + fd close on the caller
      * carrier can run before that arm drains, in which case `cancel` sees no pending entry, the fd number is freed and recycled (typically
      * by the very next listener), and the late-draining arm preps an accept SQE against the recycled socket with the closed listener's
      * promise and handler, stealing one connection per ghost. Running the teardown as an engine op closes every window:
      *   - an arm queued BEFORE this op drains first and preps against the still-open listen fd (the genuine socket), then `cancel` here
      *     fails its now-registered promise;
      *   - `requestClose` marks the handle so an arm queued AFTER this op (a re-arm racing the close) rejects inside [[submitAccept]]
      *     instead of arming, since both run on this carrier;
      *   - `flushSubmits` pushes the prepped SQEs to the kernel while the fd is still open (the kernel resolves the fd at submit), so no
      *     unsubmitted SQE can resolve the number after `closeFd` releases it. The shutdown inside `closeFd` then completes the in-kernel
      *     accept, whose CQE reaps the pending entry onto the already-failed promise.
      */
    override def closeListener(handle: PosixHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        submitEngineOp { () =>
            cancel(handle)
            handle.requestClose()
            flushSubmits()
            closeFd()
        }
    end closeListener

    /** True when a recv for `handle` is in flight: a recv SQE kernel-owned (registered in `pending`) OR a recv parked on a full submission queue
      * (held in `stalledSubmits`, re-armed next reap turn by `reArmStalledSubmits`). The STARTTLS upgrade read path consults this ON THE REAP CARRIER
      * (inside the handshake's `driveUpgradeRead`, which runs as an engine op) to learn whether the plaintext ReadPump left a stale recv that will
      * consume the peer's first handshake flight; if so the handshake routes through the handle's `upgradeHandoff` instead of issuing a second, racing
      * recv. Both queues must be scanned: under SQ-full saturation the stale recv's `submitRecv` `unregister`s it from `pending` and parks it in
      * `stalledSubmits`, so a `pending`-only check would answer "no stale recv coming" while one genuinely is, and the upgrade would clear
      * `upgradeActive` and arm a racing recv, stranding the re-armed stale recv's flight on the now-normal read path (the upgrade stall under load).
      * A stray ReadPump re-arm requested DURING the upgrade can never register a competing recv: `awaitRead`'s single-recv gate drops any arm requested
      * while `upgradeActive` is set, so it reaches neither queue. Both queues are touched only on the reap carrier (this runs there), so the scan is
      * race-free. Close-path cost, not the hot read path; a handle has at most one recv in flight at a time.
      */
    override def hasInFlightRead(handle: PosixHandle)(using AllowUnsafe): Boolean =
        def isReadFor(op: PendingOp): Boolean = op match
            case PendingOp.Read(_, h, _, _, _, _) => h.id.packed == handle.id.packed
            case _                                => false
        val pendingIt = pending.values().iterator()
        var found     = false
        while !found && pendingIt.hasNext do
            if isReadFor(pendingIt.next()) then found = true
        val stalledIt = stalledSubmits.iterator()
        while !found && stalledIt.hasNext do
            if isReadFor(stalledIt.next()) then found = true
        found
    end hasInFlightRead

    // io_uring reads are kernel-owned recv SQEs, so the TLS handshake must NOT mix a direct recv(2) probe with them on the same fd (the two race the
    // socket stream into handle.readBuffer under load, fabricating a corrupt handshake record). The handshake reads exclusively through awaitRead.
    override def inlineRecvSafe: Boolean = false

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Mark the close as requested NOW (synchronously, before the deferred engine-FIFO close machinery), so a teardown that races the deferred
        // close can force-complete it and reclaim the fd. Removed in closeNow when the fd actually closes.
        discard(pendingCloses.put(handle.id.packed, handle))
        // Route the engine free through the engine queue so it is serialized behind any read/write engine ops for this connection (no two
        // carriers touch one ssl). Installed before the close path can fire so freeResources sees the sink whether the close runs now or is
        // deferred until the in-flight count drains. Terminal-aware (mirrors the poller's own fix): once the ring has actually exited, the
        // engine FIFO has no consumer left, so submitting here would leak the native engine on top of the fd; free inline instead (safe: by
        // the time freeResources invokes this, the handle's own HandleGuard has already confirmed no read/write holder is active). Gated on
        // [[ringExited]], not `teardownDone`: `teardownDone` only proves teardownRing's body has STARTED, not that
        // io_uring_queue_exit has actually run.
        handle.engineFreeSink = op => if ringExited.get() then op() else submitEngineOp(op)
        // Put-then-recheck (the close-vs-reap idiom registerDeferredClose already uses below): a closeHandle call that races OR FOLLOWS
        // teardownRing's orphan sweep registers an obligation nothing will ever look at again (the ring has already exited) and, if it
        // fell through to submitEngineOp, an op nothing will ever drain (the reap carrier is gone) -- a residual leak. Discharging
        // directly via closeNow is the same UAF-safe post-io_uring_queue_exit path teardownRing's own orphan sweep uses (NEVER the
        // queued closure below -- cancel/shutdownTls/registerDeferredClose all reach get_sqe on the exited ring, a use-after-free). Gated on
        // [[ringExited]], not `teardownDone` (same reason as engineFreeSink above): closeNow's unconditional fd close + buffer
        // free is only UAF-safe once queue_exit has actually released the kernel's hold on this handle's recv buffer.
        //
        // `pendingCloses.remove` is the single-discharger claim: teardownRing's orphan sweep can race THIS recheck for the SAME
        // handle (both may observe `ringExited == true`), and `closeNow` touches shared, non-thread-safe collections (`stalledSubmits`,
        // a plain `java.util.ArrayDeque`), so two concurrent `closeNow` calls for different handles would race on those shared structures
        // even though each is individually idempotent per-handle. Checking (not discarding) the atomic remove's return value ensures only
        // the winner of the two ever calls `closeNow` for this handle; cancel(handle)'s promise-failing is redundant here, not
        // skipped-unsafely: teardownRing already failed every handle's pending ops (the driver-wide `pending` map sweep) before freeing
        // the ring.
        if ringExited.get() then
            Maybe(pendingCloses.remove(handle.id.packed)).foreach(closeNow)
        else
            // The whole close runs on the reap carrier, AFTER any deferred read/accept arm for this handle (FIFO): the arm is enqueued before this
            // close, so cancel here fails a promise the arm just registered (a read armed AFTER a caller-carrier cancel would otherwise wait for data
            // that never comes, a hang), and the in-flight check + closeNow is serialized with the arm so the readBuffer is never freed under a live SQE.
            submitEngineOp { () =>
                // cancel fails the handle's pending promises now; the actual fd/buffer close is deferred until the in-flight count drops to 0.
                cancel(handle)
                handle.tls match
                    case Present(engine) =>
                        // TLS close: emit this side's close_notify (RFC 8446 6.1 / RFC 5246 7.2.1) and submit the alert as a TLS send SQE BEFORE the fd
                        // is closed. The send SQE counts as an in-flight op, so closeNow (which runs only once in-flight reaches 0) waits for the alert's
                        // CQE to reap before closing the fd: the close_notify reaches the peer ahead of the FIN, no thread blocked, no wait for the peer's.
                        if handle.beginWrite() then
                            try shutdownTls(handle, engine)
                            finally discard(handle.endWrite())
                            end try
                        end if
                        registerDeferredClose(handle)
                    case Absent =>
                        registerDeferredClose(handle)
                end match
            }
        end if
    end closeHandle

    /** Register the handle for the deferred close, or close now when nothing is in flight. Factored so the plaintext close and the post-
      * close_notify TLS close share the same close-vs-reap race handling.
      */
    private def registerDeferredClose(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        val inFlightNow = Maybe(inFlight.get(handle.id.packed)).getOrElse(0L)
        if inFlightNow <= 0L then closeNow(handle)
        else
            // Hold the handle's guard open for the whole deferred window, BEFORE attempting the fd claim below: `closeAfterDrain` is this
            // driver's own private bookkeeping, invisible to PosixHandle's guard, so without this hold a concurrent, unrelated
            // PosixHandle.close caller (any release path that reaches it while this driver still owes a deferred close, win or lose the
            // fd claim itself) would
            // see zero active holders and run freeResources immediately -- before closeNow below ever installs the real fdCloseSink credit --
            // permanently stranding it (the credit sits unconsumed, the real close(fd) never runs, a CLOSE_WAIT leak). Acquiring before the
            // claim (rather than after) closes the gap for every interleaving of the two independent one-shot claims, not just the common
            // one: whichever of {this driver, the other closer} wins the raw fd claim, the credit it installs is only ever consumed once
            // this hold (or the other closer's own, if it holds one) is the LAST one released. See [[PosixHandle.beginDeferredClose]].
            if handle.beginDeferredClose() then
                // Force any in-flight recv to EOF so its CQE reaps and the deferred close runs even against an idle peer that sends nothing: a
                // recv SQE is kernel-owned and close(fd) alone does NOT complete it (io_uring holds its own reference to the file). shutdown the
                // READ half only (SHUT_RD) so a TLS close_notify or a queued raw send on the write half still flushes before closeNow sends the
                // FIN. Sockets only (readFd == writeFd); stdio (0/1) is process-owned and left untouched. Same mechanic the handshake-deadline
                // reap uses.
                //
                // Gated on winning claimFdClose: a racing transport-path closer can already have claimed this fd. A claim winner that closes
                // immediately (closeUnwiredHandle's connect-phase arm) may already have closed and recycled the fd number, so shutting it
                // down here would inject a spurious EOF into whatever the kernel handed the number back to; a claim winner that defers (the
                // failed-handshake and failed-upgrade releases install an fdCloseSink credit instead) has already shut the fd down itself.
                // Losing the claim proves another closer owns the fd's disposition either way, so the shutdown is safely skipped. Winning
                // proves the fd is still ours to shut down. markDeferredFdClose records the win so closeNow, which cannot re-win
                // claimFdClose (already spent here), still runs the real close(fd) once this handle's in-flight count drains.
                val claimedHere = handle.readFd == handle.writeFd && handle.claimFdClose()
                if claimedHere then
                    handle.markDeferredFdClose()
                    discard(sockets.shutdown(handle.readFd, PosixConstants.SHUT_RD))
                end if
                // Register the deferred close, then re-check: if the reap loop drained the count between the read above and this put, it already
                // ran (and removed) nothing, so claim the registration back and close here. This closes the close-vs-reap race either way.
                // putIfAbsent, not put: a SECOND closeHandle for this same handle (production pair: an onFatal closeHandle plus the
                // ReadPump-teardown closeHandle, both drained in one pass while an unrelated send SQE or the STARTTLS upgrade-window recv
                // keeps inFlight above zero) reaches this branch with the handle ALREADY registered. Stacking a second beginDeferredClose
                // hold behind the single map entry breaks the one-hold-per-entry pairing: the eventual discharge runs exactly one
                // endDeferredClose, the guard never returns to zero holders, freeResources never runs, and the installed fdCloseSink credit
                // strands so close(fd) never runs (the fd leaks with its buffers/engine). Release the redundant hold here to keep the pairing
                // 1:1; the existing registration's own discharge (decrementInFlight, or teardownRing's sweep) still covers this close request.
                if closeAfterDrain.putIfAbsent(handle.id.packed, handle) ne null then
                    discard(handle.endDeferredClose())
                else if Maybe(inFlight.get(handle.id.packed)).getOrElse(0L) <= 0L then
                    Maybe(closeAfterDrain.remove(handle.id.packed)).foreach(dischargeDeferredClose)
                end if
            else
                // A close was already requested by some other, unrelated closer before we could take the hold above: freeResources has
                // either already run, or will run without ever waiting on a closeAfterDrain entry from us, so registering one here would
                // just strand a credit nobody consumes. Fall back to the immediate path; closeNow's own claim/consume checks are safe
                // no-ops if the other closer already handled the fd, and PosixHandle.close is a safe no-op if it already ran.
                closeNow(handle)
        end if
    end registerDeferredClose

    /** Discharge one handle out of [[closeAfterDrain]] (its in-flight count has reached 0): run the real close (installing the credit) and
      * then release the guard hold [[registerDeferredClose]] took for the deferred window. `closeNow` runs first so the credit exists
      * before the hold's release can consume it; every [[closeAfterDrain]] entry was only ever put there after a successful
      * [[PosixHandle.beginDeferredClose]], so the matching [[PosixHandle.endDeferredClose]] here is always released, never underflowed.
      */
    private def dischargeDeferredClose(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        closeNow(handle)
        discard(handle.endDeferredClose())
    end dischargeDeferredClose

    /** Emit this side's TLS close_notify and submit it as a TLS send SQE (best-effort, one-directional). FIFO-worker-only (called from the
      * closeHandle engine op under the write guard). Runs one [[TlsEngine.shutdownStep]] to emit this side's close_notify, drains the produced
      * alert onto the handle's pending-ciphertext tail ([[appendPending]]), then [[flushTls]] submits a send SQE for it. It does NOT wait for
      * the peer's close_notify; a `shutdownStep` fatal (`-2`) is ignored and the close proceeds.
      */
    private def shutdownTls(handle: PosixHandle, engine: TlsEngine)(using AllowUnsafe, Frame): Unit =
        if engine.shutdownStep() != -2 then
            val drain = encryptDrainFor(handle)
            var more  = true
            while more do
                val n = engine.drainCiphertext(drain, handle.readBufferSize)
                if n <= 0 then more = false
                else appendPending(handle, drain, n)
            end while
            flushTls(handle)
        end if
    end shutdownTls

    /** Free the handle's resources now: the socket fd (sockets set readFd == writeFd; stdio leaves 0/1 untouched) and its read buffer. Called
      * only when nothing is in flight for the handle.
      */
    private def closeNow(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // PosixHandle.close (freeResources) clears the raw pending tail; the close-after-drain handshake already ensured no raw send is in flight.
        // Drop the handle from the SQ-full re-flush set so a closed handle is never re-flushed.
        discard(stalledRaw.remove(handle))
        // Drop any recv/accept/connect this handle parked on a full SQ: a parked op left in stalledSubmits is re-armed by the next
        // reArmStalledSubmits turn, which would submit an SQE on this now-closed fd (EBADF, or worse a recv on the fd's recycled successor). Fail
        // each parked op's promise Closed and release its pinned buffers before the fd close. closeNow runs on the reap carrier, the sole producer
        // and consumer of stalledSubmits, so iterating + removing here is race-free without a lock. Identity is by HandleId, so a stale op from an
        // earlier handle that reused this fd number is left for its own close.
        val parkedClosed = Closed(label, summon[Frame], "connection closed")
        val stalledIt    = stalledSubmits.iterator()
        while stalledIt.hasNext do
            val op = stalledIt.next()
            if op.handle.id.packed == handle.id.packed then
                stalledIt.remove()
                op.failPromise(parkedClosed)
                op.releaseBuffer()
            end if
        end while
        // Drop the handle's send-EINTR retry count so the map does not retain an entry for a closed handle.
        discard(sendEintrRetries.remove(handle.id.packed))
        // Close the socket fd: io_uring has no prep_close and PosixHandle.close frees only the buffers, so the fd is closed here through
        // SocketBindings (mirroring the poller's closeHandle). The real close(fd) is deferred to freeResources (via fdCloseSink) instead of
        // running here directly, so a synchronous write still mid-flight under a live beginWrite hold on this fd is never exposed to a fd
        // number the kernel has already recycled to an unrelated connection (the read side is already covered: closeNow only runs once
        // inFlight has drained, and the read CQE it drained is the only kernel-owned reader of this fd). One-shot via claimFdClose so a
        // racing transport-path close (a connect / STARTTLS failure, or the handshake-deadline reap) does not double-close a possibly-recycled
        // fd. sockets set readFd == writeFd; stdio (0/1) is process-owned.
        // A losing claimFdClose here does not necessarily mean someone else already closed the fd: registerDeferredClose may have won it first
        // ITSELF, purely to guard the deferred shutdown(SHUT_RD) above, in which case the real close(fd) is still THIS call's job -- consume its
        // markDeferredFdClose credit instead. Exactly one of the two branches ever runs (claimFdClose and consumeDeferredFdClose are disjoint
        // one-shot claims: the latter is only ever set by a caller that just won the former), so the fd is still closed exactly once either way.
        // The claimFdClose branch shuts the fd down fully (SHUT_RDWR) here, since (unlike registerDeferredClose's SHUT_RD) nothing shut it down
        // yet; the consumeDeferredFdClose branch needs no extra shutdown (registerDeferredClose's SHUT_RD already ran when the credit was made).
        if handle.readFd == handle.writeFd then
            if handle.claimFdClose() then
                discard(sockets.shutdown(handle.readFd, PosixConstants.SHUT_RDWR))
                handle.fdCloseSink = Present(() => discard(takeNow(sockets.close(handle.readFd))))
            else if handle.consumeDeferredFdClose() then
                handle.fdCloseSink = Present(() => discard(takeNow(sockets.close(handle.readFd))))
        end if
        PosixHandle.close(handle)
        // The requested close has completed; drop it from the teardown force-close set.
        discard(pendingCloses.remove(handle.id.packed))
    end closeNow

    /** Read the inline result of an `@Ffi.blocking` fiber that completes synchronously on JVM/Native (the `SocketBindings.close` used by the
      * deferred close), without parking. Mirrors [[PollerIoDriver.takeNow]].
      */
    private def takeNow[A](fiber: Fiber.Unsafe[A, Any])(using AllowUnsafe, Frame): Maybe[A] =
        if fiber.done() then
            fiber.poll() match
                case Present(Result.Success(v)) => Present(v.eval)
                case _                          => Absent
        else Absent

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            // Unregister this driver's Diagnostics dumper/probe now: registration happened in start() (if it ran) before the reap
            // carrier was spawned, so by the time any carrier can observe closedFlag=true the field is either already set or was never
            // going to be (start() never called). Closing here, rather than at the reap loop's later ring teardown, is what keeps a
            // per-test driver's entry from outliving its own close() call in the process-global registry.
            val reg = diagRegistration
            if reg ne null then reg.close()
            // Wake the reap loop so a wait parked INDEFINITELY on the wake observes closedFlag and tears the ring down promptly; without this an
            // indefinite park would only end on an unrelated CQE. The eventfd write is unconditional, so the close wake always lands. Harmless when
            // no reap loop is parked (never started): the eventfd is closed by teardownRing below, guarded against this write.
            wakeReapLoop()
            // Single-owner teardown: the reap carrier may be parked inside kyo_uring_submit_and_wait_timeout, holding the ring and cqePtr
            // segments (indefinitely when wake-armed, or for up to ReapTimeoutNs during the re-arm window). Freeing them here, on a different
            // carrier, while that wait is in flight is a use-after-free
            // ("Session is acquired by 1 clients" at cqePtr.close on JVM; SIGSEGV in kyo_uring_get_sqe on Native). So when a reap carrier
            // exists, only SIGNAL: it observes closedFlag, exits its loop after the current wait returns, and tears the ring down on its own
            // carrier where no ring op is in flight. When the loop was never started (a driver built but closed before start()), no wait is in
            // flight and there is no reap carrier, so tear down here. The teardownDone CAS keeps it exactly-once across all paths. Even with no reap carrier the
            // engine-FIFO worker may still be running a queued op that calls get_sqe (e.g. a connection's close_notify emit submitted just
            // before this close), so go through tryTeardown rather than freeing the ring out from under it.
            if !started.get() then
                reapExited.set(true)
                // Drain anything already queued (each op fails through its driver-closed rejection; a never-started loop can never run
                // them) before the teardown attempt, so a pre-start arming op cannot strand its promise or block the ring teardown.
                drainAfterReapExit()
            end if
        end if
    end close

    /** Free the ring exactly once, but ONLY when no carrier can still touch it: the reap loop has exited (reapExited) AND the engine-FIFO
      * worker is idle (no op in flight or queued that would call get_sqe). Called from the reap-loop exit, from the FIFO worker when it drains
      * to idle while closing, and from close() when no reap loop ran; whichever observes both conditions last performs the teardown. The
      * teardownDone CAS inside teardownRing keeps it exactly-once across those callers.
      */
    private def tryTeardown()(using AllowUnsafe, Frame): Unit =
        if closedFlag.get() && reapExited.get() && engineQueue.isEmpty then teardownRing()

    // Serializes post-reap-exit engine-queue drains (see drainAfterReapExit): once the reap carrier has exited, late-submitted ops are
    // drained by the submitting carriers themselves, and this claim keeps those drains AND the ring teardown mutually exclusive so the
    // engine FIFO's at-most-one-op-at-a-time guarantee holds through the terminal window, and so teardownRing never frees ring/handle
    // state while another carrier is mid-execution of a drained op.
    // Unsafe: body field created with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the caller's
    // AllowUnsafe (mirrors IoDriverPool.closedFlag).
    private val lateDrainClaim = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    // The carrier thread currently holding lateDrainClaim, or null. Read by submitEngineOp's offer-then-recheck to detect a RE-ENTRANT
    // offer: a drained op (a closeHandle thunk whose freeResources routes engineFreeSink through submitEngineOp while ringExited is still
    // false) enqueues a follow-up op from INSIDE drainEngineOps, on the very carrier that holds the claim. That follow-up is already
    // picked up by the ongoing tail-recursive drainEngineOps in the same pass, so re-entering drainAfterReapExit for it would only spin
    // forever on the claim this carrier already holds. Skipping the recheck on the owning carrier avoids that self-deadlock.
    @volatile private var lateDrainOwner: Thread = null

    /** Drain the engine queue once the reap carrier can no longer do it (the reap loop exited, or `close()` ran with no loop ever started),
      * then attempt the ring teardown, both under [[lateDrainClaim]]. This is the recheck half of [[submitEngineOp]]'s offer-then-recheck:
      * an op that lands in [[engineQueue]] after the reap-loop exit's final drain has no executor left, so its promise never completes (a
      * connect arm, for one, parks until the transport's connect deadline fails it 30 seconds later) and the never-empty queue blocks the
      * ring teardown forever (the kernel ring and the wake eventfd leak). Every op drained here runs with `closedFlag` set, so the arming
      * helpers fail their promises through their driver-closed rejection without touching the ring, exactly as the reap-loop exit's own
      * final drain runs them.
      *
      * The [[lateDrainClaim]] CAS keeps post-exit drains AND the teardown mutually exclusive. The teardown attempt runs WHILE STILL
      * HOLDING the claim, with the queue-empty recheck under the claim: `drainEngineOps` polls an op off the queue before running it, so a
      * currently-executing op is invisible to `engineQueue.isEmpty`. Were the teardown outside the claim, `teardownRing` could clear
      * `inFlight` / free a handle's engine and buffers while another carrier's drained op still reads them (a kernel UAF against a
      * recv SQE whose hold is only released by `io_uring_queue_exit`, and a SIGSEGV against a freed engine). Holding the claim across both
      * drain and teardown makes op execution and teardown non-overlapping, so a drained op runs either wholly before teardown (with
      * bookkeeping still truthful) or wholly after (`ringExited == true`, where the close paths free inline and are UAF-safe by the ring
      * doctrine).
      *
      * A carrier that loses the claim spins while the queue is non-empty (its own offered op is still pending, so it must drain it), and
      * returns once the queue is observably empty (the holder has already polled every offered op and will run the emptiness recheck and
      * teardown under its claim). The spin is bounded: post-close producers are finite and each op is a short rejection or inline-close path.
      */
    private def drainAfterReapExit()(using AllowUnsafe, Frame): Unit =
        var settled = false
        while !settled do
            if lateDrainClaim.compareAndSet(false, true) then
                lateDrainOwner = Thread.currentThread()
                try
                    drainEngineOps()
                    // Recheck emptiness UNDER the claim: an op is either already drained above, or still queued (loop again to drain it),
                    // so the teardown never fires while a drained op is mid-execution on another carrier.
                    if engineQueue.isEmpty then
                        tryTeardown()
                        // Recheck AGAIN after the teardown: teardownRing fails every pending promise inline, and a Read/Connect
                        // promise-completion callback can synchronously route a Connection close back into closeHandle -> submitEngineOp
                        // on THIS (owner) carrier, whose recheck is skipped by the owner guard. Draining that late op needs one more
                        // claimed pass (post-teardown execution is the safe inline-close path); the re-run tryTeardown is a teardownDone
                        // no-op. Bounded: those callbacks are finite, so the queue empties and settles.
                        settled = engineQueue.isEmpty
                    end if
                finally
                    lateDrainOwner = null
                    lateDrainClaim.set(false)
                end try
            else if engineQueue.isEmpty then
                // The claim holder has already polled every offered op (that is why the queue reads empty) and will run the emptiness
                // recheck and teardown under its claim; nothing is left for this carrier to drain.
                settled = true
            end if
        end while
    end drainAfterReapExit

    /** Tear the ring down exactly once: fail every pending op's promise, release the per-write buffers still held, drop the bookkeeping,
      * exit the kernel ring, and free the cqePtr scratch. Reached only through `tryTeardown`, which fires it from whichever of the reap-loop
      * exit, the engine-FIFO worker draining to idle, or `close()` observes last that no carrier can still touch the ring. The `teardownDone`
      * CAS makes it exactly-once across those callers, so the kernel ring and the cqePtr/per-write buffers are freed once and only after the
      * last carrier has left every ring op.
      */
    private def teardownRing()(using AllowUnsafe, Frame): Unit =
        if teardownDone.compareAndSet(false, true) then
            val closed = Closed(label, summon[Frame], "driver closed")
            pending.forEach((_, op) => op.failPromise(closed))
            // The ring teardown reclaims any kernel-owned buffers; release every per-write buffer we still hold so none leaks.
            pending.forEach((_, op) => op.releaseBuffer())
            // Close any accepted fd whose Accept CQE the reap loop never reaped (a peer connected in the window between the loop's final
            // drainReady and here): io_uring_queue_exit below would abandon that kernel-created fd, leaking an established handler-less
            // connection. Runs while `pending` still resolves the CQE's key to its Accept op; see closeOrphanedAcceptCqes.
            closeOrphanedAcceptCqes()
            pending.clear()
            inFlight.clear()
            stalledRaw.clear()
            // Fail any recv/accept/connect that parked on a full SQ and was never re-armed: the reap carrier has exited (a tryTeardown precondition),
            // so no batch will re-arm it and its promise would otherwise hang. Release each parked op's pinned buffers too (a parked Accept still
            // holds its addr/len placeholders). The deque is quiescent here (its only producers, the submit helpers and reArmStalledSubmits, run on
            // the now-exited reap carrier), so draining it from the teardown carrier is race-free.
            stalledSubmits.forEach { op =>
                op.failPromise(closed)
                op.releaseBuffer()
            }
            stalledSubmits.clear()
            sendEintrRetries.clear()
            // Close the wake eventfd before exiting the ring, guarded so it is closed EXACTLY once and only when no in-flight wakeReapLoop write
            // still holds it (mirrors the poller's closeWakeGuarded: prevents the eventfd's fd number being closed under an in-flight write and
            // recycled into another driver's socket). A no-op when there is no wake eventfd.
            closeWakeGuarded()
            uring.io_uring_queue_exit(ring)
            cqePtr.close()
            // The true completion signal, set only now that io_uring_queue_exit has released the kernel's hold on every recv
            // buffer: closeHandle's own inline self-close and engineFreeSink's inline-vs-submit decision gate on this flag, not
            // `teardownDone` (which was already true from this method's own CAS above, well before this point).
            ringExited.set(true)
            // Force-complete every requested-but-now-orphaned close still registered in pendingCloses. Snapshotting the KEYS (not
            // iterating pendingCloses directly while removing) avoids relying on ConcurrentHashMap's weak-consistency guarantee under
            // concurrent structural modification; `remove` per key is the single-discharger claim, exactly the same pattern
            // closeHandle's own inline recheck above uses, so a handle whose closeHandle call races THIS sweep (both observing
            // `ringExited == true`) is discharged by exactly one of the two, never both. This is what makes it safe to run this sweep
            // over the LIVE map instead of a pre-exit snapshot: closeNow touches shared, non-thread-safe collections (`stalledSubmits`),
            // so two concurrent closeNow calls for different handles would otherwise race on those shared structures even though each is
            // individually idempotent per-handle.
            //
            // Every entry here is close-REQUESTED (closeHandle ran), never a live or STARTTLS-detached connection, so reclaiming its fd
            // cannot disturb an in-use socket, and closeNow's own claimFdClose makes a racing close idempotent.
            val orphanedKeys = new java.util.ArrayList[Long](pendingCloses.keySet())
            orphanedKeys.forEach(key => Maybe(pendingCloses.remove(key)).foreach(closeNow))
            // Force-complete every handle still waiting in closeAfterDrain: registerDeferredClose deferred its real close pending
            // in-flight drainage, but the in-flight count can never reach zero for an op the kernel never completes and nothing else
            // ever cancels (a connect SQE against an unresponsive peer is the concrete case: cancel() only fails the local promise, it
            // does not submit an IORING_OP_ASYNC_CANCEL, and shutdown(2) has no effect on a not-yet-established socket). Before the real
            // close(fd) was routed through this same deferred credit, this was a latent gap (the engine/buffer leaked silently, invisible to
            // LeakCheck, which counts fds, not JVM objects); now that it is ALSO routed through fdCloseSink (the deferred credit), leaving a
            // closeAfterDrain entry undischarged here would leak the fd itself. io_uring_queue_exit above has already reclaimed the
            // kernel's hold on every buffer/SQE tied to this ring, so it is safe to discharge every remaining entry now, exactly like
            // the pendingCloses sweep above (same snapshot-then-remove idiom, for the same shared-mutable-collection race reason).
            // dischargeDeferredClose (not bare closeNow) also releases the guard hold registerDeferredClose took for each of these
            // entries, so freeResources runs here even if nothing else was ever going to call PosixHandle.close for a handle that was
            // ONLY ever touched by the now-abandoned connect/handshake.
            val drainedKeys = new java.util.ArrayList[Long](closeAfterDrain.keySet())
            drainedKeys.forEach(key => Maybe(closeAfterDrain.remove(key)).foreach(dischargeDeferredClose))
            // Mirrors the poller's own post-teardown gate: a pendingCloses entry surviving this point is a genuine invariant violation -- nothing
            // will ever look at it again (the ring is gone and closeHandle's own recheck above already covers every legitimately timed
            // arrival). Reported directly to Diagnostics.reportViolation so it fails the kyo-test run even though this driver is closed
            // by then (StrandedOpCheck's probe-based classifier exempts a closed component by design).
            if !pendingCloses.isEmpty() then
                kyo.internal.Diagnostics.reportViolation(
                    s"$label: ${pendingCloses.size()} close obligation(s) survived the ring teardown's post-exit sweep " +
                        "(stranded-close class regression)"
                )
            end if
        end if
    end teardownRing

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Mark BEFORE scheduling the loop: a close() racing start observes started=true and defers teardown to the reap loop, which (once
        // it runs) sees closedFlag set, runs zero cycles, and tears the ring down itself. started.set happens-before the schedule.
        started.set(true)
        // Every driver registers a diagnostics snapshot of its reap loop, not just the process-shared singleton's: the stranded-op
        // post-suite gate (kyo-test's runner) needs a probe for every driver a suite builds, and most of kyo-net's own suites build
        // their own owned transport rather than using the shared one. Held in diagRegistration and closed from close() so a
        // per-test driver's entry does not accumulate in the process-global registry for the rest of the run.
        //
        // The registered name carries a `processSharedTransport` marker for a by-design process-lifetime transport (the shared singleton
        // or the default HTTP client's own transport), never closed, so it legitimately parks forever with a pending op on a kept-alive
        // connection: the stranded-op gate matches this name the same way LeakCheck's fiber-leak allowlist already does
        // (LeakCheck.defaultAllowlist), so a driver that is SUPPOSED to look parked-with-pending-work forever is exempted by the same
        // convention, not a second one.
        // System.identityHashCode: diagnostic instance id in the driver name; fully qualified so kyo.System does not shadow it.
        val diagName =
            "IoUringDriver@" + java.lang.System.identityHashCode(this) +
                (if kyo.net.internal.ProcessSharedTransport.isBuilding then " processSharedTransport" else "")
        diagRegistration = kyo.internal.Diagnostics.register(diagName)(
            dump = () =>
                val pend = new StringBuilder
                pending.forEach((k, v) =>
                    discard(
                        pend.append(k).append("->").append(v.getClass.getSimpleName)
                            .append("(fd=").append(v.handle.readFd).append(",id=").append(v.handle.id)
                            .append(if v.handle.connectTarget.isDefined then ",client) " else ",server) ")
                    )
                )
                val infl = new StringBuilder
                inFlight.forEach((k, v) => discard(infl.append('h').append(k).append('=').append(v).append(' ')))
                val cad = new StringBuilder
                closeAfterDrain.forEach((k, _) => discard(cad.append(k).append(' ')))
                s"closed=${closedFlag.get()} reapExited=${reapExited.get()} ringExited=${ringExited.get()} reapCycles=$diagReapCycles " +
                    s"pending(${pending.size})=[$pend] inFlight=[$infl] closeAfterDrain(${closeAfterDrain.size})=[$cad] " +
                    s"pendingCloses=${pendingCloses.size} stalledRaw=${stalledRaw.size}"
            ,
            probe = () =>
                kyo.internal.Diagnostics.Probe(
                    closed = closedFlag.get(),
                    cycles = diagReapCycles,
                    // pendingCloses (mirrors the poller): a live driver stuck with an undischarged fd-close obligation and no cycle
                    // progress is the same lost-wakeup class this probe already catches for `pending`. This is a LIVE-driver signal only:
                    // the authoritative closed-driver leak check is teardownRing's own post-exit sweep, reported directly to
                    // Diagnostics.reportViolation (see teardownRing's doc).
                    pending = !pending.isEmpty || !pendingCloses.isEmpty
                )
        )
        // The reap loop runs on scheduler carriers, one turn per activation, never on a thread this driver owns. Running ONE turn and then
        // re-arming onto a different carrier returns the carrier to run the completions the turn just produced; a `while` on a carrier has no
        // fiber safepoints, so the scheduler could not preempt it, and a continuation completed inline could strand on the pinned carrier.
        //
        // The park inside a turn is unchanged, including its conditions: indefinite only when the wake multishot is armed, no op is stalled on a
        // full SQ, and NODROP is confirmed; bounded otherwise. Parking a carrier is legitimate because this driver owns an unconditional wake
        // (submitEngineOp writes the wake eventfd on every submission), which is what returns the parked carrier.
        val donePromise = Promise.Unsafe.init[Unit, Any]()
        Scheduler.get.schedule(newReapTask(donePromise, kyo.net.internal.ProcessSharedTransport.isBuilding))
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed Promise.Unsafe[Unit, Any], even though both erase to the same runtime object; the alias is transparent only
        // inside kyo.Fiber's own defining scope, so exposing donePromise as the locked IoDriver.start return needs this erased-boundary cast.
        // Safe: the promise completes only with the Unit-success/panic values the reap chain sets below.
        donePromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    private val ReapTimeoutNs =
        100_000_000L // 100ms bounded fallback (only used when the wake eventfd is unavailable); otherwise the wait is indefinite

    private val WakeKey = IoUringDriver.WakeKey

    /** Arm (or re-arm) the multishot `IORING_OP_POLL_ADD` watch on the wake eventfd. Reap-carrier-only (the SQ is single-producer). On SQ-full
      * (`get_sqe` Absent) it leaves `wakePollArmed` false; the reap loop then parks with the bounded fallback (never indefinitely without an armed
      * wake) and retries the arm on a later turn.
      */
    private def armWake()(using AllowUnsafe): Unit =
        if wakeFd >= 0 && !wakePollArmed then
            uring.kyo_uring_get_sqe(ring) match
                case Present(sqe) =>
                    uring.kyo_uring_prep_poll_multishot(sqe, wakeFd, PosixConstants.POLLIN)
                    uring.kyo_uring_sqe_set_data64(sqe, WakeKey)
                    wakePollArmed = true
                case Absent => () // SQ full: retried next turn; the loop parks bounded until then
    end armWake

    /** Wake a parked reap loop so it drains the engine queue (or observes `closedFlag`). Called from any carrier after enqueuing an engine op and
      * from `close()`. `eventfd_write` is atomic and touches no SQ, so it is safe from any carrier. The write is UNCONDITIONAL (no coalescing
      * flag): the eventfd is a counter, so concurrent writes coalesce in the kernel (the reap loop's one drain clears the whole count), and an
      * unconditional write can never be lost the way a compare-and-set gate could suppress one at the instant the loop is parking (the missed-wake
      * that hung a cross-carrier connect arming under load). It mirrors NIO's unconditional `selector.wakeup()`. The wake guard makes the write
      * mutually exclusive with the teardown close of the eventfd, so the write never lands on a closed-and-recycled fd. Redundant writes while the
      * reap carrier is already running cost only a coalesced counter increment and at most one spurious early return from the next park.
      */
    private def wakeReapLoop()(using AllowUnsafe): Unit =
        if wakeFd >= 0 && acquireWake() then
            try discard(uring.kyo_uring_eventfd_write(wakeFd))
            finally releaseWake()
    end wakeReapLoop

    /** Acquire the wake fd for one [[wakeReapLoop]] write: register an in-flight holder so [[closeWakeGuarded]] cannot close the eventfd while a
      * write touches it. Returns false once the closing bit is set (the caller then skips the write). Mirrors [[PosixHandle.acquire]] and the
      * poller's `acquireWake`.
      */
    private def acquireWake()(using AllowUnsafe): Boolean =
        var result = false
        var done   = false
        while !done do
            val g = wakeGuard.get()
            if (g & IoUringDriver.WakeClosingBit) != 0 then done = true
            else if wakeGuard.compareAndSet(g, g + 1) then
                result = true
                done = true
            end if
        end while
        result
    end acquireWake

    /** Release the wake fd after one write. If this is the last in-flight write AND the close path has set the closing bit, perform the deferred
      * eventfd close exactly once (last-releaser-frees handoff). Mirrors the poller's `releaseWake`.
      */
    private def releaseWake()(using AllowUnsafe): Unit =
        var done = false
        while !done do
            val g       = wakeGuard.get()
            val holders = g & IoUringDriver.WakeHolderMask
            val closing = (g & IoUringDriver.WakeClosingBit) != 0
            if holders == 1 && closing then
                if wakeGuard.compareAndSet(g, IoUringDriver.WakeClosed) then
                    discard(uring.kyo_uring_eventfd_close(wakeFd))
                    done = true
            else if wakeGuard.compareAndSet(g, g - 1) then done = true
            end if
        end while
    end releaseWake

    /** Close the wake eventfd exactly once, coordinated against any in-flight [[wakeReapLoop]] write via the guard: set the closing bit so no new
      * write acquires; close now if no write holds the fd, else the last in-flight write's [[releaseWake]] closes it. Idempotent once terminal.
      * Called only from [[teardownRing]] (the single ring-teardown owner). A no-op when there is no wake eventfd.
      */
    private def closeWakeGuarded()(using AllowUnsafe): Unit =
        if wakeFd >= 0 then
            var done = false
            while !done do
                val g = wakeGuard.get()
                if g == IoUringDriver.WakeClosed then done = true
                else
                    val holders = g & IoUringDriver.WakeHolderMask
                    if holders == 0 then
                        if wakeGuard.compareAndSet(g, IoUringDriver.WakeClosed) then
                            discard(uring.kyo_uring_eventfd_close(wakeFd))
                            done = true
                    else if wakeGuard.compareAndSet(g, g | IoUringDriver.WakeClosingBit) then done = true
                    end if
                end if
            end while
    end closeWakeGuarded

    // IORING_CQE_F_MORE = (1U << 1) = 2. When set on a CQE, the submission is still live and will fire more CQEs (multishot lifecycle).
    private val CqeFMore = 2

    /** The ONE task this driver reuses for every reap turn, built in `start()` so it captures `AllowUnsafe` and `Frame`, which `Task.run` does
      * not supply, and so no task or closure is allocated per turn.
      */
    private def newReapTask(donePromise: Promise.Unsafe[Unit, Any], processShared: Boolean)(using AllowUnsafe, Frame): Task =
        new Task:
            def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
                if processShared then processSharedTransportCycle(this, donePromise)
                else runCycle(this, donePromise)

    /** Named frame marking a turn of a process-lifetime transport, whose idle parked carrier is expected to sit armed forever. The end-of-run
      * stranded-op and fiber-leak gates allowlist it by this name, so it must stay on the call path of every such turn.
      */
    private def processSharedTransportCycle(task: Task, donePromise: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Task.Result =
        runCycle(task, donePromise)

    /** One reap turn: arm the wake, drain cross-carrier engine ops, flush the SQ, then park for a CQE.
      *
      * The order is load-bearing and unchanged from the loop this replaces. `drainEngineOps` runs as its own step before `flushSubmits`, which
      * only submits: the drain is the sole producer of submission-queue entries, and `get_sqe` is not thread-safe, so it must run where exactly
      * one activation is live. Flushing then puts every prepared SQE on the kernel BEFORE the park, so the park is never entered with an
      * unsubmitted op outstanding.
      */
    private def runCycle(task: Task, donePromise: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Task.Result =
        try
            if closedFlag.get() then terminal(donePromise, Result.succeed(()))
            else
                diagReapCycles += 1L
                armWake()
                // Single-producer SQ: prepare every queued SQE (reads, writes, connects, accepts, TLS engine ops) on THIS activation, then fuse
                // submit and wait in one enter. Because the fused enter both submits and reads the SQ tail, no other carrier may touch the SQ;
                // the engine queue is the cross-carrier handoff and this drain is its ONLY consumer, so omitting it leaves every submitted
                // operation unprepared and the driver silently does nothing.
                drainEngineOps()
                // Submit every prepared SQE to the kernel BEFORE the park. The fused wait below also submits, but its wrapper discards the
                // submit count and can leave SQEs unsubmitted under load, with no retry; those then sit in the ring while the loop parks for
                // CQEs that never come. An explicit flush guarantees submission, so by the time we park every op is genuinely in flight.
                flushSubmits()
                // Park INDEFINITELY only when all three conditions hold: the wake multishot is armed, no op is stalled on a full SQ (a stalled
                // op has no pending CQE to re-drive it, so it needs a bounded turn to reach reArmStalled), and IORING_FEAT_NODROP is confirmed
                // (the kernel never drops the wake CQE). Bounded otherwise, so a missing wake can never hang the chain.
                val hasStalled = !stalledRaw.isEmpty || !stalledSubmits.isEmpty
                val timeout    = if wakePollArmed && !hasStalled && nodropAvailable then Long.MaxValue else ReapTimeoutNs
                // Hand off everything this carrier is holding BEFORE parking in the wait below. The park pins this worker for the whole
                // duration of the wait, and a task sitting in its local queue cannot run while it is pinned: nothing else frees a parked
                // worker's queue, since a steal is opportunistic and preemption is deliberately withheld from a worker whose task is
                // parked in a syscall rather than burning a time slice. That deadlocks outright when the queued task is what would
                // produce the event this wait is about to block on. flush() re-schedules those tasks onto other workers (it excludes
                // this one) and is a no-op off a worker thread. It cannot close the window on its own, since a task can still land
                // here after the flush and before the wait returns; Worker.checkAvailability drains that residue once the blocking
                // monitor flags this worker.
                Scheduler.get.flush()
                val waitFiber = uring.kyo_uring_submit_and_wait_timeout(ring, cqePtr, timeout)
                val self      = task
                waitFiber.poll() match
                    case Present(Result.Success(w)) => afterWait(self, donePromise, w.eval)
                    case Present(Result.Failure(e)) =>
                        // Loud: a silent exit tears down every connection on the ring with no evidence, making a dead ring
                        // indistinguishable from "nothing happened" in post-hoc log analysis.
                        Log.live.unsafe.error(s"$label reap wait failed: $e; tearing the ring down")
                        terminal(donePromise, Result.succeed(()))
                    case Present(Result.Panic(e)) =>
                        Log.live.unsafe.error(s"$label reap wait panicked; tearing the ring down", e)
                        terminal(donePromise, Result.succeed(()))
                    case Absent =>
                        // Genuinely pending: the ring and cqePtr are owned by the in-flight enter, so nothing here may reap, re-arm, or tear
                        // down. Continue from the completion instead, which produces exactly one successor either way.
                        waitFiber.onComplete {
                            case Result.Success(w) =>
                                try afterWait(self, donePromise, w.eval)
                                catch
                                    case t: Throwable =>
                                        if !closedFlag.get() then Log.live.unsafe.error(s"$label reap turn crashed", t)
                                        terminal(donePromise, Result.panic(t))
                            case other =>
                                Log.live.unsafe.error(s"$label reap wait failed: $other; tearing the ring down")
                                terminal(donePromise, Result.succeed(()))
                        }
                end match
            end if
            Task.Done
        catch
            // Containment is mandatory, not defensive: a Throwable escaping `run` goes to the worker's uncaught handler, which returns Done, and
            // the chain is simply gone, every pending op hanging and the ring, cqePtr and wake eventfd leaked. Routing it to the terminal exit
            // is what makes a crashed turn release the ring.
            case t: Throwable =>
                if !closedFlag.get() then Log.live.unsafe.error(s"$label reap turn crashed", t)
                terminal(donePromise, Result.panic(t))
                Task.Done
        end try
    end runCycle

    /** The post-wait half of one turn, shared by the inline and pending paths so there is exactly one successor either way.
      *
      * The rc taxonomy is unchanged: 0 means CQEs to drain; a transient -EINTR/-EAGAIN/-EBUSY/-ENOMEM is a reap-side park to retry, classified
      * benign so it never tears down the ring and every connection on it; anything else is a genuine ring fault that ends the chain.
      */
    private def afterWait(task: Task, donePromise: Promise.Unsafe[Unit, Any], rc: Int)(using AllowUnsafe, Frame): Unit =
        if rc != 0 && !reapRcContinues(rc) then
            Log.live.unsafe.error(s"$label reap wait returned fatal rc=$rc; tearing the ring down")
            terminal(donePromise, Result.succeed(()))
        else
            if rc == 0 then
                drainReady(cqePtr.get(0))
                // Defensive re-arm: if drainReady saw a !more CQE (the kernel terminated the multishot on CQ-ring-full and cleared
                // wakePollArmed), queue a fresh arm now, closing the window where a stale flag would force a bounded park.
                armWake()
            end if
            // Every benign turn re-arms ops parked on a full SQ: the fused submit+wait freed the slots, and SQ space is freed by submitting,
            // not by reaping, so they must not wait for an unrelated CQE.
            reArmStalled()
            reArm(task)
        end if
    end afterWait

    /** Re-arm the next turn onto a DIFFERENT carrier, so the one that just ran the turn is free to run the completions it produced.
      *
      * The runtime reset plus a single unit mirrors a freshly submitted task: the wall-clock a turn spends parked is billed to the task, and
      * carrying it forward would make the chain look long-running and starve it against genuinely short tasks.
      */
    private def reArm(task: Task): Unit =
        task.resetRuntime()
        task.addRuntime(1)
        Scheduler.get.scheduleExcludingCurrent(task)
    end reArm

    /** The single exit for every path: an owner close, a fatal ring rc, a failed or panicked wait, and a crashed turn.
      *
      * This is the loop's own exit tail, relocated, in the same order and for the same reasons. closedFlag first, so any late submit rejects
      * before get_sqe (a fatal-rc or crash stop arrives without close() having set it). reapExited before the drain, so a concurrent
      * submitEngineOp either lands in this drain or runs its own under the shared claim. Then drainAfterReapExit, which fails every still-queued
      * op through the driver-closed rejection rather than dropping it, and fires the single-owner ring teardown.
      *
      * Routing the CRASH path through here is the fix for the leak IoDriverPool.awaitTornDown documents: previously a crashed loop completed the
      * done-promise with the ring, cqePtr and wake eventfd still held.
      */
    private def terminal(donePromise: Promise.Unsafe[Unit, Any], result: Result[Nothing, Unit < Any])(using AllowUnsafe, Frame): Unit =
        closedFlag.set(true)
        reapExited.set(true)
        // Unregister the Diagnostics probe here as well as in close(). This path SETS closedFlag rather than winning close()'s CAS, so after
        // any self-exit (a fatal rc, a failed wait, a crashed cycle) a later close() finds the CAS already lost and its own unregister never
        // runs, stranding this driver's entry in the process-global registry for the life of the JVM, the owner's close() included. The poller
        // arm leaves close() runnable and NIO routes its terminal through close(), so io_uring was the one driver that could strand an entry.
        // Safe to run twice: Registration.close() removes an entry from the registry, and removing an absent one is a no-op, so whichever of
        // this and close() runs second does nothing.
        val reg = diagRegistration
        if reg ne null then reg.close()
        drainAfterReapExit()
        donePromise.completeDiscard(result)
    end terminal

    /** Drain the CQE ring starting from `firstCqe`, completing each keyed promise in turn. Peeks additional CQEs via
      * `kyo_uring_peek_cqe` until the queue is empty. Uses the driver-field `cqePtr` (allocated once at construction,
      * safe because drainReady is called only from runCycle, which owns the reap carrier for the turn).
      */
    private def drainReady(firstCqe: Long)(using AllowUnsafe, Frame): Unit =
        var cqe = firstCqe
        while cqe != 0L do
            val key = uring.kyo_uring_cqe_get_data64(cqe)
            val res = uring.kyo_uring_cqe_res(cqe)
            // IORING_CQE_F_MORE (= 2) set means the submission is still live and will fire more CQEs.
            // Read flags before cqe_seen (which may invalidate the pointer on some kernel versions).
            val flags = uring.kyo_uring_cqe_get_flags(cqe)
            val more  = (flags & CqeFMore) != 0
            completeMultishot(key, res, more)
            uring.kyo_uring_cqe_seen(ring, cqe)
            cqe = if uring.kyo_uring_peek_cqe(ring, cqePtr) == 0 then cqePtr.get(0) else 0L
        end while
    end drainReady

    /** Reap every remaining ready CQE at ring teardown, closing any Accept completion's accepted fd. A successful accept CQE the reap loop
      * never got to (a peer connected in the microsecond window between the loop's final `drainReady` and `teardownRing`) materialized a
      * kernel-created fd that no bookkeeping tracks; `io_uring_queue_exit` then abandons it, leaking an established, handler-less socket
      * whose peer hangs on its first read (no FIN/RST ever reaches it). This mirrors `drainReady`'s reaped-case orphan close
      * (`PendingOp.Accept`, `res >= 0`, promise already failed): the accepted fd is the leak, so close it; the Accept op's own promise and
      * buffers are handled by `teardownRing`'s pending sweep. Called from `teardownRing` while `pending` still resolves the CQE's key, and
      * safe there because the reap loop has exited, so this teardown carrier is the sole CQE consumer (`cqePtr` and `ring` are still valid;
      * `io_uring_queue_exit` runs after this). Every other op type's CQE is consumed here too (its op is being torn down regardless).
      */
    private def closeOrphanedAcceptCqes()(using AllowUnsafe, Frame): Unit =
        var cqe = if uring.kyo_uring_peek_cqe(ring, cqePtr) == 0 then cqePtr.get(0) else 0L
        while cqe != 0L do
            val key = uring.kyo_uring_cqe_get_data64(cqe)
            val res = uring.kyo_uring_cqe_res(cqe)
            pending.get(key) match
                case _: PendingOp.Accept if res >= 0 => discard(takeNow(sockets.close(res)))
                case _                               => ()
            uring.kyo_uring_cqe_seen(ring, cqe)
            cqe = if uring.kyo_uring_peek_cqe(ring, cqePtr) == 0 then cqePtr.get(0) else 0L
        end while
    end closeOrphanedAcceptCqes

    /** Re-arm every operation that parked on a full submission queue: raw sends in [[stalledRaw]] and recv/accept/connect in [[stalledSubmits]].
      * Called once per reap turn from [[runCycle]] AFTER the fused submit-and-wait has submitted the turn's accumulated SQEs to the kernel and
      * freed the SQ ring slots, so a parked op sees space and re-arms. Runs on EVERY turn (whether a CQE arrived OR the wait timed out with none):
      * SQ space is freed by SUBMIT, not by reaping CQEs, so a parked op on an otherwise-idle ring (its only in-flight op stalled, no unrelated
      * traffic) must not wait for some other connection's CQE to be un-stranded. One attempt per turn, so SQ-full backpressures rather than
      * busy-spinning (a re-arm that hits SQ-full again re-parks for the next turn).
      */
    private def reArmStalled()(using AllowUnsafe, Frame): Unit =
        reflushStalledRaw()
        reArmStalledSubmits()
    end reArmStalled

    /** Re-flush every handle whose raw tail stalled on a full submission queue (drained from [[stalledRaw]]). Called once per reap turn via
      * [[reArmStalled]] after submit frees SQ slots. Each re-flush runs on the engine FIFO worker (the tail's single owner), so it never races a
      * concurrent append/flush; if the SQ is still full the re-flush re-adds the handle. Bounded by SQ-slot availability, never a spin. A handle
      * with no pending tail (already drained or closed) is a harmless no-op inside flushRaw.
      */
    private def reflushStalledRaw()(using AllowUnsafe, Frame): Unit =
        val it = stalledRaw.iterator()
        while it.hasNext do
            val handle = it.next()
            it.remove()
            // Re-flush on the FIFO worker (single owner of the tail); flushRaw is a no-op if nothing is pending or a send is already in flight.
            submitEngineOp(() => flushRaw(handle))
        end while
    end reflushStalledRaw

    /** Re-arm every recv/accept/connect that parked on a full submission queue ([[stalledSubmits]]). Called once per reap turn via [[reArmStalled]]
      * after submit frees SQ slots. Runs on the reap carrier, so it calls the submit helpers directly. The current batch is snapshotted and the queue
      * cleared first, so a re-arm that hits SQ-full again is re-parked for the NEXT turn (one attempt per turn, never a spin). A re-armed recv whose
      * handle was closed meanwhile fails cleanly inside submitRecv (the readBuffer.isClosed guard); a re-armed accept/connect on a closing driver
      * fails cleanly inside its submit helper (the closedFlag guard), so a parked op never feeds a freed buffer or a torn-down ring.
      */
    private def reArmStalledSubmits()(using AllowUnsafe, Frame): Unit =
        if !stalledSubmits.isEmpty then
            val batch = new java.util.ArrayDeque[PendingOp](stalledSubmits)
            stalledSubmits.clear()
            while !batch.isEmpty do
                batch.poll() match
                    case PendingOp.Read(promise, h, eintrRetries, handshakeOwned, armedPostUpgrade, _) =>
                        submitRecv(h, promise, eintrRetries, handshakeOwned, armedPostUpgrade)
                    case PendingOp.Accept(promise, h, noAddr, noLen) => submitAccept(promise, h, noAddr, noLen)
                    case PendingOp.Connect(promise, h)               => submitConnect(promise, h)
                    case PendingOp.Write(_, _, _, _) | PendingOp.TlsWrite(_, _, _) =>
                        () // sends park in stalledRaw / the in-flight send tail, never here
                end match
            end while
    end reArmStalledSubmits

    /** Map one reaped CQE back to its pending op and complete it. Releases the op's pinned memory and decrements the handle's in-flight
      * count (which may trigger a deferred close). `res >= 0` is the byte count or accepted fd; `res < 0` is `-errno`.
      */
    private def complete(key: Long, res: Int)(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        Maybe(pending.remove(key)) match
            case Present(op) =>
                // Ops that hand their resource use to a deferred engine-FIFO op (the TLS read feed, the send completions) defer their in-flight
                // decrement until AFTER that op runs, so the count -- and therefore freeResources -- stays pending while the deferred op is still
                // touching the engine / buffers. Decrementing inline at CQE-reap time drops the count to zero and lets a deferred close free those
                // resources before the queued op runs (the BoringSSL feedCiphertext MemorySession-alreadyClosed use-after-free).
                var deferredDecrement = false
                op match
                    case PendingOp.Read(promise, h, eintrRetries, handshakeOwned, armedPostUpgrade, armedForStaging) =>
                        // Release the exclusive-use claim (PosixHandle.recvInFlight) now that this SQE has genuinely reaped, before any
                        // branch below (including an EINTR resubmit or drainQueuedRecv) can re-claim it. Synchronous on this same reap-carrier
                        // call, so there is no window where a concurrent submitRecv could observe a stale `true`.
                        h.recvInFlight = false
                        // Named so the queued-recv drain below (which must NOT fire on an EINTR retry: that re-submits the SAME logical recv,
                        // so nothing has actually completed) can check it without repeating the condition.
                        val isEintrRetry = res < 0 && -res == PosixConstants.EINTR && eintrRetries < maxTransientIoRetries && !h.isClosing()
                        if isEintrRetry then
                            // EINTR read CQE: a signal interrupted the recv before any byte was transferred (POSIX recv(2)); no data was moved and
                            // the socket still holds whatever the peer sent, so re-submit the recv on the SAME promise rather than failing it Closed
                            // and dropping a healthy connection (the io_uring twin of PollerIoDriver's recvNowWithRetry). Bounded by
                            // maxTransientIoRetries via the carried count so an EINTR storm cannot spin: past the bound the `else` below fails Closed.
                            // Skipped when the handle is already closing (a concurrent close): re-arming a freed handle would be a use-after-free.
                            submitRecv(h, promise, eintrRetries + 1, handshakeOwned, armedPostUpgrade)
                        else if
                            !h.isClosing() &&
                            (h.upgradeActive || (h.upgrading && !handshakeOwned) || (handshakeOwned && h.isUpgraded) ||
                                (!handshakeOwned && h.isUpgraded && !armedPostUpgrade))
                        then
                            // STARTTLS stale recv: this recv was armed by the plaintext ReadPump before detachForUpgrade detached the connection, and
                            // io_uring cannot cancel it, so it just consumed the peer's first post-signal flight (the ClientHello / ServerHello, or a
                            // later boringssl flight such as the post-FINISHED NewSessionTicket). The discriminator is the handle's upgrade state plus
                            // the recv's `handshakeOwned` tag, NOT the recv promise state: the ReadPump reuses ONE promise object across reads
                            // (becomeAvailable resets it and re-arms), so detachForUpgrade's cancel may fail one arming while the pump re-arms a fresh
                            // one whose promise is still live when this CQE reaps. A `promise.done()` guard therefore mis-classifies that live-promise
                            // stale recv as a normal read and feeds the flight to the torn-down ReadPump, stranding the handshake's parked waiter (the
                            // residual stall). Single-source: upgradeActive stays set for the WHOLE upgrade and the handshake reads through the
                            // upgradeHandoff slot, arming its OWN producer recv (armUpgradeProducerRead, handshakeOwned). Four routes reach here:
                            // (1) upgradeActive set: ANY recv in flight belongs to the slot via the first clause -- the handshake's producer recv AND a
                            // kernel-owned stale pump recv can BOTH be in flight, both route here, one fulfils the parked waiter and the other
                            // stages/appends a Carryover (no double-read: the socket consumes each byte once); (2) upgradeActive cleared (only at
                            // onFinished) but `upgrading` still set AND this recv is not handshakeOwned: a stale plaintext-pump recv reaped in the narrow
                            // post-clear / pre-tls gap (boringssl multi-flight). Without the `upgrading` clause it would fall through to the raw
                            // plainReadComplete branch and be delivered as plaintext (the upgrade-handoff drop); (3) handshakeOwned AND the handle has
                            // EVER upgraded ([[PosixHandle.isUpgraded]], which -- unlike upgradeActive/upgrading -- is never cleared): the handshake's
                            // own producer recv (armUpgradeProducerRead) reaping AFTER onFinished already cleared upgradeActive/upgrading and attached
                            // `tls`. `driveUpgradeRead`'s "no stale recv in flight" check (hasInFlightRead) races the reap carrier's own engine-FIFO
                            // ordering (a TOCTOU: enqueued-for-registration is not yet registered), so a producer recv can still be genuinely
                            // kernel-owned when the handshake reaches Done from other bytes and onFinished runs. Without this clause that orphan falls
                            // through to the ordinary TLS-feed branch below, feeding its ciphertext into the SAME engine the post-upgrade ReadPump's
                            // own (now-concurrent) recv targets via the identical cached staging buffer (recvStagingFor) -- interleaving/duplicating
                            // ciphertext delivery order (a fatal TLS record, or exactly the bad_record_mac corruption shape) -- and completing the
                            // orphan's own throwaway producer promise with any decoded plaintext, which nothing observes (a silently dropped
                            // application-data flight). Routing it here instead stages it as a Carryover; the post-upgrade ReadPump's own first recv
                            // arm (submitRecv, gated on isUpgraded) QUEUES behind this orphan instead of racing it for the same staging buffer (see
                            // PosixHandle.queuedRecv), so the Carryover is staged before any conflicting recv can be armed, preserving both
                            // no-aliasing and wire order. `false` for a FRESH (non-STARTTLS) handshake's own handshakeOwned recvs: `isUpgraded` is
                            // never set on a handle that never went through `upgradeRole`, so those still fall through unaffected (their `tls` stays
                            // Absent until their own onFinished); (4) NOT handshakeOwned, the handle has EVER upgraded, AND this SPECIFIC recv was
                            // armed BEFORE that upgrade committed ([[PendingOp.Read.armedPostUpgrade]] false): the stale PLAINTEXT PUMP recv's
                            // handshakeOwned=false sibling of route (3) -- a recv armed by the ordinary pre-upgrade ReadPump continuation that
                            // outlives the ENTIRE upgrade (not just the narrow window route (2) covers, which only spans the moment between
                            // onFinished clearing upgradeActive and then upgrading). Route (2) alone cannot catch this: once BOTH flags are false,
                            // `handshakeOwned=false` + `isUpgraded=true` is indistinguishable from a genuine post-upgrade ReadPump recv using
                            // per-HANDLE state alone. Without this clause the orphan falls through to the ordinary TLS-feed branch and reads from
                            // `recvStagingFor` -- a buffer THIS recv never actually wrote to (it targeted `handle.readBuffer`, armed while `tls`
                            // was still Absent) -- feeding stale/uninitialized bytes to the engine as bogus "ciphertext" (a fatal TLS record, or a
                            // silent zero-plaintext re-arm if the staging buffer happens to hold leftover bytes from a genuine prior read) instead
                            // of the peer's real application data, which is sitting untouched in `handle.readBuffer`. This is the mechanism behind
                            // the "Closed at collect" steady-state corruption with zero TLS decode-failure markers: intra-connection
                            // (same handle throughout; PosixHandle.recvStagingOwnerId's ownership check correctly stays quiet, since staging IS
                            // owned by this handle -- the bug is staleness, not a cross-connection owner mismatch), not a session leak.
                            // Absent until their own onFinished). Either way the bytes belong to the handshake. Route
                            // them through the single upgradeHandoff slot (fulfil the parked waiter, or stage/append a Carryover when none is
                            // parked) instead of the settled/reused promise.
                            // upgradeActive is NOT cleared here: it must stay set until the handshake CARRIER actually consumes the bytes
                            // (driveUpgradeRead), otherwise a recvAndFeed running between this clear and the consume would read upgradeActive false,
                            // skip driveUpgradeRead, issue its own recv, and strand the staged Carryover. The handshake clears it once it takes the bytes.
                            import PosixHandle.UpgradeHandoff
                            // `h.tls.isDefined` (Present) means `onFinished` has ALREADY run for this handle: it is the ONLY writer of `tls`, and
                            // it runs synchronously (same carrier that drains every completion for this handle, the reap carrier for io_uring),
                            // so this read happens-after that write with no separate synchronization needed. Once onFinished has run, the
                            // handshake-driving fiber that used to consume `upgradeHandoff` via `driveUpgradeRead` is DONE and will never check
                            // this slot again -- staging a Carryover here would silently lose these bytes forever, leaving a permanent gap in the
                            // ciphertext stream the engine expects to be contiguous (the NEXT recv's chunk then desyncs TLS record framing,
                            // surfacing as an unrelated-looking fatal record or bad_record_mac on data that is itself perfectly fine). Feed it to
                            // the engine directly instead, exactly as onFinished's own post-FINISHED slot drain does for a Carryover staged
                            // during the transition window, and deliver any resulting plaintext via `h.inboundSink` -- the CURRENT connection's
                            // inbound (kept up to date by PosixTransport at connect/accept/upgrade) -- instead of this orphan's own throwaway
                            // promise, which nothing observes. Wire order is preserved for free: this engine op is enqueued synchronously inside
                            // THIS complete() call, strictly before `drainQueuedRecv` (below) can even submit the queued recv's own SQE, so
                            // `engineQueue`'s FIFO order guarantees this op decodes before that later recv's.
                            if h.tls.isDefined && res > 0 then
                                h.tls match
                                    case Present(engine) =>
                                        deferredDecrement = true
                                        submitEngineOp { () =>
                                            try
                                                val buf         = if armedForStaging then recvStagingFor(h) else h.readBuffer
                                                var fatalRecord = false
                                                val plain = feedAndDecrypt(
                                                    engine,
                                                    buf,
                                                    res,
                                                    h,
                                                    () =>
                                                        fatalRecord = true; closeHandle(h)
                                                )
                                                if !fatalRecord && !h.isClosing() && plain.length > 0 then
                                                    h.inboundSink(Span.fromUnsafe(plain))
                                                    flushTls(h)
                                                else if !fatalRecord && !h.isClosing() then
                                                    flushTls(h)
                                                end if
                                            catch
                                                case e: Throwable =>
                                                    Log.live.unsafe.warn(
                                                        s"$label TLS engine read failed (post-onFinished stale recv) fd=${h.readFd}: ${e.getMessage}"
                                                    )
                                                    closeHandle(h)
                                        }
                                    case Absent => () // unreachable: h.tls.isDefined was just checked on the same read
                            else if h.tls.isDefined then
                                // res <= 0 on a post-onFinished orphan: nothing to preserve. The current connection's own next recv (queued or
                                // freshly armed) independently observes the actual current socket state (EOF/error), so dropping this stale
                                // result is correct -- unlike the res > 0 case above, there is no application-data gap to worry about creating.
                                ()
                            else if res > 0 then
                                val arr = Buffer.copyToArray[Byte](h.readBuffer, 0, res)
                                deliverToUpgradeHandoff(h, arr)
                            else
                                // EOF (res == 0) or error (res < 0) during the upgrade: fail the parked waiter so the handshake tears down. If it has
                                // not parked yet there is nothing to stage; the handshake's own first read then observes the closed/EOF fd.
                                h.upgradeHandoff.get() match
                                    case parked: UpgradeHandoff.Waiter =>
                                        discard(h.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle))
                                        if res == 0 then parked.promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                                        else
                                            parked.promise.completeDiscard(
                                                Result.fail(NetConnectionClosedException(Operation.Upgrade, s"read errno=${-res}"))
                                            )
                                        end if
                                    case _ => ()
                            end if
                        else if res > 0 then
                            h.tls match
                                case Present(engine) =>
                                    // TLS read: the recv SQE pointed at the per-handle staging buffer (recvStagingFor), so the kernel filled it
                                    // directly. Feed it to the engine FIFO worker without any intermediate copy: the staging holds exactly `res`
                                    // bytes of ciphertext at indices [0, res). The staging buffer is alive until freeResources runs (deferred until
                                    // the handle's in-flight count drains to zero), so no use-after-free risk while the FIFO worker reads it.
                                    // The re-arm inside the engine op (awaitRead on zero-plaintext) happens only after feedAndDecrypt consumes the
                                    // staging, preserving the single-in-flight-per-handle property.
                                    val staging = recvStagingFor(h)
                                    // ALWAYS-ON buffer-role invariant (a plain branch, not an elidable `assert`; see PendingOp.Read's
                                    // armedForStaging doc): this recv's own SQE must have actually targeted `recvStagingFor`'s buffer. A `false`
                                    // here means the kernel wrote INTO A DIFFERENT BUFFER (handle.readBuffer, since tls was Absent when this
                                    // recv was armed) than the one we are about to read `res` bytes from -- the exact stale-recv/wrong-buffer
                                    // corruption class the routing condition above exists to route away from this branch (see its route (4)).
                                    // This is the permanent safety net for that whole CLASS, independent of whether the routing condition
                                    // correctly identifies every trigger: catching it here means neither trigger nor consequence goes unnoticed.
                                    if !armedForStaging then
                                        val msg = s"recvStaging buffer-role mismatch fd=${h.readFd} h.id=${h.id.packed}: this recv's SQE " +
                                            "targeted a different buffer than the one being read from"
                                        Log.live.unsafe.error(s"$label $msg: stale/mis-routed recv, aborting this connection")
                                        promise.completeDiscard(Result.fail(Closed(label, summon[Frame], msg)))
                                        closeHandle(h)
                                    // ALWAYS-ON ownership invariant (a plain branch, not an elidable `assert`; see PosixHandle.recvStagingOwnerId):
                                    // the staging buffer this recv just filled must be tagged with THIS handle's own id. A mismatch means
                                    // recvStagingFor resolved a buffer belonging to a DIFFERENT handle -- a cross-connection ciphertext leak
                                    // (the bad_record_mac shape) caught at the exact point it would occur, both ids attributed, instead of
                                    // surfacing later as an unattributed decode failure. Safe-abort THIS connection only (fail this read, then
                                    // closeHandle): the mismatched owner's own connection is left completely untouched (reaching across to it
                                    // would itself be a cross-connection operation) and keeps running normally. Never deliver the mis-owned
                                    // bytes: fail-closed, not fail-open.
                                    // No decrementInFlight in either branch above: deferredDecrement stays false on this branch, so the shared
                                    // fallback at the end of the Read case (mirroring every other non-deferred branch, e.g. the stale-recv
                                    // branch above) decrements exactly once; calling it here too would double-decrement.
                                    else if h.recvStagingOwnerId != h.id.packed then
                                        // Lazy: the interpolated message (and the Log.error call itself) only runs on the mismatch path, never
                                        // on the success path -- this branch costs one Long compare per TLS read otherwise.
                                        val msg =
                                            s"recvStaging ownership mismatch fd=${h.readFd} h.id=${h.id.packed} owner=${h.recvStagingOwnerId}"
                                        Log.live.unsafe.error(s"$label $msg: cross-connection ciphertext leak, aborting this connection")
                                        promise.completeDiscard(Result.fail(Closed(label, summon[Frame], msg)))
                                        closeHandle(h)
                                    else
                                        deferredDecrement = true
                                        submitEngineOp { () =>
                                            try
                                                // onFatal routes through closeHandle rather than a bare handle.requestClose(): io_uring never
                                                // acquires the read half of the handle's guard (reads are async/kernel-owned, tracked by
                                                // inFlight/pendingCloses/closeAfterDrain instead), so requestClose() alone would free the
                                                // handle's buffers and engine immediately, unconditionally -- unsafe when a second kernel-owned
                                                // recv for this same handle (the STARTTLS upgrade-handoff window allows more than one) is still
                                                // in flight and has already captured a reference to those buffers. closeHandle defers the actual
                                                // free until every op still in flight for the handle (this deferred decrement included) drains.
                                                // fatalRecord is a LOCAL flag, not a shared/ambient signal: closeHandle only marks pendingCloses
                                                // (shared across every close reason for this handle) synchronously, so checking pendingCloses here
                                                // would also misfire for an unrelated concurrent close racing a read that decoded cleanly.
                                                var fatalRecord = false
                                                val plain = feedAndDecrypt(
                                                    engine,
                                                    staging,
                                                    res,
                                                    h,
                                                    () =>
                                                        fatalRecord = true; closeHandle(h)
                                                )
                                                // Fatal-record check (mirrors the poller's rearmOwned endDispatch closing check): fatalRecord is set
                                                // exactly when THIS call's onFatal fired; isClosing() is kept too for any other concurrent close that
                                                // already ran the guard's free (a rare path independent of this read). Either way, failing the read
                                                // promise Closed here tears the connection down on io_uring exactly as the poller's rearmOwned /
                                                // endDispatch does when the dispatch guard observes the close bit.
                                                if fatalRecord || h.isClosing() then
                                                    promise.completeDiscard(Result.fail(Closed(
                                                        label,
                                                        summon[Frame],
                                                        s"fatal TLS record fd=${h.readFd}"
                                                    )))
                                                else if plain.length > 0 then
                                                    promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(plain))))
                                                    // Flush any ciphertext the read produced (e.g. a TLS 1.3 KeyUpdate response queued by the engine
                                                    // during the decode). drainReadProducedCiphertext (inside feedAndDecrypt) appended it to
                                                    // pendingCipher; flushTls submits a send SQE for the unsent region so it reaches the peer.
                                                    // This mirrors the poller's flushReArmPending / armWritableForFlush machinery that drains the tail
                                                    // after every write engine op. No flush is submitted when pendingCipher is empty (the common path
                                                    // for reads that produce no outbound ciphertext) or when a send is already in flight.
                                                    flushTls(h)
                                                else if h.halfClose == HalfCloseState.PeerCleanClose then
                                                    // The peer's close_notify was consumed (RFC 8446 6.1 orderly close): deliver CleanClose so the
                                                    // ReadPump tears down cleanly, rather than re-arming for ciphertext the peer will never send.
                                                    // Mirrors the poller's dispatchReadTls clean-close branch.
                                                    promise.completeDiscard(Result.succeed(ReadOutcome.CleanClose))
                                                else
                                                    // Only handshake / partial-record bytes consumed: submit another recv on the same promise rather than
                                                    // signalling EOF, mirroring the poller's rearmOwned. Then flush any read-produced ciphertext so a
                                                    // KeyUpdate response is not held until the next app-data write.
                                                    awaitRead(h, promise)
                                                    flushTls(h)
                                                end if
                                            catch
                                                // A TLS engine op threw a fatal alert (JDK SSLEngine.unwrap raises SSLHandshakeException on a received
                                                // fatal alert, which is NOT the readPlain==-2 return the isClosing branch above handles). drainEngineOps
                                                // would otherwise swallow it and strand this read promise (a silent hang); fail the read so the connection
                                                // tears down. The deferred in-flight decrement is a separate queued op, so it still runs after this.
                                                case e: Throwable =>
                                                    Log.live.unsafe.warn(
                                                        s"$label TLS engine read failed fd=${h.readFd}, closing connection: ${e.getMessage}"
                                                    )
                                                    promise.completeDiscard(Result.fail(Closed(
                                                        label,
                                                        summon[Frame],
                                                        s"TLS engine read failed fd=${h.readFd}: ${e.getMessage}"
                                                    )))
                                            end try
                                        }
                                    end if
                                case Absent =>
                                    val arr = Buffer.copyToArray[Byte](h.readBuffer, 0, res) // right-sized copy out
                                    // Keep a reference to this chunk so a subsequent STARTTLS upgrade can recover a handshake flight that
                                    // arrived coalesced with the upgrade signal in this same read (mirrors PollerIoDriver.dispatchReadPlain;
                                    // without this, feedCoalescedHandshake is permanently inert on io_uring).
                                    h.lastPlaintextRead.set(Present(arr))
                                    promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
                        else if res == 0 then
                            // Peer close via a bare TCP FIN (no close_notify, else the clean-close branch above delivered CleanClose first): record
                            // the peer-EOF half-close state, then deliver PeerFin.
                            // GUARD: a res==0 here can also be OUR OWN closeHandle forcing this in-flight recv to complete (registerDeferredClose
                            // shuts down the read half with SHUT_RD because io_uring holds its own reference to the fd and close(fd) alone would
                            // never complete a kernel-owned recv). That self-induced completion reaps through this exact branch with no way to
                            // tell it apart from a genuine unprompted peer FIN by `res` alone, so unconditionally stamping PeerEof would make a
                            // locally-closed connection report Truncated instead of LocalClose. pendingCloses is set synchronously at the top of
                            // closeHandle, strictly before the deferred SHUT_RD that produces this CQE, so its presence here reliably distinguishes
                            // "we are the closer" from a real peer FIN; isClosing() cannot substitute (the plain close path never calls
                            // requestClose() until closeNow, which runs only after this branch returns). The promise itself is unaffected: cancel
                            // (run synchronously inside closeHandle, before SHUT_RD) already failed it Closed, so completeDiscard below is a no-op
                            // on the closing path and only resolves the promise for a genuine peer FIN.
                            if !pendingCloses.containsKey(h.id.packed) then h.halfClose = HalfCloseState.PeerEof
                            promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                        else
                            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read errno=${-res}")))
                        end if
                        // Fire any recv queued behind this one (single-recv-ordering enforcement, see submitRecv / PosixHandle.queuedRecv), on
                        // EVERY completion path above -- normal (STARTTLS routing, TLS-feed, plain) AND abnormal (EOF, error) alike -- so a
                        // queued request is never stranded on an error/EOF reap. Skipped on an EINTR retry (see isEintrRetry above): that
                        // resubmitted the SAME logical recv, which is still in flight, so draining now would race it for the same buffer.
                        if !isEintrRetry then drainQueuedRecv(h)
                    case PendingOp.Write(h, _, _, len) =>
                        deferredDecrement = true
                        // The raw-send CQE was reaped: account for it (advance the pending tail, re-submit the remainder on a partial send or any
                        // coalesced bytes) on the engine FIFO worker so rawPending stays single-owner, mirroring the TlsWrite path. The pinned
                        // per-write send buffer is released below via releaseBuffer(); onRawSendComplete re-flushes from the handle's tail, not
                        // from that buffer, so it is safe to close on reap. `res` is clamped to the submitted len (the kernel never reports more).
                        val sent = if res < 0 then res else math.min(res, len)
                        submitEngineOp { () => onRawSendComplete(h, sent) }
                    case PendingOp.TlsWrite(h, _, len) =>
                        deferredDecrement = true
                        // The TLS-send CQE was reaped: account for it (advance the tail, re-submit the remainder on a partial send) on the engine
                        // FIFO worker so pendingCipher stays single-owner. The pinned send buffer is released below via releaseBuffer().
                        val sent = if res < 0 then res else math.min(res, len)
                        submitEngineOp { () => onTlsSendComplete(h, sent) }
                    case PendingOp.Connect(promise, _) =>
                        if res == 0 then promise.completeDiscard(Result.succeed(()))
                        else promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"connect errno=${-res}")))
                    case PendingOp.Accept(promise, _, _, _) =>
                        if res >= 0 then
                            // The accept SQE produced a connected fd. If the accept promise was already completed -- the listener's close
                            // cancel() failed it while this accept was in flight (the closeListener teardown) -- nobody will wrap or dispatch
                            // this fd, so close it here instead of leaking an established, handler-less connection. A peer that connected into
                            // the closing listener would otherwise see its connect succeed, then hang forever on its first read: no handler
                            // ever responds, and the orphaned fd is never closed so no FIN/RST reaches the peer.
                            if !promise.complete(Result.succeed(res)) then discard(takeNow(sockets.close(res)))
                        else promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"accept errno=${-res}")))
                end match
                op.releaseBuffer() // release per-op buffers (Write send buf, Accept addr/len) now that the CQE is reaped
                // For ops whose resource use was handed to a deferred engine op above, queue the decrement BEHIND that op (FIFO order on the
                // engine queue) so the in-flight count stays non-zero until the deferred op has finished with the engine / buffers; otherwise
                // decrement inline now. drainEngineOps catches a throwing op and continues, so the queued decrement always runs (no leaked count).
                if deferredDecrement then submitEngineOp { () => decrementInFlight(op.handle) } else decrementInFlight(op.handle)
            case Absent =>
                Log.live.unsafe.warn(s"$label CQE for unknown key=$key")
        end match
    end complete

    /** Complete a CQE, respecting the IORING_CQE_F_MORE flag. Both accept and recv use single-shot submissions (flags=0), so `more` is
      * always false in normal operation. The guard exists to handle unexpected F_MORE (e.g. a future kernel or a misconfigured op): treat it
      * as single-shot and remove the entry rather than accumulating a stale key. The kernel should not set F_MORE for single-shot ops.
      */
    private def completeMultishot(key: Long, res: Int, more: Boolean)(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        if key == WakeKey then
            // The wake eventfd's multishot poll fired: a cross-carrier submitEngineOp / close wrote the eventfd to return this parked wait. It
            // carries no connection work; the enqueued op is drained by the loop's next drainEngineOps. Drain the counter so the level-readable
            // eventfd does not immediately re-fire the poll. The multishot stays armed across completions (F_MORE); on a non-F_MORE completion
            // (the kernel terminates a multishot poll when the CQ ring is full, under heavy load) clear the armed flag so the reap loop re-arms it
            // before the next park (and parks bounded until it does, never indefinitely without an armed wake).
            discard(uring.kyo_uring_eventfd_read(wakeFd))
            if !more then wakePollArmed = false
        else if more then
            // Single-shot accept and recv do not set F_MORE. If the kernel sets it unexpectedly (future op or kernel behavior change),
            // treat the CQE as single-shot: complete and remove the entry so no stale pending key accumulates.
            Log.live.unsafe.warn(s"$label unexpected IORING_CQE_F_MORE for key=$key res=$res; treating as single-shot")
            complete(key, res)
        else complete(key, res)
        end if
    end completeMultishot

    /** Deliver `arr` (one peer ciphertext flight) into the handle's [[PosixHandle.upgradeHandoff]] slot: fulfil a parked handshake waiter, or
      * stage a Carryover the handshake's next read consumes. When a Carryover is already staged, the new bytes are APPENDED to it rather than
      * overwriting it: a single-slot replace would drop every segment but the first, the upgrade-handoff drop. Mirrors
      * [[PollerIoDriver]]'s `deliverToUpgradeHandoff`. Unlike the inline staging this replaced inside `complete()` (reap-carrier-confined, so a
      * single CAS retry sufficed), this shared version is also called from [[onInboundClosedDuringRead]] on an arbitrary channel-callback
      * carrier, so it is a full CAS retry loop rather than a single re-read.
      */
    @scala.annotation.tailrec
    private def deliverToUpgradeHandoff(handle: PosixHandle, arr: Array[Byte])(using AllowUnsafe): Unit =
        import PosixHandle.UpgradeHandoff
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
                if !handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, UpgradeHandoff.Carryover(arr)) then
                    deliverToUpgradeHandoff(handle, arr)
        end match
    end deliverToUpgradeHandoff

    /** STARTTLS handoff: the plaintext [[ReadPump]] pulled `bytes` off the socket, but by the time it tried to deliver them
      * the inbound channel was already closed. If the handle is upgrading, salvage them instead of dropping them: mirrors
      * [[PollerIoDriver]]'s own override for the identical race (`PollerIoDriver.scala:662-664`), which io_uring lacked -- inheriting
      * [[kyo.net.internal.transport.IoDriver]]'s no-op default silently dropped the peer's first TLS flight (e.g. the ClientHello),
      * stranding the handshake waiting for data that already arrived.
      *
      * Unlike the poller (whose producer read and plaintext pump both run on the same poll carrier), this callback can fire on an arbitrary
      * channel-callback carrier -- e.g. the fiber running `detachForUpgrade()` that closed the channel and woke a parked put -- not the reap
      * carrier that `complete()`'s STARTTLS routing normally runs on. The work is wrapped in [[submitEngineOp]] so it is serialized through
      * the same engine FIFO worker as every other engine op for this handle, and so it queues no earlier than any op `complete()` already
      * enqueued for a subsequent CQE, preserving wire order.
      *
      * `h.tls.isDefined` means `onFinished` already ran and attached the engine before this callback fired: feed the bytes to it directly
      * (mirroring `complete()`'s own post-onFinished branch) rather than staging a Carryover nobody will ever drain, which would otherwise
      * desync the ciphertext stream for the connection's next read.
      *
      * One-shot claim on `lastPlaintextRead`, taken BEFORE `submitEngineOp` so a lost claim queues no op at all: `PosixTransport.
      * feedCoalescedHandshake` aliases the SAME array for the peer's first upgrade flight when it arrived coalesced with the upgrade signal.
      * Both this hook and that method can run for the very same chunk (this hook fires when the plaintext pump's channel-offer for it
      * failed; `feedCoalescedHandshake` runs unconditionally right after `upgradeToTls` builds the engine), so without a single owner the
      * engine would receive that chunk twice -- a duplicate handshake record that fails the handshake with an `EngineError`. The claim
      * gates BOTH branches below (the post-onFinished direct feed and the pre-onFinished Carryover stage), not just one, since either can
      * be racing the same feed.
      */
    override def onInboundClosedDuringRead(handle: PosixHandle, bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        if handle.upgrading then
            val arr = bytes.toArrayUnsafe
            val delivered = handle.lastPlaintextRead.get() match
                case p @ Present(last) if last eq arr => handle.lastPlaintextRead.compareAndSet(p, Absent)
                case _                                => false
            if delivered then
                submitEngineOp { () =>
                    handle.tls match
                        case Present(engine) =>
                            val cipherBuf = Buffer.fromArray[Byte](arr)
                            try
                                var fatalRecord = false
                                val plain = feedAndDecrypt(
                                    engine,
                                    cipherBuf,
                                    arr.length,
                                    handle,
                                    () =>
                                        fatalRecord = true; closeHandle(handle)
                                )
                                if !fatalRecord && !handle.isClosing() && plain.length > 0 then
                                    handle.inboundSink(Span.fromUnsafe(plain))
                                    flushTls(handle)
                                else if !fatalRecord && !handle.isClosing() then
                                    flushTls(handle)
                                end if
                            catch
                                case e: Throwable =>
                                    Log.live.unsafe.warn(
                                        s"$label TLS engine read failed (upgrade-handoff salvage) fd=${handle.readFd}: ${e.getMessage}"
                                    )
                                    closeHandle(handle)
                            finally cipherBuf.close()
                            end try
                        case Absent => deliverToUpgradeHandoff(handle, arr)
                }
            end if
    end onInboundClosedDuringRead

    /** Register a pending op: store it under `key` and increment its handle's in-flight count (the count the close handshake drains). */
    private def register(key: Long, op: PendingOp)(using AllowUnsafe): Unit =
        discard(pending.put(key, op))
        discard(inFlight.merge(op.handle.id.packed, 1L, (a, b) => a + b))
    end register

    /** Undo a [[register]] for an op that was never submitted (SQ full): drop the pending entry and decrement the in-flight count. */
    private def unregister(key: Long)(using AllowUnsafe, Frame): Unit =
        Maybe(pending.remove(key)).foreach(op => decrementInFlight(op.handle))

    /** Decrement a handle's in-flight count after its CQE is reaped (or its unsubmitted op is dropped). When the count reaches 0 and the
      * handle is awaiting a deferred close, the close runs now: the kernel has released every buffer, so `PosixHandle.close` is safe.
      */
    private def decrementInFlight(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        val remaining = inFlight.merge(handle.id.packed, -1L, (a, b) => a + b)
        if remaining <= 0L then
            discard(inFlight.remove(handle.id.packed))
            Maybe(closeAfterDrain.remove(handle.id.packed)).foreach(dischargeDeferredClose)
        end if
    end decrementInFlight

    private def submitBatched()(using AllowUnsafe): Unit = discard(pendingSubmits.incrementAndGet())

    private def flushSubmits()(using AllowUnsafe, Frame): Unit =
        // Never submit on a ring that io_uring_queue_exit has already freed: a closeListener engine op drained AFTER the teardown (its
        // thunk calls flushSubmits before closeFd) would otherwise io_uring_submit on the exited ring. `ringExited`, not `closedFlag`, is
        // the precise gate: it flips only after queue_exit, so the graceful pre-teardown drain still flushes its prepared SQEs normally.
        if !ringExited.get() then
            val n = pendingSubmits.getAndSet(0L)
            if n > 0L then submitPrepared(n, 0)

    /** Submit the `remaining` prepared SQEs, re-submitting on a short count and re-queuing on a transient failure so a prepared SQE is never
      * silently stranded (libuv #4598, CWE-252). `io_uring_submit` returns the count of SQEs it consumed, or a negative `-errno` (e.g. `-EBUSY`
      * when the completion queue is full). A short count leaves the remainder in the SQ ring, so it is re-submitted (bounded by
      * [[maxTransientIoRetries]]). A negative return re-queues the unsubmitted count onto `pendingSubmits` so the next reap-loop flush, after
      * `drainReady` has reaped CQEs and freed the CQ, retries it, rather than spinning here or dropping SQEs whose CQEs would then never arrive
      * and whose reads or writes would hang.
      */
    @scala.annotation.tailrec
    private def submitPrepared(remaining: Long, attempt: Int)(using AllowUnsafe, Frame): Unit =
        // io_uring_submit returns the count of submitted SQEs, or -errno on failure. Raw signed Int, not clamped.
        val submitted = uring.io_uring_submit(ring)
        if submitted < 0 then
            discard(pendingSubmits.addAndGet(remaining))
            Log.live.unsafe.warn(
                s"$label io_uring_submit failed errno=${-submitted}; re-queued $remaining prepared SQEs for the next flush"
            )
        else if submitted.toLong >= remaining then ()
        else if attempt < maxTransientIoRetries then submitPrepared(remaining - submitted.toLong, attempt + 1)
        else
            discard(pendingSubmits.addAndGet(remaining - submitted.toLong))
            Log.live.unsafe.warn(
                s"$label io_uring_submit short after $attempt retries; re-queued ${remaining - submitted.toLong} prepared SQEs"
            )
        end if
    end submitPrepared

    /** Enqueue an engine/submission op for the reap carrier to run. EVERY io_uring SQ operation (get_sqe + prep + submit) runs on the single
      * reap carrier so the submission ring has exactly one producer: `io_uring_get_sqe` mutates the SQ tail and is not thread-safe, and
      * `io_uring_wait_cqes` (the reap wait) itself submits, so any SQE prepared off the reap carrier would race the wait's flush and be lost
      * (a dropped op whose CQE never arrives, a 15s hang) or corrupt a coalesced handshake record (`bad_record_mac`). So read/write/connect/accept
      * arming and the TLS engine ops all enqueue here; [[runCycle]] drains the queue at the top of each cycle, before it flushes and waits. The
      * queue is the only cross-carrier handoff; running the op is single-owner. An op enqueued while the reap carrier is parked indefinitely
      * is returned promptly by `wakeReapLoop()`, which writes the wake eventfd and returns the indefinite park at once rather than waiting
      * for an unrelated CQE.
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(engineQueue.offer(op))
        // Wake a reap loop parked indefinitely on the wake so it drains this op now instead of waiting for an unrelated CQE. The eventfd write is
        // unconditional (the kernel counter coalesces concurrent writes); a no-op when there is no wake eventfd (the loop then discovers the op via
        // its bounded fallback park).
        wakeReapLoop()
        // Offer-then-recheck (mirrors closeHandle's pendingCloses put-then-recheck): once the reap loop has exited, its one-shot final
        // drain never runs again, so an op that landed after that drain has no executor: its promise never completes (a client connect
        // armed here hangs until the transport's connect deadline) and the never-empty queue blocks the ring teardown. reapExited is set
        // strictly before the final drain, so either that drain sees this op, or this recheck observes reapExited and drains it here.
        // Skipped when THIS carrier already holds the late-drain claim (a re-entrant offer from inside a drained op, e.g. engineFreeSink):
        // the ongoing tail-recursive drainEngineOps polls the just-offered op in the same pass, so re-entering here would only spin on the
        // claim this carrier holds (see lateDrainOwner).
        if reapExited.get() && (lateDrainOwner ne Thread.currentThread()) then drainAfterReapExit()
    end submitEngineOp

    /** Drain every queued engine/submission op in order on the CURRENT (reap) carrier, running each to completion before the next, then return.
      * Called inline from [[runCycle]] each cycle (and once more at the terminal exit so a close fails every still-queued op's promise via the
      * closedFlag rejection in `awaitRead`/`submitRecv`). A throwing op must not abort the drain: that would strand every later op (a
      * multi-connection silent hang, Netty #7337 class). Each engine op already has an inner catch for expected typed failures (see the
      * dispatchReadTls feed op); this outer catch is a backstop for any unexpected throw that escapes the inner boundary.
      */
    @scala.annotation.tailrec
    private def drainEngineOps()(using AllowUnsafe, Frame): Unit =
        engineQueue.poll() match
            case null => ()
            case op =>
                try op()
                catch
                    // Contain ANY throw (not just NonFatal): the engine FIFO must not let one connection's
                    // engine throw kill the FIFO for all connections. SSLEngine.unwrap raises on a received
                    // fatal alert (a THROW, not the readPlain == -2 return), so the op's own connection is
                    // expected to have failed typed inside the op's inner catch; this backstop logs and
                    // continues so every other connection on this driver remains unaffected.
                    case ex: Throwable =>
                        Log.live.unsafe.error(s"$label engine op threw; the reap carrier continues draining", ex)
                end try
                drainEngineOps()
    end drainEngineOps

    /** Whether a wait result is the benign "timed out, no CQE" signal (`-ETIME`), a normal empty turn rather than a failure. The
      * argument is the return value of the fused submit-and-wait enter (0 ready / -ETIME timeout / -errno error), not the captured errno:
      * like every liburing call it returns the result directly and does not reliably set the global errno, so a stale errno must never
      * decide whether a ready CQE gets drained or the reap loop stops. The `+ETIME` arm is defensive for any platform whose
      * wrapper surfaces the timeout as a positive code.
      */
    private def isTimeout(waitResult: Int): Boolean =
        waitResult == -PosixConstants.ETIME || waitResult == PosixConstants.ETIME

    /** Whether a reap-park return code is a TRANSIENT condition the loop must RETRY rather than a
      * fatal ring fault: `-ETIME` (empty turn), `-EINTR` (a signal interrupted the wait), `-EAGAIN`
      * (resource momentarily unavailable), `-EBUSY` (the ring is momentarily busy), `-ENOMEM` (the
      * kernel could not allocate memory for this `io_uring_enter` call right now, e.g. under the
      * combined memory pressure of many concurrently-running rings; the allocation this driver's own
      * ring already holds is untouched, and the request can simply be retried on the next turn). On
      * any of these the loop continues; it tears the ring down only on `closedFlag` or a genuinely
      * fatal rc, so a transient rc never kills the ring every connection shares.
      */
    private def reapRcContinues(waitResult: Int): Boolean =
        isTimeout(waitResult)
            || waitResult == -PosixConstants.EINTR
            || waitResult == -PosixConstants.EAGAIN
            || waitResult == -PosixConstants.EBUSY
            || waitResult == -PosixConstants.ENOMEM

    /** Current count of consecutive `-EINTR` send CQE completions for `handle` (0 when none has occurred since the last non-EINTR send).
      * Engine-FIFO-worker-only, the single owner of [[sendEintrRetries]].
      */
    private def sendEintrCount(handle: PosixHandle): Int =
        Maybe(sendEintrRetries.get(handle.id.packed)).getOrElse(0)

    /** Record one more `-EINTR` send CQE for `handle`, advancing the bounded retry count. Engine-FIFO-worker-only. */
    private def bumpSendEintr(handle: PosixHandle): Unit =
        discard(sendEintrRetries.merge(handle.id.packed, 1, (a, b) => a + b))

    /** Clear the `-EINTR` send retry count for `handle` after a non-EINTR send completion, so the bound counts only an uninterrupted EINTR run.
      * Engine-FIFO-worker-only.
      */
    private def clearSendEintr(handle: PosixHandle): Unit =
        discard(sendEintrRetries.remove(handle.id.packed))

    /** Per-handle off-heap landing zone for one in-flight TLS recv ciphertext. Lazily allocated on the first TLS read and reused across reads.
      * The recv SQE points the kernel at this buffer; the reap carrier feeds it to the engine. A single TLS recv is in flight per handle at a
      * time (the read is re-armed only inside the engine op after the ciphertext is consumed), so reuse across the SQE/CQE window is safe.
      *
      * Ownership: the io_uring submit carrier allocates (on first use) and the io_uring reap carrier reads. The close-after-drain handshake
      * ensures the handle (and thus this buffer) is not freed while a recv SQE is in flight. Freed exactly once in `freeResources`.
      */
    private def recvStagingFor(handle: PosixHandle)(using AllowUnsafe): Buffer[Byte] =
        handle.recvStaging match
            case Present(buf) => buf
            case Absent =>
                val buf = Buffer.alloc[Byte](handle.readBufferSize)
                handle.recvStaging = Present(buf)
                // Ownership tag (see PosixHandle.recvStagingOwnerId): stamped once, at allocation, with the SAME handle whose field this is.
                handle.recvStagingOwnerId = handle.id.packed
                buf

    /** Per-handle reused off-heap mirror for TLS flush sends. Filled element-wise from the unsent ciphertext region and sent from offset 0,
      * eliminating the per-SQE heap-copy and fresh Buffer allocation. Grown on demand (close-before-realloc). A single TLS send SQE is in
      * flight per handle at a time (sendInFlight guard), so reuse across the SQE/CQE window is safe.
      *
      * Ownership: the engine FIFO worker (flushTls runs inside submitEngineOp). Freed exactly once in `freeResources`, never on reap.
      */
    private def flushMirrorFor(handle: PosixHandle, size: Int)(using AllowUnsafe): Buffer[Byte] =
        handle.flushMirror match
            case Present(buf) if buf.size >= size => buf
            case _ =>
                handle.flushMirror.foreach(_.close())
                val buf = Buffer.alloc[Byte](size)
                handle.flushMirror = Present(buf)
                buf

end IoUringDriver

private[net] object IoUringDriver:

    // Wake-guard encoding (see IoUringDriver.wakeGuard, acquireWake/releaseWake/closeWakeGuarded): the low WakeHolderMask bits count the in-flight
    // wakeReapLoop eventfd writes touching the wake fd, WakeClosingBit records that the eventfd teardown has begun, and WakeClosed is the terminal
    // value once the eventfd close has run (a sentinel distinct from any holders|WakeClosingBit combination, so a repeat closeWakeGuarded is
    // idempotent). Mirrors PollerIoDriver's WakeHolder encoding and PosixHandle.guard.
    final private val WakeClosingBit = 1 << 30
    final private val WakeHolderMask = WakeClosingBit - 1
    final private val WakeClosed     = -1

    /** Reserved user_data key for the wake eventfd's multishot POLL_ADD CQE. The op keyGen starts at 1 and only increments, so a negative
      * sentinel can never collide with a real op key. `private[posix]` so the recording test spy can recognize (and ignore) the wake CQE,
      * which is pure reap-loop infrastructure, not a connection-op completion.
      */
    private[posix] val WakeKey: Long = -1L

    /** Build a driver over a freshly initialized io_uring ring. The ring lives in a caller-owned `Buffer[Byte]` of `kyo_uring_sizeof()` bytes
      * (the SQ/CQ mmaps are owned internally by liburing). Throws `NetBackendUnavailableException` if `io_uring_queue_init` fails (e.g. the
      * kernel is too old or the process is sandboxed from io_uring at the production ring depth).
      */
    def init()(using AllowUnsafe, Frame): IoUringDriver =
        val uring = Ffi.load[IoUringBindings]
        val depth = math.max(256, kyo.net.ioPoolSize() * 64)
        val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt) // sizeof(struct io_uring), via the shim
        // Probe the kernel version via the uname FFI shim and select the SETUP-flag tier. The probe is mandatory because flag
        // availability varies by kernel version; see selectRingFlags for the tier breakdown and the rationale for which flags
        // are excluded (SINGLE_ISSUER and DEFER_TASKRUN enforce a per-thread ring constraint that breaks the cross-carrier model).
        val flags = selectRingFlags(uring.kyo_uring_kernel_version())
        // io_uring_queue_init returns 0 on success / -errno on failure and does NOT set the global errno (liburing returns the negated
        // errno directly). Read the RETURN VALUE, not the captured errno: a stale errno left by any prior syscall (an accept that returned
        // EAGAIN, a connect that returned EINPROGRESS, ...) is the steady state of a running program, and reading it here aborts a
        // successful init. That would silently drop io_uring to the epoll fallback and, worse, take the failure branch below that frees
        // the live ring Buffer without io_uring_queue_exit, leaving the kernel a ring over freed memory (heap corruption, SIGSEGV in a
        // later kyo_uring_get_sqe). On a genuine failure (rc < 0) the ring was not set up, so ring.close() alone is correct (liburing
        // cleaned up internally; no queue_exit is owed).
        val rc = uring.io_uring_queue_init(depth, ring, flags)
        if rc != 0 then
            ring.close()
            throw NetBackendUnavailableException(Present("io_uring"), s"queue_init failed: rc=$rc flags=$flags")
        // io_uring has no prep_close SQE, so the connection-close fd shutdown/close goes through SocketBindings (the same library the poller uses).
        init(uring, ring, Ffi.load[SocketBindings])
    end init

    /** Build a driver over caller-supplied uring bindings, an initialized ring, and socket bindings, allocating the driver's unsafe fields (the
      * atomic flags/counters and the cqe scratch) under the caller's `AllowUnsafe`: the construction site propagates the capability rather than
      * each field bridging it, so the class body never holds an ambient `AllowUnsafe` and every method keeps requiring its own. Shared by [[init]]
      * and the test construction helpers so the unsafe allocation lives in one place.
      */
    private[posix] def init(uring: IoUringBindings, ring: Buffer[Byte], sockets: SocketBindings)(using AllowUnsafe): IoUringDriver =
        // Wake eventfd for the reap loop (EFD_NONBLOCK | EFD_CLOEXEC). Fatal on failure: without the eventfd a cross-carrier submission
        // has no signal to return the parked indefinite wait, making the loop unrecoverable. Symmetric with the poller's registerWake fatal
        // at start(). On failure the ring is torn down cleanly before throwing so the caller does not leak an initialized ring.
        val wakeFd = uring.kyo_uring_eventfd_create(0, PosixConstants.EFD_NONBLOCK | PosixConstants.EFD_CLOEXEC)
        if wakeFd < 0 then
            uring.io_uring_queue_exit(ring)
            ring.close()
            throw new IllegalStateException(
                "IoUringDriver: eventfd creation failed; the wake eventfd is a liveness requirement for the indefinite park"
            )
        end if
        // IORING_FEAT_NODROP (bit 1, kernel >= 5.5): the kernel guarantees no CQE is dropped, applying backpressure on submit instead.
        // When set, the wake eventfd's multishot CQE cannot be lost under any load, making the indefinite park safe by contract.
        // When absent (kernel < 5.5), the reap loop falls back to the bounded ReapTimeoutNs floor so a lost CQE cannot hang it.
        val features        = uring.kyo_uring_get_features(ring)
        val nodropAvailable = (features & IoUringDriver.FeatNodrop) != 0
        new IoUringDriver(
            uring = uring,
            ring = ring,
            sockets = sockets,
            closedFlag = AtomicBoolean.Unsafe.init(false),
            started = AtomicBoolean.Unsafe.init(false),
            teardownDone = AtomicBoolean.Unsafe.init(false),
            ringExited = AtomicBoolean.Unsafe.init(false),
            reapExited = AtomicBoolean.Unsafe.init(false),
            keyGen = AtomicLong.Unsafe.init(1L),
            pendingSubmits = AtomicLong.Unsafe.init(0L),
            cqePtr = Buffer.alloc[Long](1),
            wakeFd = wakeFd,
            wakeGuard = AtomicInt.Unsafe.init(0),
            nodropAvailable = nodropAvailable
        )
    end init

    /** Pure SETUP-flag tier selector: maps a packed kernel version (major*1000+minor, e.g. 5.19 -> 5019) to the io_uring SETUP-flag set the
      * kernel supports. Kernel >= 5.19 gets COOP_TASKRUN|TASKRUN_FLAG; below 5.19 gets 0 (no task-run flags). Extracted as a pure function
      * so the tier logic can be exercised with real representative version inputs without a live FFI probe; `init` calls it with the real
      * `kyo_uring_kernel_version()` result.
      *
      * IORING_SETUP_SINGLE_ISSUER and IORING_SETUP_DEFER_TASKRUN are intentionally excluded. Both flags constrain io_uring_enter to the
      * same OS thread that called io_uring_setup. The driver creates the ring in `init()` on one carrier thread and runs the reap loop
      * on a different carrier spawned by `Fiber.Unsafe.init`. On kernels with SINGLE_ISSUER, the reap carrier's io_uring_enter calls
      * are rejected with -EEXIST, causing the reap loop to exit immediately and `closedFlag` to be set before any write is attempted.
      */
    private[posix] def selectRingFlags(kernelVersion: Int): Int =
        if kernelVersion >= 5019 then SetupCoopTaskrun | SetupTaskrunFlag
        else 0
    end selectRingFlags

    // io_uring SETUP flag values (liburing, stable). Used only after the kernel-version probe confirms support.
    private val SetupCoopTaskrun: Int = 1 << 8 // IORING_SETUP_COOP_TASKRUN  (5.19)
    private val SetupTaskrunFlag: Int = 1 << 9 // IORING_SETUP_TASKRUN_FLAG  (5.19)

    // IORING_FEAT_NODROP (bit 1, kernel >= 5.5): the kernel guarantees no CQE is dropped; it applies backpressure on submit
    // instead. When set, the indefinite park is safe by contract: no wake CQE can be lost so no cross-carrier submission can
    // be stranded. Exposed as private[posix] so RecordingIoUringBindings can compute nodropAvailable for test assertions.
    private[posix] val FeatNodrop: Int = 1 << 1

end IoUringDriver
