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
    private val releasedPromise: Promise.Unsafe[kyo.net.Connection.CloseOutcome, Any],
    private val upgradeClaimed: AtomicBoolean.Unsafe,
    private val teardown: AtomicRef.Unsafe[Connection.Teardown],
    private val closeFlushGrace: Duration,
    private val closeTimer: Connection.CloseTimer
) extends kyo.net.Connection:

    /** The connection's close signal (see [[kyo.net.Connection.onClosing]]). Completed once in `closeFn`'s win-the-close branch. */
    private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] = closingPromise

    def released(using AllowUnsafe): Fiber.Unsafe[kyo.net.Connection.CloseOutcome, Any] = releasedPromise

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

    /** Returns the RFC 5929 tls-server-end-point hash of the server's leaf certificate. Delegates to the
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

    private[kyo] def isReleased(using AllowUnsafe): Boolean = teardown.get() == Connection.Teardown.Released

    private[kyo] def writeState(using AllowUnsafe): WriteState = writePump.current

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
            case Absent      =>
                given Frame = frame
                Fiber.Unsafe.fromResult(Result.fail(kyo.net.NetNotUpgradableException()))
    end doUpgradeToTls

    /** Detach the underlying handle for reuse in a TLS upgrade, WITHOUT closing the handle.
      *
      * Closes the inbound/outbound channels (causing pumps to stop) and cancels the driver registration, but does not call
      * `driver.release`. This leaves the underlying socket/fd open so the caller (NioTransport.upgradeToTls) can drive a TLS handshake
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
    def detachForUpgrade()(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[Chunk[Span[Byte]]], Any] =
        // Win the detach by CASing Established -> Upgrading: the Upgrading state keeps the fd open and
        // bars teardown, so the socket can be handed to the TLS upgrade. A second detach, or a detach
        // after a close already won, loses the CAS and returns Absent (idempotent), so the fd is never
        // double-handed-off.
        if state.compareAndSet(Connection.State.Established, Connection.State.Upgrading)
            || state.compareAndSet(Connection.State.Created, Connection.State.Upgrading)
        then
            // Close inbound and capture any bytes the ReadPump already staged but nobody consumed.
            // These are raw network bytes (TLS ciphertext) that the handshake engine needs.
            //
            // The capture is a handover rather than a return value because the driver is detached below, AFTER this line: a ReadPump
            // offer carrying the peer's first flight can still be committing right here. Missing it would strand the handshake waiting
            // for bytes that were read and dropped, so the result is delivered once that offer lands. The handover settles inline
            // whenever no offer is in flight, which is the ordinary case.
            val buffered = inbound.close()
            // The outbound close result is intentionally discarded: detachForUpgrade hands the fd to the TLS upgrade, so any queued outbound
            // bytes are abandoned by design (the upgrade re-drives the socket); unlike inbound, whose staged ciphertext the handshake needs.
            discard(outbound.close())
            // Fail pending I/O promises (causes pumps to see failure and exit teardown). Routed through driver.detachForUpgrade rather than
            // driver.cancel so a driver can keep its transport registration for the upgrade: the NIO driver keeps the SelectionKey (avoiding a
            // cancel+re-register race), while other drivers fall back to cancel. Intentionally does NOT call driver.release so the fd stays open.
            driver.detachForUpgrade(handle)
            buffered.map(_.map(Chunk.from(_)))
        else Fiber.Unsafe.fromResult(Result.succeed(Absent))
        end if
    end detachForUpgrade

    /** The close's next step, run on the handle's owner after every teardown source. A recorded write failure aborts. Otherwise the handle
      * is released once flushed: the pump tore down, which it does only after it took `Closed` from the drained outbound channel, after a
      * write or writable wait failed, or when the close-flush grace abandoned it, and the driver owes the kernel nothing. A pump that tore
      * down while the driver still owes bytes leaves the close to the ledger's drain, so no close waits for a timer to learn that nothing
      * is owed.
      */
    private def advance()(using AllowUnsafe, Frame): Unit =
        if teardown.get() == Connection.Teardown.ReleaseRequested then
            val ledger = driver.life(handle).ledger
            ledger.failure match
                case Present(cause) => discard(abort(cause))
                case Absent         =>
                    if writePump.current == WriteState.TornDown then
                        // The waiter fires inside the send that drains the ledger, on the owner and possibly under the owner's gate, so
                        // its body re-enters through runOwned rather than acting inline.
                        if ledger.owed == 0 then release(ReleaseMode.Graceful)
                        else ledger.onDrained(() => driver.runOwned(handle)(() => advance()))
            end match
        end if
    end advance

    /** Bounds a close's outbound flush ([[kyo.net.NetConfig.closeFlushGrace]]). A window in which the kernel took no byte of what the handle
      * owes is a stall, and the close is aborted; any byte taken starts the next window, so a slow peer that keeps reading is never cut.
      * Progress is the ledger's monotonic `sent`, so a tail that drains and refills inside a window is progress, and a pump that keeps waking
      * with nothing reaching the kernel is not.
      */
    private def armGrace(windowStart: Long)(using AllowUnsafe, Frame): Unit =
        if closeFlushGrace.isFinite then
            closeTimer.arm(closeFlushGrace)(() => driver.runOwned(handle)(() => graceExpired(windowStart)))

    private def graceExpired(windowStart: Long)(using AllowUnsafe, Frame): Unit =
        advance()
        if teardown.get() == Connection.Teardown.ReleaseRequested then
            val sent  = driver.life(handle).ledger.sentTotal
            val cause = kyo.net.NetConnectionClosedException(
                kyo.net.NetConnectionClosedException.Operation.Close,
                s"close flush grace (${closeFlushGrace.show}) elapsed with no byte reaching the kernel"
            )
            if sent != windowStart || !abort(cause) then armGrace(sent)
            else Log.live.unsafe.debug(s"close flush grace (${closeFlushGrace.show}) elapsed on ${driver.handleLabel(handle)}; aborting")
        end if
    end graceExpired

    /** Releases abortively with `cause`, dropping what the handle still owes. The pump is abandoned on its parked instance, a CAS that a
      * writable resuming it wins, so no write lands on a released handle. False when the pump is mid-write and nothing was released.
      */
    private def abort(cause: kyo.net.NetException)(using AllowUnsafe, Frame): Boolean =
        val parked = writePump.current
        val ended  = parked match
            case WriteState.TornDown                                                => true
            case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => writePump.abandon(parked)
            case _                                                                  => false
        if ended then release(ReleaseMode.Abortive(cause))
        ended
    end abort

    /** Releases the handle once, completing [[released]] after the driver's OS close. The unsent count is what the driver still owed, what
      * the pump held when it ended, and what the outbound channel still held.
      */
    private def release(mode: ReleaseMode)(using AllowUnsafe, Frame): Unit =
        if teardown.compareAndSet(Connection.Teardown.ReleaseRequested, Connection.Teardown.Released) then
            // Reflect the lifecycle terminal too, so isOpen reads Closed; the Released CAS above is the exactly-once gate, not this.
            discard(state.compareAndSet(Connection.State.Closing, Connection.State.Closed))
            closeTimer.disarm()
            val residue  = outbound.close()
            val osClosed = Promise.Unsafe.init[Unit, Any]()
            osClosed.onComplete { _ =>
                // Read after the OS close: an io_uring send the kernel performed before an abortive cancel reaps only then, and counting
                // its bytes as owed would report more dropped than the peer missed.
                val owed = driver.life(handle).ledger.owed
                residue.onComplete { held =>
                    import AllowUnsafe.embrace.danger
                    val left    = held.map(_.eval).toMaybe.flatten.fold(0L)(_.foldLeft(0L)(_ + _.size))
                    val unsent  = owed + writePump.droppedBytes + left
                    val outcome = mode match
                        case ReleaseMode.Abortive(cause)         => kyo.net.Connection.CloseOutcome.Dropped(unsent, cause)
                        case ReleaseMode.Graceful if unsent == 0 => kyo.net.Connection.CloseOutcome.Flushed
                        case ReleaseMode.Graceful                =>
                            kyo.net.Connection.CloseOutcome.Dropped(
                                unsent,
                                kyo.net.NetConnectionClosedException(
                                    kyo.net.NetConnectionClosedException.Operation.Close,
                                    "closed with accepted bytes never written"
                                )
                            )
                    releasedPromise.completeDiscard(Result.succeed(outcome))
                }
            }
            driver.release(handle, mode, osClosed)
        end if
    end release

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
      *   - [[State.Created]]: built, pumps not yet started.
      *   - [[State.Established]]: pumps running, normal I/O.
      *   - [[State.Upgrading]]: a STARTTLS detach won; the fd is kept open and NOT torn down (the connection is
      *     closed to its own pumps yet its socket lives on for the TLS upgrade). The fd is owned by that
      *     upgrade, so this state is terminal for the connection's OWN teardown path: `closeFn` never
      *     takes an Upgrading fd, and a `close()` is routed to the upgrade's owner to abandon it instead
      *     (see `Connection.upgradeAbandon`). It is NOT a state the connection ever leaves: a successful
      *     upgrade builds a fresh connection over the same fd rather than moving this one on.
      *   - [[State.Closing]]: a close was initiated; the outbound side is draining before teardown.
      *   - [[State.Closed]]: terminal; the handle has been released exactly once.
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

    /** The named teardown state of a connection, advanced by single-CAS transitions.
      *
      * The handle is released exactly once, on its owner, by whichever teardown source finds the close requested, the WritePump torn down
      * and nothing owed, or by the abort that a write failure or a stalled close-flush window decides. A driver whose kernel operations
      * outlive a graceful release (an io_uring send, a poller registration) defers its own fd close inside `release`; an abortive one
      * cancels them.
      *
      * The release waits on the WRITE side only, NEVER on the inbound (read-side) channel draining: the in-memory inbound channel is
      * independent of the fd (a live consumer drains it separately), and a pooled connection with no reader would otherwise never release,
      * leaking the peer-FIN'd fd in CLOSE_WAIT.
      *
      *   - [[Teardown.Live]]: no close requested.
      *   - [[Teardown.ReleaseRequested]]: close requested; the outbound tail is being flushed.
      *   - [[Teardown.Released]]: terminal; the fd is closed exactly once, resources freed exactly once.
      */
    private[kyo] enum Teardown derives CanEqual:
        case Live
        case ReleaseRequested
        case Released
    end Teardown

    /** The one timer a close runs on. Arming is a no-op while a window is live, so a second close keeps the running window; the expiry
      * handler, after its own window fired, is what arms the next one. Arm and disarm run on the handle's owner.
      */
    final private class CloseTimer(clock: Clock, slot: AtomicRef.Unsafe[Maybe[Fiber.Unsafe[Unit, Any]]]):

        def arm(window: Duration)(onExpiry: () => Unit)(using AllowUnsafe, Frame): Unit =
            if slot.get().isEmpty then
                val timer = clock.unsafe.sleep(window)
                val armed = Present(timer)
                slot.set(armed)
                timer.onComplete { fired =>
                    import AllowUnsafe.embrace.danger
                    if fired.isSuccess && slot.compareAndSet(armed, Absent) then onExpiry()
                }
            end if
        end arm

        def disarm()(using allow: AllowUnsafe, frame: Frame): Unit =
            slot.getAndSet(Absent).foreach(t => discard(t.interruptDiscard(Result.Panic(Interrupted(frame, "close released")))))
    end CloseTimer

    /** Create a connection. Does not start pumps; call `start()` after creation. */
    def init[Handle](
        handle: Handle,
        driver: IoDriver[Handle],
        channelCapacity: Int,
        grace: Duration = Duration.Infinity,
        closeFlushGrace: Duration = Duration.Infinity,
        clock: Clock = Clock.live
    )(using AllowUnsafe, Frame): Connection[Handle] =
        val inbound  = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val outbound = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val state    = AtomicRef.Unsafe.init[Connection.State](Connection.State.Created)
        // The single CAS into Released is the exactly-once gate; it is reached only after the WritePump has finished with the outbound
        // channel, so the fd is closed only once every queued span has actually been written, not merely taken off the channel.
        val teardown = AtomicRef.Unsafe.init[Connection.Teardown](Connection.Teardown.Live)

        // The connection's close signal (kyo.net.Connection.onClosing). Completed once when closeFn wins the close, at close-START (before the
        // channel drains and the handle teardown below), so an observer sees close-start not fd-release. completeDiscard is idempotent, so the
        // re-entrant Closing branch and any repeat close are no-ops. Created before closeFn so the closure captures it. It never fires from
        // detachForUpgrade (state=Upgrading bars this branch), which is correct: the upgraded connection is a fresh init with its own signal.
        // Uninterruptible because awaiting a fiber links the awaiter's interrupt to it: a waiter that gives up (a timeout, a lost race) would
        // otherwise complete the signal for every observer while the connection is still open.
        val closingPromise = Promise.Unsafe.initUninterruptible[Unit, Any]()

        // Forward reference to the Connection instance closeFn releases through. Set once, synchronously, right after the
        // instance is constructed below, before closeFn can ever run (closeFn only fires once a pump starts or close() is called, both of
        // which happen strictly after init returns the constructed connection to its caller).
        var self: Connection[Handle] = null.asInstanceOf[Connection[Handle]]

        val closeFn: () => Unit = () =>
            // Win the close by CASing a live state -> Closing. Created and Established both go to
            // Closing (a never-started connection closes too); Upgrading does NOT, since its fd is
            // owned by the TLS upgrade. A second close loses the CAS and falls to the re-entrant
            // Closing branch below.
            val wonStarted   = state.compareAndSet(Connection.State.Established, Connection.State.Closing)
            val wonUnstarted = !wonStarted && state.compareAndSet(Connection.State.Created, Connection.State.Closing)
            if wonStarted || wonUnstarted then
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
                // closing the fd there would race the in-flight write and drop the tail. The WritePump's own teardown is the real "outbound
                // fully flushed" signal.
                //
                // When the channel is already empty and no offer is in flight, this call fails the pump's parked take inline, so the pump
                // tears down and re-enters below before it returns. Neither an empty channel nor an Idle pump is enough on its own: the
                // channel may have handed its last span to the pump's take while the pump has not yet moved to Flushing, and releasing there
                // lets that write land on a released handle.
                discard(outbound.closeAwaitEmpty())
                // The release decision runs on the handle's owner, so it never reads the pump's state while a write it started is still
                // changing what the handle owes. The window is armed before the first decision, so a decision that releases disarms it.
                driver.runOwned(handle) { () =>
                    if wonUnstarted then self.release(ReleaseMode.Graceful)
                    else
                        self.armGrace(driver.life(handle).ledger.sentTotal)
                        self.advance()
                    end if
                }
            else if state.get() == Connection.State.Closing then
                // A later teardown source: the WritePump finishing (it took Closed from the drained channel, or a write or writable wait
                // failed), a second close(), or the ReadPump seeing a peer byte, FIN or error after the close. None of them restarts the
                // window; each only asks the close for its next step.
                driver.runOwned(handle)(() => self.advance())
            end if

        val readPump  = new ReadPump(handle, driver, inbound, closeFn, grace, clock)
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
            Promise.Unsafe.initUninterruptible[kyo.net.Connection.CloseOutcome, Any](),
            AtomicBoolean.Unsafe.init(false),
            teardown,
            closeFlushGrace,
            new CloseTimer(clock, AtomicRef.Unsafe.init(Absent))
        )
        self
    end init

    /** Create a driverless in-memory connection over two pre-existing channels.
      *
      * Unlike [[init]], which always wires a driver and two pumps, this connection has neither: reads come straight from `inbound` and writes
      * go straight to `outbound`, so it backs piping and testing with no syscalls. It is a direct [[kyo.net.Connection]] rather than the
      * generic `Connection[Handle]` (which requires a driver), so there is no null driver. It does not support in-place STARTTLS: `upgradeToTls`
      * aborts `Closed` via the public path, and `detachForUpgrade` returns `Absent`. `close()` stops writes on both channels and leaves every
      * span already written readable to its taker: the peer still drains what this side wrote, and this side's reader what the peer wrote. A
      * closed side nobody reads keeps its spans until the pair is unreachable.
      */
    def inMemory(
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]]
    )(using AllowUnsafe, Frame): kyo.net.Connection =
        val in         = inbound
        val out        = outbound
        val closedFlag = AtomicBoolean.Unsafe.init(false)
        // Uninterruptible for the same reason as the socket connection's signal: a waiter that gives up must not complete it.
        val closingPromise  = Promise.Unsafe.initUninterruptible[Unit, Any]()
        val releasedPromise = Promise.Unsafe.initUninterruptible[kyo.net.Connection.CloseOutcome, Any]()
        new kyo.net.Connection:
            def released(using AllowUnsafe): Fiber.Unsafe[kyo.net.Connection.CloseOutcome, Any] = releasedPromise
            def inbound: Channel.Unsafe[Span[Byte]]                                             = in
            def outbound: Channel.Unsafe[Span[Byte]]                                            = out
            def isOpen(using AllowUnsafe): Boolean                                              = !closedFlag.get()
            def close()(using AllowUnsafe, Frame): Unit                                         =
                // There is no driver to cancel or handle to close. Idempotent via the CAS. Each channel is shared with the peer, so like a
                // socket close both stop new writes while every span already written stays readable: the peer drains `out`, this side's
                // reader drains `in`.
                if closedFlag.compareAndSet(false, true) then
                    closingPromise.completeDiscard(Result.succeed(()))
                    discard(in.closeAwaitEmpty())
                    discard(out.closeAwaitEmpty())
                    // Every span this side accepted is already in the peer's channel, the in-memory counterpart of the kernel, so the release
                    // never waits for a reader the pair may not have.
                    releasedPromise.completeDiscard(Result.succeed(kyo.net.Connection.CloseOutcome.Flushed))
            private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] = closingPromise
            // not upgradable: no driver or socket, so the answer is known immediately
            def detachForUpgrade()(using AllowUnsafe, Frame)            = Fiber.Unsafe.fromResult(Result.succeed(Absent))
            private[net] def start()(using AllowUnsafe, Frame): Boolean = true // no pumps; immediately usable
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
