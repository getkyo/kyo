package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.NetConnectionClosedException.Operation
import kyo.net.NetException
import kyo.net.internal.TlsEngine
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.util.GrowableByteBuffer
import kyo.net.internal.util.HandleId

/** Unified raw-fd handle (the unification of the transport layer).
  *
  * A single handle type backs every posix connection on every platform, standing alongside
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
    // One-shot credit recorded by IoUringDriver.registerDeferredClose when it wins claimFdClose to guard the deferred shutdown(SHUT_RD)
    // (see markDeferredFdClose / consumeDeferredFdClose), so the later closeNow that owes the real close(fd) can still run it despite
    // claimFdClose already being spent.
    deferredFdClose: AtomicBoolean.Unsafe,
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
    val upgradeHandoff: AtomicRef.Unsafe[PosixHandle.UpgradeHandoff],
    // The most recent plaintext chunk the driver read off this fd, kept only so a STARTTLS upgrade can recover the peer's first handshake
    // flight when it arrived coalesced with the upgrade signal in a single `recv` and the application consumed (and discarded) the whole
    // chunk. A one-shot claim, not a plain snapshot: feedCoalescedHandshake and the upgrade-handoff salvage hooks (PollerIoDriver /
    // IoUringDriver onInboundClosedDuringRead) both alias this slot for the SAME chunk when the plaintext pump's channel-offer fails, so a
    // plain read-then-clear let both paths feed the peer's flight to the handshake engine, corrupting the record stream. Whichever side
    // wins a `compareAndSet(Present(arr), Absent)` on this slot is the sole feeder for that chunk; the loser sees the winner's `Absent` and
    // skips.
    val lastPlaintextRead: AtomicRef.Unsafe[Maybe[Array[Byte]]]
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

    /** STARTTLS upgrade-window marker (io_uring read routing): set true at detachForUpgrade (with [[upgradeActive]]) and cleared at handshake
      * completion (onFinished, when [[tls]] becomes Present). Unlike [[upgradeActive]] (which clears mid-handshake, before the handshake arms its own
      * ciphertext recv), this stays true across the WHOLE window, including the gap after upgradeActive clears but before tls is installed. The
      * io_uring reap uses it to keep any stray plaintext-ReadPump recv that reaps in that gap off the raw plainReadComplete path (routing it through
      * [[upgradeHandoff]] instead): a TLS 1.3 NewSessionTicket can arrive post-FINISHED-pre-onFinished and would otherwise be delivered as raw
      * plaintext (the upgrade-handoff drop). The handshake's OWN ciphertext recv is exempt via the recv's `handshakeOwned` tag, so it still feeds the
      * engine. Unused on the pollers (their reads are synchronous, no reaped-recv routing). Cleared in freeResources.
      */
    @volatile var upgrading: Boolean = false

    /** Set true once this handle has ever gone through a STARTTLS upgrade (`upgradeRole`), and never cleared for the handle's lifetime. Unlike
      * [[upgradeActive]] / [[upgrading]] (both transient, cleared together at handshake completion), this survives `onFinished` so the io_uring
      * reap can still recognize an upgrade-owned recv that reaps AFTER those flags clear. The specific hazard: `driveUpgradeRead`'s producer-recv
      * arm ([[IoDriver.armUpgradeProducerRead]]) can race `onFinished`'s flag-clear (the check-then-arm is a TOCTOU across the reap carrier's
      * engine-FIFO ordering), leaving a `handshakeOwned` recv genuinely kernel-owned and in flight once `upgradeActive`/`upgrading`
      * are already false and `tls` is already `Present`. Without this flag, that orphan's CQE falls through to the ordinary TLS-feed branch and
      * either interleaves its ciphertext with the post-upgrade `ReadPump`'s own (concurrent) recv -- both target the SAME cached staging buffer,
      * see [[IoUringDriver.recvStagingFor]] -- or silently drops the decoded plaintext onto the orphan's own throwaway producer promise, which
      * nothing observes. `false` for a fresh (non-STARTTLS) handshake's `handshakeOwned` recvs, so they are unaffected: `tls` stays `Absent` for
      * those until their own `onFinished` runs, and this flag was never set. io_uring-only (the poller's synchronous reads hold no kernel-owned
      * recv that could outlive `onFinished` this way); harmless if read on a poller, where nothing sets it.
      */
    @volatile var isUpgraded: Boolean = false

    /** io_uring single-recv-ordering enforcement: a recv request [[IoUringDriver.submitRecv]] deferred, instead of submitting, because another
      * recv was already kernel-owned and in flight for this SAME handle -- the orphaned handshake-window recv [[isUpgraded]] documents, still
      * outstanding when the post-upgrade `ReadPump`'s first recv tries to arm. Rather than submitting a second SQE (which would target the SAME
      * cached staging buffer, see [[IoUringDriver.recvStagingFor]], and race completion order with the orphan), `submitRecv` stores the request
      * here; the reap carrier fires it the moment the in-flight recv's CQE is fully processed (see `IoUringDriver.complete`'s `drainQueuedRecv`
      * call), so the two recvs are never simultaneously in flight -- enforced structurally, not by caller discipline. Mutated only on the reap
      * carrier (both the queue decision in `submitRecv` and the drain in `complete` run there), so a plain `@volatile var` (not a CAS slot like
      * [[upgradeHandoff]]) suffices: there is a single writer, `@volatile` only carries visibility to a concurrent close-path reader. At most
      * one request is ever queued per handle (the ReadPump arms at most one recv at a time), so a single slot, not a queue collection, suffices.
      * Drained/failed on close (`freeResources`) so a queued request never leaks or fires against a closing/closed handle. Unused on the
      * pollers (their reads are synchronous, holding no kernel-owned recv that could race a second arm this way).
      */
    @volatile var queuedRecv: PosixHandle.QueuedRecv = PosixHandle.QueuedRecv.None

    /** STARTTLS poller confinement: set true once the handshake takes read ownership from the retiring plaintext ReadPump (PosixTransport's
      * awaitReadCiphertext, before the first post-detach read arm). While `upgradeActive && !handshakeReading` the poll carrier REJECTS an
      * OpRegisterRead for this fd's read side ([[PollerIoDriver]]'s registration apply): that registration is the pump's stray re-arm racing
      * detachForUpgrade, and admitting it would let the next readability event deliver the peer's first TLS flight to the pump instead of the
      * handshake. Once handshakeReading is set, the handshake's own read arm is admitted. Unused on io_uring (its upgrade read routes through the
      * [[upgradeHandoff]] slot, not this flag). Cleared with [[upgradeActive]] at handshake completion / failure.
      */
    @volatile var handshakeReading: Boolean = false

    /** STARTTLS post-completion window (kqueue edge recovery): set true at handshake completion (onFinished) and cleared on the first
      * application-plaintext read after the upgrade. While set, a post-upgrade TLS read that yielded 0 plaintext and drained to EAGAIN re-issues
      * the kqueue read registration (EV_ADD re-evaluates current readiness) instead of relying on the EV_CLEAR knote re-firing for the next flight.
      * Needed because a TLS 1.3 NewSessionTicket (or any post-handshake record) lands between FINISHED and the peer's first application flight (the
      * echo); the record reads as 0 plaintext, and the bare re-arm trusts an EV_CLEAR edge that kqueue can lose, stranding the echo. Bounded to this
      * window so steady-state reads keep the bare re-arm (no per-read kevent). kqueue-only at the use site; ignored by epoll/io_uring.
      */
    @volatile var postUpgradeReadWindow: Boolean = false

    // The half-close state, written by the loop carrier only and read by status. @volatile
    // carries the loop-carrier write to the status reader; status is a total function of
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

    /** Record that a [[claimFdClose]] win was spent guarding [[IoUringDriver.registerDeferredClose]]'s deferred `shutdown(SHUT_RD)`, not the
      * real `close(fd)` syscall: that call still owes the actual close once the in-flight recv drains, but its own `claimFdClose()` attempt
      * would lose (the claim is already spent). Set by the winner right after winning; consumed exactly once via [[consumeDeferredFdClose]].
      */
    private[posix] def markDeferredFdClose()(using AllowUnsafe): Unit = deferredFdClose.set(true)

    /** Consume the credit [[markDeferredFdClose]] recorded: returns `true` for the single caller (the deferred-close discharge) that should
      * therefore still issue `close(fd)` despite `claimFdClose` already being spent by the `shutdown(SHUT_RD)` winner, `false` otherwise
      * (nothing was deferred, or another discharge already consumed it).
      */
    private[posix] def consumeDeferredFdClose()(using AllowUnsafe): Boolean = deferredFdClose.compareAndSet(true, false)

    /** How the TLS engine's `free()` is run when the handle's resources are released (see [[PosixHandle.freeResources]]). The driver installs
      * its `submitEngineOp` here in `closeHandle` so the engine free is enqueued on the per-driver engine FIFO and therefore serialized AFTER
      * any read/write engine ops for this connection: no two carriers touch one native `ssl` at once, and the free never races an in-flight
      * shim call. The default runs the free inline (used by handles whose driver has no engine FIFO, e.g. a plaintext handle whose `tls` is
      * Absent, so the sink is never exercised). Set once, before the close path can fire.
      */
    @volatile var engineFreeSink: (() => Unit) => Unit = op => op()

    /** One-shot deferred real `close(fd)` action: the fd-number twin of [[engineFreeSink]]. The winner of [[claimFdClose]] issues
      * `shutdown(SHUT_RDWR)` immediately (safe: winning the claim proves the fd is still owned by this handle, and it makes any carrier
      * still mid-syscall on it under a live [[beginWrite]] / [[beginDispatch]] hold observe 0/EPIPE rather than a fd number the kernel has
      * already recycled to an unrelated connection) and installs the actual `close(fd)` call here instead of running it inline. `freeResources`
      * invokes it LAST, at the same exactly-once, zero-holders point that already frees the engine and buffers, so the fd number inherits
      * that same guarantee. `Absent` for a handle whose fd was never claimed this way (stdio, whose fds are process-owned and never closed
      * here; or the loser of a `claimFdClose` race, whose real close is the winner's job).
      */
    @volatile var fdCloseSink: Maybe[() => Unit] = Absent

    /** Deliver plaintext to whichever [[kyo.net.Connection]] CURRENTLY owns this handle. `PosixTransport` installs this at every point a
      * connection's `inbound` channel becomes the active one for the handle (connect, accept, and a STARTTLS upgrade's `onFinished`, where it
      * is re-pointed from the pre-upgrade connection's channel to the post-upgrade one before `upgraded.start()` runs). The driver uses it to
      * deliver plaintext that did not arrive through the normal ReadPump promise path: a late-reaping orphan recv armed before an upgrade
      * committed, reaping AFTER `onFinished` already ran (so no `driveUpgradeRead` consumer will ever drain an `upgradeHandoff` Carryover for
      * it again) is fed to the engine and its plaintext delivered here directly, mirroring `PosixTransport.deliverHandshakePlaintext`'s own
      * post-FINISHED slot drain for the same reason: staging it as a Carryover instead would silently lose it forever, leaving a permanent
      * gap in the ciphertext stream for the engine's NEXT record (the mechanism behind the "Closed at collect" corruption).
      * The default is a no-op so a driver with no upgrade machinery (or a handle before its first connection wiring) never crashes on it.
      */
    @volatile var inboundSink: Span[Byte] => Unit = _ => ()

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

    /** Ownership tag for [[recvStaging]], stamped with this handle's own [[id]] at allocation (see `IoUringDriver.recvStagingFor`). An
      * always-on invariant, not a diagnostic: every point that feeds `recvStaging`'s bytes into a TLS engine re-checks this against the
      * feeding handle's own id immediately before the feed (`IoUringDriver.complete`). Since `recvStaging` is a field on THIS handle
      * instance, the two can only ever disagree if a future change decouples the buffer lookup from the handle doing the feed (e.g. a
      * caching or generation bug); the check exists to catch that class of cross-connection ciphertext leak (the bad_record_mac shape) at
      * the exact point it would occur, attributing both handle ids, rather than as a downstream symptom with no attribution.
      */
    @volatile var recvStagingOwnerId: Long = 0L

    /** Always-on exclusive-use guard for this handle's recv buffer (`readBuffer` or `recvStaging`): `true` for exactly as long as a real recv
      * SQE is kernel-owned for it, set by `IoUringDriver.submitRecv` right after a successful submit and cleared by `IoUringDriver.complete`
      * the moment that SQE's CQE reaps. `submitRecv` checks it before arming a fresh recv (outside the [[isUpgraded]] window, which routes its
      * own legitimate dual-recv case through [[queuedRecv]] instead): a `true` reading there means something requested a SECOND recv while
      * this handle's buffer was still being written by the kernel -- structurally unexpected given ReadPump/handshake discipline (arm one,
      * await it, arm the next), so a hit safe-aborts the connection rather than letting two kernel writes interleave into one buffer. A
      * production always-on invariant (a plain field checked with a plain branch, not an elidable `assert`): a rare compare on every read is
      * cheaper than a silently corrupted or cross-connection-aliased byte stream. Not set for a recv parked on a full submission queue (see
      * [[IoUringDriver.submitRecv]]'s `stalledSubmits` branch): nothing kernel-owned touches the buffer until a real SQE is submitted, so a
      * parked request carries no aliasing risk and its later re-arm (`reArmStalledSubmits`) must not itself trip this guard. Single-writer
      * (the reap carrier both sets and clears it), so a plain `@volatile var` suffices, matching [[queuedRecv]]'s precedent. Unused on the
      * pollers (their reads are synchronous, holding no kernel-owned recv this could race).
      */
    @volatile var recvInFlight: Boolean = false

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
        if freedHere then PosixHandle.freeResources(this, deferredHolder = Present("read"))
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
        if freedHere then PosixHandle.freeResources(this, deferredHolder = Present("write"))
        freedHere
    end endWrite

    /** Acquire the shared resources for a driver-level deferred close (a third holder kind alongside [[beginDispatch]] / [[beginWrite]]; see
      * [[IoUringDriver.registerDeferredClose]]). A kernel-owned op (e.g. a submitted but not-yet-reaped recv SQE) can leave a handle's
      * resources unsafe to free for a stretch of real wall-clock time with no carrier synchronously inside a `beginDispatch` / `beginWrite`
      * bracket for any of it: `close(fd)` alone does not complete it, so the driver defers its own real close until the count drains, but that
      * deferral is bookkept in the driver's own private map, invisible to this guard. Without this hold, an unrelated concurrent
      * [[requestClose]] caller (e.g. a racing STARTTLS-upgrade-failure teardown) would see zero active holders and free the resources
      * immediately, before the driver ever installs the real close credit ([[fdCloseSink]]) -- permanently stranding it (the credit sits
      * unconsumed and the real `close(fd)` never runs, a `CLOSE_WAIT` leak). Returns `true` when the hold was taken (MUST pair with
      * [[endDeferredClose]]); `false` when a close was already requested by someone else, in which case the caller has nothing left to
      * protect (that other closer's own release already ran, or will run, without ever waiting on the driver's deferred bookkeeping).
      */
    private[posix] def beginDeferredClose()(using AllowUnsafe): Boolean = guard.acquireRead()

    /** Release the hold [[beginDeferredClose]] took, once the driver has installed the real close credit and is ready to discharge it.
      * Mirrors [[endDispatch]]: returns `true` when this is the last holder, so the just-installed credit is consumed here, exactly once.
      */
    private[posix] def endDeferredClose()(using AllowUnsafe): Boolean =
        val freedHere = guard.release(read = true)
        if freedHere then PosixHandle.freeResources(this, deferredHolder = Present("deferred-close"))
        freedHere
    end endDeferredClose

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
        val freedHere = guard.requestClose()
        if freedHere then PosixHandle.freeResources(this)
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
    private[posix] enum UpgradeHandoff derives CanEqual:
        case Idle
        case Carryover(bytes: Array[Byte])
        case Waiter(promise: Promise.Unsafe[Span[Byte], Abort[NetException]], frame: Frame)
    end UpgradeHandoff

    /** A recv request [[IoUringDriver.submitRecv]] deferred instead of submitting, because another recv was already kernel-owned and in flight
      * for the SAME handle (see [[PosixHandle.queuedRecv]]). Carries exactly what a fresh [[IoUringDriver.submitRecv]] call needs to arm it
      * later, once the in-flight one's CQE reaps: `eintrRetries` is always 0 (a freshly-queued request, never itself a retry).
      * `armedPostUpgrade` is the ORIGINAL value captured when this request was first made (see `PendingOp.Read`'s doc), carried through the
      * queue unchanged so the eventual re-arm's routing decision still reflects when the caller ACTUALLY asked for this recv, not the (later,
      * possibly different) handle state at drain time.
      */
    private[posix] enum QueuedRecv:
        case None
        case Queued(promise: Promise.Unsafe[ReadOutcome, Abort[Closed]], handshakeOwned: Boolean, armedPostUpgrade: Boolean)
    end QueuedRecv

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
            deferredFdClose = AtomicBoolean.Unsafe.init(false),
            guard = HandleGuard.init(),
            upgradeHandoff = AtomicRef.Unsafe.init(PosixHandle.UpgradeHandoff.Idle),
            lastPlaintextRead = AtomicRef.Unsafe.init(Absent)
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
            deferredFdClose = AtomicBoolean.Unsafe.init(false),
            guard = HandleGuard.init(),
            upgradeHandoff = AtomicRef.Unsafe.init(PosixHandle.UpgradeHandoff.Idle),
            lastPlaintextRead = AtomicRef.Unsafe.init(Absent)
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
      *
      * `deferredHolder` names which guard holder (`"read"` or `"write"`) this call is running on behalf of when it was `endDispatch` / `endWrite`
      * that observed the last release (i.e. a holder was still active when the close was requested), `Absent` for the immediate `requestClose`
      * path where no holder was active. It is used purely to log the arbiter WARN below when a real [[fdCloseSink]] credit is also pending.
      */
    private def freeResources(h: PosixHandle, deferredHolder: Maybe[String] = Absent)(using AllowUnsafe): Unit =
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
        // NOT an at-rest invariant here (deliberately, after testing one): unlike recvInFlight (a second recv while one is kernel-owned
        // is ALWAYS wrong, no legitimate case), unsent bytes in pendingCipher at freeResources time is routine, correct behavior, not a
        // stale-accounting symptom. freeResources is the single free path for every close, including an abrupt one that deliberately
        // abandons an in-flight, not-yet-fully-sent write (a caller closing before a backpressured flush drains): CloseDuringBackpressuredFlushTest
        // pins exactly that as correct ("close while the flush is parked on writability: frees once, clears pending state"). A blanket
        // check here fired on every such legitimate abrupt close under the full suite (confirmed via CloseDuringBackpressuredFlushTest,
        // IoUringDriverCrossTailMockedTest, FlushReArmPendingCoalesceTest), so it would spam production logs on ordinary early disconnects, not just
        // genuine bugs. The sound version of this doctrine for the send tail lives at the reap-and-declare-drained point instead (see
        // onTlsSendComplete's fully-drained branch in IoUringDriver), where "should be exactly zero" is actually always true.
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
                p.completeDiscard(Result.fail(kyo.net.NetConnectionClosedException(Operation.Upgrade)(using fr)))
            case _ => ()
        end match
        h.upgradeActive = false
        h.upgrading = false
        h.handshakeReading = false
        h.postUpgradeReadWindow = false
        // Fail (not merely drop) a queued recv (see queuedRecv): a request submitRecv deferred because another recv was still in flight for
        // this handle. If that in-flight recv's CQE never routed here in time (the handle is closing for an unrelated reason, e.g. a deadline
        // reap), the queued request would otherwise never fire, leaking the promise (the caller's ReadPump/handshake fiber hangs on it forever).
        // Never fire it here instead: freeResources is about to close the read buffer this recv would target, so submitting it now would be a
        // use-after-free.
        h.queuedRecv match
            case PosixHandle.QueuedRecv.Queued(p, _, _) =>
                p.completeDiscard(Result.fail(kyo.Closed(
                    "PosixHandle",
                    Frame.internal,
                    s"fd=${h.readFd}/${h.writeFd} closed while a recv was queued"
                )(using Frame.internal)))
            case _ => ()
        end match
        h.queuedRecv = PosixHandle.QueuedRecv.None
        // Clear promise fields: these are on-heap references with no native close needed; setting to Absent drops the reference.
        h.pendingReadPromise = Absent
        h.pendingAcceptPromise = Absent
        h.pendingWritablePromise = Absent
        // Run the deferred real close(fd) LAST: this method is the exactly-once, zero-holders point that just freed the engine and buffers
        // above, so the fd number inherits the same guarantee (see fdCloseSink). A non-Absent deferredHolder names which holder was still
        // active when the close was requested: "read"/"write" is a genuine concurrent I/O op that raced the close; "deferred-close" is
        // IoUringDriver.registerDeferredClose's own bookkeeping hold (see beginDeferredClose), not an I/O race, and is the routine steady
        // state for any io_uring connection with an armed recv.
        h.fdCloseSink.foreach { closeFd =>
            deferredHolder.foreach { holder =>
                given Frame = Frame.internal
                Log.live.unsafe.debug(s"fd=${h.readFd} handle=${h.id} close(fd) deferred past the $holder hold")
            }
            closeFd()
        }
        h.fdCloseSink = Absent
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
