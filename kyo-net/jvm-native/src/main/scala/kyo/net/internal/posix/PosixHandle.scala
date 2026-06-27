package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import kyo.net.internal.util.HandleId

/** Unified raw-fd handle (the unification of the transport layer).
  *
  * A single handle type backs every posix connection on every platform, replacing the retired native single-fd handle and standing alongside
  * the JVM `SocketChannel` Nio floor. It splits the file descriptor into a read end and a write end: sockets set `readFd == writeFd` (one fd carries both
  * directions), while stdio sets `(readFd = 0, writeFd = 1)`. Every read path keys on [[readFd]] and every write path on [[writeFd]], so the
  * driver machinery is identical for the socket and stdio cases.
  *
  * The handle carries a single [[readBuffer]] allocated once at creation and reused for every read syscall, avoiding a per-read allocation,
  * and a monotonic [[id]] used to reject events for a recycled file descriptor (a closed fd's number can be handed back to a new
  * connection before a stale readiness event is dispatched; the id distinguishes them). A client-connect socket additionally stashes its
  * [[connectTarget]] (the encoded `sockaddr` buffer and length) so the io_uring driver can submit an `IORING_OP_CONNECT` SQE; the readiness
  * poller ignores it.
  */
final private[net] class PosixHandle private (
    val readFd: Int,
    val writeFd: Int,
    val id: HandleId,
    private var readBufferField: Buffer[Byte],
    private var readBufferSizeField: Int,
    @volatile var tls: Maybe[TlsEngine],
    val connectTarget: Maybe[(Buffer[Byte], Int)],
    // One-shot claim for closing the fd (see claimFdClose), so the fd shutdown/close happens exactly once even on a recycled fd.
    fdCloseClaimed: AtomicBoolean.Unsafe,
    // The cross-direction ownership guard (independent read/write holder bits plus a close bit, the Go fdMutex model): the last holder while
    // closing runs the deferred release exactly once. A read and a write proceed full-duplex; two reads or two writes serialize.
    guard: HandleGuard,
    // The single atomic handoff slot for the STARTTLS-on-io_uring stale-recv bytes (see UpgradeHandoff). Replaces the former pair of independent
    // @volatile upgradeCarryover / upgradeReadWaiter slots: those let the reap carrier (IoUringDriver.complete) and the handshake carrier
    // (PosixTransport.driveUpgradeRead) each read-then-act without mutual exclusion, so the reap could stage the carryover while the handshake
    // (having read the old Absent) parked its waiter, stranding the bytes against a parked waiter that nothing fulfilled and hanging the upgrade.
    // Both sides now CAS this one slot, so exactly one side wins each transition and the loser's single re-read sees the winner's value and
    // completes the other half (no spin, no thread block: the waiter is fiber-parking). The close path (freeResources) swings it to Idle and fails
    // any parked waiter Closed.
    val upgradeHandoff: AtomicRef.Unsafe[PosixHandle.UpgradeHandoff]
):
    /** The reused per-handle off-heap read buffer the driver recv's into. Grown on demand by the adaptive predictor (see
      * [[growReadBufferForFullRead]]): the field is `var` so the grow can swap in a larger buffer, but every read of it is a coherent snapshot
      * (the grow happens only on the poll-loop carrier between reads, when no recv is in flight against it). Accessors, not a public `var`, so the
      * single-owner grow path is the only writer.
      */
    def readBuffer: Buffer[Byte] = readBufferField

    /** The current capacity (in bytes) of [[readBuffer]]. Starts at the seed size passed at construction and rises as the predictor grows the
      * buffer; it is the live size the recv length and the fill-ratio check use, never the original seed.
      */
    def readBufferSize: Int = readBufferSizeField

    /** The single I/O driver this handle is bound to for its lifetime (set once when the connection is opened via the transport's
      * pool.next() / openWith). Every per-handle op (read/write/await/closeHandle/submitEngineOp) routes through this driver, so a
      * connection's poll loop and TLS engine FIFO stay on one owning driver across the N-driver pool: each handle is bound to exactly one driver
      * for its whole lifetime, never re-routed mid-life. @volatile carries the bind-site write to the driver carriers that read it.
      *
      * Initialized to the [[NoDriver]] sentinel (absence as a sentinel, never null); replaced exactly once at bind time. A per-handle op that
      * reaches this field before the bind would surface a clear `UnsupportedOperationException` from the sentinel rather than a `NullPointerException`.
      */
    @volatile var driver: IoDriver[PosixHandle] = NoDriver

    /** The most recent plaintext chunk the driver read off this fd, kept only so a STARTTLS upgrade can recover the peer's first handshake
      * flight when it arrived coalesced with the upgrade signal in a single `recv` and the application consumed (and discarded) the whole
      * chunk. The upgrade scans this for a TLS record (everything before it is the signal) and clears the slot; in every other case it is just
      * the buffer the previous read already produced, held by reference at no extra allocation, and is replaced by the next read.
      */
    @volatile var lastPlaintextRead: Maybe[Array[Byte]] = Absent

    /** STARTTLS-on-io_uring carry-over of the plaintext ReadPump's stale in-flight recv. io_uring cannot cancel an in-flight recv SQE, so after
      * `detachForUpgrade` that recv stays kernel-owned and consumes the peer's first post-signal handshake flight (the ClientHello) into the read
      * buffer; its CQE then lands on an already-settled (cancelled) promise and the bytes would be lost, hanging the handshake. While
      * [[upgradeActive]] is set the handshake does NOT issue its own recv (which would race the stale recv for the same byte stream and corrupt
      * record order); it instead drives the single [[upgradeHandoff]] slot, consuming the stale recv's bytes when the reap staged them first or
      * parking a fiber waiter that the reap fulfils. Exactly one stale recv is in flight per upgrade, so the flag is cleared once its bytes are
      * delivered and subsequent handshake reads arm normally. Never set on the poller (its reads are synchronous, no stale recv exists), so this
      * whole path is io_uring-only.
      */
    @volatile var upgradeActive: Boolean = false

    // The half-close state, written by the loop carrier only and read by closeReason. @volatile
    // carries the loop-carrier write to the closeReason reader; closeReason is a total function of
    // this one state, so the close reason is derived from a single consistent value.
    @volatile var halfClose: HalfCloseState = HalfCloseState.Open

    /** One-shot claim of the socket-fd close, so the fd is closed EXACTLY once even when two teardown paths target the same handle. The plain
      * close path (`PollerIoDriver.closeHandle`) is the sole closer for a normal connection and always wins the claim. A STARTTLS upgrade is the
      * one case with two potential closers: the upgrade detached the plaintext connection (keeping the fd open) and on a handshake FAILURE both
      * the upgrade-failure cleanup and a concurrent `closeHandle` would otherwise issue `close(fd)`. Routing both through this CAS closes the fd
      * once and never double-closes (which could close a recycled fd belonging to another connection).
      */

    /** Claim the socket-fd close: returns `true` for the single caller that should issue `close(fd)`, `false` for every later caller. */
    private[posix] def claimFdClose()(using AllowUnsafe): Boolean = fdCloseClaimed.compareAndSet(false, true)

    /** How the TLS engine's `free()` is run when the handle's resources are released (see [[PosixHandle.freeResources]]). The driver installs
      * its `submitEngineOp` here in `closeHandle` so the engine free is enqueued on the per-driver engine FIFO and therefore serialized AFTER
      * any read/write engine ops for this connection: no two carriers touch one native `ssl` at once, and the free never races an in-flight
      * shim call. The default runs the free inline (used by handles whose driver has no engine FIFO, e.g. a plaintext handle whose `tls` is
      * Absent, so the sink is never exercised). Set once, before the close path can fire.
      */
    @volatile var engineFreeSink: (() => Unit) => Unit = op => op()

    /** Ciphertext the TLS write path has produced but not yet sent to the peer (the backpressure tail). When the socket send buffer fills, the
      * driver appends the un-sent ciphertext here instead of busy-spinning the engine FIFO worker on EAGAIN; a later writable readiness event
      * re-submits a flush that drains it. Lazily allocated on the FIRST backpressure event so the common one-pass write allocates nothing
      * extra. Mutated ONLY on the per-driver engine FIFO worker (the single owner of every write engine op for this connection), so the
      * `@volatile` is for the close path's safe read, not for concurrent mutation. The unsent region is `[pendingCipherSent, pendingCipher.size)`.
      */
    @volatile var pendingCipher: Maybe[GrowableByteBuffer] = Absent

    /** Head offset into [[pendingCipher]]: bytes before it have already been sent, the unsent region is `[pendingCipherSent, pendingCipher.size)`.
      * Mutated ONLY on the engine FIFO worker. Reset to 0 when the buffer fully drains (or on a hard send error that discards the tail).
      */
    @volatile var pendingCipherSent: Int = 0

    /** Off-heap landing zone for ONE in-flight TLS read ciphertext: at most one ciphertext chunk is in flight per handle at a time. Lazily
      * allocated on the first TLS read, sized to readBufferSize, reused across reads, freed in freeResources.
      *
      * The writer is whichever driver serves this handle (a handle uses exactly one driver): for the readiness poller the poll carrier writes
      * the recv result into it via recvNow (in dispatchReadTls); for the io_uring driver the kernel fills it directly (the recv SQE points at
      * it on submit) and the reap carrier feeds it to the engine. In both cases the engine FIFO worker reads it via feedCiphertext. Causally
      * serialized (recv happens-before feed via the engine-op enqueue), not a free-for-all shared var. After the first lazy alloc this field
      * is stable; the at-most-one-in-flight guarantee ensures the FIFO worker's feedCiphertext completes before the next recv into staging.
      */
    @volatile var recvStaging: Maybe[Buffer[Byte]] = Absent

    /** FIFO-worker-owned drain buffer for decryptAll: readPlain writes up to readBufferSize bytes into it on each call, then bytes are
      * accumulated. Reused across records and across reads (readPlain always writes from position 0 of the buffer, not a positional offset,
      * so no reset is needed between records). Lazily allocated on the first TLS read by drainFor inside the FIFO op. Freed in freeResources.
      * Owned by the engine FIFO worker: only drainFor (called from decryptAll, inside submitEngineOp) writes to this field, so no cross-fiber
      * synchronization is needed beyond the engine-op FIFO's sequencing.
      */
    @volatile var decryptDrain: Maybe[Buffer[Byte]] = Absent

    /** FIFO-worker-owned GrowableByteBuffer accumulator for the multi-record path of decryptAll. Lazily allocated on the first multi-record
      * read by accFor inside the FIFO op. On-heap (GrowableByteBuffer wraps a heap array), so no explicit close is needed; drop the reference
      * for promptness. Not used on the single-record fast path (no accumulator allocation when the first recv produced exactly one TLS record).
      * Owned by the engine FIFO worker: only accFor (called from decryptAll, inside submitEngineOp) writes to this field, so no cross-fiber
      * synchronization is needed beyond the engine-op FIFO's sequencing.
      */
    @volatile var decryptAcc: Maybe[GrowableByteBuffer] = Absent

    /** FIFO-worker-owned reused plaintext-staging buffer for the TLS write path. Holds the full plaintext copied once at the top of the
      * encrypt loop in encryptPlaintext, eliminating the per-record Buffer.fromArray allocation. Grown on demand to the write size; shrinks
      * are not needed because the buffer tracks the allocated capacity, not a position (the encrypt loop advances a logical offset, not a
      * buffer position). Lazily allocated on the first TLS write. Freed in freeResources. Owned by the engine FIFO worker (single owner):
      * only encryptPlaintext (inside submitEngineOp) writes to this field, so no cross-fiber synchronization is needed.
      */
    @volatile var plaintextStaging: Maybe[Buffer[Byte]] = Absent

    /** FIFO-worker-owned reused drain buffer for drainCiphertext in the TLS write encrypt loop. The drain buffer is sized to readBufferSize;
      * drainCiphertext writes from position 0 each call, so no reset is needed between records. Lazily allocated on the first TLS write.
      * Freed in freeResources. Owned by the engine FIFO worker (single owner): only encryptPlaintext (inside submitEngineOp) reads/writes
      * this field, so no cross-fiber synchronization is needed.
      */
    @volatile var encryptDrain: Maybe[Buffer[Byte]] = Absent

    /** FIFO-worker-owned reused off-heap mirror buffer for TLS flush sends. The unsent region of pendingCipher is copied once into this buffer
      * per flush and sent from offset 0, eliminating the per-SQE copyOfRange + Buffer.fromArray allocation. Grown on demand. Lazily allocated
      * on the first flush. Freed exactly once in freeResources; never closed on reap.
      *
      * The writer is whichever driver serves this handle (a handle uses exactly one driver): for the readiness poller the engine FIFO worker
      * writes this via flushPending (inside submitEngineOp or armWritableForFlush's re-arm op); for the io_uring driver via flushTls (also
      * inside submitEngineOp). In both cases the FIFO worker is the single owner; no cross-fiber synchronization is needed beyond the
      * engine-op FIFO's sequencing. The single-in-flight-send guard ensures the kernel is done reading before the next flush refills the mirror.
      */
    @volatile var flushMirror: Maybe[Buffer[Byte]] = Absent

    /** Double-arm guard for the writable re-arm that drives the pending-ciphertext flush. Set when a flush armed `awaitWritable` and has not yet
      * fired; an append that happens while it is set does NOT arm a second writable (the already-pending flush picks up the appended bytes). This
      * field has TWO writers: the engine FIFO worker SETS it true while arming (in `armWritableForFlush`), and the writable-promise completion
      * callback CLEARS it false when the promise fires. The two are causally serialized: the set happens-before the promise is registered, and
      * the clear runs only after that promise completes, so the clear can never precede the set. The `@volatile` carries that ordering across the
      * arming carrier and the completion carrier.
      */
    @volatile var flushReArmPending: Boolean = false

    /** Pump-carrier-owned reused off-heap send buffer for plaintext writes. writeRaw copies the unsent plaintext region once into this buffer
      * and sends from it, eliminating the per-write Buffer.fromArray/close alloc churn. Grown on demand; never shrunk. Lazily allocated on
      * the first writeRaw call. Freed in freeResources. Owned by the write pump carrier (writeRaw runs synchronously on that carrier under
      * beginWrite/endWrite guard), so no cross-fiber synchronization is needed beyond the write guard's sequencing.
      */
    @volatile var sendMirror: Maybe[Buffer[Byte]] = Absent

    /** Plaintext the io_uring raw write path has produced but not yet sent to the peer (the raw backpressure tail), the plaintext twin of
      * [[pendingCipher]]. io_uring's raw send is asynchronous and may short-send, and CQEs need not complete in submission order, so the raw path
      * holds AT MOST ONE send SQE in flight per handle ([[rawSendInFlight]]) and buffers the unsent bytes here: a back-to-back write that runs
      * while a send is in flight APPENDS here instead of submitting a second SQE (which would let the kernel deliver out of order), and the
      * in-flight send's CQE re-flushes this. Lazily allocated on the FIRST raw write so a write fully sent in one SQE allocates no growable buffer.
      * Mutated ONLY on the per-driver engine FIFO worker (the single owner of every io_uring raw write engine op for this connection), so the
      * `@volatile` is for the close path's safe read, not for concurrent mutation. The unsent region is `[rawPendingSent, rawPending.size)`. Used
      * only by the io_uring driver; the poller's raw send is inline (it uses [[sendMirror]] directly with no async tail).
      */
    @volatile var rawPending: Maybe[GrowableByteBuffer] = Absent

    /** Head offset into [[rawPending]]: bytes before it have already been sent, the unsent region is `[rawPendingSent, rawPending.size)`. Mutated
      * ONLY on the engine FIFO worker. Reset to 0 when the tail fully drains (or on a hard send error that discards the tail). The plaintext twin
      * of [[pendingCipherSent]].
      */
    @volatile var rawPendingSent: Int = 0

    /** Single-in-flight-send guard for the io_uring RAW (plaintext) write path, the plaintext twin of [[sendInFlight]]. Makes the io_uring driver
      * hold AT MOST ONE raw send SQE in flight per handle so back-to-back raw writes stay ordered on the wire and a short send is re-flushed before
      * any later write: `flushRaw` submits a raw send SQE only when this is false (and sets it true); a `writeRaw` that runs while it is true
      * APPENDS its bytes to [[rawPending]] and does NOT submit (coalescing); the send CQE's `onRawSendComplete` clears it false, advances
      * [[rawPendingSent]] by the bytes actually sent, and re-submits a send SQE for any remainder (including coalesced bytes). Mutated ONLY on the
      * engine FIFO worker, so the `@volatile` is for the close path's safe read, not for concurrent mutation. The poller never touches this field.
      */
    @volatile var rawSendInFlight: Boolean = false

    /** Single-in-flight-send guard for the io_uring TLS write path (the completion-driver twin of [[flushReArmPending]]). io_uring's send is
      * asynchronous: a send SQE's `pendingCipherSent` advance happens only when its CQE reaps, so without this guard a second `writeTls` whose
      * flush runs while the first send's CQE is still outstanding re-sends the still-unacknowledged ciphertext region (a duplicate-byte / wire-order
      * violation). The guard makes the io_uring driver hold AT MOST ONE TLS send SQE in flight per handle: `flushTls` submits a send SQE only when
      * this is false (and sets it true); a `writeTls` that runs while it is true APPENDS its ciphertext to [[pendingCipher]] and does NOT submit
      * (coalescing, exactly as [[flushReArmPending]] makes an append coalesce onto an already-armed poller flush); the send CQE's `onTlsSendComplete`
      * clears it false, advances [[pendingCipherSent]] by the bytes actually sent, and re-submits a send SQE for any remainder (including bytes a
      * coalesced write appended). Mutated ONLY on the engine FIFO worker (the single owner of every write engine op and the reap-enqueued
      * `onTlsSendComplete` for this connection), so the `@volatile` is for the close path's safe read, not for concurrent mutation. The poller never
      * touches this field (its send is inline; it uses [[flushReArmPending]] instead).
      */
    @volatile var sendInFlight: Boolean = false

    /** The WritePump's writable promise parked because the write-backpressure tail reached [[PosixHandle.WriteTailHighWater]]: the driver returned
      * `WriteResult.TailPartial` and the pump entered [[WriteState.Backpressured]], suspended on `awaitWritable`. The kernel send buffer alone is
      * not the readiness signal here (the tail bound, not the socket, stopped the write), so the promise is held on the handle and completed by the
      * drain path when the tail drops below [[PosixHandle.WriteTailLowWater]]. Written by `awaitWritable` (the WritePump's carrier) and read/cleared
      * by the drain path (the engine FIFO worker), causally serialized through the tail-size check: the waiter is registered only when the tail is
      * over the low-water mark, and the drain completes-and-clears it only after advancing the sent pointer below the mark, so a registration that
      * races a drain either sees the tail still high (and the next drain completes it) or is itself completed immediately by `awaitWritable`'s own
      * below-mark fast path. The `@volatile` carries the cross-carrier visibility. The close path fails any parked waiter via `cancel` / the driver
      * teardown, so a slow peer never strands the pump. The slot is retained here (rather than carrying the promise inside
      * [[WriteState.Backpressured]]) because the drain path runs on the engine FIFO worker and cannot reach the WritePump's WriteState atomic cell
      * directly; the handle is the shared bridge between the two carriers.
      */
    @volatile var backpressurePromise: Maybe[Promise.Unsafe[Unit, Abort[Closed]]] = Absent

    /** Whether the last recv on this fd filled the read buffer exactly (n == readBufferSize). When true, the kernel may still hold residual
      * bytes that an edge-triggered backend will never re-signal (epoll fires once per empty->ready transition; a filled buffer leaves data in
      * the kernel with no new edge). On the next awaitRead registration the driver immediately re-dispatches the fd rather than waiting for an
      * edge that may never arrive. Cleared to false whenever recv returns EAGAIN (buffer confirmed empty), n == 0 (peer close), or a hard error.
      * Poll-carrier-only: written and read exclusively on the single poll-loop carrier, so no @volatile is needed.
      */
    var readMightHaveMore: Boolean = false

    /** Count of consecutive reads that completely filled [[readBuffer]] (n == readBufferSize). The adaptive predictor grows the buffer
      * once this reaches [[PosixHandle.GrowAfterFullReads]]: a connection that keeps saturating its buffer is one whose peer sends in bursts
      * larger than the current size, so a larger buffer means fewer recv syscalls and fewer per-read copies for the same byte volume. A read that
      * does NOT fill the buffer resets the count to 0, so a connection that settles back to small reads stops growing (and is never shrunk: the
      * grown size is the high-water estimate). Poll-carrier-only, like [[readMightHaveMore]]: written and read exclusively on the poll-loop
      * carrier, so no synchronization is needed.
      */
    private var consecutiveFullReads: Int = 0

    /** Record one completed read of `n` bytes for the adaptive predictor and grow [[readBuffer]] when the fill ratio warrants it.
      *
      * A read that filled the buffer (`n == readBufferSize`) increments the consecutive-full-read counter; any smaller read resets it. Once the
      * counter reaches [[PosixHandle.GrowAfterFullReads]] and the buffer is below [[PosixHandle.MaxReadBufferSize]], the buffer is grown via the
      * close-old-then-replace recipe: allocate the larger buffer FIRST, swap the field to it, THEN close the old one, so if the alloc
      * throws the field still points at the live old buffer and if the close throws the field already points at the new one (freed-exactly-once).
      * Returns `true` when a grow happened (the caller may re-dispatch to fill the larger buffer), `false` otherwise.
      *
      * Poll-carrier-only and called ONLY between reads (after a read completes, before the next recv is armed), so no recv is in flight against the
      * old buffer when it is closed: the single-owner free is safe. The io_uring driver, whose recv SQE pins the buffer address until its CQE
      * reaps, never calls this (it keeps a fixed buffer), so a grow can never race a kernel-owned recv.
      */
    private[posix] def growReadBufferForFullRead(n: Int)(using AllowUnsafe): Boolean =
        if n == readBufferSizeField then
            consecutiveFullReads += 1
            if consecutiveFullReads >= PosixHandle.GrowAfterFullReads && readBufferSizeField < PosixHandle.MaxReadBufferSize then
                val newSize = math.min(readBufferSizeField * 2, PosixHandle.MaxReadBufferSize)
                // Recipe order: alloc the larger buffer, swap the field, THEN close the old one. The old reference is captured locally and
                // never escapes after close; the field points at a live buffer at every step.
                val old = readBufferField
                readBufferField = Buffer.alloc[Byte](newSize)
                readBufferSizeField = newSize
                old.close()
                consecutiveFullReads = 0
                true
            else false
            end if
        else
            consecutiveFullReads = 0
            false
    end growReadBufferForFullRead

    /** The pending read promise for this fd, stored directly on the handle rather than as a `(promise, handle)` pair in the `pendingReads`
      * map. Written by the driver carrier under `awaitRead`/`rearmOwned`; read and cleared by the driver on `dispatchRead`. The `@volatile`
      * ensures the store is visible to the change worker that may fail the promise on `rc < 0` (the happens-before barrier is the
      * `changeQueue.offer` that follows the store: the `MpscLongQueue.offer` tail swap happens-before any subsequent poll by the change
      * worker). Single owner for the write side: only the call path through `awaitRead` or `rearmOwned` writes this field, and those two
      * paths never run concurrently on the same handle (at most one read dispatch is in flight per handle at a time, enforced by
      * `beginDispatch`/`endDispatch`).
      */
    @volatile var pendingReadPromise: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent

    /** The pending accept promise for this fd, stored directly on the handle. Mirrors [[pendingReadPromise]] for the listen-fd accept path
      * (`awaitAccept` / `dispatchAccept`). Written before the corresponding change command is enqueued (the `changeQueue.offer` is the
      * happens-before barrier for the change worker's `rc < 0` failure path). Single owner for the write side: only `awaitAccept` writes
      * this field, and at most one accept is in flight per handle.
      */
    @volatile var pendingAcceptPromise: Maybe[Promise.Unsafe[Int, Abort[Closed]]] = Absent

    /** The pending writable promise for this fd, stored directly on the handle alongside the poll-fiber-confined `pendingWritables` map entry.
      * The map entry routes the readiness event to the right waiter on the poll fiber; this field lets the cancel/close paths fail the promise
      * SYNCHRONOUSLY on their own carrier (the map removal itself is deferred to the poll fiber, so the close path must not touch the non-thread-safe
      * map). Written by `armSocketWritable` (the WritePump / connect / flush re-arm carrier) before its change command; read and cleared by the poll
      * fiber on `dispatchWritable` and by the cancel/close paths. At most one writable is armed per handle at a time. The arming handle's id is
      * `id` itself, so the stale-fd guard reads `handle.id` rather than a separately-stored copy.
      */
    @volatile var pendingWritablePromise: Maybe[Promise.Unsafe[Unit, Abort[Closed]]] = Absent

    /** Ownership guard for the shared resources (the TLS engine and the reused [[readBuffer]]) against the in-flight-op-vs-close use-after-free
      * race, on BOTH the read dispatch and the write paths.
      *
      * `PollerIoDriver.dispatchRead` removes the pending read, passes the `activeFds` id guard, then SUSPENDS on the `recv` syscall (which
      * writes into [[readBuffer]]) before, in its continuation, feeding the [[tls]] engine. Independently, `PollerIoDriver.write` reads the
      * [[tls]] engine and drives `writePlain` / `drainCiphertext` (allocating off [[readBufferSize]]) synchronously on the write fiber. A
      * concurrent `closeHandle` runs `PosixHandle.close`, which frees the engine and closes [[readBuffer]]. The read pump and the write pump run
      * on independent fibers/carriers, so EITHER a read dispatch OR a write can be touching those resources when close fires: without
      * coordination the close can free them while a `recv` is still writing the buffer, while a read continuation is feeding the engine, or while
      * a write is encrypting/draining through the engine, all use-after-free. The guard lets every in-flight op and the close hand ownership off
      * so the free happens exactly once and never while ANY op (read or write) holds the resources.
      *
      * The guard is a [[HandleGuard]] (independent read-holder and write-holder counts plus a close bit). The resources are freed exactly once, by
      * whichever op or close observes both holder counts reaching zero with the close bit set, and the guard then never leaves its terminal value.
      * Reads acquire via [[beginDispatch]] (the single poll fiber is the only reader of a given fd, so it never overlaps itself, but it CAN overlap a
      * write); writes acquire via [[beginWrite]]. The separate read and write halves let a read and a write proceed full-duplex.
      */

    /** Acquire the shared resources for a read dispatch. Returns `true` when ownership was taken (the dispatch may read [[readBuffer]] and the
      * [[tls]] engine and MUST pair this with [[endDispatch]] on every exit). Returns `false` when a close has been requested: the resources are
      * being or have been freed, so the dispatch must bail without touching them.
      */
    private[posix] def beginDispatch()(using AllowUnsafe): Boolean = guard.acquireRead()

    /** Release the shared resources at the end of a read dispatch. Returns `true` when a close raced this dispatch and this op is the last holder
      * (so the dispatch must complete its promise with `Closed` rather than deliver a result): in that case the deferred free is performed HERE,
      * exactly once. Returns `false` otherwise (no close raced, or another op still holds the resources), leaving the resources live.
      */
    private[posix] def endDispatch()(using AllowUnsafe): Boolean =
        val freedHere = guard.release(read = true)
        if freedHere then PosixHandle.freeResources(this)
        freedHere
    end endDispatch

    /** Acquire the shared resources for a write. Returns `true` when ownership was taken (the writer may read the [[tls]] engine and allocate off
      * [[readBufferSize]], and MUST pair this with [[endWrite]] on every exit). Returns `false` when a close has been requested: the resources
      * are being or have been freed, so the write must bail without touching them.
      */
    private[posix] def beginWrite()(using AllowUnsafe): Boolean = guard.acquireWrite()

    /** Release the shared resources at the end of a write. Returns `true` when a close raced this write and this op is the last holder (so the
      * deferred free is performed HERE, exactly once); `false` otherwise. The write caller does not act on the return (it already produced its
      * `WriteResult`); the return exists for symmetry with [[endDispatch]] and so the free handoff is uniform across both paths.
      */
    private[posix] def endWrite()(using AllowUnsafe): Boolean =
        val freedHere = guard.release(read = false)
        if freedHere then PosixHandle.freeResources(this)
        freedHere
    end endWrite

    /** Test whether a close has been requested (or resources already freed). Used by the io_uring read CQE path to detect that
      * [[feedAndDecrypt]] triggered [[requestClose]] (fatal TLS record): the io_uring path holds no dispatch guard, so the guard state
      * is the only observable signal after [[requestClose]] returns. Returns `true` if the close bit is set or the resources are already freed.
      */
    private[posix] def isClosing()(using AllowUnsafe): Boolean = guard.isClosing()

    /** The number of bytes currently buffered but not yet sent across this handle's write-backpressure tail (the unsent region of whichever tail is
      * active: [[pendingCipher]] for the TLS write path, [[rawPending]] for the io_uring raw write path). A handle is either TLS or raw, so at most
      * one tail is ever non-empty; this returns the unsent size of the active one (0 when neither is allocated or both are fully drained).
      *
      * Read by the drivers' `write` path to decide whether the tail has reached [[PosixHandle.WriteTailHighWater]] (and the write must signal
      * backpressure instead of appending), and by `awaitWritable` to decide whether the parked WritePump promise may complete now or must wait for the
      * tail to drain below [[PosixHandle.WriteTailLowWater]]. The fields are mutated only on the engine FIFO worker and read here off `@volatile`s, so
      * this is a coherent snapshot of the tail at the moment of the read (a coarse bound is sufficient: the high-water gate is hysteretic, not exact).
      */
    private[posix] def unsentTailBytes: Int =
        val cipher = pendingCipher match
            case Present(b) => b.size - pendingCipherSent
            case Absent     => 0
        val raw = rawPending match
            case Present(b) => b.size - rawPendingSent
            case Absent     => 0
        val cipherUnsent = if cipher > 0 then cipher else 0
        val rawUnsent    = if raw > 0 then raw else 0
        cipherUnsent + rawUnsent
    end unsentTailBytes

    /** Complete the parked backpressure promise ([[backpressurePromise]]) if the write tail has drained below [[PosixHandle.WriteTailLowWater]], so the
      * WritePump retries the write that the high-water bound previously deferred. A no-op when no promise is parked or the tail is still over the mark.
      * Called by the drain paths (poller `flushPending`, io_uring `onTlsSendComplete` / `onRawSendComplete`) on the engine FIFO worker AFTER they
      * advance the sent pointer, so the tail size read here reflects the just-completed send. Completing the promise with `Success` signals writability;
      * the pump's `onWritable` re-issues the deferred write, which now passes the high-water check (the tail is below low-water) and appends.
      */
    private[posix] def releaseBackpressureWaiter()(using AllowUnsafe): Unit =
        if unsentTailBytes < PosixHandle.WriteTailLowWater then
            backpressurePromise.foreach { p =>
                backpressurePromise = Absent
                p.completeDiscard(Result.succeed(()))
            }
    end releaseBackpressureWaiter

    /** Request close of the shared resources. If no op holds them (holder count 0), they are freed immediately. If a read or write is in flight,
      * the free is deferred to that op's [[endDispatch]] / [[endWrite]] so it never races an in-flight `recv` / engine feed / encrypt. Idempotent
      * once the close bit is set (or the resources are already freed).
      */
    private[posix] def requestClose()(using AllowUnsafe): Unit =
        if guard.requestClose() then PosixHandle.freeResources(this)
    end requestClose
end PosixHandle

private[net] object PosixHandle:

    val DefaultReadBufferSize = 8192

    /** Number of consecutive buffer-filling reads (n == readBufferSize) before the adaptive predictor grows a handle's read buffer. A
      * small threshold (4) reacts quickly to a genuinely high-throughput connection while ignoring a one-off full read that a transient burst can
      * produce, so a steady small-read connection never grows. See [[PosixHandle.growReadBufferForFullRead]].
      */
    final val GrowAfterFullReads = 4

    /** Upper bound (bytes) on the adaptive read buffer. The predictor doubles from [[DefaultReadBufferSize]] toward this cap and never past it,
      * so a saturating connection's per-connection read-buffer memory is bounded (CWE-400 class): 1 MiB is large enough to amortize the recv /
      * copy count for any realistic stream while capping the worst-case pin at a fixed multiple of the seed.
      */
    final val MaxReadBufferSize = 1 << 20

    /** High-water mark (bytes) for a handle's write-backpressure tail ([[PosixHandle.pendingCipher]] / [[PosixHandle.rawPending]]). When the unsent
      * tail has reached this size, the drivers' async-write paths ([[PollerIoDriver.writeTls]], [[IoUringDriver.writeTls]],
      * [[IoUringDriver.writeRaw]]) stop appending and report `WriteResult.TailPartial` so the `WritePump` enters [[WriteState.Backpressured]] and
      * parks until the tail drains, rather than pulling the next outbound span. This bounds the per-connection memory a slow- or no-read peer can
      * pin (CWE-400), folding the async-write tail into the same outbound-channel + writable-park backpressure flow the synchronous (poller raw)
      * path already obeys. 1 MiB is well above any single application write or coalesced flush, so the bound never trips on normal traffic; it caps
      * only the pathological accumulate-without-draining case. Bytes already encrypted past the mark are not dropped: they remain in the tail and
      * drain when the peer reads (the write that crossed the mark is re-presented after the park).
      */
    final val WriteTailHighWater = 1 << 20

    /** Low-water mark (bytes) for a handle's write-backpressure tail. A WritePump promise parked by [[PollerIoDriver.awaitWritable]] /
      * [[IoUringDriver.awaitWritable]] when the tail was over [[WriteTailHighWater]] is completed (writability signaled, so the pump retries the
      * write) only once the tail has drained below this mark. The gap between the two marks is hysteresis: it stops the pump from thrashing one
      * record at a time at the boundary, letting the tail drain a useful chunk before the next batch of writes resumes. Half the high-water mark.
      */
    final val WriteTailLowWater = WriteTailHighWater / 2

    /** The single atomic handoff state for the STARTTLS-on-io_uring stale-recv bytes (see [[PosixHandle.upgradeHandoff]]). The stale recv reaped on
      * the io_uring reap carrier ([[IoUringDriver.complete]]) and the handshake-driving carrier ([[PosixTransport.driveUpgradeRead]]) run on
      * different carriers; this one state, swung by CAS, replaces the two separate `@volatile` slots whose independent check-then-act let the two
      * sides interleave and strand the bytes (the handshake parked a waiter while the reap had already staged the carryover, so neither fulfilled
      * the other and the upgrade hung). Exactly one transition wins each side's CAS, so the bytes always meet the waiter:
      *   - [[Idle]]: neither side has acted yet.
      *   - [[Carryover]]: the reap delivered the stale recv's bytes before the handshake parked; the handshake's next read consumes them.
      *   - [[Waiter]]: the handshake parked before the reap delivered; the reap fulfils this fiber-parking promise with the bytes.
      */
    private[posix] enum UpgradeHandoff:
        case Idle
        case Carryover(bytes: Array[Byte])
        case Waiter(promise: Promise.Unsafe[Span[Byte], Abort[Closed]], frame: Frame)
    end UpgradeHandoff

    /** Socket handle: one fd carries both directions, so `readFd == writeFd`. The optional `connectTarget` (an encoded `sockaddr` buffer and
      * its length) is stashed for the io_uring client-connect path: `IoUringDriver.awaitConnect` submits an `IORING_OP_CONNECT` SQE against it.
      * The readiness poller ignores it (epoll/kqueue signal connect via write-readiness), so it defaults to `Absent` for an already-connected
      * (e.g. accepted) socket.
      */
    def socket(fd: Int, bufSize: Int, connectTarget: Maybe[(Buffer[Byte], Int)])(using
        AllowUnsafe
    ): PosixHandle =
        new PosixHandle(
            fd,
            fd,
            HandleId.next(fd),
            Buffer.alloc[Byte](bufSize),
            bufSize,
            Absent,
            connectTarget,
            fdCloseClaimed = AtomicBoolean.Unsafe.init(false),
            guard = HandleGuard.init(),
            upgradeHandoff = AtomicRef.Unsafe.init(PosixHandle.UpgradeHandoff.Idle)
        )

    /** stdio handle: split fds, read end 0 and write end 1 (the split-fd case). */
    def stdio(bufSize: Int)(using AllowUnsafe): PosixHandle =
        new PosixHandle(
            0,
            1,
            HandleId.next(0),
            Buffer.alloc[Byte](bufSize),
            bufSize,
            Absent,
            Absent,
            fdCloseClaimed = AtomicBoolean.Unsafe.init(false),
            guard = HandleGuard.init(),
            upgradeHandoff = AtomicRef.Unsafe.init(PosixHandle.UpgradeHandoff.Idle)
        )

    /** Actually release the resources the handle owns: the TLS engine (if any) and the reused read buffer. Called exactly once, under the
      * `guard` ownership protocol, by whichever of `requestClose` / `endDispatch` / `endWrite` wins the handoff (the last holder while closing,
      * or the close itself when no op is in flight). Never call this directly; go through [[close]] (which routes to `requestClose`). The bound
      * socket fd is closed by the driver (`PollerIoDriver.closeHandle` issues the single `SocketBindings.close`); stdio fds 0/1 are owned by the
      * process and are never closed here.
      *
      * The engine's `free()` is run through [[engineFreeSink]] (the driver's `submitEngineOp`), so it is serialized on the per-driver engine
      * FIFO behind any read/write engine ops for this connection rather than running on the close carrier. The guard already ensures no engine
      * op holds the resources when this runs (holder count 0), so routing the free through the FIFO is purely additive serialization that also
      * keeps the native `ssl` from ever being touched by two carriers at once. The `tls` slot is cleared and the read buffer closed now: the
      * slot clear signals "freed" to any gated caller immediately, and the buffer close is already coordinated by the guard.
      *
      * The pending-ciphertext backpressure tail ([[pendingCipher]], [[pendingCipherSent]], [[flushReArmPending]], [[sendInFlight]]) is cleared here
      * too. The buffer is a plain array object (no native handle), so dropping the reference is the whole release; the guard ensures no engine op is
      * mid-flush when this runs, so the clear cannot race a concurrent append. The FIFO-worker-owned TLS write buffers ([[plaintextStaging]],
      * [[encryptDrain]], [[flushMirror]]) are closed and cleared here as well; they are off-heap and require an explicit close.
      */
    private def freeResources(h: PosixHandle)(using AllowUnsafe): Unit =
        h.tls.foreach { engine =>
            h.engineFreeSink(() => engine.free())
        }
        h.tls = Absent
        h.readBuffer.close()
        h.recvStaging.foreach(_.close())
        h.recvStaging = Absent
        h.decryptDrain.foreach(_.close())
        h.decryptDrain = Absent
        h.decryptAcc = Absent
        h.plaintextStaging.foreach(_.close())
        h.plaintextStaging = Absent
        h.encryptDrain.foreach(_.close())
        h.encryptDrain = Absent
        h.flushMirror.foreach(_.close())
        h.flushMirror = Absent
        h.sendMirror.foreach(_.close())
        h.sendMirror = Absent
        h.pendingCipher = Absent
        h.pendingCipherSent = 0
        h.flushReArmPending = false
        h.sendInFlight = false
        // The io_uring raw write tail is on-heap (a GrowableByteBuffer), so dropping the reference is the whole release; the guard ensures no raw
        // flush is mid-run when this fires. A reaped raw send CQE that runs after this sees rawPending Absent and no-ops (onRawSendComplete).
        h.rawPending = Absent
        h.rawPendingSent = 0
        h.rawSendInFlight = false
        // Fail (not merely clear) any parked write-backpressure promise. The driver's `cancel` fails a promise present at cancel time, but a
        // promise parked in the window AFTER cancel and before this teardown (the close-vs-writable race) would otherwise be left incomplete: its
        // promise never resolves and the WritePump fiber that parked on it hangs forever. Completing it with Closed here releases that fiber. The
        // free runs exactly once (guard CAS to its terminal value), and completeDiscard is idempotent, so a double-fail (here and in cancel or
        // awaitWritable's close-race check) is harmless.
        h.backpressurePromise.foreach { bp =>
            given Frame = Frame.internal
            bp.completeDiscard(
                Result.fail(kyo.Closed(
                    "PosixHandle",
                    Frame.internal,
                    s"fd=${h.readFd}/${h.writeFd} closed while a write was parked on backpressure"
                ))
            )
        }
        h.backpressurePromise = Absent
        // Fail (not merely clear) a STARTTLS handshake parked on the stale-recv handoff: if the handle closes mid-upgrade (a deadline reap or a
        // peer reset) the stale recv may never deliver, so completing the parked waiter Closed lets the handshake tear down instead of hanging on
        // it. Swing the one handoff slot to Idle and, if it held a parked Waiter, fail that promise with the frame captured when it parked. A
        // Carryover (bytes the reap staged that no read ever consumed) just drops with the reference. freeResources runs under the guard once no op
        // holds the resources, so this read-then-set never races a concurrent CAS from either carrier.
        h.upgradeHandoff.getAndSet(PosixHandle.UpgradeHandoff.Idle) match
            case PosixHandle.UpgradeHandoff.Waiter(p, fr) =>
                p.completeDiscard(Result.fail(kyo.Closed("PosixHandle", fr, s"fd=${h.readFd}/${h.writeFd} closed during upgrade")(using
                    fr
                )))
            case _ => ()
        end match
        h.upgradeActive = false
        // Clear promise fields: these are on-heap references with no native close needed; setting to Absent drops the reference.
        h.pendingReadPromise = Absent
        h.pendingAcceptPromise = Absent
        h.pendingWritablePromise = Absent
    end freeResources

    /** Release the resources the handle owns (the TLS engine and the reused read buffer), coordinated against any in-flight read dispatch OR
      * write via the handle's `guard` (see [[PosixHandle.guard]]): if a read or write holds the resources the free is deferred to that op's
      * `endDispatch` / `endWrite`, so it never races the in-flight `recv` / engine feed / encrypt. Idempotent: a repeat close is a no-op once the
      * resources are freed.
      */
    def close(h: PosixHandle)(using AllowUnsafe): Unit =
        h.requestClose()
    end close
end PosixHandle

/** Sentinel driver used as the initial value of [[PosixHandle.driver]] before the handle is bound to its real driver at connection open. It
  * makes the field a non-null sentinel rather than `null`, so an op that reaches a handle before its bind surfaces a clear, named failure
  * (`UnsupportedOperationException` naming the unbound state) instead of a `NullPointerException`. Every real per-handle op runs only after the
  * bind (the transport sets `handle.driver` before returning the connection), so no production path invokes these methods.
  */
private[posix] object NoDriver extends IoDriver[PosixHandle]:
    private def unbound: Nothing =
        throw new UnsupportedOperationException("PosixHandle is not bound to a driver")

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]                                                          = unbound
    def awaitRead(handle: PosixHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = unbound
    def awaitWritable(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = unbound
    def awaitConnect(handle: PosixHandle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit     = unbound
    def awaitAccept(handle: PosixHandle, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit       = unbound
    def write(handle: PosixHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                           = unbound
    def cancel(handle: PosixHandle)(using AllowUnsafe, Frame): Unit                                                         = unbound
    def closeHandle(handle: PosixHandle)(using AllowUnsafe, Frame): Unit                                                    = unbound
    def close()(using AllowUnsafe, Frame): Unit                                                                             = unbound
    def label: String                                                                                                       = "NoDriver"
    def handleLabel(handle: PosixHandle): String = s"fd=${handle.readFd}/${handle.writeFd}(unbound)"
end NoDriver
