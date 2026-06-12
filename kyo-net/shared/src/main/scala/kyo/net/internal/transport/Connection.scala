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
    private val closedFlag: AtomicBoolean.Unsafe,
    private val closeFn: () => Unit
) extends kyo.net.Connection
    with kyo.net.Connection.UpgradableConnection:

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
        readPump.start()
        writePump.start()
    end start

    /** Check if connection is still open. */
    def isOpen(using AllowUnsafe): Boolean =
        !closedFlag.get()

    /** Close the connection. Closes channels and handle. Idempotent. */
    def close()(using AllowUnsafe, Frame): Unit = closeFn()

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
        if closedFlag.compareAndSet(false, true) then
            // TODO to double check: do we need to close the pumps as well?
            // Close inbound and capture any bytes the ReadPump already staged but nobody consumed.
            // These are raw network bytes (TLS ciphertext) that the handshake engine needs.
            val buffered = inbound.close()
            discard(outbound.close())
            // Cancel pending I/O promises (causes pumps to see failure and exit teardown).
            // Intentionally does NOT call driver.closeHandle so the channel stays open.
            driver.cancel(handle)
            buffered.map(Chunk.from(_))
        else Absent
        end if
    end detachForUpgrade

end Connection

private[kyo] object Connection:

    /** Create a connection. Does not start pumps; call `start()` after creation. */
    def init[Handle](
        handle: Handle,
        driver: IoDriver[Handle],
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Connection[Handle] =
        val inbound    = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val outbound   = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val closedFlag = AtomicBoolean.Unsafe.init(false)
        // Set true only when closeFn itself initiated the close (its closedFlag CAS won). detachForUpgrade shares closedFlag but must NOT close
        // the handle (it hands the fd to the TLS upgrade), so it leaves this false; a later serverPlain.close() then re-enters closeFn's else
        // branch, sees closeInitiated == false, and stays a no-op, preserving the detach-keeps-the-fd-open invariant.
        val closeInitiated = AtomicBoolean.Unsafe.init(false)
        // Guards the handle teardown (cancel + closeHandle) so it runs exactly once. It is reached only after the WritePump has finished with
        // the outbound channel (it took the closing-channel's Closed and ran its own teardown, which re-enters closeFn), so the socket fd is
        // closed only once every queued span has actually been written, not merely taken off the channel.
        val tornDown = AtomicBoolean.Unsafe.init(false)

        def teardownHandle(): Unit =
            if tornDown.compareAndSet(false, true) then
                driver.cancel(handle)
                driver.closeHandle(handle)

        val closeFn: () => Unit = () =>
            if closedFlag.compareAndSet(false, true) then
                closeInitiated.set(true)
                discard(inbound.close())
                // Mirror the ReadPump's inbound drain on the outbound side: closeAwaitEmpty marks the channel closing-for-writes (new offers
                // fail Closed) while the WritePump keeps taking the already-queued spans until the channel is empty. A peer half-close
                // (shutdown(SHUT_WR)) leaves the peer still READING, so the WritePump's parked partial write resumes when the socket becomes
                // writable, flushes the queued tail in order, then takes again from the now-empty closing channel and gets Closed, which runs
                // the WritePump's teardown and re-enters this closeFn. So the queued outbound bytes reach the peer instead of being discarded
                // by a bare outbound.close(). The handle teardown is intentionally NOT chained on the drain fiber: that fiber completes when
                // the channel queue is empty (the tail was TAKEN by the WritePump) which is before the tail is WRITTEN to the socket, so
                // closing the fd there would race the in-flight write and drop the tail. The WritePump re-entry below is the real
                // "outbound fully flushed" signal.
                discard(outbound.closeAwaitEmpty())
            else if closeInitiated.get() then
                // Re-entrant close after closeFn itself initiated the close. The WritePump reached this by taking Closed from the closing
                // outbound channel after it had nothing left to write (the half-close flush completed), or by its write/writable wait failing
                // (the peer is gone, or driver.close from the owning Scope failed every pending writable as the safety net). Either way the
                // outbound side is finished, so tear down the handle. This re-entry, not the drain fiber, is what bounds close(): a half-close
                // that can drain reaches it after the flush, and a full close whose outbound can never drain reaches it once the peer's RST or
                // the Scope's driver.close fails the parked writable, so close() never waits forever on an outbound that will not empty.
                teardownHandle()
            end if

        val readPump  = new ReadPump(handle, driver, inbound, closeFn)
        val writePump = new WritePump(handle, driver, outbound, closeFn)

        new Connection(handle, driver, inbound, outbound, readPump, writePump, closedFlag, closeFn)
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
        val in         = inbound
        val out        = outbound
        val closedFlag = AtomicBoolean.Unsafe.init(false)
        new kyo.net.Connection:
            def inbound: Channel.Unsafe[Span[Byte]]  = in
            def outbound: Channel.Unsafe[Span[Byte]] = out
            def isOpen(using AllowUnsafe): Boolean   = !closedFlag.get()
            def close()(using AllowUnsafe, Frame): Unit =
                // Close both channels; there is no driver to cancel or handle to close. Idempotent via the CAS.
                if closedFlag.compareAndSet(false, true) then
                    discard(in.close())
                    discard(out.close())
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
