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
    private val state: AtomicRef.Unsafe[Connection.State],
    private val closeFn: () => Unit,
    private val closingPromise: Promise.Unsafe[Unit, Any],
    private val upgradeClaimed: AtomicBoolean.Unsafe,
    private val canRelease: () => Boolean,
    private val teardown: AtomicRef.Unsafe[Connection.Teardown],
    private val onClose: () => Unit
) extends kyo.net.Connection:

    /** The connection's close signal (see [[kyo.net.Connection.onClosing]]). Completed once in `closeFn`'s win-the-close branch. */
    private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] = closingPromise

    /** Set after construction by the transport (the posix transport on JVM/Native, the Node transport on JS). Left uninitialized on platforms
      * without STARTTLS support.
      */
    // Set post-construction by the owning transport (a different package), so private[kyo] cross-package visibility is required;
    // @volatile because the write and the pump/caller reads happen on different carriers.
    @volatile private[kyo] var upgradeFn: Maybe[(NetTlsConfig, Frame) => Fiber.Unsafe[kyo.net.Connection, Abort[kyo.net.NetException]]] =
        Absent

    /** How to abandon the in-flight STARTTLS upgrade that owns this connection's fd. Installed by every transport that upgrades in place (the
      * posix transport on JVM/Native, the NIO transport on the JVM floor, the JS transport on Node) immediately BEFORE it calls
      * [[detachForUpgrade]], and read ONLY by [[close]], and only while the state is `Upgrading`. Left `Absent` on a connection that never
      * detaches.
      *
      * A detached connection's fd is deliberately kept open for the upgrade to reuse, so [[close]] cannot close it directly: the upgrade is
      * mid-handshake on that same fd, and closing it underneath would break a handshake that is about to succeed. The thunk instead hands the
      * close to the upgrade's own owner (its outcome promise), which releases the fd, and the TLS engine where the transport owns one, on
      * exactly the path a handshake failure already takes. When the upgrade has ALREADY settled, that promise is complete and the thunk is
      * inherently a no-op, so a close arriving after a successful upgrade leaves the fd the upgraded connection now owns untouched: the promise,
      * not this connection's state, is what discriminates a still-in-flight upgrade from a finished one.
      *
      * Armed before the detach so a `close()` that observes `Upgrading` always finds it installed (the volatile write happens-before the state
      * CAS the detach wins, and only the CAS winner publishes `Upgrading`). Follows the same arm-before-detach discipline as the posix handle's
      * `upgradeActive` window flag; [[claimUpgrade]] is what makes the at-most-one-upgrade assumption both arms rely on actually hold.
      */
    // Set post-construction by the owning transport (a different package), so private[kyo] cross-package visibility is required;
    // @volatile because the write and the closing caller's read happen on different carriers.
    @volatile private[kyo] var upgradeAbandon: Maybe[() => Unit] = Absent

    /** Win the right to run this connection's single STARTTLS upgrade. The arm-before-detach discipline ([[upgradeAbandon]], the posix
      * handle's upgrade-window flags) mutates shared state BEFORE [[detachForUpgrade]]'s CAS decides a winner, so a second `upgradeToTls`
      * call must be turned away before it can touch any of that state: an unconditional second arm would overwrite the in-flight upgrade's
      * abandon thunk with one over the loser's already-settled promise, permanently disarming `close()`'s only route to the first upgrade's
      * fd, and would re-arm handle state on a window it does not own. One-shot: the first caller wins and proceeds to the arm and the
      * detach; every later caller gets `false` and must fail typed (`NetAlreadyDetachedException`) without writing anything.
      */
    private[kyo] def claimUpgrade()(using AllowUnsafe): Boolean =
        upgradeClaimed.compareAndSet(false, true)

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
      * close_notify exchange and reports the default [[kyo.net.Connection.Status.Active]].
      */
    @volatile private[kyo] var statusFn: Maybe[() => kyo.net.Connection.Status] = Absent

    /** How the inbound stream ended (RFC 8446 6.1 / RFC 5246 7.2.1). For a TLS connection this delegates to the transport-installed
      * [[statusFn]], which distinguishes an orderly close (the peer's authenticated close_notify was received) from a truncation (a bare
      * TCP FIN with no close_notify) and from a local close. For a plaintext connection (no `statusFn`) it reports
      * [[kyo.net.Connection.Status.Active]].
      */
    override def status: kyo.net.Connection.Status =
        statusFn.map(fn => fn()).getOrElse(kyo.net.Connection.Status.Active)

    /** Start the connection. Begins pumping data between socket and channels. Returns true when the Created -> Established CAS won and the
      * pumps started; false when the connection had already raced to a terminal or Upgrading state (see [[kyo.net.Connection.start]]).
      */
    private[kyo] def start()(using AllowUnsafe, Frame): Boolean =
        Log.live.unsafe.info(s"Connection starting")
        // Created -> Established: pumps begin I/O. A connection detached or closed before start
        // (CAS lost) stays in its terminal/Upgrading state and the pumps are not started; the
        // false return tells the caller not to hand the connection out as open.
        val won = state.compareAndSet(Connection.State.Created, Connection.State.Established)
        if won then
            readPump.start()
            writePump.start()
        won
    end start

    /** Check if connection is still open. */
    def isOpen(using AllowUnsafe): Boolean = state.get().isOpen

    /** Close the connection. Closes channels and handle. Idempotent.
      *
      * While the connection is `Upgrading` the close is routed to the in-flight upgrade instead (see [[upgradeAbandon]]): the fd belongs to that
      * upgrade, so `closeFn` deliberately cannot take it, and the upgrade's own owner is the only place that knows how to release everything it
      * holds (the fd, and the TLS engine where the transport owns one).
      */
    def close()(using AllowUnsafe, Frame): Unit =
        closeFn()
        // The Upgrading arm lives HERE and not in closeFn, and the distinction is load-bearing: closeFn is also what the plaintext pumps re-enter
        // as `detachForUpgrade` tears them down (it closes their channels, so each pump's teardown calls closeFn), so abandoning the upgrade from
        // closeFn would kill every upgrade at its first step. The pumps only ever call closeFn, never close(), so reaching this line means the
        // connection's OWNER asked to close, which for a detached connection means abandoning the upgrade it was detached for.
        //
        // Without this routing an abandoned upgrade's fd is reachable by NO closer: closeFn cannot take an Upgrading fd (by design), the pumps
        // that would otherwise observe the peer's FIN were torn down by the detach, and the transport's own shutdown sweep never runs on the
        // process-shared transport (a process-lifetime singleton that is never closed). The fd then sits open forever, and once the peer FINs it
        // sits in CLOSE_WAIT: FIN received, none ever sent.
        if state.get() == Connection.State.Upgrading then
            upgradeAbandon.foreach(abandon => abandon())
    end close

    /** Force this connection's handle closed SYNCHRONOUSLY even while `Upgrading`, where the fd is owned by the in-flight TLS upgrade rather than
      * by this connection (see [[Connection.State.Upgrading]]). Used ONLY by the owning transport's shutdown sweep.
      *
      * Ordinary [[close]] already abandons a still-in-flight upgrade (via [[upgradeAbandon]]), so at shutdown the fd is not stranded. But that
      * release runs on the driver's engine FIFO: it is asynchronous, not bounded by the time the transport's `close()` call returns, and the
      * shutdown is about to close the very pool whose carrier would run it. This force-close is what makes the release synchronous for that one
      * caller, so the fd is reclaimed while the driver is still alive (the alternative was observed as an intermittent CLOSE_WAIT leak under
      * kyo-test's leak check: the upgrade's failure path does eventually free the fd, just not before a fast-completing test's leak check
      * inspects the fd table).
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
        if state.get() == Connection.State.Upgrading then
            driver.cancel(handle)
            driver.closeHandle(handle)

    /** Upgrade this connection to TLS via the transport-provided upgrade function.
      *
      * Returns a Fiber.Unsafe that completes with the new TLS connection, or aborts [[kyo.net.NetException]] if the upgrade fails or is
      * unsupported on this platform.
      */
    private[net] def doUpgradeToTls(tls: NetTlsConfig, frame: Frame)(using
        AllowUnsafe
    ): Fiber.Unsafe[kyo.net.Connection, Abort[kyo.net.NetException]] =
        upgradeFn match
            case Present(fn) => fn(tls, frame)
            case Absent =>
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
        if state.compareAndSet(Connection.State.Established, Connection.State.Upgrading)
            || state.compareAndSet(Connection.State.Created, Connection.State.Upgrading)
        then
            // Close inbound and capture any bytes the ReadPump already staged but nobody consumed.
            // These are raw network bytes (TLS ciphertext) that the handshake engine needs.
            val buffered = inbound.close()
            // The outbound close result is intentionally discarded: detachForUpgrade hands the fd to the TLS upgrade, so any queued outbound
            // bytes are abandoned by design (the upgrade re-drives the socket); unlike inbound, whose staged ciphertext the handshake needs.
            discard(outbound.close())
            // Fail pending I/O promises (causes pumps to see failure and exit teardown). Routed through driver.detachForUpgrade rather than
            // driver.cancel so a driver can keep its transport registration for the upgrade: the NIO driver keeps the SelectionKey (avoiding a
            // cancel+re-register race), while other drivers fall back to cancel. Intentionally does NOT call driver.closeHandle so the fd stays open.
            driver.detachForUpgrade(handle)
            buffered.map(Chunk.from(_))
        else Absent
        end if
    end detachForUpgrade

    private def releaseHandle()(using AllowUnsafe, Frame): Unit =
        if canRelease() && teardown.compareAndSet(Connection.Teardown.AwaitingInFlight, Connection.Teardown.Released) then
            // Reflect the lifecycle terminal too, so isOpen reads Closed; the Released CAS above is the exactly-once gate, not this.
            discard(state.compareAndSet(Connection.State.Closing, Connection.State.Closed))
            driver.cancel(handle)
            driver.closeHandle(handle)
            // Notify the owning transport that this connection's handle is gone, so it drops the connection from its open-connection
            // registry. A connection that is never torn down (its peer FIN never arrives, its handler never closes it) stays registered, so
            // the transport's close() can close it explicitly instead of leaking its fd past the pool teardown.
            onClose()

end Connection

private[kyo] object Connection:

    /** The named lifecycle state of a connection, held in one atomic cell and advanced by single-CAS
      * transitions (the kyo Queue/Gate lifecycle idiom).
      *
      * The lifecycle is one named state in one atomic cell. Naming the states makes each transition a
      * single CAS one carrier wins: the loser re-reads the winner's state rather than acting on a stale
      * field, so a check-one-field-act-on-another race is unrepresentable.
      *
      * Distinct from the public close-reason enum `kyo.net.Connection.Status`: this tracks the internal
      * open/upgrading/closing progression, not the close reason surfaced to a caller.
      *
      *   - [[Created]]: built, pumps not yet started.
      *   - [[Established]]: pumps running, normal I/O.
      *   - [[Upgrading]]: a STARTTLS detach won; the fd is kept open and NOT torn down (the connection is
      *     closed to its own pumps yet its socket lives on for the TLS upgrade). The fd is owned by that
      *     upgrade, so this state is terminal for the connection's OWN teardown path: `closeFn` never
      *     takes an Upgrading fd, and a `close()` is routed to the upgrade's owner to abandon it instead
      *     (see `Connection.upgradeAbandon`). It is NOT a state the connection ever leaves: a successful
      *     upgrade builds a fresh connection over the same fd rather than moving this one on.
      *   - [[Closing]]: a close was initiated; the outbound side is draining before teardown.
      *   - [[Closed]]: terminal; the handle has been released exactly once.
      */
    private[kyo] enum State derives CanEqual:
        case Created
        case Established
        case Upgrading
        case Closing
        case Closed

        /** Whether a connection in this state is still open. */
        def isOpen: Boolean = this match
            case Created | Established        => true
            case Upgrading | Closing | Closed => false
    end State

    /** The named teardown state of a connection, advanced by single-CAS transitions and gated by a
      * per-backend "can I release now?" predicate.
      *
      * Unifies the four per-backend exactly-once teardown dances into one machine: the fd is closed
      * exactly once and resources are freed exactly once, by whichever carrier observes the predicate
      * true with the close requested. The predicate is injected per backend (io_uring in-flight count
      * == 0; poller guard holders == 0; NIO key flushed; JS destroyed), the Go increfAndClose /
      * decref-to-zero and Netty DelayedClose/canCloseNow shape.
      *
      * The `ReleaseRequested -> AwaitingInFlight` gate waits on the WRITE-side drain and the in-flight
      * count ONLY, NEVER on the inbound (read-side) channel draining: the fd close is prompt, and the
      * in-memory inbound channel is independent of the fd (a live consumer drains it separately). The
      * release MUST NOT gate on the inbound channel draining: a pooled connection with no reader would
      * then never release, leaking the peer-FIN'd fd in CLOSE_WAIT.
      *
      *   - [[Live]]: no close requested.
      *   - [[ReleaseRequested]]: close requested; parked waiters being unblocked.
      *   - [[AwaitingInFlight]]: write-side drained; awaiting the in-flight count to reach zero.
      *   - [[Released]]: terminal; the fd is closed exactly once, resources freed exactly once.
      */
    private[kyo] enum Teardown derives CanEqual:
        case Live
        case ReleaseRequested
        case AwaitingInFlight
        case Released
    end Teardown

    /** Create a connection. Does not start pumps; call `start()` after creation.
      *
      * `canRelease` is the per-backend teardown predicate that gates the `Connection.Teardown.AwaitingInFlight -> Released` transition (the io_uring
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
        val state    = AtomicRef.Unsafe.init[Connection.State](Connection.State.Created)
        // The teardown handoff as one named machine (the four per-backend exactly-once teardown dances unified): Live -> ReleaseRequested (the
        // close won) -> AwaitingInFlight (the WRITE-side drain finished) -> Released (the fd closed exactly once, resources freed exactly once).
        // The single CAS into Released is the exactly-once gate; it is reached only after the WritePump has finished with the outbound
        // channel (the WRITE-side drain), so the fd is closed only once every queued span has actually been written, not merely taken off the
        // channel. The inbound (read-side) drain NEVER gates this transition: a pooled connection with no reader would
        // otherwise leak the peer-FIN'd fd in CLOSE_WAIT.
        val teardown = AtomicRef.Unsafe.init[Connection.Teardown](Connection.Teardown.Live)
        // The AwaitingInFlight -> Released transition: gated on the per-backend canRelease predicate, performed by exactly one carrier (the CAS),
        // running cancel + closeHandle + onClose once. When canRelease is false the release is held; for the posix/JS backends canRelease is the
        // trivial () => true and the in-flight deferral happens inside driver.closeHandle.

        // The connection's close signal (kyo.net.Connection.onClosing). Completed once when closeFn wins the close, at close-START (before the
        // channel drains and the handle teardown below), so an observer sees close-start not fd-release. completeDiscard is idempotent, so the
        // re-entrant Closing branch and any repeat close are no-ops. Created before closeFn so the closure captures it. It never fires from
        // detachForUpgrade (state=Upgrading bars this branch), which is correct: the upgraded connection is a fresh init with its own signal.
        val closingPromise = Promise.Unsafe.init[Unit, Any]()

        // Forward reference to the Connection instance closeFn calls releaseHandle on. Set once, synchronously, right after the
        // instance is constructed below, before closeFn can ever run (closeFn only fires once a pump starts or close() is called, both of
        // which happen strictly after init returns the constructed connection to its caller).
        var self: Connection[Handle] = null.asInstanceOf[Connection[Handle]]

        val closeFn: () => Unit = () =>
            // Win the close by CASing a live state -> Closing. Created and Established both go to
            // Closing (a never-started connection closes too); Upgrading does NOT, since its fd is
            // owned by the TLS upgrade. A second close loses the CAS and falls to the re-entrant
            // Closing branch below.
            if state.compareAndSet(Connection.State.Established, Connection.State.Closing)
                || state.compareAndSet(Connection.State.Created, Connection.State.Closing)
            then
                // Fire the connection's close signal at close-start, before the drains and the handle teardown below.
                closingPromise.completeDiscard(Result.succeed(()))
                // Live -> ReleaseRequested: the close won; parked waiters are being unblocked by the drains below.
                discard(teardown.compareAndSet(Connection.Teardown.Live, Connection.Teardown.ReleaseRequested))
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
                    discard(teardown.compareAndSet(Connection.Teardown.ReleaseRequested, Connection.Teardown.AwaitingInFlight))
                    self.releaseHandle()
                end if
            else if state.get() == Connection.State.Closing then
                // Re-entrant close after closeFn itself initiated the close (the state is Closing).
                // The WritePump reached this by taking Closed from the closing outbound channel after
                // it had nothing left to write (the half-close flush completed), or by its
                // write/writable wait failing (the peer is gone, or driver.close from the owning Scope
                // failed every pending writable as the safety net). Either way the outbound side is
                // finished, so advance to AwaitingInFlight and tear down the handle. This re-entry, not
                // the drain fiber, is what bounds close(): a half-close that can drain reaches it after
                // the flush, and a full close whose outbound can never drain reaches it once the peer's
                // RST or the Scope's driver.close fails the parked writable, so close() never waits forever.
                discard(teardown.compareAndSet(Connection.Teardown.ReleaseRequested, Connection.Teardown.AwaitingInFlight))
                self.releaseHandle()
            end if

        val readPump  = new ReadPump(handle, driver, inbound, closeFn)
        val writePump = new WritePump(handle, driver, outbound, closeFn, AtomicRef.Unsafe.init[WriteState](WriteState.Idle))

        self = new Connection(
            handle,
            driver,
            inbound,
            outbound,
            readPump,
            writePump,
            state,
            closeFn,
            closingPromise,
            AtomicBoolean.Unsafe.init(false),
            canRelease,
            teardown,
            onClose
        )
        self
    end init

    /** Create a driverless in-memory connection over two pre-existing channels.
      *
      * Unlike [[init]], which always wires a driver and two pumps, this connection has neither: reads come straight from `inbound` and writes
      * go straight to `outbound`, so it backs piping and testing with no syscalls. It is a direct [[kyo.net.Connection]] rather than the
      * generic `Connection[Handle]` (which requires a driver), so there is no null driver. It does not support in-place STARTTLS: `upgradeToTls`
      * aborts `Closed` via the public path, and `detachForUpgrade` returns `Absent`.
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
            private[net] def start()(using AllowUnsafe, Frame): Boolean                = true   // no pumps; immediately usable
            // Plaintext, driverless connection: no peer certificate to hash and no close_notify exchange to observe.
            def serverCertificateHash: Maybe[Span[Byte]] = Absent
            def status: kyo.net.Connection.Status        = kyo.net.Connection.Status.Active
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
