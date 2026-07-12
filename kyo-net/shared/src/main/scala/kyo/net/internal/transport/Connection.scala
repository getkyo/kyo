package kyo.net.internal.transport

import kyo.*
import kyo.net.Connection
import kyo.net.NetTlsConfig
import kyo.scheduler.IOPromise

/** A TCP connection bundling handle, channels, and pumps.
  *
  * ## Architecture
  *
  * ```
  * Socket ←→ Driver ←→ Pumps ←→ Channels ←→ User code
  *                      ↑
  *                 Connection owns these
  * ```
  *
  * ## Lifecycle
  *
  *   1. `init()` creates channels and pumps
  *   2. `start()` starts the pumps (begins I/O)
  *   3. User reads from `inbound`, writes to `outbound`
  *   4. `close()` stops pumps and closes handle
  *
  * ## Backpressure
  *
  * Channel capacity controls backpressure. When inbound channel fills, ReadPump stops requesting reads, causing TCP flow control. When
  * outbound channel fills, writers block until WritePump drains data.
  */
final private[kyo] class Connection[Handle] private (
    val handle: Handle,
    private val driver: IoDriver[Handle],
    val inbound: Channel.Unsafe[Span[Byte]],
    val outbound: Channel.Unsafe[Span[Byte]],
    private val readPump: ReadPump[Handle],
    private val writePump: WritePump[Handle],
    private val state: AtomicRef.Unsafe[ConnectionState],
    private val closeFn: () => Unit,
    private val closingPromise: Promise.Unsafe[Unit, Any],
    private val readTeardownCauseRef: AtomicRef.Unsafe[Maybe[Closed]]
) extends kyo.net.Connection
    with kyo.net.Connection.UpgradableConnection: // TODO is this the only impl of Connection? if yes, why do we have a seaprate UpgradableConnection?

    /** The connection's close signal (see [[kyo.net.Connection.onClosing]]). Completed once in `closeFn`'s win-the-close branch. */
    private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] = closingPromise

    /** The structural cause this connection's [[ReadPump]] recorded when it began tearing down (a peer FIN, a local shutdown, a clean TLS
      * close, or the underlying read failure), captured on the connection BEFORE the handle teardown runs. Absent while the connection is
      * still reading, or when it was torn down by [[close]] / the write side rather than a read-side teardown.
      */
    private[kyo] def readTeardownCause(using AllowUnsafe): Maybe[Closed] = readTeardownCauseRef.get()

    import scala.compiletime.uninitialized // TODO why do we need this?

    /** Set after construction by the transport (the posix transport on JVM/Native, the Node transport on JS). Left uninitialized on platforms
      * without STARTTLS support.
      */
    // TODO can we make these vars private not private[kyo]? mutable state should be as isolated as possible. Or even better: can we avoid some of these vars?
    @volatile private[kyo] var upgradeFn: Maybe[(NetTlsConfig, Frame) => Fiber.Unsafe[kyo.net.Connection, Abort[kyo.net.NetException]]] =
        Absent

    /** Whether this connection was accepted by a listener (server origin) rather than initiated by `connect` (client origin). A STARTTLS
      * upgrade through the public `Transport.upgradeToTls` runs in the TLS role that matches the TCP origin: an accepted connection upgrades as
      * the TLS server, a connected one as the TLS client. The TCP and TLS roles always coincide for an in-place STARTTLS upgrade, so the origin
      * is the authoritative role source (a config heuristic, e.g. "has a cert+key therefore server", misclassifies a mutual-TLS client that
      * presents its own client certificate). The transport sets this at connection creation; it defaults to the client role.
      */
    @volatile private[kyo] var isServerOrigin: Boolean = false

    /** Set after construction by the transport for platforms that support TLS introspection (JVM, Native). Left Absent on platforms
      * without TLS introspection (JS).
      *
      * The transport installs a function that reads the cached cert hash (computed once at handshake completion on the engine-serialized
      * path) gated on [[isOpen]], NOT one that touches the TLS engine: `serverCertificateHash` runs on the caller's carrier, so a live engine
      * touch here would race the read/write engine ops the driver runs on its FIFO worker (concurrent `SSL` access on a native engine is
      * undefined behavior, and it could read a freed `ssl`). Caching the fixed-for-the-connection-lifetime hash makes this read synchronous and
      * engine-free.
      */
    @volatile private[kyo] var certHashFn: Maybe[() => Maybe[Span[Byte]]] = Absent

    /** Returns the SHA-256 hash of the server's leaf certificate DER bytes (RFC 5929 tls-server-end-point). Delegates to the
      * transport-installed certHashFn (a cache read gated on [[isOpen]]; see [[certHashFn]]), or returns Absent if not supported.
      */
    override def serverCertificateHash: Maybe[Span[Byte]] =
        certHashFn.map(fn => fn()).getOrElse(Absent)

    /** Set after construction by the transport for a TLS connection. Reads the handle's observed close signals (the peer's close_notify on the
      * read side vs a bare TCP FIN) to report the security-relevant close reason. Left Absent for a plaintext connection, which has no
      * close_notify exchange and reports the default [[kyo.net.Connection.CloseReason.Active]].
      */
    @volatile private[kyo] var closeReasonFn: Maybe[() => kyo.net.Connection.CloseReason] = Absent

    /** How the inbound stream ended (RFC 8446 6.1 / RFC 5246 7.2.1). For a TLS connection this delegates to the transport-installed
      * [[closeReasonFn]], which distinguishes an orderly close (the peer's authenticated close_notify was received) from a truncation (a bare
      * TCP FIN with no close_notify) and from a local close. For a plaintext connection (no `closeReasonFn`) it reports
      * [[kyo.net.Connection.CloseReason.Active]].
      */
    override def closeReason: kyo.net.Connection.CloseReason =
        closeReasonFn.map(fn => fn()).getOrElse(kyo.net.Connection.CloseReason.Active)

    /** Start the connection. Begins pumping data between socket and channels. */
    private[kyo] def start()(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.info(s"Connection starting")
        // Created -> Established: pumps begin I/O. A connection detached or closed before start
        // (CAS lost) stays in its terminal/Upgrading state and the pumps are not started.
        val cas = state.compareAndSet(ConnectionState.Created, ConnectionState.Established)
        if cas then
            readPump.start()
            writePump.start()
        // TODO this method should return a boolan and callers should properly handle the case where start returns false. If it should never happen, then this method should throw if the cas fails
    end start

    /** Check if connection is still open. */
    // TODO the logic should be in ConnectionState
    def isOpen(using AllowUnsafe): Boolean =
        state.get() match
            case ConnectionState.Created | ConnectionState.Established                        => true
            case ConnectionState.Upgrading | ConnectionState.Closing | ConnectionState.Closed => false

    /** Close the connection. Closes channels and handle. Idempotent. */
    def close()(using AllowUnsafe, Frame): Unit = closeFn()

    /** Force this connection's handle closed even while `Upgrading`, where ordinary [[close]] is a no-op by design (the fd is owned by the
      * in-flight TLS upgrade, which is responsible for its own success/failure cleanup -- see [[ConnectionState.Upgrading]]). Used ONLY by the
      * owning transport's shutdown sweep: at shutdown nothing will ever complete a still-in-flight upgrade, so deferring to it (ordinary
      * close()'s behavior) strands the fd until the upgrade's OWN cleanup happens to run on the driver's carrier, which is asynchronous and not
      * bounded by the time the transport's close() call returns (observed as an intermittent CLOSE_WAIT leak under kyo-test's leak check: the
      * upgrade's failure path does eventually free the fd, just not before a fast-completing test's leak check inspects the fd table).
      *
      * `driver.cancel(handle)` synchronously fails any promise the upgrade has parked (its own handshake read, most commonly), which drives the
      * SAME onFailed/onPanic -> engine-free-and-fd-close path an ordinary handshake failure takes, just synchronously instead of racing a
      * scheduler turn. `driver.closeHandle(handle)` is a belt-and-suspenders direct fd close for the (rarer) window where the upgrade is not
      * parked on any I/O at the moment of shutdown. Both are safe to call unconditionally alongside a concurrent, genuinely-still-running
      * upgrade outcome: `PosixHandle`'s fd-close is a CAS-guarded one-shot claim, so whichever caller reaches it first wins and the other's
      * redundant attempt is a no-op; a still-in-flight TLS engine (known only to the upgrade's own local state) is freed by whichever of this or
      * the upgrade's own failure path runs the engine.free() call, exactly once, since they are mutually exclusive on the SAME failure trigger.
      */
    private[net] def forceCloseIfUpgrading()(using AllowUnsafe, Frame): Unit =
        if state.get() == ConnectionState.Upgrading then
            driver.cancel(handle)
            driver.closeHandle(handle)

    /** Upgrade this connection to TLS via the transport-provided upgrade function.
      *
      * Returns a Fiber.Unsafe that completes with the new TLS connection, or aborts [[kyo.net.NetException]] if the upgrade fails or is
      * unsupported on this platform.
      */
    private[net] def doUpgradeToTls(tls: NetTlsConfig, frame: Frame): Fiber.Unsafe[kyo.net.Connection, Abort[kyo.net.NetException]] =
        upgradeFn match
            case Present(fn) => fn(tls, frame)
            case Absent      =>
                // Unsafe: this fallback only builds a failed Fiber.Unsafe.fromResult (no side effect), so the danger bridge is inert.
                // TODO fix fromResult then! do not workaround issues
                import AllowUnsafe.embrace.danger // TODO take the AllowUnsafe implicit in the method instead
                given Frame = frame
                Fiber.Unsafe.fromResult(Result.fail(kyo.net.NetNotUpgradableException()))
    end doUpgradeToTls

    /** Detach the underlying handle for reuse in a TLS upgrade, WITHOUT closing the handle.
      *
      * Closes the inbound/outbound channels (causing pumps to stop) and cancels the driver registration, but does not call
      * `driver.closeHandle`. This leaves the underlying socket/fd open so the caller (NioTransport.upgradeToTls) can drive a TLS handshake
      * on the same channel and then create a new Connection over it.
      *
      * Returns any bytes that the ReadPump had already placed in the inbound channel but the caller had not yet consumed. These bytes are
      * raw ciphertext (for an upgrading plaintext connection) and must be fed to the TLS engine before registering for further socket
      * reads, so that no data is silently discarded.
      *
      * The caller is responsible for: (a) cancelling driver registrations before calling this, and (b) eventually closing the handle.
      *
      * This method is idempotent. On second call it returns Absent (channel was already closed).
      */
    def detachForUpgrade()(using AllowUnsafe, Frame): Maybe[Chunk[Span[Byte]]] =
        // Win the detach by CASing Established -> Upgrading: the Upgrading state keeps the fd open and
        // bars teardown, so the socket can be handed to the TLS upgrade. A second detach, or a detach
        // after a close already won, loses the CAS and returns Absent (idempotent), so the fd is never
        // double-handed-off.
        if state.compareAndSet(ConnectionState.Established, ConnectionState.Upgrading)
            || state.compareAndSet(ConnectionState.Created, ConnectionState.Upgrading)
        then
            // Close inbound and capture any bytes the ReadPump already staged but nobody consumed.
            // These are raw network bytes (TLS ciphertext) that the handshake engine needs.
            val buffered = inbound.close()
            discard(outbound.close()) // TODO why is discarding safe here?
            // Fail pending I/O promises (causes pumps to see failure and exit teardown). Routed through driver.detachForUpgrade rather than
            // driver.cancel so a driver can keep its transport registration for the upgrade: the NIO driver keeps the SelectionKey (avoiding a
            // cancel+re-register race), while other drivers fall back to cancel. Intentionally does NOT call driver.closeHandle so the fd stays open.
            driver.detachForUpgrade(handle)
            buffered.map(Chunk.from(_))
        else Absent
        end if
    end detachForUpgrade

end Connection

private[kyo] object Connection:

    /** Create a connection. Does not start pumps; call `start()` after creation.
      *
      * `canRelease` is the per-backend teardown predicate that gates the `TeardownState.AwaitingInFlight -> Released` transition (the io_uring
      * in-flight count == 0; the poller guard holders == 0; the NIO key flushed; the JS handle destroyed). It defaults to `() => true` because the
      * posix and JS drivers defer the actual fd close internally (the io_uring `pendingCloses` in-flight gate, the poller `HandleGuard` holder
      * gate): for those backends `driver.closeHandle` is called eagerly and the in-flight deferral is realized inside the driver, so the connection
      * releases promptly and the predicate is the trivial one. A backend that needs the connection itself to hold the release until its predicate
      * clears supplies a non-trivial one.
      */
    def init[Handle](
        handle: Handle,
        driver: IoDriver[Handle],
        channelCapacity: Int,
        onClose: () => Unit = () => (),
        canRelease: () => Boolean = () => true
    )(using AllowUnsafe, Frame): Connection[Handle] =
        val inbound  = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val outbound = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val state    = AtomicRef.Unsafe.init[ConnectionState](ConnectionState.Created)
        // The teardown handoff as one named machine (the four per-backend exactly-once teardown dances unified): Live -> ReleaseRequested (the
        // close won) -> AwaitingInFlight (the WRITE-side drain finished) -> Released (the fd closed exactly once, resources freed exactly once).
        // The single CAS into Released is the exactly-once gate (INV-13); it is reached only after the WritePump has finished with the outbound
        // channel (the WRITE-side drain), so the fd is closed only once every queued span has actually been written, not merely taken off the
        // channel. The inbound (read-side) drain NEVER gates this transition (INV-12 absence half): a pooled connection with no reader would
        // otherwise leak the peer-FIN'd fd in CLOSE_WAIT.
        val teardown = AtomicRef.Unsafe.init[TeardownState](TeardownState.Live)
        // The AwaitingInFlight -> Released transition: gated on the per-backend canRelease predicate, performed by exactly one carrier (the CAS),
        // running cancel + closeHandle + onClose once. When canRelease is false the release is held; for the posix/JS backends canRelease is the
        // trivial () => true and the in-flight deferral happens inside driver.closeHandle.
        // TODO it's odd to have these impls here, why can't they be in the Connection instance class?
        def releaseHandle(): Unit =
            if canRelease() && teardown.compareAndSet(TeardownState.AwaitingInFlight, TeardownState.Released) then
                // Reflect the lifecycle terminal too, so isOpen reads Closed; the Released CAS above is the exactly-once gate, not this.
                discard(state.compareAndSet(ConnectionState.Closing, ConnectionState.Closed))
                driver.cancel(handle)
                driver.closeHandle(handle)
                // Notify the owning transport that this connection's handle is gone, so it drops the connection from its open-connection
                // registry. A connection that is never torn down (its peer FIN never arrives, its handler never closes it) stays registered, so
                // the transport's close() can close it explicitly instead of leaking its fd past the pool teardown.
                onClose()

        // The connection's close signal (kyo.net.Connection.onClosing). Completed once when closeFn wins the close, at close-START (before the
        // channel drains and the handle teardown below), so an observer sees close-start not fd-release. completeDiscard is idempotent, so the
        // re-entrant Closing branch and any repeat close are no-ops. Created before closeFn so the closure captures it. It never fires from
        // detachForUpgrade (state=Upgrading bars this branch), which is correct: the upgraded connection is a fresh init with its own signal.
        val closingPromise = Promise.Unsafe.init[Unit, Any]()

        // The structural cause the ReadPump records when its teardown begins (a peer FIN, a local shutdown, a clean TLS close, or the
        // underlying read failure), read back via [[readTeardownCause]]. Crosses from the ReadPump's scheduler-serialized teardown callback to
        // any caller reading the connection afterward, so it is an AtomicRef rather than a plain var. Set at most once: teardown() records the
        // cause before calling closeFn(), and the ReadPump's own onComplete is the single-owner callback the driver never re-enters concurrently.
        val readTeardownCauseRef = AtomicRef.Unsafe.init[Maybe[Closed]](Absent)

        val closeFn: () => Unit = () =>
            // Win the close by CASing a live state -> Closing. Created and Established both go to
            // Closing (a never-started connection closes too); Upgrading does NOT, since its fd is
            // owned by the TLS upgrade. A second close loses the CAS and falls to the re-entrant
            // Closing branch below.
            if state.compareAndSet(ConnectionState.Established, ConnectionState.Closing)
                || state.compareAndSet(ConnectionState.Created, ConnectionState.Closing)
            then
                // Fire the connection's close signal at close-start, before the drains and the handle teardown below.
                closingPromise.completeDiscard(Result.succeed(()))
                // Live -> ReleaseRequested: the close won; parked waiters are being unblocked by the drains below.
                discard(teardown.compareAndSet(TeardownState.Live, TeardownState.ReleaseRequested))
                // closeAwaitEmpty, not close: a consumer that has not yet drained the bytes the ReadPump staged before EOF can still take them
                // (takes drain a closing channel until empty), so a close initiated on the read side does not discard buffered inbound bytes as
                // close()'s dropped backlog would. The handle teardown below does not wait for that drain, so the fd is reclaimed even if the
                // consumer never reads the rest (a pooled connection with no reader). This inbound (read-side) drain NEVER gates the release.
                discard(inbound.closeAwaitEmpty())
                // Mirror the ReadPump's inbound drain on the outbound side: closeAwaitEmpty marks the channel closing-for-writes (new offers
                // fail Closed) while the WritePump keeps taking the already-queued spans until the channel is empty. A peer half-close
                // (shutdown(SHUT_WR)) leaves the peer still READING, so the WritePump's parked partial write resumes when the socket becomes
                // writable, flushes the queued tail in order, then takes again from the now-empty closing channel and gets Closed, which runs
                // the WritePump's teardown and re-enters this closeFn. So the queued outbound bytes reach the peer instead of being discarded
                // by a bare outbound.close(). The handle teardown is intentionally NOT chained on the drain fiber: that fiber completes when
                // the channel queue is empty (the tail was TAKEN by the WritePump) which is before the tail is WRITTEN to the socket, so
                // closing the fd there would race the in-flight write and drop the tail. The WritePump re-entry below is the real
                // "outbound fully flushed" signal.
                val outboundDrained = outbound.closeAwaitEmpty()
                // Tear the handle down synchronously when the outbound channel is already empty (the common idle case): nothing is queued to
                // flush, so do not wait for the WritePump's async take-Closed re-entry. This makes close() reach driver.closeHandle within its
                // own call, so the transport's close() (which closes every still-registered connection just before the pool teardown) reclaims
                // the fd while the driver's reap loop is still alive, instead of leaving the teardown to race an async re-entry. When the
                // outbound has queued writes, the drain fiber is still pending, so the WritePump re-entry below flushes the tail first.
                if outboundDrained.done() then
                    // ReleaseRequested -> AwaitingInFlight: the WRITE-side drain is done; attempt the gated release.
                    discard(teardown.compareAndSet(TeardownState.ReleaseRequested, TeardownState.AwaitingInFlight))
                    releaseHandle()
                end if
            else if state.get() == ConnectionState.Closing then
                // Re-entrant close after closeFn itself initiated the close (the state is Closing).
                // The WritePump reached this by taking Closed from the closing outbound channel after
                // it had nothing left to write (the half-close flush completed), or by its
                // write/writable wait failing (the peer is gone, or driver.close from the owning Scope
                // failed every pending writable as the safety net). Either way the outbound side is
                // finished, so advance to AwaitingInFlight and tear down the handle. This re-entry, not
                // the drain fiber, is what bounds close(): a half-close that can drain reaches it after
                // the flush, and a full close whose outbound can never drain reaches it once the peer's
                // RST or the Scope's driver.close fails the parked writable, so close() never waits forever.
                discard(teardown.compareAndSet(TeardownState.ReleaseRequested, TeardownState.AwaitingInFlight))
                releaseHandle()
            end if

        val readPump = new ReadPump(
            handle,
            driver,
            inbound,
            closeFn,
            recordTeardownCause = cause => readTeardownCauseRef.set(Present(cause))
        )
        val writePump = new WritePump(handle, driver, outbound, closeFn, AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

        new Connection(handle, driver, inbound, outbound, readPump, writePump, state, closeFn, closingPromise, readTeardownCauseRef)
    end init

    /** Create a driverless in-memory connection over two pre-existing channels.
      *
      * Unlike [[init]], which always wires a driver and two pumps, this connection has neither: reads come straight from `inbound` and writes
      * go straight to `outbound`, so it backs piping and testing with no syscalls. It is a direct [[kyo.net.Connection]] rather than the
      * generic `Connection[Handle]` (which requires a driver), so there is no null driver. It is NOT an `UpgradableConnection`: STARTTLS on it
      * aborts `Closed` via the public `upgradeToTls` path, and `detachForUpgrade` returns `Absent`.
      */
    def inMemory(
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]]
    )(using AllowUnsafe, Frame): kyo.net.Connection =
        val in             = inbound
        val out            = outbound
        val closedFlag     = AtomicBoolean.Unsafe.init(false)
        val closingPromise = Promise.Unsafe.init[Unit, Any]()
        new kyo.net.Connection:
            def inbound: Channel.Unsafe[Span[Byte]]  = in
            def outbound: Channel.Unsafe[Span[Byte]] = out
            def isOpen(using AllowUnsafe): Boolean   = !closedFlag.get()
            def close()(using AllowUnsafe, Frame): Unit =
                // Close both channels; there is no driver to cancel or handle to close. Idempotent via the CAS.
                if closedFlag.compareAndSet(false, true) then
                    closingPromise.completeDiscard(Result.succeed(()))
                    discard(in.close())
                    discard(out.close())
            private[kyo] def onClosing: Fiber.Unsafe[Unit, Any]                        = closingPromise
            def detachForUpgrade()(using AllowUnsafe, Frame): Maybe[Chunk[Span[Byte]]] = Absent // not upgradable: no driver or socket
            private[net] def start()(using AllowUnsafe, Frame): Unit                   = ()     // no pumps to start
            // Plaintext, driverless connection: no peer certificate to hash and no close_notify exchange to observe.
            def serverCertificateHash: Maybe[Span[Byte]]    = Absent
            def closeReason: kyo.net.Connection.CloseReason = kyo.net.Connection.CloseReason.Active
        end new
    end inMemory

    /** Allocate two unbounded channels and cross-wire them into a connected in-memory pair: each side's `outbound` is the other side's
      * `inbound`, so a write on one is readable on the other (the INMEM shape kyo-jsonrpc's `InMemoryTransport` uses).
      */
    def inMemoryPair()(using AllowUnsafe, Frame): (kyo.net.Connection, kyo.net.Connection) =
        val ab = Channel.Unsafe.init[Span[Byte]](Int.MaxValue)
        val ba = Channel.Unsafe.init[Span[Byte]](Int.MaxValue)
        (inMemory(inbound = ba, outbound = ab), inMemory(inbound = ab, outbound = ba))
    end inMemoryPair

end Connection
