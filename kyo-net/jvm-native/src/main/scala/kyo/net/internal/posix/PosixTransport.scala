package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.Connection as NetConnection
import kyo.net.Listener as NetListener
import kyo.net.NetAddress
import kyo.net.NetAlreadyDetachedException
import kyo.net.NetBindException
import kyo.net.NetConnectException
import kyo.net.NetConnectionClosedException
import kyo.net.NetConnectTimeoutException
import kyo.net.NetDnsResolutionException
import kyo.net.NetErrno
import kyo.net.NetException
import kyo.net.NetNotUpgradableException
import kyo.net.NetStdioAlreadyOpenException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsException
import kyo.net.NetTlsHandshakeException
import kyo.net.NetUnixConnectException
import kyo.net.internal.tls.HandshakeFailure
import kyo.net.internal.tls.HandshakeState
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsProviderPlatform
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool
import kyo.net.internal.transport.ListenerImpl
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.TransportImpl
import kyo.net.internal.transport.WriteResult
import kyo.scheduler.IOPromise

/** The unified `PosixHandle`-backed transport: the full TCP / UDS / stdio surface over [[SocketBindings]] plus an [[IoDriver]] (the readiness
  * [[PollerIoDriver]] on epoll/kqueue or the completion [[IoUringDriver]] on io_uring). It is the production transport on every posix host:
  * the sole Native transport, and the posix tier above the pure-JDK `NioTransport` floor on the JVM.
  *
  * `connect` / `connectUnix` open a client socket, set it non-blocking, and drive a non-blocking connect: an immediate success (loopback)
  * completes inline, otherwise the driver awaits connect completion and the readiness arm reads `SO_ERROR` to confirm. `listen` / `listenUnix`
  * bind + listen, resolve the actual (possibly ephemeral) port, and run a dedicated accept loop via the poller-armed `scheduleNextAccept` +
  * `acceptAll` pattern (the listen fd is set non-blocking; `acceptNow` drains all pending connections inline before re-arming the driver).
  * Each accepted client fd is made non-blocking with `TCP_NODELAY`, wrapped in a [[Connection]], and handed to the handler in its own fiber.
  *
  * TLS reuses the existing handshake machinery: `connect(tls)` / `listen(tls)` build the engine via `TlsProviderPlatform.engine` and drive it
  * over the same fd with [[driveHandshake]] (the very loop the STARTTLS path uses), then attach the engine to the handle. No legacy OpenSSL
  * handshake code is involved.
  *
  * `stdio` opens a [[Connection]] over `PosixHandle.stdio()` (readFd = 0, writeFd = 1), guarded by a process-wide CAS so exactly one stdio
  * connection exists at a time (fd 0/1 are process-global and must not be double-owned). Before wiring the connection
  * it runs the pollability probe: a regular-file stdin under an epoll backend is the one cell the poller cannot register, so it falls back
  * to the [[BlockingReaderDriver]]; every other case (io_uring, kqueue, pipes, ttys) uses the real driver.
  *
  * `upgradeToTls` is the STARTTLS path: it detaches the plaintext connection, feeds any staged ciphertext into the new TLS engine before
  * the first post-upgrade socket read (so no byte is dropped), drives the handshake over the same fd, and rebuilds the connection.
  */
final private[net] class PosixTransport private[posix] (
    config: kyo.net.TransportConfig,
    val pool: IoDriverPool[PosixHandle],
    representative: IoDriver[PosixHandle],
    sockets: SocketBindings,
    backendIsEpoll: Boolean,
    // Claim flag for the process-global stdio handle, so stdio is claimed at most once.
    stdioClaimed: AtomicBoolean.Unsafe,
    // Count of accept loops that have started but not yet exited. Each loop increments it on start and decrements it on exit (every path), so a
    // caller that closed the listeners can observe via [[activeAcceptLoops]] when every blocking `accept` has actually returned and its loop has
    // wound down. That matters for orderly shutdown: a blocking `accept` cannot be cancelled, so after closing a listen fd the only way to know
    // its parked `accept` has woken (and the fd is safe to recycle without a stale loop stealing a connection) is to watch this drop to 0.
    acceptLoopsActive: AtomicLong.Unsafe
) extends TransportImpl[PosixHandle]:
    // `representative` is one pool driver used ONLY for the transport-level, NON-per-handle paths that have no bound handle:
    //   - stdio (a single per-process handle; selectDriver bypasses the pool, so stdio rides the representative),
    //   - the backend-uniform label/capability queries (ioDriver.label is identical across all pool drivers).
    // Every PER-HANDLE op (read/write/await/closeHandle/submitEngineOp) routes through handle.driver, the driver the handle was bound to at
    // openWith/pool.next() time, so a connection's engine FIFO and poll loop stay on its one owning driver for the connection's whole lifetime.
    private val ioDriver: IoDriver[PosixHandle] = representative

    import PosixTransport.AcceptDrain

    /** Backoff before re-arming accept interest after `accept` returned `EMFILE`/`ENFILE` (out of file descriptors). The pending connection
      * stays in the backlog and the listen fd stays read-ready, so an immediate re-arm re-fires the same error in a tight CPU spin; the backoff
      * breaks the spin while keeping the accept loop alive, so accepting resumes once a fd frees. 50ms drops the carrier from 100% CPU to one
      * cheap retry per interval yet recovers promptly when a fd frees (libuv backs off ~1s on the same condition; a shorter interval here
      * resumes serving faster without measurably loading an otherwise idle poll loop).
      */
    private val acceptResourceBackoff: Duration = 50.millis

    /** Cap on consecutive `EINTR`/`ECONNABORTED` retries within a single drain cycle. These are transient (accept(2) says retry like `EAGAIN`),
      * but bounding the in-cycle retries stops a persistently-firing transient error from itself spinning the drain; past the cap the cycle
      * re-arms normally and the next readiness event drives it forward.
      */
    private val maxTransientAcceptRetries = 8

    /** Every live listener this transport opened. `close()` closes them all (so their accept loops terminate) before the pool shuts down, and a
      * listener removes itself once closed; without this, a transport shutdown would strand accept loops parked in a blocking `accept`.
      */
    // Concurrent-collection audit: a raw ConcurrentHashMap-backed key-set tracking the open listeners so close() can wind them all down.
    // kyo has no concurrent-set/map type, and its effect-based collections cannot back this set, which is added to on each listen carrier and
    // iterated on the transport-close carrier without suspension. Retained as a documented no-equivalent exception.
    private val listeners = java.util.concurrent.ConcurrentHashMap.newKeySet[PosixListener]()

    /** Every connection this transport opened (client connect, accepted server, or STARTTLS upgrade), keyed by handle id. A connection removes
      * itself when its handle is torn down (via the `onClose` wired at creation); `close()` closes whatever is still registered before the pool
      * shuts down, so a connection whose ordinary close never completed (its peer FIN never arrived, its handler never closed it) is reclaimed
      * while the driver's reap loop is still alive instead of leaking its fd. The shared process transport never calls `close()`, so its registry
      * only ever shrinks as connections close; an owned transport (per test, per kyo-http config) clears it at `close()`.
      */
    // Concurrent-collection audit: a raw ConcurrentHashMap tracking open connections, added on each connect/accept carrier and iterated on the
    // transport-close carrier without suspension. Same no-Kyo-equivalent rationale as `listeners` above. Retained as a documented exception.
    private val connections = new java.util.concurrent.ConcurrentHashMap[Long, InternalConnection[PosixHandle]]()

    /** Build a connection over `handle`/`driver` and register it in [[connections]], wiring its `onClose` so it self-removes when its handle is
      * torn down. Used for every connection whose fd this transport must reclaim at `close()` (connect, accept, STARTTLS upgrade); the untracked
      * [[openWith]] stays for stdio, whose fds are process-owned and must not be closed by the transport.
      *
      * Called exactly once per handle's lifetime (a STARTTLS upgrade reuses the same handle/id, it does not call this again).
      */
    private def openTracked(handle: PosixHandle, driver: IoDriver[PosixHandle])(using AllowUnsafe, Frame): InternalConnection[PosixHandle] =
        handle.driver = driver
        val conn = InternalConnection.init(handle, driver, config.channelCapacity, () => discard(connections.remove(handle.id.packed)))
        discard(connections.put(handle.id.packed, conn))
        conn
    end openTracked

    /** In-flight handshake fd/engine-teardown obligations (accept-side server TLS, connect-side client TLS, and STARTTLS upgrade), keyed by an
      * opaque per-handshake token. A handshake in flight has no [[Connection]] yet, so it is invisible to the [[connections]] registry `close()`
      * sweeps above: the driver-level fd-close fix (`PollerIoDriver`/`IoUringDriver`'s terminal sweep) only guarantees a submitted `closeHandle`
      * obligation is discharged, it does not make a still-handshaking connection's OWN teardown thunk run at all when the transport shuts down
      * mid-handshake. Entered by [[registerHandshake]] before `driveHandshake` starts; each value is a thunk that races the handshake's own
      * exactly-once outcome gate and discharges only if it wins, so `close()`'s synchronous sweep can never double-free a handshake that
      * completes at the same moment it runs. Removed by [[unregisterHandshake]] once the outcome is known, so the never-closed shared process
      * transport does not accumulate one entry per handshake forever.
      */
    // Concurrent-collection audit: same no-Kyo-equivalent rationale as `listeners`/`connections` above.
    private val pendingHandshakes   = new java.util.concurrent.ConcurrentHashMap[Long, () => Unit]()
    private val pendingHandshakeSeq = new java.util.concurrent.atomic.AtomicLong(0)

    /** Register a handshake's teardown obligation under an already-existing exactly-once `disarm` gate (the accept-side handshake deadline's
      * own guard): the stored thunk attempts `disarm()` itself and runs `teardown` only if it wins, so a `close()` sweep racing the real
      * handshake outcome (or the deadline) never discharges twice. Returns the token [[unregisterHandshake]] needs once the winning side
      * is known.
      */
    private def registerHandshake(disarm: () => Boolean, teardown: () => Unit)(using AllowUnsafe): Long =
        val token = pendingHandshakeSeq.incrementAndGet()
        discard(pendingHandshakes.put(token, () => if disarm() then teardown()))
        token
    end registerHandshake

    /** Register a handshake's teardown obligation with no pre-existing gate (connect-side / STARTTLS have none: `driveHandshake` guarantees
      * exactly one of onFinished/onFailed/onPanic ever fires), building a fresh exactly-once flag shared between the handshake outcome and a
      * racing `close()` sweep. Returns the token for [[unregisterHandshake]] and the `disarm` gate the call site's own outcome callbacks must
      * win before proceeding, exactly like the accept-side deadline's guard.
      *
      * `private[posix]` (not `private`) so a discriminating test (PosixTransportShutdownReclaimTest) can drive this and
      * [[unregisterHandshake]] directly, registering a handshake obligation AFTER `close()`'s own one-shot [[sweepPendingHandshakes]] has
      * already run, to prove the driver-level closed-recheck (not this registry's sweep) is what reclaims a handshake that races past it.
      */
    private[posix] def registerHandshake(teardown: () => Unit)(using AllowUnsafe): (Long, () => Boolean) =
        val settled           = AtomicBoolean.Unsafe.init(false)
        def disarm(): Boolean = settled.compareAndSet(false, true)
        (registerHandshake(disarm, teardown), disarm)
    end registerHandshake

    private[posix] def unregisterHandshake(token: Long)(using AllowUnsafe): Unit =
        discard(pendingHandshakes.remove(token))

    /** Discharge ONE registered handshake teardown obligation by token, removing its entry as it runs. The single-entry form of
      * [[sweepPendingHandshakes]], running the identical exactly-once thunk, so a discharge racing the handshake's real outcome is a safe no-op
      * for whichever loses.
      *
      * Used for a handshake whose own outcome promise settled without ANY of its three outcomes having run: the caller interrupted the upgrade it
      * was awaiting, or the plaintext connection it detached was closed underneath it. Both leave the handshake parked forever on a read nothing
      * will complete, holding a detached fd; and unlike a stalled handshake on an owned transport, no later `close()` will sweep it, because the
      * process-shared transport is never closed. A no-op once the entry is gone (the outcome already unregistered it).
      */
    private def dischargePendingHandshake(token: Long)(using AllowUnsafe): Unit =
        val discharge = pendingHandshakes.remove(token)
        if discharge ne null then discharge()
    end dischargePendingHandshake

    /** Discharge every still-registered handshake teardown, removing each entry as it runs. Called by `close()` before `pool.close()`, so a
      * handshake stalled forever (its peer stopped mid-flight, no deadline armed) is reclaimed instead of leaking its fd/engine past shutdown;
      * a handshake that is concurrently finishing loses the race for its own thunk's `disarm()` call and this is then a safe no-op for it.
      */
    private def sweepPendingHandshakes()(using AllowUnsafe): Unit =
        val it = pendingHandshakes.values().iterator()
        while it.hasNext do
            val discharge = it.next()
            it.remove()
            discharge()
        end while
    end sweepPendingHandshakes

    /** The number of accept loops still running (in `scheduleNextAccept` or `acceptAll`). Drops to 0 once every closed listener's loop has exited. */
    private[posix] def activeAcceptLoops(using AllowUnsafe): Long = acceptLoopsActive.get()

    /** Open the stdio connection: read side is process stdin (fd 0), write side is process stdout (fd 1). A second concurrent call aborts
      * `Closed` (the CAS fails) so fd 0/1 are never double-owned. The connection closes its driver registration on scope exit but never closes
      * fds 0/1 (the process owns them).
      */
    override def stdio()(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]] =
        if !stdioClaimed.compareAndSet(false, true) then
            // Exactly one stdio per process (no double-ownership of fd 0/1).
            Fiber.Unsafe.fromResult(Result.fail(NetStdioAlreadyOpenException()))
        else
            Fiber.Unsafe.init {
                val handle           = PosixHandle.stdio(PosixHandle.DefaultReadBufferSize)
                val conn: Connection = openWith(handle, selectDriver(handle.readFd))
                if !conn.start() then
                    // Unreachable: openWith (:211-217) registers the connection nowhere a concurrent close could reach before start() runs
                    // immediately above, so the Created -> Established CAS cannot lose here. Defensive assertion, not a reachable path.
                    throw new IllegalStateException("stdio connection start lost its CAS immediately after construction")
                end if
                conn
            }
                // Chaining the cast detaches this call from the method's expected type, so Fiber.Unsafe.init[E, A] resolves its phantom effect
                // row standalone: with E left to inference it resolves to Fiber.Unsafe[Connection, Any]. That view already conforms to the
                // declared Fiber.Unsafe[Connection, Abort[NetException]] by contravariance of S (Abort[NetException] <: Any), so nothing here
                // crosses the opaque-alias boundary: the value is already a Fiber.Unsafe and the cast is not required to obtain one. It is
                // kept for uniformity with the module's other Fiber.Unsafe boundary casts, which recover the opaque alias from a plain
                // IOPromise and do need it.
                .asInstanceOf[Fiber.Unsafe[Connection, Abort[NetException]]]

    /** The driver that should back a stdio handle whose read end is `fd`: the real `ioDriver` when the fd is pollable, else the
      * [[BlockingReaderDriver]] fallback (the one fallback cell: epoll + regular file).
      */
    private[posix] def selectDriver(fd: Int)(using AllowUnsafe): IoDriver[PosixHandle] =
        if pollable(fd) then ioDriver
        else BlockingReaderDriver.init(ioDriver)

    /** Build an internal connection over `handle`/`driver`, returned as the public `Connection`. Binds `handle.driver` so every per-handle op
      * (read/write/await/closeHandle/submitEngineOp) routes through the driver this handle was assigned to. The caller starts it.
      */
    private[posix] def openWith(handle: PosixHandle, driver: IoDriver[PosixHandle])(using
        AllowUnsafe,
        Frame
    ): InternalConnection[PosixHandle] =
        handle.driver = driver
        InternalConnection.init(handle, driver, config.channelCapacity)
    end openWith

    // ---------------------------------------------------------------------------------------------------------------------------------------
    // TCP / UDS client connect
    // ---------------------------------------------------------------------------------------------------------------------------------------

    /** Connect a non-blocking TCP socket to `host:port` and complete with an open plaintext [[Connection]]. */
    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        connectResolving(host, port, nodelay = true, tls = Absent)

    /** Connect a non-blocking TCP socket to `host:port`, then drive a client TLS handshake before completing. */
    def connect(host: String, port: Int, tls: NetTlsConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        connectResolving(host, port, nodelay = true, tls = Present((tls, host)))

    /** Resolve `host` (numeric / loopback inline, otherwise through the offloaded-blocking [[HostResolver]]) and then drive the connect.
      *
      * The connect must return its `Fiber.Unsafe` synchronously, but resolution is an `< Async` step (it can suspend the fiber on the system
      * resolver). So this builds the result promise immediately, spawns a fiber that resolves + encodes the address, and on success hands the
      * encoded `sockaddr` to the existing synchronous [[connectImpl]] (which completes the same promise). A resolution failure (or a panic)
      * fails the promise `NetDnsResolutionException` without ever opening a socket. The numeric and loopback fast paths resolve inline, so the spawned fiber
      * completes without parking and the connect is no slower than before for them.
      */
    private def connectResolving(
        host: String,
        port: Int,
        nodelay: Boolean,
        tls: Maybe[(NetTlsConfig, String)]
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val target  = s"$host:$port"
        val promise = new IOPromise[NetException, NetConnection]
        resolveAndEncode(host, port).onComplete {
            case Result.Success(pending) =>
                // pending: (Int, Buffer[Byte], Int) < Any; .eval extracts the concrete tuple, mirroring resolveAndEncode's own identical step.
                connectImpl(Present(pending.eval), nodelay, target, host, port, tls, promise)
            case Result.Failure(e) =>
                promise.completeDiscard(Result.fail(e))
            case Result.Panic(e) =>
                promise.completeDiscard(Result.panic(e))
        }
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetConnection], even though both erase to the same runtime object; the
        // alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.connect return
        // needs this erased-boundary cast. Safe: the promise completes only with the NetConnection/NetException values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connectResolving

    /** Connect a non-blocking Unix-domain socket to `path` and complete with an open plaintext [[Connection]] (no `TCP_NODELAY`). A Unix path
      * needs no name resolution, so the encoded `sockaddr` is built inline and handed straight to [[connectImpl]] with no resolver fiber.
      */
    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetConnection]
        connectImpl(
            SockAddr.encodeUnix(PosixConstants.AF_UNIX, path).map((b, l) => (PosixConstants.AF_UNIX, b, l)),
            nodelay = false,
            target = path,
            host = path,
            port = -1, // sentinel: a Unix socket has no port; connectFail routes port < 0 to NetUnixConnectException
            tls = Absent,
            promise = promise
        )
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetConnection], even though both erase to the same runtime object; the
        // alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.connectUnix
        // return needs this erased-boundary cast. Safe: the promise completes only with the NetConnection/NetException values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connectUnix

    /** Encode an inet `sockaddr` for `host` WITHOUT any DNS resolution: IPv6 when `host` is a v6 literal, otherwise IPv4. Returns the family,
      * buffer, and length, or `Absent` when `host` is neither a valid IPv4 nor IPv6 literal (i.e. it needs name resolution).
      *
      * The well-known loopback host NAMES (`localhost`, `ip6-localhost`, `ip6-loopback`) are normalized to their loopback literal first via
      * [[SockAddr.resolveLoopbackName]]: they have a fixed RFC answer no resolver varies, so encoding them without DNS matches what the old
      * NIO `InetSocketAddress` produced and is what the kyo-http client (which passes a hostname, commonly `localhost`, straight through)
      * needs. Any OTHER non-numeric hostname returns `Absent` here so [[resolveAndEncode]] sends it through the [[HostResolver]] (offloaded
      * blocking system resolution); keeping the numeric and loopback cases on this synchronous fast path means they never touch the resolver.
      */
    private def encodeInetFast(host: String, port: Int)(using AllowUnsafe): Maybe[(Int, Buffer[Byte], Int)] =
        val resolved = SockAddr.resolveLoopbackName(host)
        if resolved.contains(':') then
            SockAddr.encodeInet6(PosixConstants.AF_INET6, resolved, port).map((b, l) => (PosixConstants.AF_INET6, b, l))
        else
            SockAddr.encodeInet4(PosixConstants.AF_INET, resolved, port).map((b, l) => (PosixConstants.AF_INET, b, l))
        end if
    end encodeInetFast

    /** Encode an inet `sockaddr` for `host`, resolving a non-numeric, non-loopback hostname through the [[HostResolver]] when the synchronous
      * [[encodeInetFast]] cannot (a numeric IPv4/IPv6 literal or a loopback name encodes inline with no resolution). Resolution is an offloaded
      * blocking system call (`InetAddress` on JVM, the `getaddrinfo` C shim on Native), cached and TTL-bounded, so a hostname connect suspends
      * the fiber rather than blocking a carrier, and repeated connects to the same host hit the cache.
      *
      * The family hint passed to the resolver mirrors [[encodeInetFast]]'s dispatch: a host containing ':' requests `AF_INET6`, otherwise
      * `AF_INET`. The resolver returns the first A or AAAA, and its family drives the encoder (`encodeInet6Raw` vs `encodeInet4Raw`) on the
      * resolved bytes, so IPv4/IPv6 selection stays consistent with the synchronous fast path. A resolution failure (unknown host, resolver
      * error) is surfaced as `NetDnsResolutionException`, exactly the failure the caller already produced for an unencodable address.
      */
    private def resolveAndEncode(host: String, port: Int)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[(Int, Buffer[Byte], Int), Abort[NetDnsResolutionException]] =
        encodeInetFast(host, port) match
            case Present(encoded) =>
                // Numeric literal or loopback name: already-complete fiber, no resolver call.
                Fiber.Unsafe.fromResult(Result.succeed(encoded))
            case Absent =>
                val familyHint = if host.contains(':') then PosixConstants.AF_INET6 else PosixConstants.AF_INET
                // Mirrors resolveWith's own out/onComplete/completeDiscard shape: the target Abort[NetDnsResolutionException] leaf can also
                // come from encodeResolved's own (defensive, effectively unreachable) failure, so this cannot stay a total .map over the
                // resolver's success channel alone; a fresh promise lets both the resolver's failure and encodeResolved's failure complete
                // the same typed Abort row.
                val out = Promise.Unsafe.init[(Int, Buffer[Byte], Int), Abort[NetDnsResolutionException]]()
                HostResolver.resolveWith(host, familyHint, SystemResolver.resolveRaw).onComplete {
                    case Result.Success(pending) =>
                        // pending: Resolved < Any (kyo's opaque Pending type, `opaque type <[+A, -S] = A | Kyo[A, S]`,
                        // kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:42) is NOT the same type as a bare Resolved, so it
                        // cannot be destructured directly against the Resolved case-class pattern; .eval extracts the concrete value
                        // (always immediately available here since S = Any carries no real suspension), mirroring resolveWith's own
                        // identical innerPending.eval step above.
                        pending.eval match
                            case HostResolver.Resolved(family, rawAddr) =>
                                encodeResolved(family, rawAddr, port) match
                                    case Present(encoded) => out.completeDiscard(Result.succeed(encoded))
                                    case Absent =>
                                        out.completeDiscard(Result.fail(NetDnsResolutionException(
                                            host,
                                            s"connect: could not encode resolved address for $host"
                                        )))
                    case Result.Failure(e) => out.completeDiscard(Result.fail(e))
                    case Result.Panic(e)   => out.completeDiscard(Result.panic(e))
                }
                out
        end match
    end resolveAndEncode

    /** Encode a sockaddr from a resolver's raw address bytes (4 for `AF_INET`, 16 for `AF_INET6`) and family, through the [[SockAddr]] raw
      * encoders, which lay the bytes out exactly as the numeric fast path does (family + network-order port + the address bytes + padding).
      * Returns `Absent` only if the byte length does not match the family (not reachable for well-formed resolver output).
      */
    private def encodeResolved(family: Int, rawAddr: Array[Byte], port: Int)(using AllowUnsafe): Maybe[(Int, Buffer[Byte], Int)] =
        if family == PosixConstants.AF_INET6 then
            SockAddr.encodeInet6Raw(PosixConstants.AF_INET6, rawAddr, port).map((b, l) => (PosixConstants.AF_INET6, b, l))
        else
            SockAddr.encodeInet4Raw(PosixConstants.AF_INET, rawAddr, port).map((b, l) => (PosixConstants.AF_INET, b, l))
    end encodeResolved

    /** Open a client socket of the encoded address's family, set it non-blocking (+ `TCP_NODELAY` / macOS `SO_NOSIGPIPE` when `nodelay`),
      * and drive the non-blocking connect to completion, optionally followed by a TLS handshake. The encoded `sockaddr` buffer is stashed on
      * the handle as its `connectTarget` so the io_uring driver can submit the connect SQE; on the readiness arm the transport issues the
      * `connect` syscall itself and the driver only waits for write-readiness. The buffer is closed once the connect resolves (success or
      * failure), and on any pre-connection failure the raw fd is closed.
      */
    /** Build the connect-stage [[NetException]] leaf for `host:port`: a TCP connect failure ([[NetConnectException]]), or a Unix-socket connect
      * failure ([[NetUnixConnectException]], signaled by the sentinel `port < 0` the Unix path passes since a Unix socket has no port). `cause`
      * carries the underlying failure (a [[NetErrno]] on the Posix errno paths, a driver `Closed` on the readiness paths).
      */
    private def connectFail(host: String, port: Int, cause: String | Throwable)(using Frame): NetException =
        if port < 0 then NetUnixConnectException(host, cause) else NetConnectException(host, port, cause)

    private def connectImpl(
        encoded: Maybe[(Int, Buffer[Byte], Int)],
        nodelay: Boolean,
        target: String,
        host: String,
        port: Int,
        tls: Maybe[(NetTlsConfig, String)],
        promise: IOPromise[NetException, NetConnection]
    )(using AllowUnsafe, Frame): Unit =
        encoded match
            case Absent =>
                promise.completeDiscard(Result.fail(connectFail(host, port, "")))
            case Present((family, addr, len)) =>
                val sockR = sockets.socket(family, PosixConstants.SOCK_STREAM, 0)
                val fd    = sockR.value
                if fd < 0 then
                    addr.close()
                    promise.completeDiscard(Result.fail(connectFail(host, port, new NetErrno(sockR.errorCode))))
                else if !prepareClientSocket(fd, nodelay) then
                    addr.close()
                    closeRawFd(fd)
                    promise.completeDiscard(Result.fail(connectFail(host, port, "")))
                else
                    val driver = pool.next()
                    val handle = PosixHandle.socket(fd, config.readChunkSize, connectTarget = Present((addr, len)))
                    handle.driver = driver
                    // Arm the connect-deadline before either arm awaits, so the deadline races the OS connect on the same `promise` for both the
                    // io_uring completion arm and the epoll/kqueue readiness arm. A deadline-fired close surfaces the typed
                    // NetConnectTimeoutException; an OS-failure close surfaces NetConnectException through `connectFail`: the close cause is
                    // discriminated by which arm completes `promise` first (completeDiscard, at most once).
                    armConnectDeadline(promise, host, port)
                    if isCompletionConnect(driver) then
                        // Completion arm (io_uring): the driver submits the connect SQE itself against `handle.connectTarget`.
                        awaitConnectThen(handle, addr, driver, target, host, port, tls, promise, checkSoError = false)
                    else
                        // Readiness arm (epoll/kqueue): issue the non-blocking connect, then wait for write-readiness + SO_ERROR.
                        driveReadinessConnect(handle, addr, len, driver, target, host, port, tls, promise)
                    end if
                end if
        end match
    end connectImpl

    /** Arm a `Clock`-driven deadline for one in-flight client TCP connect, mirroring the accept-path `armHandshakeDeadline`. When
      * `config.connectTimeout` is finite (and the target is a TCP host:port, not a Unix socket whose `port < 0` has no typed timeout leaf),
      * schedule `Clock.live.unsafe.sleep(d).onComplete(...)` (a timer fiber on the clock executor, never a blocked carrier) and fail `promise`
      * with `NetConnectTimeoutException(host, port, connectTimeout)` when the deadline fires. `promise.completeDiscard` completes the promise at
      * most once, so the deadline and the OS connect outcome are mutually exclusive. This is the close-cause discrimination: the deadline arm is
      * the only producer of the typed timeout leaf, so a deadline-fired close surfaces `NetConnectTimeoutException` while an OS-failure close
      * (refused/unreachable/errno) surfaces `NetConnectException` through the existing `connectFail` path. When the connect completes first it
      * disarms the timer by interrupting the timer fiber, so the timer never fires.
      */
    private def armConnectDeadline(
        promise: IOPromise[NetException, NetConnection],
        host: String,
        port: Int
    )(using AllowUnsafe, Frame): Unit =
        val timeout = config.connectTimeout
        if port >= 0 && timeout.isFinite then
            val timer = Clock.live.unsafe.sleep(timeout)
            timer.onComplete { _ =>
                promise.completeDiscard(Result.fail(NetConnectTimeoutException(host, port, timeout)))
            }
            // Disarm: when the connect outcome completes `promise` first, interrupt the timer fiber so it never fires.
            promise.onComplete { _ =>
                timer.interruptDiscard(Result.Panic(Closed("PosixTransport", summon[Frame], "connect completed before deadline")))
            }
        end if
    end armConnectDeadline

    /** Issue the readiness-arm non-blocking connect: 0 means the connect completed inline (loopback), `EINPROGRESS` means it is in flight and
      * the driver must signal write-readiness, any other error fails `Closed`. The `addr` buffer is consumed synchronously by the kernel here,
      * so it is closed by the eventual completion path (inline complete, or after the awaited SO_ERROR read).
      */
    private def driveReadinessConnect(
        handle: PosixHandle,
        addr: Buffer[Byte],
        len: Int,
        driver: IoDriver[PosixHandle],
        target: String,
        host: String,
        port: Int,
        tls: Maybe[(NetTlsConfig, String)],
        promise: IOPromise[NetException, NetConnection]
    )(using AllowUnsafe, Frame): Unit =
        takeNow(sockets.connect(handle.writeFd, addr, len)) match
            case Present(result) =>
                val rc = result.value
                if rc == 0 then
                    // Immediate connect (loopback): no SO_ERROR check needed.
                    completeOrTls(handle, addr, driver, target, port, tls, promise)
                else if result.errorCode == PosixConstants.EINPROGRESS then
                    awaitConnectThen(handle, addr, driver, target, host, port, tls, promise, checkSoError = true)
                else
                    addr.close()
                    closeUnwiredHandle(handle, driver, connectPhase = true)
                    promise.completeDiscard(Result.fail(connectFail(host, port, new NetErrno(result.errorCode))))
                end if
            case Absent =>
                // The inline-completed connect fiber yielded no value (only possible off JVM/Native, where the poller does not run).
                awaitConnectThen(handle, addr, driver, target, host, port, tls, promise, checkSoError = true)
        end match
    end driveReadinessConnect

    /** Register for connect completion and continue once the driver signals it. On the readiness arm (`checkSoError`) the signal is mere
      * write-readiness, so `SO_ERROR` is read to confirm the connect actually succeeded; on the completion arm the driver already verified the
      * connect result, so success flows straight through. Failure / interrupt closes the fd (no `Connection` exists yet).
      */
    private def awaitConnectThen(
        handle: PosixHandle,
        addr: Buffer[Byte],
        driver: IoDriver[PosixHandle],
        target: String,
        host: String,
        port: Int,
        tls: Maybe[(NetTlsConfig, String)],
        promise: IOPromise[NetException, NetConnection],
        checkSoError: Boolean
    )(using AllowUnsafe, Frame): Unit =
        val writablePromise = new IOPromise[Closed, Unit]
        writablePromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    val err = if checkSoError then soError(handle.writeFd) else 0
                    if err != 0 then
                        addr.close()
                        closeUnwiredHandle(handle, driver, connectPhase = true)
                        promise.completeDiscard(Result.fail(connectFail(host, port, new NetErrno(err))))
                    else completeOrTls(handle, addr, driver, target, port, tls, promise)
                    end if
                case Result.Failure(closed) =>
                    addr.close()
                    closeUnwiredHandle(handle, driver, connectPhase = true)
                    promise.completeDiscard(Result.fail(connectFail(host, port, closed)))
                case Result.Panic(e) =>
                    addr.close()
                    closeUnwiredHandle(handle, driver, connectPhase = true)
                    promise.completeDiscard(Result.panic(e))
            end match
        }
        // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Closed, Unit], even though both erase to the same runtime object; the alias is transparent only
        // inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitConnect's fixed Promise.Unsafe-typed parameter needs this
        // erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/Unit values above, never a
        // suspended computation.
        driver.awaitConnect(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
        // If the connect promise is completed before the writable arm resolves (an external interrupt: e.g. an Async.timeout connectTimeout
        // interrupts the awaiting fiber, which interrupts this promise), the arm is still parked with the socket in SYN_SENT. Forward the
        // completion to the arm so its onComplete runs the connect-failure cleanup (closeUnwiredHandle), reclaiming the in-flight connect fd
        // instead of leaking it until the OS TCP timeout. completeDiscard is at-most-once: a normal connect outcome (the arm completed first)
        // makes this a no-op, and the arm's own onComplete then completes this promise.
        promise.onComplete { _ =>
            writablePromise.completeDiscard(Result.panic(Closed("PosixTransport", summon[Frame], "connect interrupted before completion")))
        }
    end awaitConnectThen

    /** Test-only engine construction override: `Absent` in every production transport, where [[buildEngine]] always goes through
      * [[TlsProviderPlatform.engine]]. A test sets this (before `listen`/`connect`/`upgradeToTls` can race it; `@volatile` for cross-carrier
      * visibility) to wrap the real engine in a spy, e.g. `RecordingTlsEngine`, so an accept-side handshake's engine reclaim becomes
      * observable without an allocation counter: the engine is otherwise transport-internal, with no other injection point.
      */
    @volatile private[posix] var testEngineFactory: Maybe[(NetTlsConfig, String, Boolean) => TlsEngine] = Absent

    /** Build the handshake engine for a config/host/role via [[TlsProviderPlatform.engine]], which honors a [[NetTlsConfig.tlsProvider]] pin
      * (fail-closed if unavailable) and otherwise the platform-selected default.
      */
    private def buildEngine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        testEngineFactory match
            case Present(f) => f(config, hostname, isServer)
            case Absent     => TlsProviderPlatform.engine(config, hostname, isServer)
    end buildEngine

    /** The posix transport drives any TLS engine the platform registry exposes (BoringSSL and the JDK SslEngine on JVM; BoringSSL and system
      * OpenSSL on Native), so a connection may pin any of those via [[NetTlsConfig.tlsProvider]]. This is the architectural set; whether a given
      * provider's library is staged on the host is a separate [[TlsProvider.isAvailable]] probe.
      */
    override private[net] def supportedTlsProviders: Set[String] =
        TlsProviderPlatform.registered.map(_.name).toSet

    /** After the TCP connect is established: for a plaintext connect complete immediately; for a TLS connect drive a client handshake over the
      * same fd (reusing [[driveHandshake]]) and complete once it succeeds. The `addr` buffer is closed here (the connect is done with it). The
      * handshake is kicked synchronously (no extra carrier); its suspension points arm `onComplete` callbacks that fire from the driver carrier.
      */
    private def completeOrTls(
        handle: PosixHandle,
        addr: Buffer[Byte],
        driver: IoDriver[PosixHandle],
        target: String,
        port: Int,
        tls: Maybe[(NetTlsConfig, String)],
        promise: IOPromise[NetException, NetConnection]
    )(using AllowUnsafe, Frame): Unit =
        addr.close()
        tls match
            case Absent               => completeConnect(handle, driver, promise)
            case Present((cfg, host)) =>
                // buildEngine fails closed (throws Closed) when a verifying client has no reference identity (an empty connect host), so a
                // build failure must fail the connect promise and close the fd rather than escaping into the driver carrier.
                val engine =
                    try buildEngine(cfg, host, isServer = false)
                    catch
                        case e: NetTlsException =>
                            closeUnwiredHandle(handle, driver, connectPhase = false)
                            promise.completeDiscard(Result.fail(e))
                            return
                // Register this in-flight client handshake so a transport `close()` racing it (no deadline exists on the connect path)
                // reclaims the fd/engine and fails `promise` instead of leaking them / hanging the caller past shutdown. `driveHandshake`
                // guarantees exactly one of onFinished/onFailed/onPanic ever fires, so `handshakeDisarm` builds its own fresh gate (there is no
                // pre-existing one to share, unlike the accept-side deadline). See [[pendingHandshakes]].
                //
                // `reaped` mirrors the accept-side #243 guard (see handleAccepted's `teardown`): the sweep below can free the engine while the
                // handshake machine is still actively chaining (a `close()` racing a mid-flight handshake wins `handshakeDisarm` and offers its
                // free op into the engine FIFO; the machine's own next step thunk, already in flight, enqueues AFTER it). A FIFO honors
                // submission order, so it cannot protect an op enqueued after the free: `reaped` is the guard `isReaped` reads inside
                // driveHandshake's handshakeStep/feedCiphertext/awaitReadCiphertext thunks, set as the FIRST statement of every path here that
                // frees the engine, so a step thunk that runs AFTER the free skips instead of touching freed native memory.
                val reaped = AtomicBoolean.Unsafe.init(false)
                val (handshakeToken, handshakeDisarm) = registerHandshake(() =>
                    driver.submitEngineOp { () =>
                        reaped.set(true)
                        closeUnwiredHandle(handle, driver, connectPhase = false)
                        engine.free()
                        promise.completeDiscard(Result.fail(NetConnectionClosedException("handshake")))
                    }
                )
                // `promise` is this handshake's fd-and-engine owner, mirroring the STARTTLS upgrade's `out.onComplete` hook: driveHandshake's
                // three outcomes below discharge the obligation themselves (they win `handshakeDisarm` and unregister), so this hook bites
                // for exactly the settlement they do NOT cover: the caller's fiber interrupting the connect it was awaiting (a timeout, a
                // losing race arm, an enclosing abort). That interrupt leaves the handshake parked on a read a silently stalling peer never
                // completes, holding the fd and engine with no deadline armed (none exists on the connect path) and no sweep coming on the
                // process-shared transport: a permanent leak. Discharging the registered obligation runs the identical release a transport
                // close() would.
                //
                // Installed AFTER registerHandshake so the obligation exists to discharge, and BEFORE driveHandshake so a promise already
                // settled by this point fires the hook immediately: `reaped` is then set and driveHandshake's steps skip rather than
                // touching a freed engine.
                promise.onComplete {
                    case Result.Success(_) => ()
                    case _                 => dischargePendingHandshake(handshakeToken)
                }
                driveHandshake(
                    handle,
                    engine,
                    onFinished = () =>
                        if handshakeDisarm() then
                            unregisterHandshake(handshakeToken)
                            handle.tls = Present(engine)
                            completeConnect(handle, driver, promise),
                    onFailed = cause =>
                        if handshakeDisarm() then
                            unregisterHandshake(handshakeToken)
                            reaped.set(true)
                            closeUnwiredHandle(handle, driver, connectPhase = false)
                            // The handshake never reached onFinished, so the engine was not attached to handle.tls and the closeUnwiredHandle teardown
                            // above (whose PosixHandle.close frees only an attached engine) cannot free it. Free it directly here: the handshake is over
                            // and no pump started, so nothing else touches the engine. It is mutually exclusive with onFinished, so there is no double-free.
                            engine.free()
                            val causeMsg: String | Throwable = cause match
                                case hf: HandshakeFailure.EngineThrew => hf.cause
                                case hf: HandshakeFailure             => hf.toString
                                case st: (String | Throwable)         => st
                            promise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, causeMsg))),
                    onPanic = e =>
                        if handshakeDisarm() then
                            unregisterHandshake(handshakeToken)
                            reaped.set(true)
                            closeUnwiredHandle(handle, driver, connectPhase = false)
                            engine.free()
                            promise.completeDiscard(Result.panic(e)),
                    isReaped = () => reaped.get()
                )
        end match
    end completeOrTls

    /** Wire and start the [[Connection]] over `handle`/`driver` and complete the connect promise. Mirrors the native transport: it installs
      * the STARTTLS `upgradeFn` (delegating to [[upgradeToTls]]) and the `certHashFn` (the engine's RFC 5929 token), so an accepted or
      * connected plaintext connection can still upgrade and a TLS connection can report its channel-binding hash. If the promise was already
      * interrupted, the freshly built connection is closed so nothing leaks.
      */
    private def completeConnect(
        handle: PosixHandle,
        driver: IoDriver[PosixHandle],
        promise: IOPromise[NetException, NetConnection]
    )(using AllowUnsafe, Frame): Unit =
        val connection = openTracked(handle, driver)
        connection.upgradeFn = Present { (cfg, frame) =>
            given Frame = frame
            upgradeToTls(connection, cfg, config.channelCapacity)
        }
        installCertHash(connection, handle)
        // Point the handle's inboundSink at THIS connection before anything can reap on it (see PosixHandle.inboundSink).
        handle.inboundSink = bytes => discard(connection.inbound.offer(bytes))
        // Prefix safety: the handshake may have pulled the peer's first application record off the socket and into the engine
        // (loopback delivers the final handshake flight and the first data together). That plaintext is in the engine buffer, NOT the socket, so
        // the ReadPump's socket-readiness read would never fire for it. Deliver it into inbound before starting the pumps so it is not stranded.
        deliverHandshakePlaintext(handle, connection.inbound)
        if connection.start() then
            if !promise.complete(Result.succeed(connection)) then
                // The connect was interrupted before delivery: nobody will use this connection, so close it.
                connection.close()
        else
            // The connection raced to a terminal/Upgrading state before start (a close won); it must not be handed out as open.
            promise.completeDiscard(Result.fail(NetConnectionClosedException("start")))
        end if
    end completeConnect

    /** Drain any application plaintext the TLS handshake already decrypted into the engine buffer, delivering it into `inbound` before the pumps
      * start (a no-op for a plaintext handle). On a loopback the peer's first record can arrive with the handshake's final flight and be consumed
      * into the engine during the handshake read; that data sits in the engine, not the socket, so the readiness-based ReadPump would never see a
      * socket event for it and the bytes would be stranded. Injecting them first keeps ordering (this is the FIRST application data) and unblocks
      * the consumer without a phantom socket read.
      */
    private def deliverHandshakePlaintext(handle: PosixHandle, inbound: Channel.Unsafe[Span[Byte]])(using AllowUnsafe, Frame): Unit =
        handle.tls match
            case Absent => ()
            case Present(engine) =>
                val acc = new java.io.ByteArrayOutputStream
                if engine.hasBufferedPlaintext then
                    val buffered = engine.readBuffered()
                    if buffered.nonEmpty then acc.write(buffered.toArrayUnsafe)
                // Also pull any further records the engine can decrypt from ciphertext it already holds.
                var more = true
                while more do
                    val out = Buffer.alloc[Byte](handle.readBufferSize)
                    try
                        val n = engine.readPlain(out, handle.readBufferSize)
                        if n > 0 then acc.write(Buffer.copyToArray[Byte](out, 0, n))
                        else more = false
                    finally out.close()
                    end try
                end while
                val bytes = acc.toByteArray
                if bytes.length > 0 then discard(inbound.offer(Span.fromUnsafe(bytes)))
        end match
    end deliverHandshakePlaintext

    /** Install `connection.certHashFn` for the RFC 5929 tls-server-end-point token. The leaf certificate is fixed for the connection's
      * lifetime, so the hash is computed ONCE here and then served from a cache. This is called from each connection-wiring site (a directly
      * connected client, an accepted server connection, a STARTTLS upgrade). Every call site runs at handshake completion, BEFORE
      * `connection.start()` launches the read and write pumps, so no read or write engine op for this connection can exist yet: the single
      * `certSha256()` read here cannot race any other engine touch. The installed function then does NO engine touch: it reads the cached value
      * and returns Absent once the connection is closed. Without the cache, `serverCertificateHash` would touch the live engine on the caller's
      * carrier, racing the driver's FIFO read/write ops (concurrent native `SSL` access is undefined behavior, and the touch could read a freed
      * `ssl`). The cache also makes `serverCertificateHash` return Absent deterministically after close.
      */
    private def installCertHash(connection: InternalConnection[PosixHandle], handle: PosixHandle)(using AllowUnsafe): Unit =
        val cached = handle.tls.flatMap(_.certSha256())
        connection.certHashFn = Present(() => if connection.isOpen then cached else Absent)
        installCloseReason(connection, handle)
    end installCertHash

    /** Install `connection.closeReasonFn` so a TLS connection reports the RFC 8446 6.1 / RFC 5246 7.2.1 close distinction. It reads the handle's
      * observed read-side close signal (the `halfClose` state on the handle). While the connection is open with no half-close, it reports
      * Active; once closed with the state still Open, it was a local close. The function touches no engine, only the handle's `@volatile`
      * `halfClose` field, so it is safe to call on the caller's carrier after close.
      */
    private def installCloseReason(connection: InternalConnection[PosixHandle], handle: PosixHandle)(using AllowUnsafe): Unit =
        connection.closeReasonFn = Present(() =>
            handle.halfClose match
                case HalfCloseState.PeerCleanClose => NetConnection.CloseReason.CleanClose
                case HalfCloseState.PeerEof        => NetConnection.CloseReason.Truncated
                case _ if connection.isOpen        => NetConnection.CloseReason.Active
                case _                             => NetConnection.CloseReason.LocalClose
        )
    end installCloseReason

    // ---------------------------------------------------------------------------------------------------------------------------------------
    // TCP / UDS server listen + accept
    // ---------------------------------------------------------------------------------------------------------------------------------------

    /** Listen for plaintext TCP connections on `host:port`, resolving the actual (possibly ephemeral) port. */
    def listen(host: String, port: Int, backlog: Int)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        listenResolving(host, port, backlog, handler, tls = Absent)

    /** Listen for TLS TCP connections on `host:port`; each accepted connection drives a server handshake before reaching the handler. */
    def listen(host: String, port: Int, backlog: Int, tls: NetTlsConfig)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        listenResolving(host, port, backlog, handler, tls = Present(tls))

    /** Resolve the bind `host` (numeric / loopback inline, otherwise through the offloaded-blocking [[HostResolver]]) and then bind + listen.
      *
      * Mirrors [[connectResolving]]: a `listen` returns its `Fiber.Unsafe` synchronously, but resolution is an `< Async` step, so this builds
      * the result promise immediately, spawns a fiber that resolves + encodes the bind address, and on success hands it to the synchronous
      * [[listenImpl]]. A bind host is almost always numeric or a loopback name (a server binds an interface, not a remote name), so resolution
      * usually completes inline; resolving a bind hostname is supported for symmetry. A resolution failure fails the promise `NetDnsResolutionException`.
      */
    private def listenResolving(
        host: String,
        port: Int,
        backlog: Int,
        handler: NetConnection => Unit,
        tls: Maybe[NetTlsConfig]
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetListener]
        resolveAndEncode(host, port).onComplete {
            case Result.Success(pending) =>
                // pending: (Int, Buffer[Byte], Int) < Any; .eval extracts the concrete tuple, mirroring resolveAndEncode's own identical step.
                listenImpl(Present(pending.eval), nodelay = true, host, port, unixPath = Absent, backlog, handler, tls, promise)
            case Result.Failure(e) =>
                promise.completeDiscard(Result.fail(e))
            case Result.Panic(e) =>
                promise.completeDiscard(Result.panic(e))
        }
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen return needs
        // this erased-boundary cast. Safe: the promise completes only with the NetListener/NetException values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listenResolving

    /** Listen for plaintext connections on a Unix-domain socket `path` (no `TCP_NODELAY`, `NetAddress.Unix`, port -1). A Unix path needs no
      * name resolution, so the encoded `sockaddr` is built inline and handed straight to [[listenImpl]] with no resolver fiber.
      */
    def listenUnix(path: String, backlog: Int)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetListener]
        listenImpl(
            SockAddr.encodeUnix(PosixConstants.AF_UNIX, path).map((b, l) => (PosixConstants.AF_UNIX, b, l)),
            nodelay = false,
            host = path,
            port = -1,
            unixPath = Present(path),
            backlog,
            handler,
            tls = Absent,
            promise = promise
        )
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen (Unix) return
        // needs this erased-boundary cast. Safe: the promise completes only with the NetListener/NetException values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listenUnix

    /** Bind + listen on the encoded address, resolve the bound port (for TCP; -1 for Unix), build a [[PosixListener]], and launch the accept
      * loop. The listen socket is set non-blocking before arming the poller so accept never parks; `acceptNow` returns a fd or EAGAIN inline.
      * On any setup failure the partially opened fd is closed and the promise fails `Closed`.
      */
    private def listenImpl(
        encoded: Maybe[(Int, Buffer[Byte], Int)],
        nodelay: Boolean,
        host: String,
        port: Int,
        unixPath: Maybe[String],
        backlog: Int,
        handler: NetConnection => Unit,
        tls: Maybe[NetTlsConfig],
        promise: IOPromise[NetException, NetListener]
    )(using AllowUnsafe, Frame): Unit =
        encoded match
            case Absent =>
                promise.completeDiscard(Result.fail(NetBindException(host, port, "")))
            case Present((family, addr, len)) =>
                try
                    val sockR = sockets.socket(family, PosixConstants.SOCK_STREAM, 0)
                    val fd    = sockR.value
                    if fd < 0 then
                        promise.completeDiscard(Result.fail(NetBindException(host, port, new NetErrno(sockR.errorCode))))
                    else
                        setReuseAddr(fd)
                        applySocketBuffers(fd)
                        val bindR = sockets.bind(fd, addr, len)
                        if bindR.value != 0 then
                            closeRawFd(fd)
                            promise.completeDiscard(Result.fail(NetBindException(host, port, new NetErrno(bindR.errorCode))))
                        else
                            val listenR = sockets.listen(fd, backlog)
                            if listenR.value != 0 then
                                closeRawFd(fd)
                                promise.completeDiscard(Result.fail(NetBindException(host, port, new NetErrno(listenR.errorCode))))
                            else
                                val (actualPort, address) = unixPath match
                                    case Present(path) => (-1, NetAddress.Unix(path))
                                    case Absent =>
                                        val resolved = resolvePort(fd, family)
                                        (resolved, NetAddress.Tcp(host, resolved))
                                val listener =
                                    new PosixListener(fd, actualPort, host, address, sockets, listeners, AtomicBoolean.Unsafe.init(false))
                                discard(listeners.add(listener))
                                // Flip fd non-blocking BEFORE arming the poller (atomic with awaitAccept arming; no busy-spin window).
                                if shim.kyo_posix_set_nonblocking(fd) != 0 then
                                    Log.live.unsafe.warn(s"listen: failed to set listen fd non-blocking fd=$fd")
                                startAcceptLoop(listener, handler, tls)
                                promise.completeDiscard(Result.succeed(listener))
                            end if
                        end if
                    end if
                finally addr.close()
                end try
        end match
    end listenImpl

    /** Run the accept loop for a listener. The listen fd is non-blocking (set in [[listenImpl]] just before this call). Termination: when
      * the listener is closed, the driver completes the pending accept promise with `Failure(Closed)`, which decrements `acceptLoopsActive`
      * and stops scheduling further accepts. Each accepted fd is made non-blocking + `TCP_NODELAY`, wrapped in a [[Connection]] (after a
      * server TLS handshake when `tls` is present), and dispatched to the handler.
      *
      * Two accept models are unified here. The readiness driver (epoll/kqueue) completes the accept promise with -1 as a sentinel; the
      * transport then calls `acceptNow` in a loop to drain all pending connections. The completion driver (io_uring) completes the promise
      * with the accepted fd directly (>= 0); the transport handles it first, then calls `acceptAll` to drain any further queued connections.
      */
    private def startAcceptLoop(
        listener: PosixListener,
        handler: NetConnection => Unit,
        tls: Maybe[NetTlsConfig]
    )(using AllowUnsafe, Frame): Unit =
        discard(acceptLoopsActive.incrementAndGet())
        val driver = pool.next()
        val handle = PosixHandle.socket(listener.serverFd, config.readChunkSize, connectTarget = Absent)

        // Tear down this listener's accept interest AND its fd through the driver when the listener closes, so the two are sequenced safely
        // for the driver's model. On the readiness drivers `closeListener` cancels synchronously (clearing the fd-keyed pendingAccepts /
        // activeFds entries while the fd is still open, so a recycled fd number never trips over stale accept state) and then closes the fd.
        // On io_uring the whole teardown runs on the reap carrier BEHIND any accept arm still queued on the engine FIFO: closing the fd on
        // this carrier first would let the fd number recycle (typically to the very next listener) before the queued arm preps its SQE, and
        // that ghost arm would then accept on the NEW socket with THIS listener's promise and handler, stealing one connection per close.
        // The shutdown wakes a blocked/armed accept so it observes the close deterministically on every platform (close() alone does not
        // reliably interrupt an accept on Linux); it is harmless where the accept already failed (ENOTCONN on a listener is discarded).
        listener.onClose { () =>
            driver.closeListener(
                handle,
                () =>
                    discard(sockets.shutdown(listener.serverFd, PosixConstants.SHUT_RDWR))
                    discard(sockets.close(listener.serverFd))
            )
        }

        def scheduleNextAccept()(using AllowUnsafe, Frame): Unit =
            if listener.isClosed then
                discard(acceptLoopsActive.decrementAndGet())
            else
                val acceptPromise = new IOPromise[Closed, Int]
                acceptPromise.onComplete {
                    case Result.Success(fd) =>
                        // io_uring completes with the real accepted fd (>= 0); the poller uses -1 as a readiness sentinel.
                        // Handle the already-accepted fd directly before draining any further pending connections.
                        if fd >= 0 then handleAccepted(fd, handler, tls)
                        acceptAll() match
                            case AcceptDrain.Drained =>
                                // Backlog emptied (EAGAIN/EWOULDBLOCK) or a non-resource error consumed the event: re-arm immediately.
                                scheduleNextAccept()
                            case AcceptDrain.ResourceExhausted =>
                                // EMFILE/ENFILE: accept(2) did NOT dequeue the pending connection, so the listen fd stays read-ready. An
                                // immediate re-arm would re-fire the same error in a tight CPU spin (joyent/libuv #690, asyncio Tulip #78).
                                // Re-arm after a bounded backoff instead: the poll loop stops pegging the CPU, and accepting resumes when the
                                // backoff elapses (succeeding once a fd frees elsewhere in the process). The accept loop stays alive throughout.
                                scheduleAcceptAfterBackoff()
                        end match
                    case Result.Failure(_) =>
                        // Listener closed; driver completed the accept promise with Failure(Closed).
                        discard(acceptLoopsActive.decrementAndGet())
                    case Result.Panic(e) =>
                        Log.live.unsafe.error("PosixTransport accept loop panic", e)
                        discard(acceptLoopsActive.decrementAndGet())
                }
                // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
                // plainly-constructed IOPromise[Closed, Int], even though both erase to the same runtime object; the alias is transparent
                // only inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitAccept's fixed Promise.Unsafe-typed parameter needs
                // this erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/Int values above, never
                // a suspended computation.
                driver.awaitAccept(handle, acceptPromise.asInstanceOf[Promise.Unsafe[Int, Abort[Closed]]])
        end scheduleNextAccept

        // Re-arm accept interest after the resource-exhaustion backoff, without blocking the poll-loop carrier. `Clock.live.unsafe.sleep`
        // schedules a timer on the clock executor and returns a fiber; its completion callback re-enters scheduleNextAccept. If the listener
        // closed during the backoff, scheduleNextAccept observes isClosed and winds the loop down (no re-arm on a dead fd).
        def scheduleAcceptAfterBackoff()(using AllowUnsafe, Frame): Unit =
            Clock.live.unsafe.sleep(acceptResourceBackoff).onComplete(_ => scheduleNextAccept())

        def acceptAll()(using AllowUnsafe, Frame): AcceptDrain =
            val noAddr = Buffer.alloc[Byte](SockAddr.inet6Size)
            val noLen  = Buffer.alloc[Int](1)
            noLen.set(0, SockAddr.inet6Size)
            try
                @scala.annotation.tailrec
                def drain(transientRetries: Int): AcceptDrain =
                    val r  = sockets.acceptNow(listener.serverFd, noAddr, noLen)
                    val fd = r.value
                    if fd >= 0 then
                        handleAccepted(fd, handler, tls)
                        drain(transientRetries)
                    else if isWouldBlock(r.errorCode) then
                        // Backlog drained: re-arm read interest normally.
                        AcceptDrain.Drained
                    else if r.errorCode == PosixConstants.EMFILE || r.errorCode == PosixConstants.ENFILE then
                        // Resource exhaustion: the connection stays in the backlog. Stop draining and signal a backoff re-arm.
                        AcceptDrain.ResourceExhausted
                    else if (r.errorCode == PosixConstants.EINTR || r.errorCode == PosixConstants.ECONNABORTED)
                        && transientRetries < maxTransientAcceptRetries
                    then
                        // EINTR (interrupted) / ECONNABORTED (peer aborted before accept returned) are transient: accept(2) says treat them
                        // like EAGAIN by retrying. Retry the accept (do NOT drop the connection), bounded so a persistent transient error
                        // cannot itself spin this cycle; past the bound, fall through to a normal re-arm.
                        drain(transientRetries + 1)
                    else
                        // Listener closed mid-drain, the transient-retry budget is spent, or an unclassified errno: re-arm normally (the next
                        // readiness event, or scheduleNextAccept's isClosed check, drives the loop forward as before).
                        AcceptDrain.Drained
                    end if
                end drain
                drain(0)
            finally
                noAddr.close()
                noLen.close()
            end try
        end acceptAll

        scheduleNextAccept()
    end startAcceptLoop

    /** Configure an accepted client fd (non-blocking + `TCP_NODELAY` / macOS `SO_NOSIGPIPE`), build its [[Connection]] (driving a server TLS
      * handshake first when `tls` is present), and spawn the handler fiber. A failure to configure closes the raw fd; TLS handshake failures
      * are surfaced via the `onFailed`/`onPanic` callbacks. The handshake is kicked synchronously (no extra carrier); its suspension points arm
      * `onComplete` callbacks that fire from the driver carrier.
      */
    private def handleAccepted(
        clientFd: Int,
        handler: NetConnection => Unit,
        tls: Maybe[NetTlsConfig]
    )(using AllowUnsafe, Frame): Unit =
        if !prepareClientSocket(clientFd, nodelay = true) then closeRawFd(clientFd)
        else
            val driver = pool.next()
            val handle = PosixHandle.socket(clientFd, config.readChunkSize, connectTarget = Absent)
            handle.driver = driver
            tls match
                case Absent =>
                    spawnHandler(openTracked(handle, driver), driver, handler)
                case Present(cfg) =>
                    val engine =
                        try buildEngine(cfg, "", isServer = true)
                        catch
                            case e: NetTlsException =>
                                Log.live.unsafe.error(s"PosixTransport server TLS engine setup failed fd=$clientFd", e)
                                closeRawFd(clientFd)
                                return
                    // Once the teardown has run, every still-enqueued engine-touching handshake thunk must skip (it would otherwise feed a freed
                    // engine). The reap flag is set by teardown BEFORE engine.free and read by the driveHandshake / recvAndFeed / awaitReadCiphertext
                    // thunks via the isReaped guard; teardown and those thunks all run on the one per-driver engine FIFO worker, so the set is
                    // serialized against the reads (the AtomicBoolean is for safe publication across the timer / handshake carriers).
                    val reaped = AtomicBoolean.Unsafe.init(false)
                    // Teardown reused by both the handshake failure / panic paths and the handshake-deadline expiry. The deadline path is the #243
                    // use-after-free: when handshakeTimeout reaps a handshake parked in awaitReadCiphertext, an io_uring recv SQE is in flight into
                    // handle.readBuffer. The old teardown freed that buffer (PosixHandle.close) and the engine while the recv SQE was still kernel-
                    // owned, so the kernel wrote recv data into freed memory and a late recv-Success enqueued a feed on a freed engine. The fix routes
                    // the buffer + engine teardown through the driver's UAF-safe ioDriver.closeHandle:
                    //   - ioDriver.closeHandle -> cancel(handle) fails the parked read promise (so awaitReadCiphertext's Success feed cannot run
                    //     post-reap) and DEFERS PosixHandle.close (the readBuffer free) on io_uring until the in-flight recv CQE reaps, so the kernel
                    //     never writes into freed memory. On the poller closeHandle frees inline (already safe: the poller never hands the kernel the
                    //     buffer) AND closes the fd itself through handle.claimFdClose (after the fatal-alert send drainAllDirect already flushed inline
                    //     on the failure path, so the peer still gets the alert before the FIN).
                    // The fd close is coordinated by handle.claimFdClose so it happens EXACTLY once across the two backends:
                    //   - poller: closeHandle already claimed and closed the fd, so claimFdClose here returns false and this skips it (no double-close,
                    //     and the alert the poller's inline send already put on the wire is not truncated by a redundant close).
                    //   - io_uring: closeHandle does NOT close the fd (it has no socket bindings), so claimFdClose here wins; shutdown(SHUT_RDWR) then
                    //     forces the kernel-owned recv to complete (EOF) so its CQE reaps, draining the in-flight count to zero and running the deferred
                    //     PosixHandle.close (io_uring's cancel submits no async-cancel SQE and a stalled client sends nothing, so without this the recv
                    //     CQE would never arrive and the buffer free would never run, a leak); the real closeRawFd is installed as handle.fdCloseSink
                    //     and runs LAST, inside that same freeResources, instead of here directly. The recv SQE references the original file, not the
                    //     fd number, so closing the fd there does not affect the pending recv's completion.
                    // engine.free() runs AFTER the cancel and after the reap flag is set: the handshake engine was never attached to handle.tls
                    // (onFinished did not run), so closeHandle does not free it, and with reaped set + the read promise cancelled no feed can follow,
                    // so it frees the engine exactly once.
                    def teardown(): Unit =
                        reaped.set(true)
                        handle.driver.closeHandle(handle)
                        if handle.claimFdClose() then
                            discard(sockets.shutdown(clientFd, PosixConstants.SHUT_RDWR))
                            handle.fdCloseSink = Present(() => closeRawFd(clientFd))
                        engine.free()
                    end teardown
                    // One deadline per accepted connection: when handshakeTimeout is finite, a client that completed the TCP accept but stalls
                    // the TLS handshake (sends nothing / a partial ClientHello) would otherwise pin this fd + engine + buffers forever
                    // (a slowloris handshake-stall DoS, CWE-400). The guard ensures exactly one of (handshake outcome, deadline) runs the
                    // teardown / completion; the deadline reuses the same teardown a failed handshake runs. The deadline teardown is submitted
                    // on the engine FIFO worker (submitEngineOp) so it is serialized against any in-flight feedCiphertext from a read that completed
                    // just before the deadline, matching the engine-op single-owner discipline. handshakeTimeout = Infinity arms no timer
                    // (preserving the original behavior). See [[armHandshakeDeadline]].
                    // Register this in-flight handshake so a transport `close()` racing a stalled/slow accept handshake (no deadline armed,
                    // or one that has not yet fired) reclaims the fd/engine instead of leaking them past shutdown. Shares `disarm`'s exactly-once
                    // gate with the handshake outcome and the deadline below, so `close()`'s sweep can never double-discharge a handshake that is
                    // concurrently finishing or timing out. See [[pendingHandshakes]].
                    //
                    // `handshakeTokenRef` publishes the real token through a `java.util.concurrent.atomic.AtomicLong` (mirrors
                    // `pendingHandshakeSeq` above), not a plain `var`. The deadline's own timer fiber (armed below) reads this on a
                    // DIFFERENT carrier than the one that writes it a few lines later; a bare var gives no happens-before guarantee for
                    // that cross-carrier read (a prior version of this comment claimed the timer "cannot fire before registerHandshake
                    // returns below," which is not a guarantee the JMM gives a plain var write/read pair, only a usually-true wall-clock
                    // ordering). The write below happens-before any subsequent read the timer's callback performs.
                    val handshakeTokenRef = new java.util.concurrent.atomic.AtomicLong(0L)
                    val disarm = armHandshakeDeadline(
                        clientFd,
                        () =>
                            unregisterHandshake(handshakeTokenRef.get()); handle.driver.submitEngineOp(() => teardown())
                    )
                    handshakeTokenRef.set(registerHandshake(disarm, () => handle.driver.submitEngineOp(() => teardown())))
                    driveHandshake(
                        handle,
                        engine,
                        onFinished = () =>
                            if disarm() then
                                unregisterHandshake(handshakeTokenRef.get())
                                handle.tls = Present(engine)
                                spawnHandler(openTracked(handle, driver), driver, handler),
                        onFailed = cause =>
                            if disarm() then
                                unregisterHandshake(handshakeTokenRef.get())
                                val causeMsg: String | Throwable = cause match
                                    case hf: HandshakeFailure.EngineThrew => hf.cause
                                    case hf: HandshakeFailure             => hf.toString
                                    case st: (String | Throwable)         => st
                                Log.live.unsafe.warn(
                                    s"PosixTransport server TLS handshake failed fd=$clientFd: ${NetException.show(causeMsg)}"
                                )
                                teardown(),
                        onPanic = e =>
                            if disarm() then
                                unregisterHandshake(handshakeTokenRef.get())
                                Log.live.unsafe.error(s"PosixTransport server TLS handshake panic fd=$clientFd", e)
                                teardown(),
                        isReaped = () => reaped.get()
                    )
            end match
        end if
    end handleAccepted

    /** Arm a `Clock`-driven deadline for one accepted connection's TLS handshake, returning a `disarm` thunk the handshake outcome calls to
      * claim completion. The returned thunk returns `true` exactly once: the first caller (the handshake's `onFinished`/`onFailed`/`onPanic`,
      * OR the deadline's expiry) wins and proceeds; every later caller returns `false` and is a no-op. So the handshake outcome and the
      * deadline are mutually exclusive: only one runs the teardown / completion.
      *
      *   - When `config.handshakeTimeout` is finite, this schedules `Clock.live.unsafe.sleep(d).onComplete(...)` (the same non-blocking timer
      *     idiom [[startAcceptLoop.scheduleAcceptAfterBackoff]] uses: a timer fiber on the clock executor, never a blocked carrier). If the
      *     deadline fires before the handshake completes, the timer wins the guard and runs `onDeadline` (the connection's fd + engine
      *     teardown), reaping a stalled handshake. When the handshake completes first it wins the guard and interrupts the timer fiber, so the
      *     timer never fires.
      *   - When `config.handshakeTimeout` is `Duration.Infinity`, NO timer is armed, but the returned `disarm` is still a fresh one-shot gate
      *     (its own `AtomicBoolean` CAS), not a constant `true`: [[registerHandshake]] hands this SAME `disarm` to `close()`'s
      *     [[sweepPendingHandshakes]], which can call it concurrently with the handshake's own outcome callback even though no deadline timer
      *     is racing it. A constant-`true` gate would let both callers win at once: the sweep frees the engine (`teardown()`) while the
      *     handshake's own `onFinished` wires that SAME freed engine into `handle.tls` and spawns the handler, a use-after-free with no
      *     deadline involved at all. The one-shot gate keeps the exactly-once contract [[registerHandshake]]'s doc promises regardless of
      *     whether a timer is armed.
      */
    private def armHandshakeDeadline(clientFd: Int, onDeadline: () => Unit)(using AllowUnsafe, Frame): () => Boolean =
        val timeout = config.handshakeTimeout
        if !timeout.isFinite then
            val settled = AtomicBoolean.Unsafe.init(false)
            () => settled.compareAndSet(false, true)
        else
            val settled = AtomicBoolean.Unsafe.init(false)
            val timer   = Clock.live.unsafe.sleep(timeout)
            timer.onComplete { _ =>
                if settled.compareAndSet(false, true) then
                    val reapedState = HandshakeState.Failed(HandshakeFailure.DeadlineReaped)
                    Log.live.unsafe.warn(s"PosixTransport server TLS handshake timed out fd=$clientFd after ${timeout.show}: $reapedState")
                    onDeadline()
            }
            () =>
                if settled.compareAndSet(false, true) then
                    // The handshake won the race: disarm the deadline so the timer never fires (its onComplete sees the guard already set).
                    timer.interruptDiscard(Result.Panic(Closed("PosixTransport", summon[Frame], "handshake completed before deadline")))
                    true
                else false
        end if
    end armHandshakeDeadline

    /** Wire `connection` (upgrade + cert-hash functions), start it, and invoke the handler in its own carrier fiber.
      * The connection's lifecycle is managed by its pumps, not by the handler's return. The handler is fire-and-forget: if it needs async
      * work it spawns its own carrier via Fiber.Unsafe.init. A throw from the handler is logged and does not propagate to the accept loop.
      */
    private def spawnHandler(
        connection: InternalConnection[PosixHandle],
        driver: IoDriver[PosixHandle],
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Unit =
        // Server-accepted connection: mark its origin so a STARTTLS upgrade through the public upgradeToTls runs in the TLS server role
        // (upgradeToTls reads isServerOrigin), and route its upgradeFn through the same public entry as the client so the role lives in one
        // place. Otherwise both peers would handshake as clients and the upgrade aborts Closed.
        connection.isServerOrigin = true
        connection.upgradeFn = Present { (cfg, frame) =>
            given Frame = frame
            upgradeToTls(connection, cfg, config.channelCapacity)
        }
        installCertHash(connection, connection.handle)
        // Point the handle's inboundSink at THIS connection before anything can reap on it (see PosixHandle.inboundSink).
        connection.handle.inboundSink = bytes => discard(connection.inbound.offer(bytes))
        // Deliver any application plaintext the server handshake already decrypted (the client's first record may have arrived with the final
        // handshake flight) before the pumps start, so it is not stranded in the engine awaiting a socket read that never comes.
        deliverHandshakePlaintext(connection.handle, connection.inbound)
        if connection.start() then
            discard(Fiber.Unsafe.init {
                // Contain ANY throw from the user handler (not just NonFatal): a throw must never escape to the carrier, abort the process, or stall
                // the accept loop. On Scala Native an exception that unwinds out of the carrier can abort via the libunwind compact-unwind walk, so the
                // containment is mandatory on every platform, including for the fatal/control throwables NonFatal would let through.
                try handler(connection)
                catch case e: Throwable => Log.live.unsafe.error("listen handler panic", e)
            })
        else
            // The connection raced to a terminal/Upgrading state before start (the transport's close swept it, or a detach won);
            // it is not usable, so the handler is never spawned.
            Log.live.unsafe.info(s"PosixTransport accepted connection closed before start; handler not spawned")
        end if
    end spawnHandler

    /** Shut the transport down: close every still-open connection, then every live listener (so the driver completes each pending accept promise
      * with a Closed failure, causing the accept loop to decrement `acceptLoopsActive` and stop scheduling further accepts), and then the driver
      * pool. Connections are closed FIRST, while the pool's reap loops are still alive, so each connection's deferred fd close completes (the FIN
      * goes out, the fd is reclaimed) instead of being stranded when the pool tears down; a connection whose ordinary close never ran (its peer
      * FIN never arrived, its handler never closed it) would otherwise leak its fd past the pool teardown. Without closing the listeners before
      * the pool, their accept loops would keep arming the driver for new accept events after the pool is gone.
      *
      * `forceCloseIfUpgrading` runs alongside `close()` for every connection: ordinary `close()` is a no-op while a connection is `Upgrading`
      * (its fd is owned by the in-flight TLS upgrade, which normally does its own success/failure cleanup), but at transport shutdown nothing
      * will ever complete that upgrade. Without the force-close, a connection whose upgrade never finished (its peer disconnected mid-handshake,
      * e.g. a verifying client rejecting the peer's identity before ever sending a ClientHello) leaks its fd: the upgrade's own failure path
      * DOES eventually free it, but only once the driver's reap loop gets a scheduler turn to run the async teardown `pool.close()` queues below,
      * which is not bounded by the time this call returns (an intermittent CLOSE_WAIT leak under a fast-completing test's leak check).
      */
    def close()(using AllowUnsafe, Frame): Unit =
        connections.values().forEach { c =>
            c.close()
            c.forceCloseIfUpgrading()
        }
        connections.clear()
        listeners.forEach(l => l.close())
        sweepPendingHandshakes()
        pool.close()
    end close

    // ---------------------------------------------------------------------------------------------------------------------------------------
    // Low-level socket helpers
    // ---------------------------------------------------------------------------------------------------------------------------------------

    /** True when `driver` performs the connect itself via a completion SQE (io_uring) rather than relying on the transport to issue the
      * `connect` syscall and then waiting for write-readiness (epoll/kqueue). The io_uring driver's `awaitConnect` submits an
      * `IORING_OP_CONNECT` against the handle's `connectTarget`, so for it the transport must NOT also call `connect` (which would race a
      * second connect on the same fd).
      */
    private def isCompletionConnect(driver: IoDriver[PosixHandle]): Boolean =
        driver.label == "IoUringDriver"

    /** Set a client / accepted fd non-blocking, opt it out of SIGPIPE on macOS/BSD, and (when `nodelay`) disable Nagle. Returns false if the
      * non-blocking shim fails; the `SO_NOSIGPIPE` / `TCP_NODELAY` options are best-effort (a failure there does not abort the connection).
      *
      * `SO_NOSIGPIPE` is set on EVERY client fd on macOS/BSD regardless of `nodelay`: it is the per-socket SIGPIPE opt-out, not a performance
      * knob, and on macOS/BSD `MSG_NOSIGNAL` is the 0 sentinel so a `send` to a peer-closed socket relies entirely on `SO_NOSIGPIPE` to avoid a
      * SIGPIPE (process kill on Native). Gating it behind `nodelay` left `connectUnix` (which passes `nodelay = false`) exposed. `TCP_NODELAY`
      * stays gated on `nodelay`: disabling Nagle is a TCP-only performance choice and is meaningless on a Unix-domain socket.
      */
    private def prepareClientSocket(fd: Int, nodelay: Boolean)(using AllowUnsafe): Boolean =
        if shim.kyo_posix_set_nonblocking(fd) != 0 then false
        else
            if PosixConstants.isMacOrBsd then
                setIntOpt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_NOSIGPIPE, 1)
            if nodelay then
                setIntOpt(fd, PosixConstants.IPPROTO_TCP, PosixConstants.TCP_NODELAY, 1)
                if PosixConstants.isLinux then
                    setIntOpt(fd, PosixConstants.IPPROTO_TCP, PosixConstants.TCP_QUICKACK, 1)
            end if
            applySocketBuffers(fd)
            true
    end prepareClientSocket

    /** Apply the configured SO_RCVBUF and SO_SNDBUF socket buffer sizes when Present. A kernel may silently clamp the value to a site-maximum
      * (see setsockopt(7) SO_RCVBUF). Both options are best-effort: a failure does not abort the connection. Absent leaves the kernel default.
      */
    private def applySocketBuffers(fd: Int)(using AllowUnsafe): Unit =
        config.soRcvBuf.foreach(sz => setIntOpt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_RCVBUF, sz))
        config.soSndBuf.foreach(sz => setIntOpt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_SNDBUF, sz))

    /** Set `SO_REUSEADDR` on a listen socket so repeated binds do not trip `TIME_WAIT`. */
    private def setReuseAddr(fd: Int)(using AllowUnsafe): Unit =
        setIntOpt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_REUSEADDR, 1)

    /** Set a 4-byte integer socket option. Best-effort: the value buffer is laid out little-endian (all supported targets are LE). */
    private def setIntOpt(fd: Int, level: Int, optname: Int, value: Int)(using AllowUnsafe): Unit =
        val opt = Buffer.alloc[Byte](4)
        try
            var i = 0
            while i < 4 do
                opt.set(i, ((value >> (i * 8)) & 0xff).toByte)
                i += 1
            discard(sockets.setsockopt(fd, level, optname, opt, 4))
        finally opt.close()
        end try
    end setIntOpt

    /** Read `SO_ERROR` for `fd` after a non-blocking connect signalled write-readiness: 0 means the connect succeeded, any other value is the
      * connect's `errno`. The option is a 4-byte int read little-endian.
      */
    private def soError(fd: Int)(using AllowUnsafe): Int =
        val opt = Buffer.alloc[Byte](4)
        val len = Buffer.alloc[Int](1)
        len.set(0, 4)
        try
            if sockets.getsockopt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_ERROR, opt, len).value != 0 then -1
            else
                (opt.get(0) & 0xff) |
                    ((opt.get(1) & 0xff) << 8) |
                    ((opt.get(2) & 0xff) << 16) |
                    ((opt.get(3) & 0xff) << 24)
        finally
            opt.close()
            len.close()
        end try
    end soError

    /** Close a raw fd that is not yet owned by a [[Connection]] (a connect / accept failure path). Extracts the result of the
      * already-inline-completed `@Ffi.blocking` `close` fiber via `takeNow` (poll-based, no park); a live connection's fd is instead closed
      * through `Connection.close` so in-flight driver ops are cancelled first.
      */
    private def closeRawFd(fd: Int)(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        discard(takeNow(sockets.close(fd)))
    end closeRawFd

    /** Tear down a client-side handle whose connect or TLS handshake failed before any [[Connection]] was wired, mirroring the server-accept
      * teardown. Routing through `driver.closeHandle` (rather than shutting the fd down and closing it directly) is what keeps the poller state
      * consistent: it deregisters the handle's fds (removing the activeFds / pendingReads / pendingWritables entries and, on epoll, the
      * `epollDesired` mask) so a recycled fd number starts from a clean slate and a fresh arm re-publishes the owner-id cookie, and it frees
      * the handle's `readBuffer` via `PosixHandle.close` (the bare close path leaked it on the connect-failure paths). The fd close is
      * backend-coordinated through the shared `claimFdClose`: the poller's `closeHandle` already closed it (this skips), io_uring's defers it
      * (this `shutdown` runs it). Any unattached handshake engine is freed by the caller AFTER this (it is not on `handle.tls`, so
      * `PosixHandle.close` does not free it).
      *
      * `connectPhase` distinguishes the two callers: a plain TCP connect that has not yet completed (`driveReadinessConnect` /
      * `awaitConnectThen`, before any handshake starts) vs a client TLS handshake failing over an ALREADY-established connect
      * (`completeOrTls`, mirroring the accept-side `handleAccepted.teardown`). Only the latter defers the real close(fd) to
      * `PosixHandle.freeResources` (via `fdCloseSink`): the handle is not yet wired to a `Connection`, so no pump can ever be racing this fd
      * through `beginWrite` / `beginDispatch`, and the only in-flight kernel op a live handshake read leaves is a recv, which `shutdown`
      * forces to complete promptly, so deferring is both safe and sufficient there. A STILL-CONNECTING socket has no such forcing function:
      * `shutdown(2)` has no effect on a not-yet-established connect, and on io_uring nothing ever explicitly cancels an outstanding
      * `IORING_OP_CONNECT` (`cancel()` only fails the local promise), so against an unresponsive peer the deferred credit could wait past
      * this process's lifetime for `PosixHandle.freeResources` to ever run -- closing the raw fd immediately here, the same as every other
      * fd close before the deferred-credit path existed, is correct rather than merely expedient.
      */
    private def closeUnwiredHandle(handle: PosixHandle, driver: IoDriver[PosixHandle], connectPhase: Boolean)(using
        AllowUnsafe,
        Frame
    ): Unit =
        driver.closeHandle(handle)
        if handle.claimFdClose() then
            discard(sockets.shutdown(handle.writeFd, PosixConstants.SHUT_RDWR))
            if connectPhase then closeRawFd(handle.writeFd)
            else handle.fdCloseSink = Present(() => closeRawFd(handle.writeFd))
        end if
    end closeUnwiredHandle

    /** The non-blocking fcntl shim (loaded once), used to set client / accepted sockets non-blocking on every architecture (RI: variadic
      * fcntl is ABI-unsafe on arm64).
      */
    private def shim(using AllowUnsafe): PosixShimBindings = Ffi.load[PosixShimBindings]

    /** Extract the value of an already-inline-completed `@Ffi.blocking` fiber via `poll()` (non-parking peek). Returns `Absent` only if the
      * fiber is still pending (not reachable on JVM/Native where this transport runs). Uses `poll()` rather than any parking call.
      */
    // Unsafe: poll() is the non-parking extraction of an already-inline-completed @Ffi.blocking fiber (JVM/Native).
    private def takeNow[A](fiber: Fiber.Unsafe[A, Any])(using AllowUnsafe, Frame): Maybe[A] =
        if fiber.done() then
            fiber.poll() match
                case Present(Result.Success(v)) => Present(v.eval)
                case _                          => Absent
        else Absent
    end takeNow

    /** Resolve the bound port via `getsockname` (the ephemeral port chosen when binding to port 0). The port lives at offset 2 (network
      * order) for both `sockaddr_in` and `sockaddr_in6`. Returns the requested family's nominal port (0) on a `getsockname` failure.
      */
    private def resolvePort(fd: Int, family: Int)(using AllowUnsafe): Int =
        val size = if family == PosixConstants.AF_INET6 then SockAddr.inet6Size else SockAddr.inet4Size
        val out  = Buffer.alloc[Byte](size)
        val ol   = Buffer.alloc[Int](1)
        ol.set(0, size)
        try
            if sockets.getsockname(fd, out, ol).value != 0 then 0
            else ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
        finally
            out.close()
            ol.close()
        end try
    end resolvePort

    /** The read end is pollable unless it is a regular file under an epoll backend. epoll rejects regular files (the one cell needing
      * the fallback), while io_uring and kqueue stream them natively, and pipes (`S_ISFIFO`) and ttys (`S_ISCHR`) are pollable on every
      * backend. The probe is single-sided (readFd only) and runs once via `fstat` + `S_ISREG`, never a register-and-detect-EPERM attempt
      * (EPERM is not a portable non-pollable signal: kqueue tolerates regular files, so a failed registration would have to be unwound).
      */
    private[posix] def pollable(fd: Int)(using AllowUnsafe): Boolean =
        !(ioDriver.label == "PollerIoDriver" && backendIsEpoll && isRegularFile(fd))

    private def isRegularFile(fd: Int)(using AllowUnsafe): Boolean =
        val stat = Buffer.alloc[Byte](PosixConstants.statSize)
        try
            if sockets.fstat(fd, stat).value < 0 then false
            else (PosixStat.stMode(stat) & PosixConstants.S_IFMT) == PosixConstants.S_IFREG
        finally stat.close()
        end try
    end isRegularFile

    /** STARTTLS upgrade through [[TlsEngine]]. Detaches the plaintext connection (closing its channels but keeping the fd open via
      * `detachForUpgrade`), feeds any staged ciphertext the plaintext `ReadPump` had already pulled off the socket into the new engine BEFORE
      * the first post-upgrade socket read (so no byte is dropped), drives the handshake over the same fd, attaches the engine to the handle,
      * and builds a NEW `Connection[PosixHandle]` over the SAME fd. A connection that is not upgradable (`detachForUpgrade` returns `Absent`,
      * e.g. the in-memory connection) aborts `Closed` rather than silently no-op.
      */
    def upgradeToTls(
        conn: Connection,
        tls: NetTlsConfig,
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]] =
        // The TLS role follows the connection's TCP origin: an accepted connection upgrades as the TLS server, a connected one as the client.
        // The origin is set at connection creation (`spawnHandler` -> server, `completeConnect` -> client); a non-upgradable connection
        // (e.g. the in-memory connection) is not an InternalConnection and falls back to the client role, then aborts Closed in upgradeRole.
        val isServer = conn match
            case ic: InternalConnection[?] @unchecked => ic.isServerOrigin
            case _                                    => false
        upgradeRole(conn, tls, channelCapacity, isServer = isServer)
    end upgradeToTls

    /** STARTTLS upgrade for the given role. The public `upgradeToTls` is the client (`isServer = false`) STARTTLS path; the server-accept role
      * shares every step (detach, feed staged ciphertext, drive the handshake, rebuild over the same fd) and differs only in the engine the
      * registry builds, so a server peer (the test, kyo-sql's server side) drives the same machinery.
      */
    private[posix] def upgradeRole(
        conn: Connection,
        tls: NetTlsConfig,
        channelCapacity: Int,
        isServer: Boolean
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]] =
        conn match
            case posixConn: InternalConnection[PosixHandle] @unchecked if posixConn.handle.isInstanceOf[PosixHandle] =>
                // One upgrade per connection: win the one-shot claim BEFORE arming any shared upgrade state (the abandon thunk below, the
                // handle's upgrade-window flags). A second upgradeToTls call on the same connection would otherwise overwrite the in-flight
                // upgrade's `upgradeAbandon` with a thunk over its own about-to-fail promise, permanently disarming close()'s only route to
                // the first upgrade's fd, and re-arm the handle flags on a window it does not own.
                if !posixConn.claimUpgrade() then
                    return Fiber.Unsafe.fromResult(Result.fail(NetAlreadyDetachedException()))
                val out    = new IOPromise[NetException, Connection]
                val handle = posixConn.handle
                // `out` owns the detached fd for the whole upgrade (see the `out.onComplete` owner below), so route a close() of the plaintext
                // connection to it: settling `out` drives the same release a handshake failure takes. Armed BEFORE the detach, so no close() can
                // observe the connection Upgrading without an owner to hand itself to. A typed leaf, not an Interrupted panic: the upgrade did not
                // fail on its own terms, its connection was closed underneath it.
                posixConn.upgradeAbandon = Present(() => out.interruptDiscard(Result.Failure(NetConnectionClosedException("close"))))
                // Arm the upgrade window BEFORE detach closes the inbound channel, on EVERY backend. Two distinct uses keyed on the same flag:
                //   - io_uring: the plaintext ReadPump left a recv SQE kernel-owned (io_uring cannot cancel it) that will consume the peer's first
                //     post-signal flight; its CQE reaps on the reap carrier. If that CQE reaps in the window between detach's inbound.close() and the
                //     upgradeActive set, with upgradeActive still false the reap routes the flight to the torn-down ReadPump promise and the closed
                //     inbound drops it, stranding the handshake. Setting it before detach makes the reap always route the flight into upgradeHandoff
                //     (a Carryover, or a fulfilled Waiter) instead.
                //   - pollers: recv is synchronous (no kernel-owned stale recv), but the pump's read re-arm can still race detachForUpgrade and
                //     register the fd's read side ahead of the handshake. While upgradeActive && !handshakeReading the poll carrier rejects that stray
                //     re-arm (PollerIoDriver's registration apply), so the peer's first TLS flight reaches the handshake, not the pump. handshakeReading
                //     flips when the handshake takes the read (awaitReadCiphertext).
                handle.upgradeActive = true
                handle.upgrading =
                    true // durable across the whole window (upgradeActive clears mid-handshake); io_uring read-routing reads it
                posixConn.detachForUpgrade() match
                    case Absent =>
                        // The claim above was won, so no other upgrade can be in flight: losing the detach CAS here means the connection
                        // reached a terminal state first (a close raced or preceded this call). Undo this call's own pre-detach arm; the
                        // close path owns the fd.
                        handle.upgradeActive = false
                        handle.upgrading = false
                        out.completeDiscard(Result.fail(NetAlreadyDetachedException()))
                    case Present(staged) =>
                        // Committed to the upgrade: mark it durably (see PosixHandle.isUpgraded) before any handshakeOwned recv can be armed, so
                        // the io_uring reap can recognize one that outlives onFinished's flag-clear even after upgradeActive/upgrading go false.
                        handle.isUpgraded = true
                        // buildEngine fails closed (throws a NetTlsException) when a verifying STARTTLS client has no reference identity
                        // (no sniHostname), so a build failure must release the detached-but-open fd and fail the upgrade promise rather
                        // than escaping. No engine exists yet on this path, so only the fd is released (releaseFailedUpgrade also frees
                        // the engine, which is absent here). The release routes through closeUnwiredHandle, never a bare PosixHandle.close:
                        // driver.closeHandle runs first, so on io_uring the read-buffer free is deferred until every kernel-owned SQE for
                        // this handle reaps. The stale plaintext recv the upgrade window leaves in flight is kernel-owned by construction
                        // here, with the buffer's address already captured; an inline free would return that off-heap memory to the
                        // process-wide allocator while the kernel can still complete the recv into it. The real close(fd) is deferred to
                        // freeResources via the fdCloseSink credit closeUnwiredHandle installs.
                        val engine =
                            try buildEngine(tls, upgradeHost(tls, isServer), isServer)
                            catch
                                case e: NetTlsException =>
                                    closeUnwiredHandle(handle, handle.driver, connectPhase = false)
                                    out.completeDiscard(Result.fail(e))
                                    // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala),
                                    // structurally different from this plainly-constructed, invariant IOPromise[NetException, Connection],
                                    // even though both erase to the same runtime object; the alias is transparent only inside kyo.Fiber's
                                    // own defining scope, so returning this promise as the locked Transport.upgradeToTls result needs this
                                    // erased-boundary cast. Safe: the promise is completed only with the NetException/Connection values above.
                                    return out.asInstanceOf[Fiber.Unsafe[Connection, Abort[NetException]]]
                        // Feed every staged ciphertext byte into the engine before the first post-upgrade read.
                        feedStaged(engine, staged)
                        // Recover the peer's first handshake flight when it arrived coalesced with the upgrade signal. A STARTTLS
                        // client writes its signal byte and its ClientHello back to back, so on a loopback the kernel can hand both
                        // to one `recv`; the consumer takes the whole chunk as "the signal" and discards the ClientHello bytes riding
                        // behind it. feedCoalescedHandshake re-feeds those bytes (everything from the chunk's first TLS record on) so
                        // the engine sees the ClientHello the handshake would otherwise wait for forever. A no-op when the last read
                        // was the bare signal or ordinary plaintext (no TLS record found). The last read is skipped when it is itself
                        // one of the staged spans (the ClientHello arrived in its own read and `feedStaged` already fed it), so it is
                        // never fed twice. It also cannot be fed twice against `PosixHandle.onInboundClosedDuringRead`'s salvage, which
                        // aliases the SAME chunk when the plaintext pump's channel-offer failed instead of landing it in `staged`:
                        // `feedCoalescedHandshake` claims `handle.lastPlaintextRead` before feeding, so exactly one of the two ever delivers it.
                        feedCoalescedHandshake(handle, engine, staged)
                        // upgradeActive stays set (it was armed before detach) all the way into driveHandshake. While it is set, awaitRead's
                        // single-recv gate drops every recv arm requested for this fd, so no NEW recv can register during the upgrade: the only recv
                        // that can be in flight is the genuine stale one the plaintext ReadPump armed before detach (kernel-owned, uncancellable). The
                        // no-stale-recv decision is therefore made later, ON THE REAP CARRIER, by the handshake's first read (driveUpgradeRead), whose
                        // hasInFlightRead scans both `pending` AND `stalledSubmits` authoritatively: the genuine stale recv is registered before
                        // driveUpgradeRead runs (FIFO on the reap carrier), in `pending` if its SQE went out or in `stalledSubmits` if it parked on a
                        // full SQ, and a stray ReadPump re-arm racing the upgrade was gated away and reached neither. Making the decision here on the
                        // upgrade carrier instead would be a TOCTOU (a hasInFlightRead snapshot could miss a not-yet-registered stale recv).
                        // On a poller the poll carrier is the standing producer for every upgrade read (driveUpgradeRead parks a waiter it fulfils via
                        // armUpgradeProducerRead), so upgradeActive stays set across the whole handshake; it is cleared at completion (onFinished below).
                        // Register this in-flight STARTTLS handshake so a transport `close()` racing it (no deadline exists on the upgrade path
                        // either) reclaims the fd/engine and fails `out` instead of leaking them / stranding the upgrade fiber past shutdown.
                        // `driveHandshake` guarantees exactly one of onFinished/onFailed/onPanic ever fires, so `handshakeDisarm` builds its own
                        // fresh gate, same as the connect-side registration above. See [[pendingHandshakes]].
                        //
                        // `reaped` mirrors the accept-side #243 guard and the connect-side registration above: the sweep below can free the
                        // engine while the handshake machine is still actively chaining (a `close()` racing a mid-flight upgrade wins
                        // `handshakeDisarm` and offers its free op into the engine FIFO; the machine's own next step thunk, already in flight,
                        // enqueues AFTER it). A FIFO honors submission order, so it cannot protect an op enqueued after the free: `reaped` is
                        // the guard `isReaped` reads inside driveHandshake's handshakeStep/feedCiphertext/awaitReadCiphertext thunks, set as the
                        // FIRST statement of every path here that frees the engine, so a step thunk that runs AFTER the free skips instead of
                        // touching freed native memory.
                        val reaped = AtomicBoolean.Unsafe.init(false)
                        val (handshakeToken, handshakeDisarm) = registerHandshake(() =>
                            handle.driver.submitEngineOp { () =>
                                reaped.set(true)
                                releaseFailedUpgrade(handle, engine)
                                out.completeDiscard(Result.fail(NetConnectionClosedException("handshake")))
                            }
                        )
                        // `out` is this upgrade's fd-and-engine owner. driveHandshake's three outcomes below each discharge the obligation
                        // themselves (they win `handshakeDisarm` and unregister), so this hook bites for exactly the settlements they do NOT
                        // cover: the caller's fiber interrupting the upgrade it was awaiting (Async.useResult links the awaiting task to `out`,
                        // so a timeout, a losing race arm, or an enclosing abort settles it), and the plaintext connection's close() routing
                        // through the `upgradeAbandon` thunk armed above. Both leave the handshake parked on a read nothing will ever complete,
                        // holding a detached fd that no other closer can reach: the connection's own closeFn cannot take an Upgrading fd, and
                        // sweepPendingHandshakes only runs from a transport close() the process-shared transport never makes. So the fd would stay
                        // open forever and, once the peer FINs, sit in CLOSE_WAIT with no shutdown. Discharging the registered obligation runs the
                        // identical release the shutdown sweep would.
                        //
                        // Installed AFTER registerHandshake so the obligation exists to discharge, and BEFORE driveHandshake so an `out` already
                        // settled by this point (an interrupt landing during the engine build above) fires the hook immediately: `reaped` is then
                        // set and driveHandshake's steps skip rather than touching a freed engine.
                        out.onComplete {
                            case Result.Success(_) => ()
                            case _                 => dischargePendingHandshake(handshakeToken)
                        }
                        // driveUpgradeRead's parked waiter fails with the transport's own typed leaf (a close raced the in-flight read): surface
                        // it directly rather than re-wrapping a transport failure as a handshake one. Any other cause is a genuine handshake
                        // failure (protocol error, engine throw), wrapped as NetTlsHandshakeException as before.
                        def completeUpgradeFailure(cause: HandshakeFailure | String | Throwable): Unit =
                            cause match
                                case netEx: NetException => out.completeDiscard(Result.fail(netEx))
                                case other =>
                                    val causeMsg: String | Throwable = other match
                                        case hf: HandshakeFailure.EngineThrew => hf.cause
                                        case hf: HandshakeFailure             => hf.toString
                                        case st: (String | Throwable)         => st
                                    out.completeDiscard(Result.fail(NetTlsHandshakeException(upgradeHost(tls, isServer), -1, causeMsg)))
                        driveHandshake(
                            handle,
                            engine,
                            onFinished = () =>
                                if handshakeDisarm() then
                                    unregisterHandshake(handshakeToken)
                                    // Clear the upgrade flags before attaching the engine and starting the pumps. upgradeActive/upgrading stay set for
                                    // the WHOLE upgrade on every backend (driveUpgradeRead never clears them itself; see its scaladoc), and a handshake
                                    // that completes purely from staged ciphertext (feedStaged / feedCoalescedHandshake) never reaches driveUpgradeRead
                                    // at all, so onFinished is the single clear point that covers every path. It runs on the I/O carrier, so this clear
                                    // happens-before the new ReadPump's recv is armed by upgraded.start(). handshakeReading is cleared here too so the
                                    // poller admits the upgraded connection's ReadPump read arm (the failure paths clear both via PosixHandle.close).
                                    // PosixHandle.isUpgraded is NOT cleared here (and never is): it must outlive this clear so the io_uring reap can
                                    // still recognize an orphaned handshakeOwned recv and route it correctly (see IoUringDriver.complete), and so
                                    // IoUringDriver.submitRecv can recognize the upgraded connection's first post-upgrade recv and queue it behind
                                    // any such orphan still in flight (see PosixHandle.queuedRecv) instead of racing it for the same staging buffer.
                                    // lastPlaintextRead is also cleared here (re-upgrade hygiene): a stale claim left over from THIS upgrade's
                                    // feedCoalescedHandshake/salvage race must not alias a future upgrade's own coalesced flight.
                                    handle.lastPlaintextRead.set(Absent)
                                    handle.upgradeActive = false
                                    handle.handshakeReading = false
                                    handle.tls = Present(engine)
                                    // Clear the durable upgrade-window marker AFTER tls becomes Present, so the io_uring reap never observes
                                    // upgrading=false while tls is still Absent (which would route a reaping recv to the raw plainReadComplete path).
                                    // Volatile-write ordering: a reaper that sees upgrading=false also sees tls=Present, so it takes the TLS branch.
                                    handle.upgrading = false
                                    // Track the upgraded connection under handle.id, replacing the now-detached plaintext entry on the same handle so
                                    // close() reclaims the TLS fd (detachForUpgrade left the plaintext connection registered: it keeps the fd open).
                                    val upgraded = InternalConnection.init(
                                        handle,
                                        handle.driver,
                                        channelCapacity,
                                        () => discard(connections.remove(handle.id.packed))
                                    )
                                    discard(connections.put(handle.id.packed, upgraded))
                                    // Wire the cert-hash and re-upgrade functions on the upgraded connection, exactly as completeConnect /
                                    // spawnHandler do for a directly-connected or accepted connection. Without this the TLS connection
                                    // produced by STARTTLS could not report its RFC 5929 channel-binding hash (certHashFn stays null ->
                                    // serverCertificateHash returns Absent). The re-upgrade function keeps the same role this upgrade ran in.
                                    wireUpgraded(upgraded, isServer)
                                    // Re-point the handle's inboundSink at the UPGRADED connection now (see PosixHandle.inboundSink), before anything
                                    // below can reap a late-arriving orphan recv that uses it: onFinished runs synchronously on the same carrier that
                                    // drains every completion for this handle (the reap carrier, for io_uring), so this write happens-before any
                                    // later completion on that same carrier observes it -- no separate synchronization needed.
                                    handle.inboundSink = bytes => discard(upgraded.inbound.offer(bytes))
                                    // Post-FINISHED slot drain: a peer flight (a TLS 1.3 NewSessionTicket, or any post-handshake record) can land in the
                                    // upgradeHandoff slot during the FINISHED transition with no parked waiter to consume it (the handshake stopped parking).
                                    // Feed it to the engine BEFORE deliverHandshakePlaintext so the engine's record sequence stays intact (an un-fed
                                    // post-FINISHED record desyncs the sequence and the next record fails to decrypt) and deliverHandshakePlaintext then
                                    // flushes any application bytes it produced. The posix analog of nio's drainUpgradeLeftover; a no-op when the slot is Idle.
                                    handle.upgradeHandoff.get() match
                                        case staged: PosixHandle.UpgradeHandoff.Carryover =>
                                            discard(handle.upgradeHandoff.compareAndSet(staged, PosixHandle.UpgradeHandoff.Idle))
                                            val drainBuf = Buffer.fromArray[Byte](staged.bytes)
                                            try discard(engine.feedCiphertext(drainBuf, staged.bytes.length))
                                            finally drainBuf.close()
                                        case _ => ()
                                    end match
                                    // Deliver any application plaintext the handshake already decrypted before the pumps start, so a record
                                    // that arrived with the handshake's final flight is not stranded in the engine (see completeConnect).
                                    deliverHandshakePlaintext(handle, upgraded.inbound)
                                    // Force the first ReadPump read to re-evaluate socket readiness: the peer's first application flight (e.g. the
                                    // STARTTLS echo) can arrive in the socket BEFORE upgraded.start() arms the read, and on epoll the register-once
                                    // re-arm skips the MOD so that buffered flight gets no new edge and the read strands. A no-op on every other backend.
                                    handle.driver.forceReadRecovery(handle)
                                    // Open the kqueue post-upgrade read window: a TLS 1.3 NewSessionTicket (or any post-handshake record) lands between
                                    // here and the echo, reads as 0 plaintext, and the bare re-arm trusts an EV_CLEAR edge for the echo that kqueue can
                                    // lose. While set, dispatchReadTls re-issues the kqueue read registration (EV_ADD re-evaluates) on a 0-plaintext
                                    // drained re-arm; it clears on the first application read. A no-op on epoll/io_uring (gated at the use site).
                                    handle.postUpgradeReadWindow = true
                                    // upgraded.start() arms the new ReadPump's first recv immediately: it does NOT wait for any handshake-window recv
                                    // still in flight for this handle (the orphaned-producer TOCTOU, see PosixHandle.isUpgraded) to drain first.
                                    // Blocking here instead (parking a waiter and deferring start()) was tried and reverted: it can deadlock, since
                                    // BOTH peers of an upgrade can symmetrically be waiting on their own orphan to drain while that orphan's bytes are
                                    // exactly the OTHER peer's first post-upgrade write, which peer's own (symmetrically blocked) upgrade fiber never
                                    // reaches (io_uring-only stress-tested: IoUringMutualTlsStressTest hung with a "stalled-at=upgrade" Timeout under
                                    // the blocking variant). The ordering invariant instead lives entirely in IoUringDriver.awaitRead/submitRecv: the
                                    // new ReadPump's first recv arm QUEUES behind a still-in-flight orphan (PosixHandle.queuedRecv) rather than racing
                                    // it for the same staging buffer, and fires the moment that orphan's CQE reaps -- non-blocking, no fiber parked.
                                    if upgraded.start() then
                                        // Checked complete, mirroring completeConnect: a caller interrupt or the plaintext connection's
                                        // close() can settle `out` after onFinished won `handshakeDisarm` above (the discharge hook then
                                        // loses the gate and releases nothing) and before this success delivery. Discarding the lost
                                        // completion would leave this fully started connection live but unreferenced on the never-swept
                                        // process-shared transport while the caller believes the fd was released; close the orphan.
                                        if !out.complete(Result.succeed(upgraded: Connection)) then
                                            upgraded.close()
                                    else
                                        // The upgraded connection raced to a terminal/Upgrading state before start (a close won); it must not be
                                        // handed out as open.
                                        out.completeDiscard(Result.fail(NetConnectionClosedException("start")))
                                    end if
                                end if
                            ,
                            onFailed = cause =>
                                if handshakeDisarm() then
                                    unregisterHandshake(handshakeToken)
                                    reaped.set(true)
                                    releaseFailedUpgrade(handle, engine)
                                    completeUpgradeFailure(cause),
                            onPanic = e =>
                                if handshakeDisarm() then
                                    unregisterHandshake(handshakeToken)
                                    reaped.set(true)
                                    releaseFailedUpgrade(handle, engine)
                                    out.completeDiscard(Result.panic(e)),
                            isReaped = () => reaped.get()
                        )
                end match
                // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different
                // from this plainly-constructed, invariant IOPromise[NetException, Connection], even though both erase to the same runtime
                // object; the alias is transparent only inside kyo.Fiber's own defining scope, so returning this promise as the locked
                // Transport.upgradeToTls result needs this erased-boundary cast. Safe: the promise is completed only with the
                // NetException/Connection values above.
                out.asInstanceOf[Fiber.Unsafe[Connection, Abort[NetException]]]
            case _ =>
                // Not an upgradable Posix connection (e.g. Connection.inMemory): abort loudly.
                Fiber.Unsafe.fromResult(Result.fail(NetNotUpgradableException()))
        end match
    end upgradeRole

    /** Release everything a FAILED STARTTLS upgrade strands. `detachForUpgrade` detached the plaintext connection (closing its channels and
      * cancelling its driver registration) but deliberately kept the fd OPEN for the upgrade to reuse, and the new engine was built but never
      * attached to `handle.tls` (onFinished never ran). So on the failure / panic path BOTH the fd and the engine leak unless released here.
      *
      * The new engine is known only to this upgrade (it is never attached to `handle.tls` on the failure path), so this is the sole place it can
      * be freed. It is routed through `handle.driver.submitEngineOp` rather than run inline: this can run on a promise-completion carrier (an
      * abandoned upgrade's discharge), which can race an in-flight feed thunk for this SAME engine still queued on the driver's engine FIFO
      * (the handshake's own next step, submitted before this failure/panic was observed); the FIFO serializes the free behind it instead of
      * the two carriers touching the native `ssl` at once. A concurrent `closeHandle` cannot free it (it finds `handle.tls` Absent), so there is
      * no double-free either way.
      *
      * The fd and the handle's buffers release through [[closeUnwiredHandle]], exactly like a failed connect-side handshake and never via a
      * bare `PosixHandle.close`: `driver.closeHandle` runs FIRST, so on io_uring the read-buffer free is deferred until every kernel-owned SQE
      * for this handle reaps. A failed upgrade routinely leaves such a SQE in flight (the stale plaintext pump recv the upgrade window cannot
      * cancel, or the handshake's own producer recv), and its SQE has already captured the buffer's address: an inline free would return that
      * off-heap memory (the read buffer, and the flush/send mirrors freeResources releases with it) to the process-wide allocator while the
      * kernel can still complete the old op into it, corrupting whatever connection reuses the memory. The real close(fd) is deferred to
      * freeResources via the fdCloseSink credit, installed after winning `handle.claimFdClose()` so the fd is closed exactly once across
      * every racing closer.
      */
    private def releaseFailedUpgrade(handle: PosixHandle, engine: TlsEngine)(using AllowUnsafe, Frame): Unit =
        // Re-upgrade hygiene: a claim this failed attempt left on lastPlaintextRead (won or not) must not alias a future upgrade attempt on
        // the same handle (a re-upgrade after a failed one reuses the fd and can reach feedCoalescedHandshake/the salvage again).
        handle.lastPlaintextRead.set(Absent)
        handle.driver.submitEngineOp(() => engine.free())
        closeUnwiredHandle(handle, handle.driver, connectPhase = false)
    end releaseFailedUpgrade

    /** The reference identity (and SNI host) for a STARTTLS upgrade engine. The `PosixHandle` is fd-only and no longer carries the original
      * connect host, so the client role draws it from `tls.sniHostname` (the only host the caller can supply at upgrade time). The provider
      * then decides what to do with it: a `trustAll` or `hostnameVerification = false` client does not check it, but a verifying client with
      * an empty identity FAILS CLOSED at `buildEngine` time rather than handshaking with no name bound (RFC 9525 §6.1). A verifying STARTTLS
      * client must therefore set `sniHostname`; this is the intended behavior change that surfaces a previously-masked insecure config
      * (STARTTLS-without-SNI verifying clients now fail at the upgrade instead of silently accepting any chain-valid cert). The server role
      * takes accept state with no SNI, so its host is always empty and never fails closed.
      */
    private def upgradeHost(tls: NetTlsConfig, isServer: Boolean): String =
        if isServer then "" else tls.sniHostname.getOrElse("")

    /** Wire the cert-hash and re-upgrade functions on a STARTTLS-upgraded connection, mirroring [[completeConnect]] (client) and `spawnHandler`
      * (server). `certHashFn` exposes the engine's RFC 5929 channel-binding token now that the handle is TLS; `upgradeFn` keeps the same role
      * the connection was upgraded in, so a further upgrade does not silently flip client/server.
      */
    private def wireUpgraded(upgraded: InternalConnection[PosixHandle], isServer: Boolean)(using AllowUnsafe, Frame): Unit =
        upgraded.isServerOrigin = isServer
        upgraded.upgradeFn = Present { (cfg, frame) =>
            given Frame = frame
            upgradeToTls(upgraded, cfg, config.channelCapacity)
        }
        installCertHash(upgraded, upgraded.handle)
    end wireUpgraded

    /** Feed each staged ciphertext span into the engine's read side. No byte is dropped. */
    private def feedStaged(engine: TlsEngine, staged: Chunk[Span[Byte]])(using AllowUnsafe): Unit =
        staged.foreach { sp =>
            val arr = sp.toArrayUnsafe
            if arr.length > 0 then
                val buf = Buffer.fromArray[Byte](arr)
                try discard(engine.feedCiphertext(buf, arr.length))
                finally buf.close()
            end if
        }

    /** If the connection's last plaintext read carried the peer's first TLS handshake flight behind the upgrade signal, feed those bytes into
      * the engine. The chunk is `[signal][TLS record ...]`; the signal is non-TLS (a byte, a text command, a length-prefixed request), so the
      * handshake starts at the first byte that opens a well-formed TLS record. Only handshake (`0x16`) records are accepted, with a TLS major
      * version (`0x03`), a minor in `0x00..0x04`, and a length within a record's 16 KB ceiling, so an ordinary plaintext tail cannot be
      * mistaken for ciphertext. When no such record is found (the common case: the read was just the bare signal) nothing is fed.
      */
    private def feedCoalescedHandshake(handle: PosixHandle, engine: TlsEngine, staged: Chunk[Span[Byte]])(using AllowUnsafe): Unit =
        handle.lastPlaintextRead.get() match
            case Absent           => ()
            case p @ Present(arr) =>
                // If this exact buffer is one of the staged (unconsumed) spans, `feedStaged` already fed it: the handshake flight arrived in its
                // own read and sits in the channel, not behind a consumed signal. Re-feeding it would hand the engine the same ClientHello twice
                // and corrupt the handshake. The pump delivers the read buffer by reference, so identity (`eq`) distinguishes the two cases.
                val alreadyStaged = staged.exists(_.toArrayUnsafe eq arr)
                if !alreadyStaged then
                    val start = tlsRecordStart(arr)
                    if start >= 0 && start < arr.length then
                        // One-shot claim: PosixHandle.onInboundClosedDuringRead's salvage aliases this SAME array when the plaintext pump's
                        // channel-offer for this chunk failed (a channel close raced the offer). Winning the CAS makes this the sole feeder;
                        // losing it means the salvage already claimed (and is feeding, or will feed) this exact chunk, so skip here instead
                        // of handing the engine the same handshake record twice.
                        if handle.lastPlaintextRead.compareAndSet(p, Absent) then
                            val tail = java.util.Arrays.copyOfRange(arr, start, arr.length)
                            val buf  = Buffer.fromArray[Byte](tail)
                            try discard(engine.feedCiphertext(buf, tail.length))
                            finally buf.close()
                        end if
                    end if
                end if
        end match
    end feedCoalescedHandshake

    /** Index of the first byte in `arr` that opens a TLS handshake record (`0x16`, major version `0x03`, minor `0x00..0x04`, fragment length
      * `<= 16384`), or `-1` if none. Identifies where a coalesced ClientHello/ServerHello begins after a STARTTLS signal prefix.
      */
    private def tlsRecordStart(arr: Array[Byte]): Int =
        val limit = arr.length - 5 // a record needs at least its 5-byte header
        var i     = 0
        var found = -1
        while found < 0 && i <= limit do
            val contentType = arr(i) & 0xff
            val major       = arr(i + 1) & 0xff
            val minor       = arr(i + 2) & 0xff
            val length      = ((arr(i + 3) & 0xff) << 8) | (arr(i + 4) & 0xff)
            if contentType == 0x16 && major == 0x03 && minor <= 0x04 && length > 0 && length <= 16384 then found = i
            i += 1
        end while
        found
    end tlsRecordStart

    /** Drive the TLS handshake over the handle's fd using a synchronous `step()` state machine with `onComplete` continuations for I/O.
      * Each engine call (handshakeStep, drainCiphertext, feedCiphertext) runs inside a submitEngineOp thunk so it is serialized against
      * the read and write pumps on the same engine. The recv/send syscalls and the awaitReadCiphertext/awaitWritable continuations run
      * outside the thunk. Each step submits ONE coarse-grained thunk covering all engine ops for that step; the continuation (which may
      * re-submit a new thunk for the next step) is called after the FIFO worker finishes the current thunk, avoiding reentrancy.
      * The engine consumes any staged ciphertext first (fed before this runs), so a server whose ClientHello was already staged
      * completes without an extra read. All I/O suspension points (EAGAIN on write, EAGAIN on read) arm a driver promise and return;
      * the driver carrier fires the promise's `onComplete` continuation to resume the handshake.
      *
      * `isReaped` is the reap guard for the server-accept handshake deadline (finding #243): when a finite `handshakeTimeout` reaps a
      * stalled handshake it frees the handshake engine, so any engine-touching thunk that runs AFTER the reap (an in-flight recv CQE that
      * delivered just before the deadline and enqueued a feed) MUST skip rather than touch a freed engine. The guard is checked at the top of
      * every `submitEngineOp` thunk the handshake submits (`step`, and the feed thunks in `recvAndFeed` / `awaitReadCiphertext`); the reap and
      * these thunks all run on the one per-driver engine FIFO worker, so the flag write in the reap thunk is serialized against these reads.
      * The connect and STARTTLS handshakes arm no deadline and pass `() => false` (never reaped).
      */
    private[posix] def driveHandshake(
        handle: PosixHandle,
        engine: TlsEngine,
        onFinished: () => Unit,
        onFailed: (HandshakeFailure | String | Throwable) => Unit,
        onPanic: Throwable => Unit,
        isReaped: () => Boolean = () => false
    )(using AllowUnsafe, Frame): Unit =
        // Adapter for helpers that carry the narrower (String | Throwable) onFailed type.
        // HandshakeFailure-typed failures (EngineError, EngineThrew) are produced only inside this
        // method's own match arms and catch; the helpers only ever call onFailed with String or
        // Throwable values (send errors, recv errors, EOF), so the adapter correctly routes them.
        val onFailedStr: (String | Throwable) => Unit = st => onFailed(st)
        def step(): Unit =
            // Submit one coarse-grained thunk: run handshakeStep + drainCiphertext inside the FIFO worker so no concurrent
            // read or write op can touch the engine during this step. The send and recv calls stay outside the thunk.
            handle.driver.submitEngineOp { () =>
                // Reap guard: a deadline reap that ran ahead of this thunk on the FIFO worker has already freed the engine; skip rather
                // than call handshakeStep on freed native state (the disarm guard already ensured onFinished / onFailed will not fire).
                if isReaped() then ()
                else
                    try
                        val hsState = HandshakeState.fromCode(engine.handshakeStep())
                        hsState match
                            case HandshakeState.Done =>
                                // Handshake complete: drain any final ciphertext (engine op, inside thunk), then call onFinished (outside thunk).
                                drainAllDirect(handle, engine, cont = onFinished, onFailedStr, onPanic)
                            case HandshakeState.Failed(reason) =>
                                // Fatal alert (RFC 5246 7.2 / RFC 8446 6.2): drain and send the alert record the engine queued (e.g. protocol_version,
                                // bad_certificate) so the peer learns the failure reason before the fd closes, THEN fail. Every other arm (done,
                                // want-write, want-read) already drains; this arm did not, dropping the alert.
                                //
                                // openssl returns the fatal -2 from the first SSL_do_handshake but does not flush the queued alert into the write BIO
                                // until a SECOND SSL_do_handshake call, so re-step once (it returns -2 again, the handshake does not otherwise proceed)
                                // to force the flush before draining. BoringSSL already flushed on the first call, so the re-step adds no new bytes there.
                                // drainAllDirect is bounded (one drain+send pass on the closing handshake) and routes a send failure to onFailed as well,
                                // so the handshake always terminates.
                                discard(engine.handshakeStep())
                                drainAllDirect(
                                    handle,
                                    engine,
                                    cont = () => onFailed(reason),
                                    onFailedStr,
                                    onPanic
                                )
                            case HandshakeState.WantWrite =>
                                // Ciphertext queued: drain it (engine op, inside thunk), send it (syscall, outside thunk via sendAll), then re-step.
                                drainAllDirect(handle, engine, cont = step, onFailedStr, onPanic)
                            case HandshakeState.WantRead =>
                                // Need more peer ciphertext: drain any output first, then probe recv and maybe arm read (both outside thunk).
                                drainAllDirect(
                                    handle,
                                    engine,
                                    cont = () => recvAndFeed(handle, engine, step, onFailedStr, onPanic, isReaped),
                                    onFailedStr,
                                    onPanic
                                )
                        end match
                    catch
                        // Throwable (not just NonFatal) because JDK SSLEngine.handshakeStep raises
                        // SSLHandshakeException on a received fatal alert, which is a RuntimeException
                        // and therefore NonFatal. Fatal JVM errors are rethrown by onFailed's caller
                        // (the engine FIFO outer catch) so omitting NonFatal here is safe.
                        case e: Throwable => onFailed(HandshakeFailure.EngineThrew(e))
                    end try
                end if
            }
        end step
        try step()
        catch case e: Throwable => onPanic(e)
        end try
    end driveHandshake

    /** Drain all outbound ciphertext the engine has queued and send it. Called from INSIDE a submitEngineOp thunk, so calls drainCiphertext
      * directly (no nested submitEngineOp: that would enqueue a new entry to the FIFO but the current thunk still holds the worker; the
      * @tailrec drainEngineOps picks it up next, but only AFTER this thunk returns, so there is no reentrancy deadlock as long as this method
      * does not wait for that next entry). The send call (sendAll) happens outside the FIFO thunk on the continuation. When nothing is drained,
      * calls cont() immediately.
      */
    private def drainAllDirect(
        handle: PosixHandle,
        engine: TlsEngine,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit
    )(using AllowUnsafe, Frame): Unit =
        try
            val out = Buffer.alloc[Byte](handle.readBufferSize)
            try
                val n = engine.drainCiphertext(out, handle.readBufferSize)
                if n <= 0 then cont()
                else
                    val arr = Span.fromUnsafe(Buffer.copyToArray[Byte](out, 0, n))
                    // sendAll calls the socket send (a syscall) + may arm awaitWritable: both stay outside the engine thunk.
                    sendAll(handle, arr, 0, cont = () => drainAllDirect(handle, engine, cont, onFailed, onPanic), onFailed, onPanic)
                end if
            finally out.close()
            end try
        catch case e: Throwable => onPanic(e)
        end try
    end drainAllDirect

    /** Send a full span over the socket starting at `offset`. On a partial write, arms write interest via [[awaitWritable]] and resumes
      * via `onComplete` with the advanced offset.
      */
    private def sendAll(
        handle: PosixHandle,
        data: Span[Byte],
        offset: Int,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit
    )(using AllowUnsafe, Frame): Unit =
        try
            handle.driver.write(handle, data, offset) match
                case WriteResult.Done  => cont()
                case WriteResult.Error => onFailed(NetConnectionClosedException("send"))
                case WriteResult.Partial(rem, newOffset) =>
                    awaitWritable(handle, cont = () => sendAll(handle, rem, newOffset, cont, onFailed, onPanic), onFailed, onPanic)
                case WriteResult.TailPartial(rem, newOffset) =>
                    awaitWritable(handle, cont = () => sendAll(handle, rem, newOffset, cont, onFailed, onPanic), onFailed, onPanic)
        catch
            // Contain ANY throw (not just NonFatal): a driver-carrier throw is routed to onPanic,
            // never allowed to escape the carrier (a carrier escape would abort the process or stall
            // the event loop). See the listen-handler containment pattern at listenImpl ~:972-974.
            case e: Throwable => onPanic(e)
        end try
    end sendAll

    /** Arm write interest on the driver; the `cont` callback fires from the driver carrier when the socket becomes writable. */
    private def awaitWritable(
        handle: PosixHandle,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit
    )(using AllowUnsafe, Frame): Unit =
        val writablePromise = new IOPromise[Closed, Unit]
        writablePromise.onComplete {
            case Result.Success(_)      => cont()
            case Result.Failure(closed) => onFailed(closed)
            case Result.Panic(e)        => onPanic(e)
        }
        // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Closed, Unit], even though both erase to the same runtime object; the alias is transparent only
        // inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitWritable's fixed Promise.Unsafe-typed parameter needs this
        // erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/Unit values above, never a
        // suspended computation.
        handle.driver.awaitWritable(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
    end awaitWritable

    private def isWouldBlock(errno: Int): Boolean =
        errno == PosixConstants.EAGAIN || errno == PosixConstants.EWOULDBLOCK

    /** Read one chunk of ciphertext from the socket and feed it into the engine. An empty read (EOF) calls `onFailed`.
      *
      * Tries one synchronous `recvNow` first, with `MSG_DONTWAIT`: if the peer's next flight is already in the socket buffer it
      * is fed straight away. `MSG_DONTWAIT` keeps this probe non-blocking even if the fd is momentarily blocking, so it never
      * stalls the handshake fiber inline (a freshly accepted fd is blocking by default; `accept` does not inherit `O_NONBLOCK`).
      * Only when the buffer is empty (`EAGAIN`) does it fall back to [[awaitReadCiphertext]], which arms read interest and
      * resumes via `onComplete` when the poller delivers the flight. The feedCiphertext call goes through submitEngineOp so it is
      * serialized against concurrent read and write ops on the same engine.
      */
    private def recvAndFeed(
        handle: PosixHandle,
        engine: TlsEngine,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit,
        isReaped: () => Boolean
    )(using AllowUnsafe, Frame): Unit =
        // Route the handshake's ciphertext read. During a STARTTLS upgrade (upgradeActive) the read is confined to the I/O carrier on EVERY backend,
        // so the handshake fiber never issues its own recv that could race the I/O carrier's read of the same fd: io_uring consumes its kernel-owned
        // stale recv through the handoff slot, and the readiness pollers make the poll carrier the sole reader (driveUpgradeRead parks a waiter the poll
        // carrier fulfils via armUpgradeProducerRead). Outside an upgrade, a poller reads synchronously (recvNow) and io_uring arms a recv.
        if handle.upgradeActive then driveUpgradeRead(handle, engine, cont, onFailed, onPanic, isReaped)
        else if handle.driver.inlineRecvSafe then recvNowAndFeed(handle, engine, cont, onFailed, onPanic, isReaped)
        else awaitReadCiphertext(handle, engine, cont, onFailed, onPanic, isReaped)
        end if
    end recvAndFeed

    /** Feed `arr` (ciphertext read off the socket) into the handshake engine on the engine FIFO, then continue the handshake. Shared by the
      * recvNow path and the STARTTLS upgrade carry-over path. The `isReaped` guard skips the feed if a deadline reap freed the engine first (#243).
      */
    private def feedCiphertextThenCont(
        handle: PosixHandle,
        engine: TlsEngine,
        arr: Array[Byte],
        cont: () => Unit,
        onPanic: Throwable => Unit,
        isReaped: () => Boolean
    )(using AllowUnsafe, Frame): Unit =
        handle.driver.submitEngineOp { () =>
            if isReaped() then ()
            else
                try
                    val buf = Buffer.fromArray[Byte](arr)
                    try discard(engine.feedCiphertext(buf, arr.length))
                    finally buf.close()
                    cont()
                catch
                    // Contain ANY throw (not just NonFatal): a driver-carrier throw is routed to onPanic,
                    // never allowed to escape the carrier (a carrier escape would abort the process or stall
                    // the event loop). See the listen-handler containment pattern at listenImpl ~:972-974.
                    case e: Throwable => onPanic(e)
                end try
        }
    end feedCiphertextThenCont

    /** The STARTTLS upgrade read path, confining every ciphertext recv to the I/O carrier so the handshake fiber never races it. The handshake
      * consumes bytes the I/O carrier delivered through the handle's [[PosixHandle.upgradeHandoff]] slot, or parks a fiber waiter the I/O carrier
      * fulfils, and never issues its own recv. On io_uring the producer is the reap carrier delivering each kernel-owned recv it routes here
      * ([[IoUringDriver]]'s `complete` upgrade branch); on a readiness poller the producer is the poll carrier, armed via
      * [[IoDriver.armUpgradeProducerRead]], reading each peer flight. [[PosixHandle.upgradeActive]] stays set across the WHOLE upgrade on both
      * backends (the poll carrier is the standing producer for every read on a poller; io_uring may have more than one recv in flight across the
      * handshake, see the comment below), cleared only at completion (`onFinished`, see `driveHandshake`).
      */
    private def driveUpgradeRead(
        handle: PosixHandle,
        engine: TlsEngine,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit,
        isReaped: () => Boolean
    )(using AllowUnsafe, Frame): Unit =
        import PosixHandle.UpgradeHandoff

        // On io_uring the plaintext ReadPump leaves stale recv(s) kernel-owned across the detach; clear upgradeActive only once the LAST has been
        // consumed (no recv remains in flight for this handle), so EVERY stale recv routes through upgradeHandoff in order. The detach-vs-rearm race
        // can leave MORE THAN ONE stale recv (the kernel-owned one whose promise the detach cancelled PLUS a fresh one the pump re-armed before the
        // upgrade flag was observed, see IoUringDriver.awaitRead's note): clearing after just the first (the old "exactly one stale recv" assumption)
        // let the second reap at upgradeActive=false and deliver its handshake bytes to the detached ReadPump promise via complete()'s plaintext
        // branch, dropping a chunk of the flight and failing the handshake with an EngineError under repeated/concurrent upgrades. The hasInFlightRead
        // scan is authoritative on the reap carrier (where this runs), serialized with every recv registration and reap. A poller's poll carrier is the
        // standing producer for EVERY handshake read, so upgradeActive must stay set across the whole upgrade (cleared at completion in driveHandshake's
        // onFinished); the clear is gated on the backend (only the kernel-owned-stale-recv backend clears it here).
        // Claim staged bytes the I/O carrier delivered: swing the slot to Idle and feed them to the engine. Single-source: upgradeActive stays set for
        // the whole upgrade (cleared only at onFinished), so every recv -- the kernel-owned stale one AND the handshake's own producer recv -- routes
        // through the upgradeHandoff slot (the reap's upgradeActive branch). It is NOT cleared mid-handshake: clearing it early reopened a second read
        // source (the handshake's handshakeOwned recv then bypassed the slot and fed the engine directly), which orphaned a staged Carryover under
        // contention (the io_uring upgrade strand).
        def consume(arr: Array[Byte]): Unit =
            feedCiphertextThenCont(handle, engine, arr, cont, onPanic, isReaped)

        // Build the fiber-parking waiter the I/O carrier fulfils when it delivers a peer flight. Parking suspends a fiber, never a thread.
        // The promise's error channel is NetException (PosixHandle.close's :744 completer fails it with NetConnectionClosedException, never
        // Closed): constructing it as anything else here would be a lie the compiler cannot catch (IOPromise's type parameter is erased at
        // the completion boundary), so it must match what actually completes the promise.
        val waiter =
            val p = new IOPromise[NetException, Span[Byte]]
            p.onComplete {
                case Result.Success(bytes) =>
                    // An empty read is EOF mid-handshake: the peer closed before completing it. Surface that as the failure cause (a typed
                    // NetConnectionClosedException("read")) so a bare close is distinguishable from a received fatal alert (which carries its own
                    // engine-level failure, never this leaf): the dropped-alert symptom PosixTransportHandshakeAlertTest guards against.
                    if bytes.isEmpty then onFailed(NetConnectionClosedException("read"))
                    else feedCiphertextThenCont(handle, engine, bytes.toArrayUnsafe, cont, onPanic, isReaped)
                case Result.Failure(netEx) =>
                    onFailed(netEx)
                case Result.Panic(e) =>
                    onPanic(e)
            }
            // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
            // plainly-constructed IOPromise[NetException, Span[Byte]], even though both erase to the same runtime object; the alias is
            // transparent only inside kyo.Fiber.Promise's own defining scope, so storing this promise in the Waiter's fixed
            // Promise.Unsafe-typed field needs this erased-boundary cast. Safe: the promise is completed only with the plain
            // NetException/Span[Byte] values above, never a suspended computation.
            UpgradeHandoff.Waiter(p.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[NetException]]], summon[Frame])
        end waiter

        handle.upgradeHandoff.get() match
            case staged: UpgradeHandoff.Carryover =>
                // The I/O carrier already staged the bytes; claim them by CAS to Idle against the exact instance read (reference equality, since each
                // delivery stages once this cannot lose to another) and consume.
                discard(handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Idle))
                consume(staged.bytes)
            case _ if handle.driver.inlineRecvSafe =>
                // Poller: the poll carrier is the sole reader. Park the waiter, then arm the poll-carrier producer (armUpgradeProducerRead) which reads
                // the next peer flight and fulfils this waiter. The handshake never reads the socket itself, so it cannot race the poll carrier's recv.
                // A CAS-loss means the producer staged a Carryover between the get above and this CAS (a missed-edge re-dispatch ran ahead of the park);
                // a single re-read consumes it, never spun on.
                if handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, waiter) then
                    handle.driver.armUpgradeProducerRead(handle)
                else
                    handle.upgradeHandoff.get() match
                        case staged: UpgradeHandoff.Carryover =>
                            discard(handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Idle))
                            consume(staged.bytes)
                        case _ => ()
                end if
            case _ if !handle.driver.hasInFlightRead(handle) =>
                // io_uring, no bytes staged AND no stale recv kernel-owned for this fd: arm the handshake's OWN producer recv through the slot (single
                // source). upgradeActive stays SET, so the reap routes that recv's CQE to the upgradeHandoff slot and fulfils this parked waiter, exactly
                // as a stale recv would; the handshake never feeds a recv promise directly, so there is no second read source to orphan a staged Carryover
                // (the dual-source upgrade strand the earlier clear-and-awaitReadCiphertext path created). Same shape as the poller branch above.
                if handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, waiter) then
                    handle.driver.armUpgradeProducerRead(handle)
                else
                    handle.upgradeHandoff.get() match
                        case staged: UpgradeHandoff.Carryover =>
                            discard(handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Idle))
                            consume(staged.bytes)
                        case _ => ()
                end if
            case _ =>
                // A stale recv is in flight (no carryover yet): park the waiter for its CQE to fulfil. Both the stale recv's reap and this run on the
                // reap carrier, so the staging cannot interleave mid-park; the CAS-loss re-read stays as a defensive belt for any future cross-carrier
                // staging path (a Carryover that appeared between the get above and this CAS is consumed by a single re-read, never spun on).
                if !handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, waiter) then
                    handle.upgradeHandoff.get() match
                        case staged: UpgradeHandoff.Carryover =>
                            discard(handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Idle))
                            consume(staged.bytes)
                        case _ => ()
                end if
        end match
    end driveUpgradeRead

    private def recvNowAndFeed(
        handle: PosixHandle,
        engine: TlsEngine,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit,
        isReaped: () => Boolean
    )(using AllowUnsafe, Frame): Unit =
        try
            val result = sockets.recvNow(handle.readFd, handle.readBuffer, handle.readBufferSize.toLong, PosixConstants.MSG_DONTWAIT)
            val n      = result.value.toInt
            if n > 0 then
                val cipherArr = Buffer.copyToArray[Byte](handle.readBuffer, 0, n)
                // feedCiphertext is an engine op: submit it through the FIFO so it is serialized against concurrent ops.
                // The continuation (cont) is called from inside the FIFO thunk after the feed; cont itself may re-enter step(),
                // which submits a NEW submitEngineOp and returns (no reentrancy: the inner submit enqueues, this thunk returns first).
                handle.driver.submitEngineOp { () =>
                    // Reap guard (#243): a deadline reap that ran ahead of this thunk on the FIFO worker freed the engine; skip the feed.
                    if isReaped() then ()
                    else
                        try
                            val buf = Buffer.fromArray[Byte](cipherArr)
                            try discard(engine.feedCiphertext(buf, n))
                            finally buf.close()
                            cont()
                        catch
                            // Contain ANY throw (not just NonFatal): a driver-carrier throw is routed to onPanic,
                            // never allowed to escape the carrier (a carrier escape would abort the process or stall
                            // the event loop). See the listen-handler containment pattern at listenImpl ~:972-974.
                            case e: Throwable => onPanic(e)
                        end try
                }
            else if n == 0 then
                // EOF mid-handshake: the peer closed before completing it. The typed leaf distinguishes a bare close from a received fatal
                // alert (see awaitReadCiphertext / PosixTransportHandshakeAlertTest).
                onFailed(NetConnectionClosedException("read"))
            else if isWouldBlock(result.errorCode) then
                awaitReadCiphertext(handle, engine, cont, onFailed, onPanic, isReaped)
            else
                onFailed(new NetErrno(result.errorCode))
            end if
        catch case e: Throwable => onPanic(e)
        end try
    end recvNowAndFeed

    /** Arm read interest on the driver; when the poller delivers ciphertext the `cont` callback fires from the driver carrier. The
      * feedCiphertext call goes through submitEngineOp so it is serialized against concurrent ops on the same engine.
      *
      * This is the park point a stalled server handshake reaches (finding #243): the io_uring `awaitRead` submits an in-flight recv SQE
      * into `handle.readBuffer`. If a finite `handshakeTimeout` reaps the handshake while this is parked, the reap routes through
      * `ioDriver.closeHandle` (which `cancel`s `readPromise`, so the Success branch below cannot fire post-reap, and defers the buffer/fd
      * free until the recv CQE drains). A recv CQE that delivered Success just before the reap may still have enqueued the feed thunk below;
      * the `isReaped` guard makes that thunk skip rather than feed a freed engine.
      */
    private def awaitReadCiphertext(
        handle: PosixHandle,
        engine: TlsEngine,
        cont: () => Unit,
        onFailed: (NetException | Throwable) => Unit,
        onPanic: Throwable => Unit,
        isReaped: () => Boolean
    )(using AllowUnsafe, Frame): Unit =
        val readPromise = new IOPromise[Closed, ReadOutcome]
        readPromise.onComplete {
            case Result.Success(outcome) =>
                outcome match
                    case ReadOutcome.Bytes(bytes) =>
                        val arr = bytes.toArrayUnsafe
                        // feedCiphertext is an engine op: submit through the FIFO. The continuation runs inside the FIFO thunk.
                        handle.driver.submitEngineOp { () =>
                            // Reap guard (#243): a deadline reap that ran ahead of this thunk on the FIFO worker freed the engine; skip the feed.
                            if isReaped() then ()
                            else
                                try
                                    val buf = Buffer.fromArray[Byte](arr)
                                    try discard(engine.feedCiphertext(buf, arr.length))
                                    finally buf.close()
                                    cont()
                                catch
                                    // Contain ANY throw (not just NonFatal): a driver-carrier throw is routed to onPanic,
                                    // never allowed to escape the carrier (a carrier escape would abort the process or stall
                                    // the event loop). See the listen-handler containment pattern at listenImpl ~:972-974.
                                    case e: Throwable => onPanic(e)
                                end try
                        }
                    case _ =>
                        // PeerFin, CleanClose, LocalShutdown, WouldBlock, Failed: the peer closed or the read could not deliver ciphertext.
                        // The typed leaf distinguishes a bare close from a received fatal alert (which fails the handshake with its
                        // own engine-level cause, never this leaf): the dropped-alert symptom PosixTransportHandshakeAlertTest guards against.
                        onFailed(NetConnectionClosedException("read"))
                end match
            case Result.Failure(closed) => onFailed(closed)
            case Result.Panic(e)        => onPanic(e)
        }
        // STARTTLS poller confinement: this is the handshake taking read ownership from the retiring plaintext pump. Mark handshakeReading so the poll
        // carrier admits this read arm (PollerIoDriver) while still rejecting the pump's stray re-arm. Gated on upgradeActive so it is a no-op on a
        // fresh (non-upgrade) handshake; during a STARTTLS upgrade recvAndFeed always routes io_uring to driveUpgradeRead instead (upgradeActive
        // stays set on every backend until onFinished, never cleared by driveUpgradeRead itself), so io_uring never reaches this arm with
        // upgradeActive true either.
        if handle.upgradeActive then handle.handshakeReading = true
        // awaitReadHandshake (not awaitRead): on io_uring this tags the recv handshakeOwned so the reap exempts the handshake's own ciphertext read
        // from the upgrade-window stale-recv handoff routing (a non-handshake recv reaping while `upgrading` is set is the stray pump recv); the
        // pollers route it identically to awaitRead.
        // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Closed, ReadOutcome], even though both erase to the same runtime object; the alias is transparent
        // only inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitReadHandshake's fixed Promise.Unsafe-typed parameter needs
        // this erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/ReadOutcome values above,
        // never a suspended computation.
        handle.driver.awaitReadHandshake(handle, readPromise.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
    end awaitReadCiphertext

end PosixTransport

private[net] object PosixTransport:

    /** Outcome of draining the listen-fd backlog via `acceptNow` in one readiness cycle, telling the accept loop how to re-arm.
      *
      *   - [[AcceptDrain.Drained]]: the backlog is empty (`EAGAIN`/`EWOULDBLOCK`), or the cycle ended on a closed listener / spent transient
      *     budget / unclassified errno. Re-arm read interest immediately, as the accept loop always did.
      *   - [[AcceptDrain.ResourceExhausted]]: `accept` failed with `EMFILE`/`ENFILE`. The kernel did NOT dequeue the pending connection, so the
      *     listen fd stays read-ready; an immediate re-arm would re-fire the same error in a tight CPU spin. Re-arm after a bounded backoff.
      */
    private enum AcceptDrain derives CanEqual:
        case Drained
        case ResourceExhausted

    /** Build the production transport over the given `pool`. The representative driver (pool.next() before any connection is opened, i.e.
      * drivers(0)) backs stdio and the backend-label queries. Loads the real socket bindings and detects whether the active poller backend
      * is epoll (the regular-file fallback's gate; true only on Linux when epoll, not io_uring, is selected).
      */
    def init(config: kyo.net.TransportConfig, pool: IoDriverPool[PosixHandle])(using AllowUnsafe): PosixTransport =
        val representative = pool.next()
        init(config, pool, representative, Ffi.load[SocketBindings], backendIsEpoll(representative))

    /** Build a transport over a caller-supplied pool, representative driver, socket bindings, and epoll flag, allocating the transport's unsafe
      * fields under the caller's `AllowUnsafe`: the construction site propagates the capability rather than each field bridging it. Shared by
      * [[init]] and the test construction helper so the unsafe allocation lives in one place.
      */
    private[posix] def init(
        config: kyo.net.TransportConfig,
        pool: IoDriverPool[PosixHandle],
        representative: IoDriver[PosixHandle],
        sockets: SocketBindings,
        backendIsEpoll: Boolean
    )(using AllowUnsafe): PosixTransport =
        new PosixTransport(
            config = config,
            pool = pool,
            representative = representative,
            sockets = sockets,
            backendIsEpoll = backendIsEpoll,
            stdioClaimed = AtomicBoolean.Unsafe.init(false),
            acceptLoopsActive = AtomicLong.Unsafe.init(0)
        )

    /** True when `ioDriver` is the readiness poller AND that poller is epoll (the only backend the regular-file fallback applies to). io_uring's driver has
      * a different label, and kqueue is selected on macOS/BSD, so both correctly report false.
      */
    private def backendIsEpoll(ioDriver: IoDriver[PosixHandle])(using AllowUnsafe): Boolean =
        ioDriver.label == "PollerIoDriver" && (PollerBackend.default() eq EpollPollerBackend)

end PosixTransport

/** Active server-side listener over a bound `PosixHandle` socket fd.
  *
  * Holds the listen fd, the resolved port (`-1` for Unix sockets), the bind host, and the [[NetAddress]]. `close()` flips a CAS-guarded flag,
  * deregisters the accept interest from the driver, and closes the listen fd. The deregister runs BEFORE the fd close: it clears the driver's
  * `pendingAccepts` / `activeFds` entries for this listen fd (and removes the poller interest while the fd is still open, so EV_DELETE / epoll_ctl
  * DEL land on a live fd). Without it, a closed listen fd's stale `pendingAccepts` entry survives; when the OS recycles that fd number for a new
  * CLIENT connection, the recycled fd's read-readiness is routed to the stale accept dispatch instead of the connection's ReadPump, and the
  * connection's read never completes (the lost-wakeup hang). The close also removes itself from the owning transport's listener registry so a
  * transport shutdown does not try to close it twice. Idempotent: a second `close()` is a no-op.
  */
final private[net] class PosixListener(
    private[posix] val serverFd: Int,
    val port: Int,
    val host: String,
    val address: NetAddress,
    private val sockets: SocketBindings,
    private val registry: java.util.Set[PosixListener],
    // CAS-guarded close flag: close() flips it so a second close() is a no-op (idempotent listener teardown).
    closedFlag: AtomicBoolean.Unsafe
) extends ListenerImpl:

    /** Tears down this listener's accept interest AND closes its fd, sequenced through the driver ([[kyo.net.internal.transport.IoDriver.closeListener]]).
      * Installed by the accept loop in `startAcceptLoop` (it holds the listen `handle` and `driver`); invoked by `close()`, which then must NOT
      * close the fd itself (the teardown owns the shutdown + close, ordered so a queued accept arm can never run against a recycled fd number).
      * `Absent` until the accept loop wires it.
      */
    @volatile private var teardownAccept: Maybe[() => Unit] = Absent

    private[posix] def onClose(f: () => Unit): Unit = teardownAccept = Present(f)

    def isClosed(using AllowUnsafe): Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            discard(registry.remove(this))
            teardownAccept match
                case Present(teardown) =>
                    // The accept loop's driver-sequenced teardown owns the whole close: it cancels the accept interest while the fd is still
                    // open (a stale fd-keyed accept entry outliving the fd would misroute a recycled fd's events; on io_uring an accept arm
                    // still queued behind it would otherwise prep against the recycled fd), then shuts the socket down (the wake a blocked /
                    // armed accept needs on Linux; close() alone does not reliably interrupt it) and closes the fd.
                    teardown()
                case Absent =>
                    // No accept loop ever wired (listenImpl failed before startAcceptLoop): nothing is registered anywhere, close directly.
                    discard(sockets.shutdown(serverFd, PosixConstants.SHUT_RDWR))
                    discard(sockets.close(serverFd))
            end match
    end close

end PosixListener
