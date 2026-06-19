package kyo.net.internal.posix

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer

/** One submitted-but-not-yet-reaped io_uring operation, keyed by a dense `user_data` value.
  *
  * Each variant pins the off-heap memory the kernel owns for the duration of the operation, which is the heart of the UAF invariant: the
  * memory MUST stay alive until the operation's CQE is reaped. A [[Read]] pins the handle's reused `readBuffer`; a [[Write]] pins a
  * per-write `Buffer` that is closed only when its send CQE arrives and additionally carries the payload `offset` of its first byte and the
  * requested send `len`, so a partial send (`res < len`) can re-submit the unsent `[offset + res, offset + len)` tail; a [[TlsWrite]] pins
  * the per-send ciphertext `Buffer` the same way and carries the requested send length so a partial send can be re-submitted; a [[Connect]]
  * carries only the promise to complete (its `sockaddr` is pinned by the handle's `connectTarget`); an [[Accept]] pins the addr/addrlen
  * placeholder buffers that `kyo_uring_prep_accept` requires to stay alive until the single-shot accept CQE is reaped.
  * Each accepted connection uses one SQE and one CQE; the accept loop calls [[IoUringDriver.awaitAccept]] with a fresh promise
  * after each CQE to arm the next connection. The buffers are released via `releaseBuffer` when the CQE is processed.
  *
  * Every variant carries its [[handle]] so [[IoUringDriver.cancel]] can find every in-flight op for a handle, and the per-handle
  * in-flight count can be decremented when the CQE is reaped.
  */
private[net] enum PendingOp(val handle: PosixHandle):

    /** A recv submitted for `promise`. `eintrRetries` is the number of times this read has already been re-submitted because its CQE reaped
      * `-EINTR` (a signal interrupted the recv before any byte was transferred; POSIX recv(2) says to retry). It is carried across re-submissions
      * so the retry is bounded by [[IoUringDriver.maxTransientIoRetries]]: a fresh read starts at 0, and the reap re-submits with an incremented
      * count until the bound, past which the last `-EINTR` falls through to the normal hard-error branch (fail Closed) so an EINTR storm cannot spin.
      */
    case Read(promise: Promise.Unsafe[Span[Byte], Abort[Closed]], h: PosixHandle, eintrRetries: Int) extends PendingOp(h)

    /** A plaintext send: pins the per-write `buf` for the kernel for the duration of the send SQE. `offset` is the index of `buf`'s first byte
      * in the original payload span and `len` is the number of bytes this SQE was asked to send, so the reap can detect a partial send
      * (`res < len`) and re-submit the unsent `[offset + res, offset + len)` tail on a fresh per-write buffer (raw sends are held single-in-flight
      * per handle, so the remainder is sent before any later write). The buffer is per-write and IS closed on reap (unlike the TlsWrite mirror).
      */
    case Write(h: PosixHandle, buf: Buffer[Byte], offset: Int, len: Int) extends PendingOp(h)

    /** A TLS ciphertext send: pins the per-handle flush mirror for the kernel for the duration of the send SQE. Carries the requested send `len`
      * so the reap can detect a partial send (`res < len`) and re-submit the unsent ciphertext remainder (io_uring has no writability re-arm).
      * The buffer is the per-handle reused flush mirror; it must NOT be closed on reap (it is freed only in `freeResources`).
      */
    case TlsWrite(h: PosixHandle, buf: Buffer[Byte], len: Int)                 extends PendingOp(h)
    case Connect(promise: Promise.Unsafe[Unit, Abort[Closed]], h: PosixHandle) extends PendingOp(h)
    case Accept(
        promise: Promise.Unsafe[Int, Abort[Closed]],
        h: PosixHandle,
        noAddr: Buffer[Byte],
        noLen: Buffer[Int]
    ) extends PendingOp(h)

    /** Fail the promise this op carries with `closed`. A [[Write]] op carries no promise (its failure is surfaced on the write pump), so
      * this is a no-op for it.
      */
    def failPromise(closed: Closed)(using AllowUnsafe): Unit =
        this match
            case Read(promise, _, _)      => promise.completeDiscard(Result.fail(closed))
            case Connect(promise, _)      => promise.completeDiscard(Result.fail(closed))
            case Accept(promise, _, _, _) => promise.completeDiscard(Result.fail(closed))
            case Write(_, _, _, _)        => ()
            case TlsWrite(_, _, _)        => ()
        end match
    end failPromise

    /** Release the off-heap memory this op pinned for the kernel. Safe to call only after the op's CQE has been reaped.
      *
      * [[Write]] and [[Accept]] own per-op buffers and close them here. [[TlsWrite]] pins the per-handle reused flush mirror (owned by the
      * handle, freed only in `freeResources`); closing it here would be a use-after-free because the next flush refills the same buffer.
      * So [[TlsWrite]] is intentionally a no-op: the mirror survives the reap and is reused by the next flush.
      */
    def releaseBuffer()(using AllowUnsafe): Unit =
        this match
            case Write(_, buf, _, _)     => buf.close()
            case TlsWrite(_, _, _)       => () // per-handle reused mirror; freed only in freeResources, never on reap
            case Accept(_, _, addr, len) => addr.close(); len.close()
            case _                       => ()
end PendingOp

/** Completion-native I/O driver over io_uring (Linux >= 5.6), unified onto [[PosixHandle]] and the kyo-ffi [[IoUringBindings]].
  *
  * Unlike the readiness-based [[PollerIoDriver]] (epoll/kqueue: "fd is ready", then the caller reads), io_uring is already
  * completion-based: each operation is one self-identifying SQE submitted to the ring and keyed by a dense `user_data` value, and a
  * single reap loop drains the completion queue and fulfils the keyed promise. It presents the unchanged completion
  * `IoDriver[PosixHandle]` contract, identical to the poller arm from the caller's perspective.
  *
  * #### Bounded reap loop
  *
  * The reap loop runs on a dedicated carrier spawned by `start()` via `Fiber.Unsafe.init`. Each cycle uses `kyo_uring_submit_and_wait_timeout`
  * to flush accumulated SQEs AND wait for the next CQE in a single `io_uring_enter` syscall (bounded to ~100ms), then drains every
  * already-ready CQE with `kyo_uring_peek_cqe`. On JVM/Native the wait fiber completes synchronously and the loop continues via the `while`
  * body without growing the stack. On JS the wait fiber is genuinely pending; the loop exits the `while`, registers an `onComplete` callback,
  * and re-enters via a fresh `Fiber.Unsafe.init` on the next event-loop tick. A timeout with no CQE is a normal empty turn, not an error.
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
    // The ring and cqePtr are touched by the reap carrier inside its in-flight kyo_uring_submit_and_wait_timeout (which holds both segments for
    // up to ReapTimeoutNs) and by user carriers in kyo_uring_get_sqe. Their teardown must therefore be SINGLE-OWNER: when the reap loop was
    // started, only the reap carrier (on its own exit, after the wait has returned) frees them; close() then merely signals via closedFlag.
    // `started` records whether a reap carrier exists to own that teardown; `teardownDone` makes the actual free idempotent across the two
    // paths (reap-loop exit when started, close() inline when never started). See #177.
    started: AtomicBoolean.Unsafe,
    teardownDone: AtomicBoolean.Unsafe,
    // Set when the reap loop has exited (or never ran). The ring is touched by BOTH the reap carrier (wait/peek/seen) and the engine-FIFO
    // worker (flushTls/flushRaw get_sqe); io_uring_queue_exit frees the ring, so teardown must wait for BOTH to be done with it, not just the
    // reap carrier (#177 covered the reap carrier only). `tryTeardown` fires the exactly-once teardown when reapExited AND the FIFO worker is idle.
    reapExited: AtomicBoolean.Unsafe,
    keyGen: AtomicLong.Unsafe, // dense user_data keys (NOT fds: SQEs self-identify)
    pendingSubmits: AtomicLong.Unsafe,
    // The cqe pointer scratch is owned solely by the reap carrier (reapLoop and drainReady both run on it), allocated once for the driver
    // lifetime. Both methods are called sequentially on the same carrier: reapLoop calls drainReady inline after the wait, then loops; the two
    // sites never overlap.
    cqePtr: Buffer[Long]
) extends IoDriver[PosixHandle], TlsEngineIo:

    // Concurrent-collection audit: the raw java.util.concurrent maps and the ConcurrentLinkedQueue below are retained
    // deliberately. kyo has no concurrent-map type, and its effect-based Queue/Channel cannot back these non-parking hot syscall paths (the
    // user_data->op maps are touched without suspension on the reap and engine-FIFO carriers; the engineQueue is a lock-free single-consumer FIFO
    // drained inline at the top of each reap cycle). The per-field comments name each field's owning carrier; no raw type is shared unsafely.
    private val pending = new ConcurrentHashMap[Long, PendingOp]()

    // Cross-carrier submission handoff: every SQ operation (get_sqe + prep + submit) and every TLS engine op for every connection on this driver
    // runs on the single reap carrier, which drains this queue at the top of each reap cycle (see [[submitEngineOp]] / [[reapLoop]]). One producer
    // for the io_uring submission ring; no two engine ops overlap on the same engine because one carrier runs them in FIFO order.
    private val engineQueue = new ConcurrentLinkedQueue[() => Unit]()

    // handle id -> count of in-flight (submitted, not yet reaped) ops for that handle. A handle whose id appears in
    // `closeAfterDrain` is closed by the reap loop once this count drops to 0 (close runs only after the kernel releases the buffers).
    private val inFlight        = new ConcurrentHashMap[Long, Long]()
    private val closeAfterDrain = new ConcurrentHashMap[Long, PosixHandle]()

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

    // Bound on consecutive `-EINTR` retries for one logical read or send. POSIX recv(2)/send(2): a non-blocking call interrupted by a signal
    // before any byte is transferred completes with `-EINTR` and MUST be retried (no data was moved, the socket is unchanged), exactly as
    // PollerIoDriver retries EINTR in place (commit 5498ada6b) and as the accept path already retries it. The retry is bounded so a pathological
    // EINTR storm cannot spin the reap loop: past the bound the last `-EINTR` falls through to the existing hard-error branch (fail Closed for a
    // read, discard the tail for a send). 8 matches PollerIoDriver.maxTransientIoRetries and PosixTransport.maxTransientAcceptRetries.
    private val maxTransientIoRetries = 8

    def label: String = "IoUringDriver"

    def handleLabel(handle: PosixHandle): String = s"fd=${handle.readFd}/${handle.writeFd}"

    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        // STARTTLS single-recv gate. While `upgradeActive` is set the handshake owns the fd's read side exclusively: the only recv that may be in
        // flight is the one the plaintext ReadPump armed BEFORE the upgrade (kernel-owned, uncancellable, carrying the peer's first post-signal
        // flight), which the reap routes through `upgradeHandoff`. Any recv arm REQUESTED while the flag is set is a stray plaintext-ReadPump re-arm
        // racing the upgrade (the ReadPump's last plaintext read re-armed after detachForUpgrade cancelled it, on its reused callback-less promise);
        // arming it would put a second recv on the fd that wins the peer's flight and drops the bytes onto the dead promise, stranding the handshake
        // (the STARTTLS upgrade stall). Drop it at the source: the ReadPump is being torn down, so its promise never needing to complete is correct.
        // The handshake's own ciphertext recv is never gated here: driveUpgradeRead clears `upgradeActive` BEFORE it calls awaitReadCiphertext, so
        // the handshake arm always reads the flag false. The flag is volatile and read synchronously on the calling carrier, so the discriminator is
        // "was the upgrade active at the instant this arm was requested", which a stray re-arm always satisfies and the handshake arm never does.
        // False on the pollers (upgradeActive is never set there: synchronous reads hold no kernel-owned recv), so this is io_uring-only.
        if handle.upgradeActive then ()
        else
            // Arm the recv on the reap carrier (via the engine queue) so get_sqe has a single producer. The closedFlag check runs there too: a submit
            // on a ring being torn down is a use-after-free (#177), and once closing, the reap-loop exit drains this op and fails the promise. The
            // reap loop's own -EINTR re-submit calls submitRecv directly, already on the reap carrier.
            submitEngineOp { () =>
                if closedFlag.get() then promise.completeDiscard(Result.fail(Closed(label, summon[Frame], "driver closed")))
                else submitRecv(handle, promise, eintrRetries = 0)
            }
        end if
    end awaitRead

    /** Submit one recv SQE for `promise`, recording `eintrRetries` (the count of prior re-submissions caused by a `-EINTR` read CQE) on the
      * pending op so the reap can bound the EINTR retry. The public [[awaitRead]] enters at 0; the reap's `-EINTR` re-submit enters at the
      * incremented count.
      */
    private def submitRecv(handle: PosixHandle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]], eintrRetries: Int)(using
        AllowUnsafe,
        Frame
    ): Unit =
        if handle.readBuffer.isClosed then
            // The handle was closed (its buffers freed) before this deferred arm ran on the reap carrier; never point a recv SQE at freed memory.
            // Fail the read so the caller's pump tears down. closeNow (the buffer free) also runs on the reap carrier, so this arm and the free are
            // serialized: either the arm wins and the close defers behind the recv CQE, or the buffer is already closed and this branch fires.
            promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"fd=${handle.readFd} closed")))
        else
            val key = keyGen.getAndIncrement()
            register(key, PendingOp.Read(promise, handle, eintrRetries))
            uring.kyo_uring_get_sqe(ring) match
                case Present(sqe) =>
                    // For TLS handles: point the recv SQE directly at the per-handle staging buffer so the kernel fills it without an extra copy.
                    // The staging buffer is lazily allocated on the first TLS read (recvStagingFor) and reused across reads. A single TLS recv is
                    // in flight per handle at a time (re-arm happens only inside the engine op after the ciphertext is consumed), so the kernel
                    // never overwrites the staging with a second recv before the engine op feeds the first.
                    // For non-TLS handles: read into the handle's reused readBuffer (the raw path is unchanged).
                    val recvTarget =
                        handle.tls match
                            case Present(_) => recvStagingFor(handle)
                            case Absent     => handle.readBuffer
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
                    end if
                case Absent =>
                    // SUBMISSION queue full: no recv SQE is in flight, so no CQE will re-drive this read. Park it (the promise stays pending) and
                    // re-arm after the next CQE batch frees a slot (reArmStalledSubmits), mirroring stalledRaw for sends so a transient SQ-full
                    // backpressures the read instead of failing the connection. unregister first: the key is re-assigned on re-submit.
                    unregister(key)
                    discard(stalledSubmits.add(PendingOp.Read(promise, handle, eintrRetries)))
            end match
        end if
    end submitRecv

    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        if handle.unsentTailBytes >= PosixHandle.WriteTailLowWater then
            // Tail-bound park (CWE-400): the pump suspended because the write tail reached the high-water mark, not because of a kernel send-buffer
            // limit (io_uring's send is async and self-drains via the in-flight SQE's CQE re-flush). Completing the promise now (the old immediate
            // signal) would busy-loop: the pump would retry, hit the high-water bound again, get Partial, and await again with no progress. Instead,
            // hold the promise on the handle; the CQE re-flush path (onTlsSendComplete / onRawSendComplete) completes it via releaseBackpressureWaiter
            // once the tail falls below the low-water mark. An in-flight send is guaranteed: the tail only reaches the high-water mark through a write
            // whose flush submitted a send SQE (or coalesced behind one), and that SQE's reap re-flushes and re-checks the waiter.
            handle.backpressureWaiter = Present((promise, summon[Frame]))
            // Double-check on the FIFO worker: a CQE re-flush may have drained the tail below the low-water mark between the check above and this
            // registration (the reap runs on the FIFO worker, this runs on the pump carrier). Routing the re-check through the FIFO observes a
            // consistent tail snapshot (no race with onTlsSendComplete) and completes the just-registered waiter if the drain already happened, so
            // the pump is never stranded. A waiter parked while the handle is closing is failed by PosixHandle.freeResources (it completes this
            // promise with Closed using the frame captured here), so the close-vs-park race never strands the pump.
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
        // Reject a write once the driver is closing: flushRaw/flushTls would get_sqe on a ring the reap carrier is tearing down (#177).
        // beginWrite() guards the per-HANDLE lifecycle but not the driver's; the pump treats Error as teardown, which is correct here.
        if closedFlag.get() then WriteResult.Error
        else if data.isEmpty || offset >= data.size then WriteResult.Done
        else if handle.unsentTailBytes >= PosixHandle.WriteTailHighWater then
            // Write-backpressure bound (CWE-400): the write tail (pendingCipher for TLS, rawPending for raw) has reached the high-water mark because
            // the in-flight send is not draining it fast enough (a slow- or no-read peer). io_uring's write is async and always returns Done after
            // appending, so without this gate the WritePump would keep pulling spans and the tail would grow without limit toward OOM. Report Partial
            // so the pump parks on writability instead of appending; the data is not consumed (re-presented unchanged on retry), and awaitWritable
            // holds the pump until the in-flight send's CQE re-flush drains the tail below the low-water mark (releaseBackpressureWaiter). Checked
            // before beginWrite so an over-bound write touches no guard.
            WriteResult.Partial(data, offset)
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

    /** Submit one send SQE for the unsent region `[pendingCipherSent, size)` of the handle's pending ciphertext tail, holding AT MOST ONE TLS send
      * SQE in flight per handle. Engine-FIFO-worker-only.
      *
      * Unlike the poller's inline-send flush, this does not loop: io_uring's send completes asynchronously, so the unsent region is copied into a
      * pinned `Buffer` and handed to a single send SQE; the partial-send re-flush happens when that SQE's CQE is reaped ([[onTlsSendComplete]]).
      *
      * The [[PosixHandle.sendInFlight]] guard is what keeps the asynchronous send correct: it is the completion-driver twin of the poller's
      * `writableArmed` coalesce. If a send is already in flight this returns immediately WITHOUT submitting; the freshly-encrypted ciphertext a
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
            if op.handle.id == handle.id then op.failPromise(closed)
        }
        // Fail any WritePump promise parked at the write-backpressure high-water bound. It is held on the handle (a tail-bound park, not a pending
        // SQE), so it is not in `pending`; releasing it with Closed lets the pump tear down rather than hang on a tail that will never drain.
        handle.backpressureWaiter.foreach { case (p, _) => p.completeDiscard(Result.fail(closed)) }
        handle.backpressureWaiter = Absent
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

    /** True when a recv SQE for `handle` is kernel-owned (registered in `pending`). The STARTTLS upgrade read path consults this ON THE REAP CARRIER
      * (inside the handshake's `driveUpgradeRead`, which runs as an engine op) to learn whether the plaintext ReadPump left a stale recv that will
      * consume the peer's first handshake flight; if so the handshake routes through the handle's `upgradeHandoff` instead of issuing a second, racing
      * recv. `pending` alone is authoritative here: the genuine stale recv was armed BEFORE `upgradeActive` was set, so its `submitRecv` registered it
      * on the reap carrier before this `driveUpgradeRead` op runs (FIFO). A stray ReadPump re-arm requested DURING the upgrade can never register a
      * competing recv: `awaitRead`'s single-recv gate drops any arm requested while `upgradeActive` is set, so it never reaches `pending` (and the
      * earlier queued-arm counting that guarded against it is no longer needed). Scans `pending` (close-path cost, not the hot read path); a handle
      * has at most one recv in flight at a time.
      */
    override def hasInFlightRead(handle: PosixHandle)(using AllowUnsafe): Boolean =
        val it    = pending.values().iterator()
        var found = false
        while !found && it.hasNext do
            it.next() match
                case PendingOp.Read(_, h, _) if h.id == handle.id => found = true
                case _                                            => ()
        end while
        found
    end hasInFlightRead

    // io_uring reads are kernel-owned recv SQEs, so the TLS handshake must NOT mix a direct recv(2) probe with them on the same fd (the two race the
    // socket stream into handle.readBuffer under load, fabricating a corrupt handshake record). The handshake reads exclusively through awaitRead.
    override def inlineRecvSafe: Boolean = false

    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        // Route the engine free through the engine queue so it is serialized behind any read/write engine ops for this connection (no two
        // carriers touch one ssl). Installed before the close path can fire so freeResources sees the sink whether the close runs now or is
        // deferred until the in-flight count drains.
        handle.engineFreeSink = op => submitEngineOp(op)
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
    end closeHandle

    /** Register the handle for the deferred close, or close now when nothing is in flight. Factored so the plaintext close and the post-
      * close_notify TLS close share the same close-vs-reap race handling.
      */
    private def registerDeferredClose(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        if Maybe(inFlight.get(handle.id)).getOrElse(0L) <= 0L then closeNow(handle)
        else
            // Force any in-flight recv to EOF so its CQE reaps and the deferred close runs even against an idle peer that sends nothing: a recv SQE
            // is kernel-owned and close(fd) alone does NOT complete it (io_uring holds its own reference to the file). shutdown the READ half only
            // (SHUT_RD) so a TLS close_notify or a queued raw send on the write half still flushes before closeNow sends the FIN. Sockets only
            // (readFd == writeFd); stdio (0/1) is process-owned and left untouched. Same mechanic the handshake-deadline reap uses (#243).
            if handle.readFd == handle.writeFd then discard(sockets.shutdown(handle.readFd, PosixConstants.SHUT_RD))
            // Register the deferred close, then re-check: if the reap loop drained the count between the read above and this put, it already
            // ran (and removed) nothing, so claim the registration back and close here. This closes the close-vs-reap race either way.
            discard(closeAfterDrain.put(handle.id, handle))
            if Maybe(inFlight.get(handle.id)).getOrElse(0L) <= 0L then
                Maybe(closeAfterDrain.remove(handle.id)).foreach(closeNow)
        end if
    end registerDeferredClose

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
        // Drop the handle's send-EINTR retry count so the map does not retain an entry for a closed handle.
        discard(sendEintrRetries.remove(handle.id))
        // Close the socket fd: io_uring has no prep_close and PosixHandle.close frees only the buffers, so the fd is closed here through
        // SocketBindings (mirroring the poller's closeHandle). close(fd) sends the FIN, so the peer observes the close (without this an io_uring
        // connection close never reached the peer). One-shot via claimFdClose so a racing transport-path close (a connect / STARTTLS failure, or
        // the handshake-deadline reap) does not double-close a possibly-recycled fd. sockets set readFd == writeFd; stdio (0/1) is process-owned.
        if handle.readFd == handle.writeFd && handle.claimFdClose() then discard(takeNow(sockets.close(handle.readFd)))
        PosixHandle.close(handle)
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
            // Single-owner teardown (#177): the reap carrier may be parked inside kyo_uring_submit_and_wait_timeout, holding the ring and cqePtr
            // segments for up to ReapTimeoutNs. Freeing them here, on a different carrier, while that wait is in flight is a use-after-free
            // ("Session is acquired by 1 clients" at cqePtr.close on JVM; SIGSEGV in kyo_uring_get_sqe on Native). So when a reap carrier
            // exists, only SIGNAL: it observes closedFlag, exits its loop after the current wait returns, and tears the ring down on its own
            // carrier where no ring op is in flight. When the loop was never started (a driver built but closed before start()), no wait is in
            // flight and there is no reap carrier, so tear down here. The teardownDone CAS keeps it exactly-once across all paths. Even with no reap carrier the
            // engine-FIFO worker may still be running a queued op that calls get_sqe (e.g. a connection's close_notify emit submitted just
            // before this close), so go through tryTeardown rather than freeing the ring out from under it.
            if !started.get() then
                reapExited.set(true)
                tryTeardown()
        end if
    end close

    /** Free the ring exactly once, but ONLY when no carrier can still touch it: the reap loop has exited (reapExited) AND the engine-FIFO
      * worker is idle (no op in flight or queued that would call get_sqe). Called from the reap-loop exit, from the FIFO worker when it drains
      * to idle while closing, and from close() when no reap loop ran; whichever observes both conditions last performs the teardown. The
      * teardownDone CAS inside teardownRing keeps it exactly-once across those callers.
      */
    private def tryTeardown()(using AllowUnsafe, Frame): Unit =
        if closedFlag.get() && reapExited.get() && engineQueue.isEmpty then teardownRing()

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
            pending.clear()
            inFlight.clear()
            closeAfterDrain.clear()
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
            uring.io_uring_queue_exit(ring)
            cqePtr.close()
        end if
    end teardownRing

    // Unsafe: Fiber.Unsafe.init spawns the reap-loop carrier without re-entering the effect system. On JVM/Native the @Ffi.blocking
    // wait fiber completes synchronously and the while loop body runs inline, without growing the stack. On JS the wait fiber is
    // genuinely pending; we exit the while loop, register an onComplete callback, and re-enter via a fresh Fiber.Unsafe.init from the
    // libuv event loop (fresh stack frame, no stack growth). The onComplete path is NOT taken on JVM/Native because done() is always
    // true: this avoids the StackOverflowError that naive self-recursive onComplete would cause (IOPromise.eval fires onComplete inline
    // when the fiber is already done; it is NOT a trampoline).
    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Mark BEFORE spawning the carrier: a close() racing start observes started=true and defers teardown to the reap loop, which (once
        // spawned) sees closedFlag set, runs zero iterations, and tears the ring down itself. started.set happens-before the spawn.
        started.set(true)
        Fiber.Unsafe.init { reapLoop() }
    end start

    private val ReapTimeoutNs = 100_000_000L // 100ms bounded wait; a timeout with no CQE is a normal empty turn, not an error

    // IORING_CQE_F_MORE = (1U << 1) = 2. When set on a CQE, the submission is still live and will fire more CQEs (multishot lifecycle).
    private val CqeFMore = 2

    /** Run the reap loop on the current carrier. Submits accumulated SQEs and waits at most ~100ms for a CQE via the fused submit-and-wait
      * enter, then drains every ready CQE and completes the keyed promises. On JVM/Native the wait fiber is already done() on return; the while
      * loop continues inline without stack growth. On JS the wait fiber is genuinely pending: the while loop exits, an onComplete callback
      * re-enters via a fresh `Fiber.Unsafe.init`. The `cqePtr` buffer is the driver field allocated once at construction; it is safe because
      * reapLoop and drainReady run sequentially on the same carrier (drainReady is called inline before the next wait).
      */
    private def reapLoop()(using AllowUnsafe, Frame): Unit =
        var running = !closedFlag.get()
        // JS-only: the while also exits to re-enter on a fresh stack frame (the wait is genuinely pending), which is NOT a stop. `reenter`
        // separates that case from a true stop so the single-owner ring teardown below runs only when the loop is actually done.
        var reenter = false
        while running do
            // Single-producer SQ: prepare every queued SQE (reads/writes/connects/accepts + TLS engine ops) on THIS carrier, then fuse
            // submit+wait in one io_uring_enter syscall. Because the fused enter submits AND reads the SQ tail, no other carrier may
            // touch the SQ; the engine queue is the cross-carrier handoff and this drain is its only consumer.
            drainEngineOps()
            // kyo_uring_submit_and_wait_timeout submits accumulated SQEs AND waits for a CQE in one io_uring_enter syscall.
            // The pendingSubmits counter is consumed here implicitly: the fused enter submits everything in the SQ ring at the time of the call.
            // We reset pendingSubmits so the count stays consistent for the submitPrepared short-count retry path.
            discard(pendingSubmits.getAndSet(0L))
            val waitFiber = uring.kyo_uring_submit_and_wait_timeout(ring, cqePtr, ReapTimeoutNs) // sanctioned bounded park
            if waitFiber.done() then
                // JVM/Native inline-completion path: extract result and continue the while loop without growing the stack.
                // rc is a raw signed Int; isTimeout operates on the raw negative -ETIME (a POSIX-clamped -1 would lose the errno identity).
                waitFiber.poll() match
                    case Present(Result.Success(w)) =>
                        val rc = w.eval
                        if rc != 0 && !isTimeout(rc) then running = false
                        else
                            if rc == 0 then drainReady(cqePtr.get(0))
                            // Re-arm parked ops every turn (a CQE arrived, or -ETIME: an empty turn). The fused submit+wait freed the SQ slots,
                            // so a recv/accept/connect/send parked on SQ-full re-arms now rather than stranding until some other CQE arrives.
                            reArmStalled()
                        end if
                    case Present(Result.Failure(_)) => running = false
                    case Present(Result.Panic(_))   => running = false
                    case Absent                     => ()
                end match
                if running then running = !closedFlag.get()
            else
                // JS path: genuinely pending. Exit while, re-enter on a fresh stack frame when the wait resolves.
                running = false
                reenter = true
                waitFiber.onComplete {
                    case Result.Success(w) =>
                        val rc = w.eval
                        if rc == 0 || isTimeout(rc) then
                            if rc == 0 then drainReady(cqePtr.get(0))
                            // Re-arm parked ops every turn (CQE or -ETIME), as in the inline path: SQ space is freed by submit, not by reaping.
                            reArmStalled()
                            if !closedFlag.get() then discard(Fiber.Unsafe.init { reapLoop() })
                        end if
                    case _ => ()
                }
            end if
        end while
        // Single-owner ring teardown (#177). A true stop (closedFlag set by close(), or a wait Failure/Panic) leaves this carrier OUTSIDE any
        // ring op: the wait has returned and released the ring/cqePtr segments, so freeing them here is race-free, unlike close() doing it from
        // another carrier mid-wait. Mark closedFlag first so any late submit rejects before get_sqe (a Failure/Panic stop reaches here without
        // close() having set it). The JS re-enter case skips this: it is not a stop, and io_uring is JVM/Native-only regardless.
        if !reenter then
            closedFlag.set(true)
            reapExited.set(true)
            // Run every still-queued op once more: closedFlag is set, so each arming op fails its promise through the `awaitRead`/`submitRecv`
            // driver-closed rejection rather than being dropped (which would strand a read/write pump waiting on a promise that never completes).
            // The reap carrier is the sole ring owner, so once it has drained and exited no other carrier can touch the ring.
            drainEngineOps()
            tryTeardown()
        end if
    end reapLoop

    /** Drain the CQE ring starting from `firstCqe`, completing each keyed promise in turn. Peeks additional CQEs via
      * `kyo_uring_peek_cqe` until the queue is empty. Uses the driver-field `cqePtr` (allocated once at construction,
      * safe because drainReady is called only from reapLoop, which owns the reap carrier exclusively).
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

    /** Re-arm every operation that parked on a full submission queue: raw sends in [[stalledRaw]] and recv/accept/connect in [[stalledSubmits]].
      * Called once per reap turn from [[reapLoop]] AFTER the fused submit-and-wait has submitted the turn's accumulated SQEs to the kernel and
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
                    case PendingOp.Read(promise, h, eintrRetries)    => submitRecv(h, promise, eintrRetries)
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
                op match
                    case PendingOp.Read(promise, h, eintrRetries) =>
                        if res < 0 && -res == PosixConstants.EINTR && eintrRetries < maxTransientIoRetries && !h.isClosing() then
                            // EINTR read CQE: a signal interrupted the recv before any byte was transferred (POSIX recv(2)); no data was moved and
                            // the socket still holds whatever the peer sent, so re-submit the recv on the SAME promise rather than failing it Closed
                            // and dropping a healthy connection (the io_uring twin of PollerIoDriver's recvNowWithRetry). Bounded by
                            // maxTransientIoRetries via the carried count so an EINTR storm cannot spin: past the bound the `else` below fails Closed.
                            // Skipped when the handle is already closing (a concurrent close): re-arming a freed handle would be a use-after-free.
                            submitRecv(h, promise, eintrRetries + 1)
                        else if h.upgradeActive && !h.isClosing() then
                            // STARTTLS stale recv: this recv was armed by the plaintext ReadPump before detachForUpgrade detached the connection, and
                            // io_uring cannot cancel it, so it just consumed the peer's first post-signal flight (the ClientHello / ServerHello). The
                            // discriminator is h.upgradeActive alone, NOT the recv promise state: the ReadPump reuses ONE promise object across reads
                            // (becomeAvailable resets it and re-arms), so detachForUpgrade's cancel may fail one arming while the pump re-arms a fresh
                            // one whose promise is still live when this CQE reaps. A `promise.done()` guard therefore mis-classifies that live-promise
                            // stale recv as a normal read and feeds the flight to the torn-down ReadPump, stranding the handshake's parked waiter (the
                            // residual stall). While upgradeActive is set the handshake never arms its own recv (it routes through driveUpgradeRead,
                            // which issues none), so the ONLY recv in flight is the stale one and its bytes always belong to the handshake. Route them
                            // through the single upgradeHandoff slot (fulfil the waiter it parked, or stage a Carryover when it has not parked yet)
                            // instead of the settled/reused promise. upgradeActive is NOT cleared here: it must stay set until the handshake CARRIER
                            // actually consumes the bytes (driveUpgradeRead), otherwise a recvAndFeed running between this clear and the consume would
                            // read upgradeActive false, skip driveUpgradeRead, issue its own recv, and strand the staged Carryover. The handshake
                            // clears it once it takes the bytes; exactly one stale recv exists, so this never re-fires on a later normal recv.
                            import PosixHandle.UpgradeHandoff
                            if res > 0 then
                                val arr = Buffer.copyToArray[Byte](h.readBuffer, 0, res)
                                h.upgradeHandoff.get() match
                                    case parked: UpgradeHandoff.Waiter =>
                                        // The handshake already parked; claim the waiter by CAS to Idle (against the exact instance read: reference
                                        // equality, and the handshake parks exactly once per upgrade so this cannot lose to another park) and fulfil it.
                                        discard(h.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle))
                                        parked.promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
                                    case _ =>
                                        // The handshake has not parked yet: stage the bytes for its next read. If the CAS loses, the handshake parked a
                                        // Waiter in the window between the read above and this CAS; a SINGLE re-read then observes that Waiter and
                                        // fulfils it (no loop, no spin: the handshake parks at most once per upgrade).
                                        if !h.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, UpgradeHandoff.Carryover(arr)) then
                                            h.upgradeHandoff.get() match
                                                case parked: UpgradeHandoff.Waiter =>
                                                    discard(h.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle))
                                                    parked.promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
                                                case _ => ()
                                end match
                            else
                                // EOF (res == 0) or error (res < 0) during the upgrade: fail the parked waiter so the handshake tears down. If it has
                                // not parked yet there is nothing to stage; the handshake's own first read then observes the closed/EOF fd.
                                h.upgradeHandoff.get() match
                                    case parked: UpgradeHandoff.Waiter =>
                                        discard(h.upgradeHandoff.compareAndSet(parked, UpgradeHandoff.Idle))
                                        if res == 0 then parked.promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                                        else
                                            parked.promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read errno=${-res}")))
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
                                    submitEngineOp { () =>
                                        val plain = feedAndDecrypt(engine, staging, res, h)
                                        // Fatal-record check (mirrors the poller's rearmOwned endDispatch closing check): feedAndDecrypt calls
                                        // handle.requestClose() on a fatal TLS record (readPlain == -2). The io_uring read CQE holds no dispatch
                                        // guard, so requestClose runs immediately; the handle is closing or already closed. Failing the read
                                        // promise Closed here tears the connection down on io_uring exactly as the poller's rearmOwned / endDispatch
                                        // does when the dispatch guard observes the close bit.
                                        if h.isClosing() then
                                            promise.completeDiscard(Result.fail(Closed(
                                                label,
                                                summon[Frame],
                                                s"fatal TLS record fd=${h.readFd}"
                                            )))
                                        else if plain.length > 0 then
                                            promise.completeDiscard(Result.succeed(Span.fromUnsafe(plain)))
                                            // Flush any ciphertext the read produced (e.g. a TLS 1.3 KeyUpdate response queued by the engine
                                            // during the decode). drainReadProducedCiphertext (inside feedAndDecrypt) appended it to
                                            // pendingCipher; flushTls submits a send SQE for the unsent region so it reaches the peer.
                                            // This mirrors the poller's writableArmed / armWritableForFlush machinery that drains the tail
                                            // after every write engine op. No flush is submitted when pendingCipher is empty (the common path
                                            // for reads that produce no outbound ciphertext) or when a send is already in flight.
                                            flushTls(h)
                                        else if h.peerCleanClose then
                                            // The peer's close_notify was consumed (RFC 8446 6.1 orderly close): deliver EOF (empty Span) so the
                                            // ReadPump tears down, rather than re-arming for ciphertext the peer will never send. closeReason then
                                            // reports CleanClose, not Truncated. Mirrors the poller's dispatchReadTls clean-close branch.
                                            promise.completeDiscard(Result.succeed(Span.empty[Byte]))
                                        else
                                            // Only handshake / partial-record bytes consumed: submit another recv on the same promise rather than
                                            // signalling EOF, mirroring the poller's rearmOwned. Then flush any read-produced ciphertext so a
                                            // KeyUpdate response is not held until the next app-data write.
                                            awaitRead(h, promise)
                                            flushTls(h)
                                        end if
                                    }
                                case Absent =>
                                    val arr = Buffer.copyToArray[Byte](h.readBuffer, 0, res) // right-sized copy out
                                    promise.completeDiscard(Result.succeed(Span.fromUnsafe(arr)))
                        else if res == 0 then
                            // Peer close via a bare TCP FIN (no close_notify, else the clean-close branch above delivered EOF first): record the
                            // truncation condition so closeReason reports Truncated, then deliver EOF.
                            h.peerEof = true
                            promise.completeDiscard(Result.succeed(Span.empty[Byte])) // EOF
                        else promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"read errno=${-res}")))
                        end if
                    case PendingOp.Write(h, _, _, len) =>
                        // The raw-send CQE was reaped: account for it (advance the pending tail, re-submit the remainder on a partial send or any
                        // coalesced bytes) on the engine FIFO worker so rawPending stays single-owner, mirroring the TlsWrite path. The pinned
                        // per-write send buffer is released below via releaseBuffer(); onRawSendComplete re-flushes from the handle's tail, not
                        // from that buffer, so it is safe to close on reap. `res` is clamped to the submitted len (the kernel never reports more).
                        val sent = if res < 0 then res else math.min(res, len)
                        submitEngineOp { () => onRawSendComplete(h, sent) }
                    case PendingOp.TlsWrite(h, _, len) =>
                        // The TLS-send CQE was reaped: account for it (advance the tail, re-submit the remainder on a partial send) on the engine
                        // FIFO worker so pendingCipher stays single-owner. The pinned send buffer is released below via releaseBuffer().
                        val sent = if res < 0 then res else math.min(res, len)
                        submitEngineOp { () => onTlsSendComplete(h, sent) }
                    case PendingOp.Connect(promise, _) =>
                        if res == 0 then promise.completeDiscard(Result.succeed(()))
                        else promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"connect errno=${-res}")))
                    case PendingOp.Accept(promise, _, _, _) =>
                        if res >= 0 then promise.completeDiscard(Result.succeed(res))
                        else promise.completeDiscard(Result.fail(Closed(label, summon[Frame], s"accept errno=${-res}")))
                end match
                op.releaseBuffer() // release per-op buffers (Write send buf, Accept addr/len) now that the CQE is reaped
                decrementInFlight(op.handle)
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
        if more then
            // Single-shot accept and recv do not set F_MORE. If the kernel sets it unexpectedly (future op or kernel behavior change),
            // treat the CQE as single-shot: complete and remove the entry so no stale pending key accumulates.
            Log.live.unsafe.warn(s"$label unexpected IORING_CQE_F_MORE for key=$key res=$res; treating as single-shot")
            complete(key, res)
        else complete(key, res)
        end if
    end completeMultishot

    /** Register a pending op: store it under `key` and increment its handle's in-flight count (the count the close handshake drains). */
    private def register(key: Long, op: PendingOp)(using AllowUnsafe): Unit =
        discard(pending.put(key, op))
        discard(inFlight.merge(op.handle.id, 1L, (a, b) => a + b))
    end register

    /** Undo a [[register]] for an op that was never submitted (SQ full): drop the pending entry and decrement the in-flight count. */
    private def unregister(key: Long)(using AllowUnsafe, Frame): Unit =
        Maybe(pending.remove(key)).foreach(op => decrementInFlight(op.handle))

    /** Decrement a handle's in-flight count after its CQE is reaped (or its unsubmitted op is dropped). When the count reaches 0 and the
      * handle is awaiting a deferred close, the close runs now: the kernel has released every buffer, so `PosixHandle.close` is safe.
      */
    private def decrementInFlight(handle: PosixHandle)(using AllowUnsafe, Frame): Unit =
        val remaining = inFlight.merge(handle.id, -1L, (a, b) => a + b)
        if remaining <= 0L then
            discard(inFlight.remove(handle.id))
            Maybe(closeAfterDrain.remove(handle.id)).foreach(closeNow)
        end if
    end decrementInFlight

    private def submitBatched()(using AllowUnsafe): Unit = discard(pendingSubmits.incrementAndGet())

    private def flushSubmits()(using AllowUnsafe, Frame): Unit =
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
      * arming and the TLS engine ops all enqueue here; [[reapLoop]] drains the queue at the top of each cycle, before it flushes and waits. The
      * queue is the only cross-carrier handoff; running the op is single-owner. An op enqueued while the reap carrier is parked in the bounded
      * wait runs when that wait next returns (on a CQE or the ~100ms timeout).
      */
    override def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(engineQueue.offer(op))
    end submitEngineOp

    /** Drain every queued engine/submission op in order on the CURRENT (reap) carrier, running each to completion before the next, then return.
      * Called inline from [[reapLoop]] each cycle (and once more at reap-loop exit so a close fails every still-queued op's promise via the
      * closedFlag rejection in `awaitRead`/`submitRecv`). A throwing op must not abort the drain: that would strand every later op (a
      * multi-connection silent hang, Netty #7337 class), so the failure is logged and the drain continues.
      */
    @scala.annotation.tailrec
    private def drainEngineOps()(using AllowUnsafe, Frame): Unit =
        engineQueue.poll() match
            case null => ()
            case op =>
                try op()
                catch case ex: Throwable => Log.live.unsafe.error(s"$label engine op threw; the reap carrier continues draining", ex)
                end try
                drainEngineOps()
    end drainEngineOps

    /** Whether a wait result is the benign "timed out, no CQE" signal (`-ETIME`), a normal empty turn rather than a failure. The
      * argument is the return value of the fused submit-and-wait enter (0 ready / -ETIME timeout / -errno error), not the captured errno:
      * like every liburing call it returns the result directly and does not reliably set the global errno, so a stale errno must never
      * decide whether a ready CQE gets drained or the reap loop stops (#258). The `+ETIME` arm is defensive for any platform whose
      * wrapper surfaces the timeout as a positive code.
      */
    private def isTimeout(waitResult: Int): Boolean =
        waitResult == -PosixConstants.ETIME || waitResult == PosixConstants.ETIME

    /** Current count of consecutive `-EINTR` send CQE completions for `handle` (0 when none has occurred since the last non-EINTR send).
      * Engine-FIFO-worker-only, the single owner of [[sendEintrRetries]].
      */
    private def sendEintrCount(handle: PosixHandle): Int =
        Maybe(sendEintrRetries.get(handle.id)).getOrElse(0)

    /** Record one more `-EINTR` send CQE for `handle`, advancing the bounded retry count. Engine-FIFO-worker-only. */
    private def bumpSendEintr(handle: PosixHandle): Unit =
        discard(sendEintrRetries.merge(handle.id, 1, (a, b) => a + b))

    /** Clear the `-EINTR` send retry count for `handle` after a non-EINTR send completion, so the bound counts only an uninterrupted EINTR run.
      * Engine-FIFO-worker-only.
      */
    private def clearSendEintr(handle: PosixHandle): Unit =
        discard(sendEintrRetries.remove(handle.id))

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

    /** Build a driver over a freshly initialized io_uring ring. The ring lives in a caller-owned `Buffer[Byte]` of `kyo_uring_sizeof()` bytes
      * (the SQ/CQ mmaps are owned internally by liburing). Throws `Closed` if `io_uring_queue_init` fails (e.g. the kernel is too old or
      * the process is sandboxed from io_uring at the production ring depth).
      */
    def init(config: kyo.net.TransportConfig)(using AllowUnsafe, Frame): IoUringDriver =
        val uring = Ffi.load[IoUringBindings]
        val depth = math.max(256, config.ioPoolSize * 64)
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
        // cleaned up internally; no queue_exit is owed). See #258.
        val rc = uring.io_uring_queue_init(depth, ring, flags)
        if rc != 0 then
            ring.close()
            throw Closed("IoUringDriver", summon[Frame], s"queue_init failed: rc=$rc flags=$flags")
        // io_uring has no prep_close SQE, so the connection-close fd shutdown/close goes through SocketBindings (the same library the poller uses).
        init(uring, ring, Ffi.load[SocketBindings])
    end init

    /** Build a driver over caller-supplied uring bindings, an initialized ring, and socket bindings, allocating the driver's unsafe fields (the
      * atomic flags/counters and the cqe scratch) under the caller's `AllowUnsafe`: the construction site propagates the capability rather than
      * each field bridging it, so the class body never holds an ambient `AllowUnsafe` and every method keeps requiring its own. Shared by [[init]]
      * and the test construction helpers so the unsafe allocation lives in one place.
      */
    private[posix] def init(uring: IoUringBindings, ring: Buffer[Byte], sockets: SocketBindings)(using AllowUnsafe): IoUringDriver =
        new IoUringDriver(
            uring = uring,
            ring = ring,
            sockets = sockets,
            closedFlag = AtomicBoolean.Unsafe.init(false),
            started = AtomicBoolean.Unsafe.init(false),
            teardownDone = AtomicBoolean.Unsafe.init(false),
            reapExited = AtomicBoolean.Unsafe.init(false),
            keyGen = AtomicLong.Unsafe.init(1L),
            pendingSubmits = AtomicLong.Unsafe.init(0L),
            cqePtr = Buffer.alloc[Long](1)
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

end IoUringDriver
