package kyo.net.internal

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.UnresolvedAddressException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager
import kyo.*
import kyo.net.Connection as NetConnection
import kyo.net.Listener as NetListener
import kyo.net.NetAddress
import kyo.net.NetAlreadyDetachedException
import kyo.net.NetBindException
import kyo.net.NetConnectException
import kyo.net.NetConnectionClosedException
import kyo.net.NetConnectionClosedException.Operation
import kyo.net.NetConnectTimeoutException
import kyo.net.NetDnsResolutionException
import kyo.net.NetException
import kyo.net.NetNotUpgradableException
import kyo.net.NetStdioAlreadyOpenException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.NetTlsException
import kyo.net.NetTlsHandshakeException
import kyo.net.NetUnixConnectException
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** JVM TCP transport using a non-blocking NIO `Selector`.
  *
  * Manages the full connection lifecycle for both plain TCP and TLS:
  *   - `connect` / `listen`: plain TCP (IPv4/IPv6)
  *   - `connect(tls)` / `listen(tls)`: TLS via `javax.net.ssl.SSLEngine`
  *
  * Each `NioIoDriver` owns one `Selector` that multiplexes all connections for its thread pool slot. The transport creates `NioHandle`
  * objects (one per connection) and registers them with the driver before returning connections to callers.
  *
  * TLS handshakes are driven entirely in callback chains (`driveHandshake`) without blocking any thread. Once the handshake completes, the
  * handle transitions to TLS mode and the driver handles all subsequent encryption/decryption inline.
  *
  * Note: TCP_NODELAY is set on all non-Unix-domain connections to disable Nagle's algorithm and reduce latency.
  */
final private[kyo] class NioTransport private (
    val driver: NioIoDriver,
    private val channelCapacity: Int,
    private val readBufferSize: Int,
    private val connectTimeout: Duration,
    private val handshakeTimeout: Duration
) extends TransportImpl[NioHandle]:

    /** The driver pool powering this transport. A single-driver pool wrapping `driver`: connect, listen, accept, and the TLS handshake all run
      * on that one driver, and `close()` shuts it down through the pool (no additional driver seam is introduced). Mirrors PosixTransport's
      * single-driver pool: NIO runs exactly one driver, so the pool is a one-element wrapper that implements the abstract `TransportImpl.pool`
      * member without an eager constructor-threaded field.
      */
    override val pool: IoDriverPool[NioHandle] =
        import AllowUnsafe.embrace.danger
        IoDriverPool.init(Array[IoDriver[NioHandle]](driver))
    end pool

    /** Every connection this transport opened (client connect, accepted server, or STARTTLS upgrade), keyed by its handle. A connection removes
      * itself when its handle is torn down (via the `onClose` wired at creation); `close()` closes whatever is still registered before the driver
      * shuts down, so a connection whose ordinary close never completed (its peer FIN never arrived, its handler never closed it) is reclaimed
      * instead of leaking its fd. Mirrors PosixTransport's connection registry. The shared process transport never calls `close()`, so its
      * registry only ever shrinks as connections close; an owned transport (per test, per kyo-http config) clears it at `close()`.
      */
    // Concurrent-collection audit: a raw ConcurrentHashMap tracking open connections, added on the connect/accept carrier and iterated on the
    // transport-close carrier without suspension. kyo has no concurrent map its effect collections can back here; retained as a documented exception.
    private val connections = new java.util.concurrent.ConcurrentHashMap[NioHandle, Connection[NioHandle]]()

    /** Build a connection over `handle` and register it in [[connections]], wiring its `onClose` so it self-removes when its handle is torn
      * down. Used for every connection so `close()` can reclaim any that did not close on their own.
      */
    private def initTracked(handle: NioHandle)(using AllowUnsafe, Frame): Connection[NioHandle] =
        val conn = Connection.init(handle, driver, channelCapacity, () => discard(connections.remove(handle)))
        discard(connections.put(handle, conn))
        conn
    end initTracked

    /** The NIO floor terminates TLS inline with the JDK SSLEngine, so it can serve only the "jdk" implementation. A connection pinning any
      * other [[NetTlsConfig.tlsProvider]] fails closed (see `startTlsHandshake`). The cross-backend test matrix reads this to skip non-jdk
      * cells against the NIO backend.
      */
    override private[net] def supportedTlsProviders: Set[String] = Set("jdk")

    /** Claim flag for the process-global stdio connection, so stdio is claimed at most once (fds 0/1 must never be double-owned). Mirrors the
      * posix and Node transports' claim.
      */
    // Unsafe: created at construction with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the caller's
    // AllowUnsafe.
    private val stdioClaimed = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    /** Open a connection over process stdin (fd 0, read) and stdout (fd 1, write). stdin/stdout are not selectable, so the connection is a
      * driverless [[NioStdioConnection]] pumped by dedicated blocking-read/write fibers rather than a `Selector`-registered handle. A second
      * call aborts [[NetStdioAlreadyOpenException]] (the claim CAS lost: fds 0/1 are process-global). Closing the connection never closes fds
      * 0/1 (the process owns them). Shared limitation with the posix `BlockingReaderDriver`: a read parked in stdin when the connection closes
      * stays parked until the next stdin byte or EOF.
      */
    def stdio()(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        if !stdioClaimed.compareAndSet(false, true) then
            // Exactly one stdio per process (no double-ownership of fd 0/1).
            Fiber.Unsafe.fromResult(Result.fail(NetStdioAlreadyOpenException()))
        else
            // Chaining the cast detaches this call from the method's expected type, so Fiber.Unsafe.init[E, A] resolves its phantom effect row
            // standalone: with E left to inference it resolves to Fiber.Unsafe[NetConnection, Any]. That view already conforms to the declared
            // Fiber.Unsafe[NetConnection, Abort[NetException]] by contravariance of S (Abort[NetException] <: Any), so nothing here crosses
            // the opaque-alias boundary: the value is already a Fiber.Unsafe and the cast is not required to obtain one. It is kept for
            // uniformity with the module's other Fiber.Unsafe boundary casts, which recover the opaque alias from a plain IOPromise and do
            // need it.
            Fiber.Unsafe.init(NioStdioConnection.open(channelCapacity, readBufferSize))
                .asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]

    /** Build the connect-stage [[NetException]] leaf for `host:port`: a TCP connect failure ([[NetConnectException]]), or a Unix-socket connect
      * failure ([[NetUnixConnectException]], signaled by the sentinel `port < 0` the Unix path passes since a Unix socket has no port).
      */
    private def connectFail(host: String, port: Int, cause: String | Throwable)(using Frame): NetException =
        if port < 0 then NetUnixConnectException(host, cause) else NetConnectException(host, port, cause)

    /** Close a channel on a failure path, swallowing any close exception: the failure leaf is already being reported, so a secondary close error
      * must not mask it. Used where a channel was opened but no [[NioHandle]]/[[Connection]] took ownership of it yet (a connect or handshake that
      * fails before registration), so the fd would otherwise leak.
      */
    private def closeQuietly(channel: java.nio.channels.Channel): Unit =
        try channel.close()
        catch case _: Throwable => ()

    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, Connection[NioHandle]]

        // Hoisted so the catch can close it: channel.connect throws UnresolvedAddressException (DNS failure) / IOException AFTER the channel is
        // open, and the failure paths inside the try (awaitConnect, registerChannel) already close it, so only this synchronous catch was leaking
        // the just-opened channel fd.
        var channel: SocketChannel = null
        try
            channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
            Log.live.unsafe.debug(s"NioTransport connect $host:$port channel=${channel.hashCode()}")

            val connected = channel.connect(new InetSocketAddress(host, port))
            Log.live.unsafe.debug(s"NioTransport connect immediate=$connected channel=${channel.hashCode()}")
            if connected then
                // Immediate connection (localhost)
                val handle = NioHandle.init(channel, readBufferSize)
                discard(driver.registerChannel(handle))
                completeConnect(handle, promise)
            else
                // Connection in progress, wait for writable. Arm the connect-deadline: when connectTimeout is finite, a Clock-driven timer
                // fails `promise` with the typed NetConnectTimeoutException if the OS connect does not complete first. The deadline arm and the
                // OS outcome race on the same `promise` (completeDiscard, at most once), so a deadline-fired close surfaces the timeout leaf and
                // an OS-failure close surfaces NetConnectException: the close cause is discriminated by which arm completes `promise` first.
                awaitConnect(channel, host, port, promise)
                armConnectDeadline(promise, host, port)
            end if
        catch
            case e: UnresolvedAddressException =>
                if channel != null then closeQuietly(channel)
                promise.completeDiscard(Result.fail(NetDnsResolutionException(host, e)))
            case e: IOException =>
                if channel != null then closeQuietly(channel)
                promise.completeDiscard(Result.fail(connectFail(host, port, e)))
        end try

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[NioHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.connect
        // return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connect

    private def awaitConnect(
        channel: SocketChannel,
        host: String,
        port: Int,
        promise: IOPromise[NetException, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        // For fast localhost, check if already connected.
        // Returns true if the connect was handled (success or error), false if still pending.
        def tryFinishConnect(): Boolean =
            try
                if channel.finishConnect() then
                    val handle = NioHandle.init(channel, readBufferSize)
                    discard(driver.registerChannel(handle))
                    completeConnect(handle, promise)
                    true
                else false
            catch
                case e: IOException =>
                    channel.close()
                    promise.completeDiscard(Result.fail(connectFail(host, port, e)))
                    true
        end tryFinishConnect

        if !tryFinishConnect() then
            // Not yet connected: register and let the driver handle OP_CONNECT
            val handle = NioHandle.init(channel, readBufferSize)
            if !driver.registerChannel(handle) then
                channel.close()
                promise.completeDiscard(Result.fail(connectFail(host, port, "")))
            else
                val connectPromise = new IOPromise[Closed, Unit]
                connectPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            Log.live.unsafe.debug(s"NioTransport connected channel=${channel.hashCode()}")
                            completeConnect(handle, promise)
                        case Result.Failure(closed) =>
                            channel.close()
                            promise.completeDiscard(Result.fail(connectFail(host, port, closed)))
                        case Result.Panic(e) =>
                            channel.close()
                            promise.completeDiscard(Result.panic(e))
                }
                // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
                // plainly-constructed IOPromise[Closed, Unit], even though both erase to the same runtime object; the alias is transparent
                // only inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitConnect's fixed Promise.Unsafe-typed parameter needs
                // this erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/Unit values above,
                // never a suspended computation.
                driver.awaitConnect(handle, connectPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end if
    end awaitConnect

    private def completeConnect(
        handle: NioHandle,
        promise: IOPromise[NetException, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"NioTransport completeConnect channel=${handle.channel.hashCode()}")
        val connection = initTracked(handle)
        // Wire in the STARTTLS upgrade function. NioTransport is the only platform that supports it.
        connection.upgradeFn = Present { (tls, frame) =>
            given Frame = frame
            upgradeToTls(connection, tls, channelCapacity)
        }
        // Wire in the TLS certificate hash function. Compute the hash ONCE here, at handshake completion before connection.start() launches the
        // pumps, so the single getPeerCertificates read cannot race the Selector carrier's concurrent engine ops (concurrent SSLEngine/SSLSession
        // access is undefined behavior). The installed function then returns the cached value (engine-free) gated on isOpen, mirroring
        // PosixTransport.installCertHash. Without the cache, a live read on the caller's carrier raced the engine and returned Absent under load.
        val cachedCertHash = NioTransport.serverCertificateHash(handle)
        connection.certHashFn = Present(() => if connection.isOpen then cachedCertHash else Absent)
        // Wire in the TLS close-reason function so a TLS connection reports the RFC 8446 6.1 / RFC 5246 7.2.1 close distinction. Only a TLS
        // handle has the close_notify-vs-bare-FIN signal (a plaintext handle has no close_notify exchange and keeps the default Active), so it is
        // installed only when handle.tls is Present at wiring time (after driveHandshake set it). This converges the inline NIO path with the
        // engine driver path (PosixTransport.installStatus), which installs the same accessor over PosixHandle.peerCleanClose / peerEof.
        handle.tls.foreach { tlsState =>
            connection.statusFn = Present(() => NioTransport.statusFor(connection, tlsState))
        }
        // Deliver any application plaintext the handshake decrypted during a STARTTLS upgrade (peer data coalesced with its final flight) BEFORE
        // the pumps start, so it precedes anything the ReadPump reads next. A no-op for a fresh handshake (nothing captured).
        deliverUpgradeAppData(handle, connection.inbound)
        if connection.start() then
            val completed = promise.complete(Result.succeed(connection))
            if !completed then
                // Promise was already interrupted: nobody will use this connection, close it
                connection.close()
            end if
        else
            // The connection raced to a terminal/Upgrading state before start (a close won); it must not be handed out as open.
            promise.completeDiscard(Result.fail(NetConnectionClosedException(Operation.Start)))
        end if
    end completeConnect

    def listen(host: String, port: Int, backlog: Int)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetListener]

        // Hoisted so the catch can close it: bind throws (e.g. address-already-in-use) after the server channel is open, and that catch otherwise
        // leaked the listen fd.
        var serverChannel: ServerSocketChannel = null
        try
            serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
            serverChannel.bind(new InetSocketAddress(host, port), backlog)

            if !driver.registerServerChannel(serverChannel) then
                serverChannel.close()
                promise.completeDiscard(Result.fail(NetBindException(host, port, "")))
            else
                val actualPort = serverChannel.socket().getLocalPort
                val actualHost = Maybe(serverChannel.socket().getInetAddress.getHostAddress).getOrElse(host)
                Log.live.unsafe.debug(s"NioTransport listen $host:$actualPort")

                val listener = new NioListener(serverChannel, actualPort, actualHost, driver, NetAddress.Tcp(actualHost, actualPort))
                startAcceptLoop(serverChannel, handler, listener)
                promise.completeDiscard(Result.succeed(listener))
            end if
        catch
            case e: UnresolvedAddressException =>
                if serverChannel != null then closeQuietly(serverChannel)
                promise.completeDiscard(Result.fail(NetDnsResolutionException(host, e)))
            case e: IOException =>
                if serverChannel != null then closeQuietly(serverChannel)
                promise.completeDiscard(Result.fail(NetBindException(host, port, e)))
        end try

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen return needs
        // this erased-boundary cast. Safe: the promise completes only with the NetException/NetListener values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listen

    /** Drive the accept loop for a server channel: on each readiness, drain every pending connection via `acceptAllPending`, then re-arm, until
      * the listener is closed. The plain and TLS accept paths supply their own per-batch accept action and panic label; the loop skeleton (the
      * await/re-arm/wind-down) is shared.
      */
    private def startAcceptLoopWith(
        serverChannel: ServerSocketChannel,
        listener: NioListener,
        panicLabel: String
    )(acceptAllPending: () => Unit)(using AllowUnsafe, Frame): Unit =

        def scheduleNextAccept(): Unit =
            if !listener.isClosed then
                val acceptPromise = new IOPromise[Closed, Unit]
                acceptPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            acceptAllPending()
                            scheduleNextAccept()
                        case Result.Failure(_) =>
                            // Server closed
                            ()
                        case Result.Panic(e) =>
                            Log.live.unsafe.error(panicLabel, e)
                }
                // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
                // plainly-constructed IOPromise[Closed, Unit], even though both erase to the same runtime object; the alias is transparent
                // only inside kyo.Fiber.Promise's own defining scope, so NioIoDriver's ServerSocketChannel overload of awaitAccept, whose
                // promise parameter is Promise.Unsafe[Unit, Abort[Closed]], needs this erased-boundary cast to accept it. Safe: the
                // promise is completed only with the plain Closed/Unit values above, never a suspended computation.
                driver.awaitAccept(serverChannel, acceptPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end scheduleNextAccept

        scheduleNextAccept()
    end startAcceptLoopWith

    private def startAcceptLoop(
        serverChannel: ServerSocketChannel,
        handler: NetConnection => Unit,
        listener: NioListener
    )(using AllowUnsafe, Frame): Unit =
        startAcceptLoopWith(serverChannel, listener, "Accept loop panic") { () =>
            acceptAllPending(serverChannel, handler, listener)
        }
    end startAcceptLoop

    private def acceptAllPending(
        serverChannel: ServerSocketChannel,
        handler: NetConnection => Unit,
        listener: NioListener
    )(using AllowUnsafe, Frame): Unit =
        def tryAcceptOne(): Boolean =
            try
                val clientChannel = serverChannel.accept()
                if clientChannel ne null then
                    clientChannel.configureBlocking(false)
                    // Unix domain sockets do not support TCP_NODELAY
                    listener.address match
                        case _: NetAddress.Tcp => clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                        case _                 => ()
                    Log.live.unsafe.debug(
                        s"NioTransport accepted client channel=${clientChannel.hashCode()} on server port=${listener.port}"
                    )

                    val handle = NioHandle.init(clientChannel, readBufferSize)
                    discard(driver.registerChannel(handle))
                    val connection = initTracked(handle)
                    // Accepted connection: a STARTTLS upgrade through the public upgradeToTls runs in the TLS server role (upgradeToTls reads
                    // isServerOrigin).
                    connection.isServerOrigin = true
                    connection.upgradeFn = Present { (tls, frame) =>
                        given Frame = frame
                        upgradeToTls(connection, tls, channelCapacity)
                    }
                    // Cache the cert hash once here (pre-start, engine quiescent); a live read would race the Selector's engine ops (see completeConnect).
                    val cachedCertHash = NioTransport.serverCertificateHash(handle)
                    connection.certHashFn = Present(() => if connection.isOpen then cachedCertHash else Absent)
                    if connection.start() then
                        // Spawn the handler in its own carrier fiber. The connection lifecycle is managed by its pumps;
                        // the handler is fire-and-forget. A throw from the handler is logged and does not propagate here.
                        discard(Fiber.Unsafe.init {
                            // Contain ANY throw from the user handler (not just NonFatal): a throw must never escape to the carrier, abort the process,
                            // or stall the accept loop. Uniform with the posix and node backends.
                            try handler(connection)
                            catch case e: Throwable => Log.live.unsafe.error(s"Connection handler panic", e)
                        })
                    else
                        // The connection raced to a terminal/Upgrading state before start (the transport's close swept it, or a detach won);
                        // it is not usable, so the handler is never spawned.
                        Log.live.unsafe.info(s"NioTransport accepted connection closed before start; handler not spawned")
                    end if
                    true   // accepted one, try again
                else false // no more pending
                end if
            catch
                case _: IOException if listener.isClosed => false
                case e: IOException =>
                    Log.live.unsafe.error(s"Accept error", e)
                    false
        @tailrec def acceptLoop(): Unit =
            if !listener.isClosed && tryAcceptOne() then acceptLoop()
        acceptLoop()
    end acceptAllPending

    def connect(host: String, port: Int, tls: NetTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, Connection[NioHandle]]

        // Hoisted so the catch can close it (same as the plaintext connect): channel.connect throws after the channel is open, and the failure
        // paths inside the try already close it, so only this synchronous catch was leaking the just-opened channel fd.
        var channel: SocketChannel = null
        try
            channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
            Log.live.unsafe.debug(s"NioTransport TLS connect $host:$port channel=${channel.hashCode()}")

            val connected = channel.connect(new InetSocketAddress(host, port))
            if connected then
                startTlsHandshake(channel, host, port, tls, isServer = false, promise, existingHandle = Absent, preRead = Absent)
            else
                awaitConnectThenTls(channel, host, port, tls, promise)
            end if
        catch
            case e: UnresolvedAddressException =>
                if channel != null then closeQuietly(channel)
                promise.completeDiscard(Result.fail(NetDnsResolutionException(host, e)))
            case e: IOException =>
                if channel != null then closeQuietly(channel)
                promise.completeDiscard(Result.fail(NetConnectException(host, port, e)))
        end try

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[NioHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.connect
        // (TLS) return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connect

    private def awaitConnectThenTls(
        channel: SocketChannel,
        host: String,
        port: Int,
        tls: NetTlsConfig,
        promise: IOPromise[NetException, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        // For fast localhost, check if already connected.
        // Returns true if the connect was handled (success or error), false if still pending.
        def tryFinishConnect(): Boolean =
            try
                if channel.finishConnect() then
                    startTlsHandshake(channel, host, port, tls, isServer = false, promise, existingHandle = Absent, preRead = Absent)
                    true
                else false
            catch
                case e: IOException =>
                    channel.close()
                    promise.completeDiscard(Result.fail(NetConnectException(host, port, e)))
                    true
        end tryFinishConnect

        if !tryFinishConnect() then
            // Not yet connected: register and let the driver handle OP_CONNECT
            val handle = NioHandle.init(channel, readBufferSize)
            if !driver.registerChannel(handle) then
                channel.close()
                promise.completeDiscard(Result.fail(NetConnectException(host, port, "")))
            else
                val connectPromise = new IOPromise[Closed, Unit]
                connectPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            Log.live.unsafe.debug(s"NioTransport TCP connected, starting TLS handshake channel=${channel.hashCode()}")
                            startTlsHandshake(channel, host, port, tls, isServer = false, promise, Present(handle), preRead = Absent)
                        case Result.Failure(closed) =>
                            channel.close()
                            promise.completeDiscard(Result.fail(NetConnectException(host, port, closed)))
                        case Result.Panic(e) =>
                            channel.close()
                            promise.completeDiscard(Result.panic(e))
                }
                // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
                // plainly-constructed IOPromise[Closed, Unit], even though both erase to the same runtime object; the alias is transparent
                // only inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitConnect's fixed Promise.Unsafe-typed parameter needs
                // this erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/Unit values above,
                // never a suspended computation.
                driver.awaitConnect(handle, connectPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end if
    end awaitConnectThenTls

    private def startTlsHandshake(
        channel: SocketChannel,
        host: String,
        port: Int,
        tls: NetTlsConfig,
        isServer: Boolean,
        connectPromise: IOPromise[NetException, Connection[NioHandle]],
        existingHandle: Maybe[NioHandle],
        preRead: Maybe[Chunk[Span[Byte]]]
    )(using AllowUnsafe, Frame): Unit =
        // Central failure-close for a channel THIS handshake owns (a fresh client connect, not a STARTTLS upgrade of an existing handle): every
        // handshake failure path in this method and in driveHandshake completes connectPromise with a failure and never reaches completeConnect, so
        // the just-opened channel would leak. One onComplete closes it on any failure (a version mismatch, a name-mismatch rejection, a CLOSED
        // unwrap, a verifying-client-with-no-identity reject, a TLS-to-plaintext peer). On success completeConnect has already wrapped the channel
        // in a tracked Connection that owns it, so the success arm skips the close. Closing the channel cancels its Selector key (NIO semantics), so
        // no separate driver deregister is needed (matching the plaintext awaitConnect failure path). The STARTTLS case (existingHandle present) is
        // owned by upgradeToTls, which closes on its own failure path.
        if existingHandle.isEmpty then
            connectPromise.onComplete {
                case Result.Success(_) => ()
                case _                 => closeQuietly(channel)
            }
        end if
        try
            // Honor a NetTlsConfig.tlsProvider pin: the NIO floor terminates TLS with the inline JDK SSLEngine, so it can serve only the "jdk"
            // implementation. A pin to any other provider fails closed (caught below and reported on connectPromise) rather than silently
            // using the JDK engine under a different provider's name. Config truthfulness, mirroring TlsProvider.selectFor on the posix path.
            if tls.tlsProvider.exists(_ != "jdk") then
                throw new IllegalStateException(
                    s"pinned TLS provider '${tls.tlsProvider.get}' is not supported by the NIO transport (only 'jdk')"
                )
            end if
            val sslContext = NioTransport.createSslContext(tls, isServer)
            val engine     = sslContext.createSSLEngine(host, port)
            engine.setUseClientMode(!isServer)
            // Enforce the configured [minVersion, maxVersion] range. The raw SSLEngine enables a broad default protocol set, so without pinning a
            // version-mismatched peer would silently negotiate a common version (CWE-326). Mirrors SslEngineProvider via the shared
            // NioTransport.enabledProtocols helper so the inline NIO path and the JDK-floor provider pin identically.
            engine.setEnabledProtocols(NioTransport.enabledProtocols(tls))

            if !isServer then
                // Verifying client with NO reference identity: FAIL CLOSED. A chain-valid certificate with no name bound is never an
                // acceptable silent outcome (RFC 9525 §6.1; CWE-295). This mirrors SslEngineProvider.createEngine exactly so the inline NIO
                // path and the SSLEngine-provider path reach the identical accept/reject decision for the same NetTlsConfig + host. Reached
                // via connect("", port, tls) and the STARTTLS upgrade with sniHostname = Absent (host = sniHostname.getOrElse("")).
                if tls.hostnameVerification && !tls.trustAll && host.isEmpty then
                    throw NetTlsConfigException(
                        "verifying client has no reference identity: a hostname is required to verify the server certificate (set trustAll " +
                            "or hostnameVerification = false to opt out of name verification)"
                    )
                end if
                if host.nonEmpty then
                    // Only set endpoint identification when we have a real hostname to verify against.
                    // trustAll disables all verification (chain + hostname). hostnameVerification = false
                    // disables only hostname verification (e.g. sslmode=verify-ca).
                    val params = engine.getSSLParameters
                    if tls.hostnameVerification && !tls.trustAll then
                        params.setEndpointIdentificationAlgorithm("HTTPS")
                    tls.sniHostname.foreach { sni =>
                        params.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(sni)))
                    }
                    engine.setSSLParameters(params)
                end if
            else
                // Server role: honor clientAuth. The SSLContext already has a TrustManager built from caCertPath (createSslContext), so a
                // requested client certificate is validated against it; without setNeed/WantClientAuth the server would never request one and
                // clientAuth would be silently ignored.
                tls.clientAuth match
                    case NetTlsConfig.ClientAuth.Required => engine.setNeedClientAuth(true)
                    case NetTlsConfig.ClientAuth.Optional => engine.setWantClientAuth(true)
                    case NetTlsConfig.ClientAuth.None     => ()
            end if

            engine.beginHandshake()

            // Create handle in raw mode (tls = Absent) for handshake.
            // The driver reads raw ciphertext during handshake.
            val handle = existingHandle.getOrElse {
                val h = NioHandle.init(channel, readBufferSize)
                discard(driver.registerChannel(h))
                h
            }

            // Create TLS state but don't attach yet: handshake uses raw I/O
            val session   = engine.getSession
            val netInBuf  = ByteBuffer.allocate(session.getPacketBufferSize)
            val netOutBuf = ByteBuffer.allocate(session.getPacketBufferSize)
            val appInBuf  = ByteBuffer.allocate(session.getApplicationBufferSize)
            val tlsState  = NioTlsState(engine, netInBuf, netOutBuf, appInBuf)

            // Replay any handshake ciphertext the plaintext ReadPump already pulled off the socket
            // (STARTTLS upgrade case). driveHandshake expects netInBuf in write mode at entry (its
            // first action is netInBuf.flip() to expose accumulated bytes for unwrap), and netInBuf
            // is freshly allocated at position 0 in write mode, so we simply put the pre-read bytes:
            // the handshake's buffered-unwrap path then consumes them before issuing any socket read.
            // Absent/empty preRead writes nothing, leaving behavior identical to a fresh handshake.
            preRead.foreach { spans =>
                spans.foreach { span =>
                    if span.nonEmpty then discard(netInBuf.put(span.toArrayUnsafe))
                }
            }
            // STARTTLS handoff (upgrade path only): the handshake now takes over reading from the retiring plaintext pump. Set handshakeReading so a
            // dispatched read completes the handshake's read (rather than being salvaged), then feed any of the peer's first flight the pump salvaged
            // during the plaintext phase (e.g. the ClientHello) into the engine. The salvage follows preRead in the byte stream (preRead is what the
            // pump staged before the upgrade window opened; the salvage is what it pulled off the socket during it). handshakeReading is set BEFORE the
            // drain so the selector carrier completes rather than re-salvages a read that lands while the drain runs.
            if existingHandle.isDefined then
                handle.handshakeReading = true
                val drained = driver.drainUpgradeSalvage(handle)
                drained.foreach(arr => discard(netInBuf.put(arr)))
                // The upgrade producer is armed ON DEMAND by the handshake itself: driveHandshake's NEED_UNWRAP park (below) calls
                // armUpgradeProducerRead for each read it needs, so the selector carrier reads exactly one peer flight per park and is idle once the
                // handshake stops parking at FINISHED. No standing self-re-arming producer is bootstrapped here: a standing producer over-reads past
                // FINISHED (it cannot know which read is the handshake's last) and races the FINISHED hand-off to the upgraded connection's ReadPump,
                // which under load corrupts the post-FINISHED record (the dropped-upgrade regression). Every demand arm is selector-confined
                // (armUpgradeProducerRead defers to the poll carrier via pendingUpgradeArms with an unconditional wakeup, never a cross-carrier
                // interestOps read-modify-write). handshakeReading is set above so any read dispatched during the window routes to the producer path;
                // the first park installs the first producer arm.
            end if

            driveHandshake(handle, tlsState, host, port, connectPromise)
        catch
            case e: NetTlsException =>
                connectPromise.completeDiscard(Result.fail(e))
            case e: Exception =>
                // The channel close is handled centrally by the connectPromise failure onComplete above (for a fresh channel); here just report the
                // failure. A STARTTLS upgrade (existingHandle present) is closed by upgradeToTls.
                connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
        end try
    end startTlsHandshake

    /** Capture application plaintext the handshake's unwrap produced during a STARTTLS upgrade window. When a peer sends application data coalesced
      * with (or right after) its final handshake flight, the selector-carrier upgrade producer reads it and `driveHandshake`'s unwrap decrypts it
      * into appInBuf; it belongs to the upgraded connection, not the handshake, so stash it on the handle (delivered to the upgraded inbound at
      * [[completeConnect]]) instead of discarding it. A no-op for a fresh (non-upgrade) handshake or when the unwrap produced no application plaintext.
      */
    private def captureUpgradeAppData(handle: NioHandle, appInBuf: ByteBuffer, produced: Int)(using AllowUnsafe): Unit =
        if handle.upgrading && produced > 0 then
            appInBuf.flip()
            val arr = new Array[Byte](produced)
            appInBuf.get(arr)
            @tailrec def append(): Unit =
                val cur = handle.upgradeAppData.get()
                if !handle.upgradeAppData.compareAndSet(cur, cur.append(arr)) then append()
            append()
        end if
    end captureUpgradeAppData

    /** Deliver application plaintext captured during a STARTTLS upgrade ([[captureUpgradeAppData]]) to the upgraded connection's inbound, in order,
      * BEFORE the ReadPump starts so it precedes any data the pump reads next. A no-op when nothing was captured (the common case / fresh handshake).
      */
    private def deliverUpgradeAppData(handle: NioHandle, inbound: Channel.Unsafe[Span[Byte]])(using AllowUnsafe, Frame): Unit =
        val captured = handle.upgradeAppData.getAndSet(Chunk.empty)
        captured.foreach(arr => if arr.length > 0 then discard(inbound.offer(Span.fromUnsafe(arr))))
    end deliverUpgradeAppData

    /** Drain any ciphertext left when the handshake reached FINISHED into application plaintext for the upgraded connection. The main source is
      * (1) the peer's first application record arriving coalesced with its final handshake flight, sitting unconsumed in netInBuf. A backstop source is
      * (2) a residual [[NioHandle.upgradeHandoff]] Carryover (demand-driven, the producer stops at the last handshake read so it does not normally read
      * a post-FINISHED flight; the Carryover drain remains for safety). Both are ciphertext the upgraded connection owns: unwrap them here (on the
      * carrier that completed the handshake, after the producer is retired so nothing stages more) and stash the plaintext, delivered to the inbound in
      * [[completeConnect]] before the ReadPump starts. Without this the coalesced bytes strand: the engine holds no socket event for already-read
      * ciphertext. A no-op for a fresh (non-upgrade) handshake.
      */
    private def drainUpgradeLeftover(handle: NioHandle, tlsState: NioTlsState)(using AllowUnsafe): Unit =
        if handle.upgrading then
            import NioHandle.UpgradeHandoff
            val carry = handle.upgradeHandoff.getAndSet(UpgradeHandoff.Idle) match
                case staged: UpgradeHandoff.Carryover => staged.bytes
                case _                                => Array.emptyByteArray
            tlsState.netInBuf.flip() // read mode: expose any coalesced leftover
            val leftover = tlsState.netInBuf.remaining()
            if leftover > 0 || carry.length > 0 then
                // Concatenate leftover (earlier in the stream) then the Carryover into one exact-sized buffer, so a record split across the two is
                // reassembled and no fixed-size netInBuf overflow is possible for a large coalesced echo.
                val combined = ByteBuffer.allocate(leftover + carry.length)
                discard(combined.put(tlsState.netInBuf))
                discard(combined.put(carry))
                combined.flip()
                tlsState.netInBuf.clear()
                var more = combined.hasRemaining
                while more do
                    tlsState.appInBuf.clear()
                    val res = tlsState.engine.unwrap(combined, tlsState.appInBuf)
                    captureUpgradeAppData(handle, tlsState.appInBuf, res.bytesProduced())
                    more = (res.getStatus eq SSLEngineResult.Status.OK) && res.bytesConsumed() > 0 && combined.hasRemaining
                end while
                // A trailing partial record (BUFFER_UNDERFLOW) returns to netInBuf for the ReadPump to complete with its next socket read.
                if combined.hasRemaining then discard(tlsState.netInBuf.put(combined))
            else
                discard(tlsState.netInBuf.clear())
            end if
        end if
    end drainUpgradeLeftover

    /** Drive TLS handshake via callback-driven promise chains.
      *
      * During handshake, handle.tls is Absent so the driver reads raw ciphertext. We feed ciphertext to the SSLEngine manually via
      * unwrap/wrap. After handshake completes, we set handle.tls = Present(tlsState) so the driver switches to TLS mode for data transfer.
      */
    private def driveHandshake(
        handle: NioHandle,
        tlsState: NioTlsState,
        host: String,
        port: Int,
        connectPromise: IOPromise[NetException, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        val engine = tlsState.engine
        val hs     = engine.getHandshakeStatus
        if hs eq SSLEngineResult.HandshakeStatus.NEED_UNWRAP then
            // First check if netInBuf has buffered data from a previous read
            // (multiple TLS records can arrive in a single read)
            tlsState.netInBuf.flip()
            // needMoreData: true = fall through to read from network, false = handled
            val needMoreData =
                if tlsState.netInBuf.hasRemaining then
                    tlsState.appInBuf.clear()
                    try
                        val res = engine.unwrap(tlsState.netInBuf, tlsState.appInBuf)
                        captureUpgradeAppData(handle, tlsState.appInBuf, res.bytesProduced())
                        tlsState.netInBuf.compact()
                        val status = res.getStatus
                        if status eq SSLEngineResult.Status.OK then
                            driveHandshake(handle, tlsState, host, port, connectPromise)
                            false
                        else if status eq SSLEngineResult.Status.CLOSED then
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, "")))
                            false
                        else if status eq SSLEngineResult.Status.BUFFER_OVERFLOW then
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, "")))
                            false
                        else
                            // BUFFER_UNDERFLOW: need more data from network
                            true
                        end if
                    catch
                        case e: Exception =>
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
                            false
                    end try
                else
                    tlsState.netInBuf.compact()
                    true
                end if
            end needMoreData

            if needMoreData then
                // Feed one peer ciphertext flight into the engine, unwrap, and re-drive the handshake (or fail on a bad status). Shared by the
                // non-upgrade read path and the STARTTLS upgrade handoff path below.
                def feedCipher(arr: Array[Byte]): Unit =
                    tlsState.netInBuf.put(arr)
                    tlsState.netInBuf.flip()
                    tlsState.appInBuf.clear()
                    try
                        val res = engine.unwrap(tlsState.netInBuf, tlsState.appInBuf)
                        captureUpgradeAppData(handle, tlsState.appInBuf, res.bytesProduced())
                        tlsState.netInBuf.compact()
                        val status = res.getStatus
                        if (status eq SSLEngineResult.Status.OK) || (status eq SSLEngineResult.Status.BUFFER_UNDERFLOW) then
                            driveHandshake(handle, tlsState, host, port, connectPromise)
                        else if status eq SSLEngineResult.Status.CLOSED then
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, "")))
                        else // BUFFER_OVERFLOW
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, "")))
                        end if
                    catch
                        case e: Exception =>
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
                    end try
                end feedCipher
                def failClosed(): Unit = connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, "")))

                if handle.upgrading then
                    // STARTTLS confinement: the selector carrier is the sole reader and OP_READ owner during the upgrade, so the handshake never arms
                    // OP_READ from this carrier (which would race the selector's interestOps read-modify-write and lose the bit / wakeup, the upgrade
                    // residual). Park a waiter on the handle's upgrade handoff slot; the selector carrier reads the peer flight and fulfils it (or has
                    // staged a Carryover when it read first), and `armUpgradeProducerRead` triggers it. The waiter resolves on the SELECTOR carrier, so
                    // its re-drive (and the next park's arm) run there too: every per-read arm after the first is selector-confined by construction.
                    import NioHandle.UpgradeHandoff
                    val waiterPromise = new IOPromise[Closed, Span[Byte]]
                    waiterPromise.onComplete {
                        case Result.Success(bytes) => if bytes.isEmpty then failClosed() else feedCipher(bytes.toArrayUnsafe)
                        case Result.Failure(_)     => failClosed()
                        case Result.Panic(t)       => connectPromise.completeDiscard(Result.panic(t))
                    }
                    // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
                    // plainly-constructed IOPromise[Closed, Span[Byte]], even though both erase to the same runtime object; the alias is
                    // transparent only inside kyo.Fiber.Promise's own defining scope, so storing this promise in the Waiter's fixed
                    // Promise.Unsafe-typed field needs this erased-boundary cast. Safe: the promise is completed only with the plain
                    // Closed/Span[Byte] values above, never a suspended computation.
                    val waiter = UpgradeHandoff.Waiter(waiterPromise.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]], summon[Frame])
                    handle.upgradeHandoff.get() match
                        case staged: UpgradeHandoff.Carryover =>
                            discard(handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Idle))
                            feedCipher(staged.bytes)
                        case _ =>
                            // Park the waiter, then arm the producer ON DEMAND for this one read. armUpgradeProducerRead defers the arm to the poll
                            // carrier via pendingUpgradeArms with an unconditional selector.wakeup() (never a cross-carrier interestOps RMW), so the
                            // selector reads the next peer flight and fulfils this waiter through the slot. The producer reads exactly one flight per
                            // park and does NOT re-arm itself, so it never over-reads past the handshake's need (the dropped-upgrade root). A CAS loss
                            // means the producer staged a Carryover between the get above and this CAS; consume it (no arm needed, feedCipher re-drives
                            // and the next park re-arms).
                            if handle.upgradeHandoff.compareAndSet(UpgradeHandoff.Idle, waiter) then
                                driver.armUpgradeProducerRead(handle)
                            else
                                handle.upgradeHandoff.get() match
                                    case staged: UpgradeHandoff.Carryover =>
                                        discard(handle.upgradeHandoff.compareAndSet(staged, UpgradeHandoff.Idle))
                                        feedCipher(staged.bytes)
                                    case _ => ()
                    end match
                else
                    // Non-upgrade handshake: read normally (the selector completes the read promise; no cross-carrier upgrade arm exists).
                    val readPromise = new IOPromise[Closed, ReadOutcome]
                    readPromise.onComplete { result =>
                        result match
                            case Result.Success(outcome) =>
                                outcome match
                                    case ReadOutcome.Bytes(bytes) => feedCipher(bytes.toArrayUnsafe)
                                    case _                        => failClosed()
                            case Result.Failure(e) =>
                                connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
                            case Result.Panic(t) =>
                                connectPromise.completeDiscard(Result.panic(t))
                    }
                    // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
                    // plainly-constructed IOPromise[Closed, ReadOutcome], even though both erase to the same runtime object; the alias is
                    // transparent only inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitRead's fixed Promise.Unsafe-typed
                    // parameter needs this erased-boundary cast to accept it. Safe: the promise is completed only with the plain
                    // Closed/ReadOutcome values above, never a suspended computation.
                    driver.awaitRead(handle, readPromise.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]])
                end if
            end if
        else if hs eq SSLEngineResult.HandshakeStatus.NEED_WRAP then
            try
                tlsState.netOutBuf.clear()
                val emptyBuf = ByteBuffer.allocate(0)
                discard(engine.wrap(emptyBuf, tlsState.netOutBuf))
                tlsState.netOutBuf.flip()
                // Try one write: if socket buffer full, await writable asynchronously
                discard(handle.channel.write(tlsState.netOutBuf))
                if tlsState.netOutBuf.hasRemaining then
                    flushHandshakeWrite(handle, tlsState, host, port, connectPromise)
                else
                    driveHandshake(handle, tlsState, host, port, connectPromise)
                end if
            catch
                case e: Exception =>
                    connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
        else if hs eq SSLEngineResult.HandshakeStatus.NEED_TASK then
            // Run the SSLEngine's delegated tasks INLINE, then re-enter the handshake directly. The delegated tasks are synchronous Runnables
            // (CPU-bound key-exchange crypto, not blocking I/O), so running them on the current carrier is correct and brief. Spawning a Fiber
            // per NEED_TASK and resuming via onComplete would make each handshake depend on that fiber being scheduled and its onComplete
            // firing, so under high concurrency a handshake could stall indefinitely at NEED_TASK; inline execution has no extra fiber and no
            // scheduling dependency.
            try
                var task = engine.getDelegatedTask
                while task != null do
                    task.run()
                    task = engine.getDelegatedTask
                end while
                driveHandshake(handle, tlsState, host, port, connectPromise)
            catch
                case e: Exception =>
                    connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
        else // FINISHED or NOT_HANDSHAKING
            Log.live.unsafe.debug(s"NioTransport TLS handshake complete channel=${handle.channel.hashCode()}")
            // Handshake done: close the STARTTLS handoff window so the upgraded connection's pumps read normally from here. The ordering below is
            // race-safe whether this branch runs on the selector carrier (inline waiter completion) or a scheduler carrier (async completion):
            //   1. handshakeReading=false first, so no further read routes to the producer path (dispatchRead's upgrade gate); demand-driven, the
            //      producer never self-re-arms, so the last handshake read was the last producer read and nothing reads into the slot after this.
            //   2. stopUpgradeProducer clears any still-armed producer cell + OP_READ + pendingReads (a no-op in the common demand-driven case where
            //      the last read already retired the cell), so no later readiness dispatch reads into the slot; pendingReads removal is the gate.
            //   3. drainUpgradeLeftover, still while upgrading is true (captureUpgradeAppData keys off it), pulls any application record the peer
            //      coalesced with its final handshake flight (left in netInBuf), plus any residual slot Carryover (a backstop), and stashes the plaintext.
            // A no-op for a fresh (non-upgrade) handshake that never set the flags or armed a producer.
            handle.handshakeReading = false
            driver.stopUpgradeProducer(handle)
            drainUpgradeLeftover(handle, tlsState)
            val wasUpgrade = handle.upgrading
            handle.upgrading = false
            // STARTTLS only: the upgraded connection's first ReadPump read arm (in completeConnect -> connection.start) sets OP_READ via a
            // cross-carrier interestOps read-modify-write that can lose to the selector's own write; into a selector that quiesces between
            // repeated upgrades the guarded wakeup can coalesce so the reassert backstop never runs and the read strands. Mark the handle so that
            // first arm forces an unconditional selector.wakeup(), guaranteeing one poll cycle where reassertPendingInterest re-applies OP_READ
            // on the selector carrier. A no-op for a fresh (non-upgrade) connect (wasUpgrade=false), whose connect already cycles the selector.
            handle.forceReadArmWakeup = wasUpgrade
            engine.setEnableSessionCreation(false) // disable renegotiation
            // Switch handle to TLS mode: driver will now unwrap/wrap inline
            handle.tls = Present(tlsState)
            completeConnect(handle, connectPromise)
        end if
    end driveHandshake

    /** Async flush for handshake NEED_WRAP ciphertext.
      *
      * Awaits writable, tries writing, and either continues handshake or awaits again.
      */
    private def flushHandshakeWrite(
        handle: NioHandle,
        tlsState: NioTlsState,
        host: String,
        port: Int,
        connectPromise: IOPromise[NetException, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        val writablePromise = new IOPromise[Closed, Unit]
        writablePromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    try
                        discard(handle.channel.write(tlsState.netOutBuf))
                        if tlsState.netOutBuf.hasRemaining then
                            // Still not flushed: await writable again
                            flushHandshakeWrite(handle, tlsState, host, port, connectPromise)
                        else
                            driveHandshake(handle, tlsState, host, port, connectPromise)
                        end if
                    catch
                        case e: Exception =>
                            connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
                case Result.Failure(e) =>
                    connectPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, e)))
                case Result.Panic(t) =>
                    connectPromise.completeDiscard(Result.panic(t))
        }
        // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Closed, Unit], even though both erase to the same runtime object; the alias is transparent only
        // inside kyo.Fiber.Promise's own defining scope, so IoDriver.awaitWritable's fixed Promise.Unsafe-typed parameter needs this
        // erased-boundary cast to accept it. Safe: the promise is completed only with the plain Closed/Unit values above, never a
        // suspended computation.
        driver.awaitWritable(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
    end flushHandshakeWrite

    def listen(host: String, port: Int, backlog: Int, tls: NetTlsConfig)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetListener]

        // Hoisted so the catch can close it: bind throws (e.g. address-already-in-use) after the server channel is open, and that catch otherwise
        // leaked the listen fd.
        var serverChannel: ServerSocketChannel = null
        try
            serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
            serverChannel.bind(new InetSocketAddress(host, port), backlog)

            if !driver.registerServerChannel(serverChannel) then
                serverChannel.close()
                promise.completeDiscard(Result.fail(NetBindException(host, port, "")))
            else
                val actualPort = serverChannel.socket().getLocalPort
                val actualHost = Maybe(serverChannel.socket().getInetAddress.getHostAddress).getOrElse(host)
                Log.live.unsafe.debug(s"NioTransport TLS listen $host:$actualPort")

                val listener = new NioListener(serverChannel, actualPort, actualHost, driver, NetAddress.Tcp(actualHost, actualPort))
                startTlsAcceptLoop(serverChannel, handler, listener, tls)
                promise.completeDiscard(Result.succeed(listener))
            end if
        catch
            case e: UnresolvedAddressException =>
                if serverChannel != null then closeQuietly(serverChannel)
                promise.completeDiscard(Result.fail(NetDnsResolutionException(host, e)))
            case e: IOException =>
                if serverChannel != null then closeQuietly(serverChannel)
                promise.completeDiscard(Result.fail(NetBindException(host, port, e)))
        end try

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen (TLS) return
        // needs this erased-boundary cast. Safe: the promise completes only with the NetException/NetListener values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listen

    private def startTlsAcceptLoop(
        serverChannel: ServerSocketChannel,
        handler: NetConnection => Unit,
        listener: NioListener,
        tls: NetTlsConfig
    )(using AllowUnsafe, Frame): Unit =
        startAcceptLoopWith(serverChannel, listener, "TLS accept loop panic") { () =>
            acceptAllPendingTls(serverChannel, handler, listener, tls)
        }
    end startTlsAcceptLoop

    private def acceptAllPendingTls(
        serverChannel: ServerSocketChannel,
        handler: NetConnection => Unit,
        listener: NioListener,
        tls: NetTlsConfig
    )(using AllowUnsafe, Frame): Unit =
        def tryAcceptOne(): Boolean =
            try
                val clientChannel = serverChannel.accept()
                if clientChannel ne null then
                    clientChannel.configureBlocking(false)
                    clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                    Log.live.unsafe.debug(
                        s"NioTransport TLS accepted client channel=${clientChannel.hashCode()} on server port=${listener.port}"
                    )

                    // Create the handshake handle here (rather than inside startTlsHandshake) so the connPromise teardown arms below can route
                    // through driver.closeHandle(handle). The handle owns the parked awaitRead and the driver's pendingReads[channel] -> handle
                    // entry; a bare clientChannel.close() on a failed/timed-out handshake strands that entry and the armed IOPromise. Passing the
                    // handle as existingHandle to startTlsHandshake reuses it (no double registerChannel).
                    val handle = NioHandle.init(clientChannel, readBufferSize)
                    discard(driver.registerChannel(handle))

                    // Create a per-connection promise for the TLS handshake result
                    val connPromise = new IOPromise[NetException, Connection[NioHandle]]
                    connPromise.onComplete { result =>
                        result match
                            case Result.Success(connection) =>
                                // Handshake complete: spawn the handler in its own carrier fiber. Fire-and-forget.
                                discard(Fiber.Unsafe.init {
                                    // Contain ANY throw from the user handler (not just NonFatal): a throw must never escape to the carrier, abort
                                    // the process, or stall the accept loop. Uniform with the posix and node backends.
                                    try handler(connection: NetConnection)
                                    catch
                                        case e: Throwable =>
                                            Log.live.unsafe.error(s"TLS connection handler panic", e)
                                })
                            case Result.Failure(closed) =>
                                Log.live.unsafe.warn(s"TLS handshake failed for client: ${closed.getMessage}")
                                // Reap the handle through the driver first (removes the pendingReads entry + fails the parked read), the same seam
                                // PosixTransport.teardown uses, then close the channel. Reached by both a handshake failure and the deadline arm.
                                driver.closeHandle(handle)
                                try clientChannel.close()
                                catch case _: IOException => ()
                            case Result.Panic(e) =>
                                Log.live.unsafe.error(s"TLS handshake panic", e)
                                driver.closeHandle(handle)
                                try clientChannel.close()
                                catch case _: IOException => ()
                    }

                    // One deadline per accepted connection: a client that completed the TCP accept but stalls the TLS handshake (sends nothing /
                    // a partial ClientHello) would otherwise leave connPromise parked forever, pinning the channel + handle + buffers (a slowloris
                    // handshake-stall DoS, CWE-400). When handshakeTimeout is finite, fail connPromise on the deadline; its onComplete Failure arm
                    // runs the existing channel teardown (the same cleanup a failed handshake already uses). connPromise completes at most once, so
                    // the deadline and the handshake outcome are mutually exclusive. handshakeTimeout = Infinity arms no timer.
                    armHandshakeDeadline(connPromise, listener.host, listener.port)

                    startTlsHandshake(
                        clientChannel,
                        listener.host,
                        listener.port,
                        tls,
                        isServer = true,
                        connPromise,
                        existingHandle = Present(handle),
                        preRead = Absent
                    )
                    true   // accepted one, try again
                else false // no more pending
                end if
            catch
                case _: IOException if listener.isClosed => false
                case e: IOException =>
                    Log.live.unsafe.error(s"TLS accept error", e)
                    false
        @tailrec def acceptLoop(): Unit =
            if !listener.isClosed && tryAcceptOne() then acceptLoop()
        acceptLoop()
    end acceptAllPendingTls

    /** Arm a `Clock`-driven deadline for one accepted connection's server TLS handshake. When `handshakeTimeout` is finite, schedule
      * `Clock.live.unsafe.sleep(d).onComplete(...)` (a timer fiber on the clock executor, never a blocked carrier) and fail `connPromise` with a
      * `Closed` when the deadline fires. `connPromise.completeDiscard` completes the promise at most once, so the deadline and the handshake
      * outcome are mutually exclusive: a deadline that fires after the handshake already completed is a no-op, and a handshake that completes
      * after the deadline already failed `connPromise` is a no-op. The deadline-failed `connPromise` runs the existing `onComplete` Failure arm
      * (closing the accepted channel), reaping the stalled handshake with the same teardown a failed handshake already uses. When the handshake
      * completes first, it disarms the timer by interrupting the timer fiber, so the timer never fires. `Duration.Infinity` (the default) arms no
      * timer (no handshake deadline).
      */
    private def armHandshakeDeadline(
        connPromise: IOPromise[NetException, Connection[NioHandle]],
        host: String,
        port: Int
    )(using AllowUnsafe, Frame): Unit =
        if handshakeTimeout.isFinite then
            val timer = Clock.live.unsafe.sleep(handshakeTimeout)
            timer.onComplete { _ =>
                connPromise.completeDiscard(Result.fail(NetTlsHandshakeException(host, port, "")))
            }
            // Disarm: when the handshake outcome completes connPromise first, interrupt the timer fiber so it never fires.
            connPromise.onComplete { _ =>
                timer.interruptDiscard(Result.Panic(Closed("NioTransport", summon[Frame], "handshake completed before deadline")))
            }
    end armHandshakeDeadline

    /** Arm a `Clock`-driven deadline for one in-flight client TCP connect, mirroring [[armHandshakeDeadline]]. When `connectTimeout` is finite,
      * schedule `Clock.live.unsafe.sleep(d).onComplete(...)` (a timer fiber on the clock executor, never a blocked carrier) and fail `connPromise`
      * with `NetConnectTimeoutException(host, port, connectTimeout)` when the deadline fires. `connPromise.completeDiscard` completes the promise
      * at most once, so the deadline and the OS connect outcome are mutually exclusive: a deadline that fires after the connect already completed
      * is a no-op, and a connect that completes after the deadline already failed `connPromise` is a no-op. This is the close-cause
      * discrimination: the deadline arm is the only producer of the typed timeout leaf, so a deadline-fired close surfaces
      * `NetConnectTimeoutException` while an OS-failure close (refused/unreachable/reset) surfaces `NetConnectException` through the existing
      * `connectFail` path. When the connect completes first it disarms the timer by interrupting the timer fiber, so the timer never fires.
      */
    private def armConnectDeadline(
        connPromise: IOPromise[NetException, Connection[NioHandle]],
        host: String,
        port: Int
    )(using AllowUnsafe, Frame): Unit =
        if connectTimeout.isFinite then
            val timer = Clock.live.unsafe.sleep(connectTimeout)
            timer.onComplete { _ =>
                connPromise.completeDiscard(Result.fail(NetConnectTimeoutException(host, port, connectTimeout)))
            }
            // Disarm: when the connect outcome completes connPromise first, interrupt the timer fiber so it never fires.
            connPromise.onComplete { _ =>
                timer.interruptDiscard(Result.Panic(Closed("NioTransport", summon[Frame], "connect completed before deadline")))
            }
    end armConnectDeadline

    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, Connection[NioHandle]]

        try
            val addr    = java.net.UnixDomainSocketAddress.of(path)
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            channel.configureBlocking(false)
            Log.live.unsafe.debug(s"NioTransport connectUnix $path channel=${channel.hashCode()}")

            val connected = channel.connect(addr)
            Log.live.unsafe.debug(s"NioTransport connectUnix immediate=$connected channel=${channel.hashCode()}")
            if connected then
                val handle = NioHandle.init(channel, readBufferSize)
                discard(driver.registerChannel(handle))
                completeConnect(handle, promise)
            else
                // port = -1 sentinel: a Unix socket has no port, so connectFail routes failures to NetUnixConnectException.
                awaitConnect(channel, path, -1, promise)
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(NetUnixConnectException(path, e)))
        end try

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[NioHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked
        // Transport.connectUnix return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection
        // values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end connectUnix

    def listenUnix(path: String, backlog: Int)(
        handler: NetConnection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetListener, Abort[NetException]] =
        val promise = new IOPromise[NetException, NetListener]

        try
            val addr          = java.net.UnixDomainSocketAddress.of(path)
            val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            serverChannel.configureBlocking(false)
            serverChannel.bind(addr, backlog)

            if !driver.registerServerChannel(serverChannel) then
                serverChannel.close()
                promise.completeDiscard(Result.fail(NetBindException(path, -1, "")))
            else
                Log.live.unsafe.debug(s"NioTransport listenUnix $path")

                val listener = new NioListener(serverChannel, -1, path, driver, NetAddress.Unix(path))
                startAcceptLoop(serverChannel, handler, listener)
                promise.completeDiscard(Result.succeed(listener))
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(NetBindException(path, -1, e)))
        end try

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, NetListener], even though both erase to the same runtime object; the alias
        // is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked Transport.listen (Unix) return
        // needs this erased-boundary cast. Safe: the promise completes only with the NetException/NetListener values above.
        promise.asInstanceOf[Fiber.Unsafe[NetListener, Abort[NetException]]]
    end listenUnix

    def close()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Close every still-open connection first, while the driver is alive, so each connection's fd is reclaimed instead of being stranded when
        // the pool tears down; a connection whose ordinary close never ran (peer FIN never arrived, handler never closed it) would otherwise leak.
        // forceCloseIfUpgrading additionally covers a connection stuck Upgrading (ordinary close() is a no-op there by design, deferring to the
        // in-flight TLS upgrade's own success/failure cleanup): at shutdown nothing will ever complete that upgrade, so without the force-close a
        // peer that disconnects mid-handshake leaks the fd until the upgrade's own (asynchronous, unbounded-by-this-call) failure path happens to
        // run (see Connection.forceCloseIfUpgrading's scaladoc; the posix transport hits the identical gap).
        connections.values().forEach { c =>
            c.close()
            c.forceCloseIfUpgrading()
        }
        connections.clear()
        // Each c.close() above cancels its channel's SelectionKey and calls channel.close(), but on JDK 11+ the actual fd close (kill()) is
        // deferred until the selector deregisters the cancelled key on its own next select() pass (see NioIoDriver.wakeup's scaladoc). The
        // driver's select() call is indefinite (no timeout), so an otherwise-idle selector (this transport's last connection just closed, nothing
        // else pending) never runs that pass on its own and every fd closed above leaks in CLOSE_WAIT past this call returning. NioListener.close
        // already wakes the selector for the identical reason on a listener close; connection close here had no equivalent nudge.
        driver.wakeup()
        // The pool's fiber is this transport's release signal: it completes once the driver has torn down and its fds are gone.
        pool.close()
    end close

    /** Upgrade a plaintext [[Connection]] to TLS after STARTTLS-style pre-handshake bytes have been exchanged.
      *
      * Steps:
      *   1. Extract the underlying [[NioHandle]] from `conn`.
      *   2. Cancel the handle's selector registration and fail pending I/O promises (so the old pumps drain and stop).
      *   3. Close the old connection's channels (inbound/outbound): this signals the pumps to exit.
      *   4. Drive the TLS handshake over the same [[SocketChannel]] using [[startTlsHandshake]] (passing the existing handle so no new
      *      channel is opened).
      *   5. Return the new [[Connection]] built by `completeConnect` after the handshake.
      *
      * The old [[Connection]] must NOT be used after calling this method.
      */
    def upgradeToTls(
        conn: NetConnection,
        tls: kyo.net.NetTlsConfig,
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        conn match
            case nioConn: Connection[NioHandle] @unchecked if nioConn.handle.isInstanceOf[NioHandle] =>
                upgradeToTlsNio(nioConn, tls, channelCapacity)
            case _ =>
                // Not an upgradable NIO connection (e.g. Connection.inMemory): abort typed, never a cast crash.
                Fiber.Unsafe.fromResult(Result.fail(NetNotUpgradableException()))
        end match
    end upgradeToTls

    private def upgradeToTlsNio(
        nioConn: Connection[NioHandle],
        tls: kyo.net.NetTlsConfig,
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[NetConnection, Abort[NetException]] =
        val promise = new IOPromise[NetException, Connection[NioHandle]]
        // The SNI host the upgrade engine verifies against; also the host reported by any handshake failure (an upgrade has no fresh port, so -1).
        val host = tls.sniHostname.getOrElse("")
        try
            val handle = nioConn.handle
            // One upgrade per connection: win the one-shot claim BEFORE arming any shared upgrade state (the owner hook, the abandon thunk,
            // the handle's upgrading flag). A second upgradeToTls call on the same connection would otherwise overwrite the in-flight
            // upgrade's `upgradeAbandon` with a thunk over its own about-to-fail promise (permanently disarming close()'s only route to the
            // first upgrade's channel) and, via the unguarded detach fallthrough, drive a second handshake over a channel the first upgrade
            // still owns.
            if !nioConn.claimUpgrade() then promise.completeDiscard(Result.fail(NetAlreadyDetachedException()))
            else
                // Central failure-close for the upgrade: detachForUpgrade gives up the plaintext connection's ownership of the channel WITHOUT
                // closing it, and on a STARTTLS handshake failure startTlsHandshake (existingHandle present) does not close it either, so the
                // channel would leak (e.g. a verifying client with no reference identity rejecting the upgrade). On success completeConnect wraps
                // the channel in a new tracked Connection that owns it, so the success arm skips the close.
                promise.onComplete {
                    case Result.Success(_) =>
                        // The handshake's FINISHED branch already cleared the handoff flags before completeConnect; ensure they are clear.
                        handle.upgrading = false
                        handle.handshakeReading = false
                    case _ =>
                        // Failure / interrupt: close the handoff window so a torn-down handle never lingers mid-upgrade, then release the fd.
                        handle.upgrading = false
                        handle.handshakeReading = false
                        closeQuietly(handle.channel)
                }
                // `promise` owns the detached channel for the whole upgrade (the onComplete failure arm above is what releases it), so route a
                // close() of the plaintext connection to it: settling `promise` runs the same release a handshake failure takes, and once the
                // upgrade has succeeded the promise is complete and this is inherently a no-op, leaving the upgraded connection's channel
                // untouched. Armed BEFORE the detach, so no close() can observe the connection Upgrading without an owner to hand itself to.
                // Without it a close() (a scope teardown, a transport-level sweep) cannot reach a detached channel at all: Connection.closeFn
                // never takes an Upgrading fd.
                nioConn.upgradeAbandon =
                    Present(() => promise.interruptDiscard(Result.Failure(NetConnectionClosedException(Operation.Close))))
                // Mark the handle upgrading BEFORE detach so the selector carrier recognizes the window: while set, a plaintext read the pump
                // pulls off the socket is STASHED into the handle's salvage (NioIoDriver.dispatchReadPlain / onInboundClosedDuringRead) rather
                // than completing the pump's promise (which would drop the peer's first TLS flight) or re-arming (which would steal the
                // handshake's read). Mirrors PosixHandle.upgradeActive being set before detach. Cleared at handshake completion / failure.
                handle.upgrading = true
                // Step 1: detach the handle, closing inbound/outbound channels and cancelling selector
                // registration, but does NOT close the underlying SocketChannel.
                // Capture any bytes the ReadPump already pulled off the socket and staged in the inbound
                // channel but that were never consumed. During a STARTTLS upgrade these are the peer's
                // first TLS handshake flight (ClientHello/ServerHello ciphertext): they are already OFF
                // the socket, so the SSLEngine (which reads from the SocketChannel) will never see them.
                // They MUST be replayed into the handshake's network-input buffer, otherwise the handshake
                // blocks forever waiting for data that was consumed and discarded. Whether the pump raced
                // ahead and buffered this flight is timing-dependent on fast loopback, so preRead is often
                // empty (no-op) and sometimes non-empty (corrective).
                val preRead: Maybe[Chunk[Span[Byte]]] = nioConn.detachForUpgrade()
                if preRead.isEmpty then
                    // The claim was won but the connection reached a terminal state before the detach (a close raced or preceded this call):
                    // nothing was detached and the close path owns the channel, so fail typed. The failure settlement runs the owner arm
                    // above, whose closeQuietly is idempotent against the close that won.
                    promise.completeDiscard(Result.fail(NetAlreadyDetachedException()))
                // Step 2: re-register the same channel (still open) with the driver for TLS handshake I/O.
                else if !driver.registerChannel(handle) then
                    promise.completeDiscard(Result.fail(NetTlsHandshakeException(host, -1, "")))
                else
                    // Step 3: drive TLS handshake on the same SocketChannel.
                    // The TLS role follows the connection's TCP origin: an accepted connection (isServerOrigin) upgrades as the TLS server, a
                    // connected one as the client (e.g. a Postgres client doing an SSLRequest upgrade). The origin is authoritative: a config
                    // heuristic ("has a cert+key therefore server") would misclassify a mutual-TLS client that presents its own client certificate.
                    val isServer = nioConn.isServerOrigin
                    startTlsHandshake(handle.channel, host, -1, tls, isServer = isServer, promise, Present(handle), preRead)
                end if
            end if
        catch
            case e: Exception =>
                promise.completeDiscard(Result.fail(NetTlsHandshakeException(host, -1, e)))
        end try
        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed, invariant IOPromise[NetException, Connection[NioHandle]], even though both erase to the same runtime object;
        // the alias is transparent only inside kyo.Fiber's own defining scope, so exposing this promise as the locked
        // Transport.upgradeToTls return needs this erased-boundary cast. Safe: the promise completes only with the NetException/Connection
        // values above.
        promise.asInstanceOf[Fiber.Unsafe[NetConnection, Abort[NetException]]]
    end upgradeToTlsNio

end NioTransport

/** Factory and SSL helpers for `NioTransport`. */
private[kyo] object NioTransport:
    def init(
        channelCapacity: Int,
        readBufferSize: Int,
        connectTimeout: Duration,
        handshakeTimeout: Duration
    )(using AllowUnsafe, Frame): NioTransport =
        // Build the concrete NioIoDriver via the floor backend (NioBackend is the registry's Nio entry). NioTransport
        // needs the concrete NioIoDriver (it calls NIO-specific channel-registration methods), so it is constructed
        // directly here; the registry-level backend selection (-Dkyo.net.backend, the posix/Nio choice) happens in
        // IoBackendPlatform.transport, which invokes this init only when the Nio floor is the selected entry.
        val driver = kyo.net.internal.backend.NioBackend.createDriver(kyo.net.TransportConfig.default)
        discard(driver.start())
        new NioTransport(driver, channelCapacity, readBufferSize, connectTimeout, handshakeTimeout)
    end init

    /** Create an SSLContext from NetTlsConfig.
      *
      * For client: uses trustAll, a custom CA cert from `caCertPath`, or the default trust store. For server: loads key managers from PEM
      * cert/key paths.
      */
    private[kyo] def createSslContext(config: NetTlsConfig, isServer: Boolean)(using AllowUnsafe): SSLContext =
        val ctx = SSLContext.getInstance("TLS")
        // The server verifies client certs against trustStorePath, falling back to caCertPath; the client verifies the server chain against
        // caCertPath.
        val trustAnchor = if isServer then config.trustStorePath.orElse(config.caCertPath) else config.caCertPath
        val tm: Array[javax.net.ssl.TrustManager] =
            if config.trustAll then Array(NioTrustAllManager)
            else
                trustAnchor match
                    case Present(caPath) => loadCaCertTrustManagers(caPath)
                    case Absent          => null // use JDK default trust store
        val km: Array[javax.net.ssl.KeyManager] =
            (config.certChainPath, config.privateKeyPath) match
                case (Present(certPath), Present(keyPath)) =>
                    loadPemKeyManagers(certPath, keyPath)
                case _ => null
        ctx.init(km, tm, null)
        ctx
    end createSslContext

    /** The JDK protocol strings enabled for the configured [minVersion, maxVersion] range, so the version pin is actually enforced. The raw
      * SSLEngine enables a broad default protocol set, so without pinning a TLS1.3-only peer would silently negotiate TLS1.2 with a TLS1.2 peer
      * (CWE-326). Shared by the inline NIO TLS path and SslEngineProvider so both JDK-SSLEngine paths pin identically, matching the
      * BoringSSL/OpenSSL providers (kyo_*_ctx_set_min_max_version) and Node. An inverted range (min > max) enables nothing, failing the
      * handshake closed rather than negotiating an unintended version.
      */
    private[net] def enabledProtocols(config: NetTlsConfig): Array[String] =
        val all = Array("TLSv1.2", "TLSv1.3")
        def idx(v: NetTlsConfig.Version): Int = v match
            case NetTlsConfig.Version.TLS12 => 0
            case NetTlsConfig.Version.TLS13 => 1
        val lo = idx(config.minVersion)
        val hi = idx(config.maxVersion)
        if lo <= hi then all.slice(lo, hi + 1) else Array.empty[String]
    end enabledProtocols

    /** Load a PEM-encoded CA certificate and build TrustManagers that validate against it.
      *
      * Used for `sslmode=verify-ca` and `sslmode=verify-full` to pin server cert validation to a specific CA instead of the JDK default
      * trust store.
      */
    private def loadCaCertTrustManagers(caPath: String)(using AllowUnsafe): Array[javax.net.ssl.TrustManager] =
        import java.io.FileInputStream
        import java.security.KeyStore
        import java.security.cert.CertificateFactory
        import javax.net.ssl.TrustManagerFactory

        val cf       = CertificateFactory.getInstance("X.509")
        val caStream = new FileInputStream(caPath)
        val caCert =
            try cf.generateCertificate(caStream)
            finally caStream.close()

        val ks = KeyStore.getInstance(KeyStore.getDefaultType)
        ks.load(null, null)
        ks.setCertificateEntry("ca", caCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        tmf.init(ks)
        tmf.getTrustManagers
    end loadCaCertTrustManagers

    /** Load PEM certificate chain and private key into KeyManagers for SSLContext.
      *
      * Reads PEM-encoded X.509 certificate chain and PKCS#8 private key, loads them into an in-memory PKCS12 keystore, and returns
      * KeyManagers.
      */
    private def loadPemKeyManagers(certPath: String, keyPath: String)(using AllowUnsafe): Array[javax.net.ssl.KeyManager] =
        import java.io.FileInputStream
        import java.security.KeyFactory
        import java.security.KeyStore
        import java.security.cert.CertificateFactory
        import java.security.spec.PKCS8EncodedKeySpec
        import javax.net.ssl.KeyManagerFactory

        // Load certificate chain
        val certFactory = CertificateFactory.getInstance("X.509")
        val certStream  = new FileInputStream(certPath)
        val certs =
            try certFactory.generateCertificates(certStream)
            finally certStream.close()
        val certArray = new Array[java.security.cert.Certificate](certs.size())
        certs.toArray(certArray)

        // Load private key (PKCS#8 PEM)
        val keyBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(keyPath))
        val keyPem   = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8)
        val keyBase64 = keyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "")
        val keyDer  = java.util.Base64.getDecoder.decode(keyBase64)
        val keySpec = new PKCS8EncodedKeySpec(keyDer)
        val kf      = KeyFactory.getInstance("RSA")
        val privKey = kf.generatePrivate(keySpec)

        // Build in-memory PKCS12 keystore
        val ks       = KeyStore.getInstance("PKCS12")
        val password = "".toCharArray
        ks.load(null, password)
        ks.setKeyEntry("server", privKey, password, certArray)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        kmf.init(ks, password)
        kmf.getKeyManagers
    end loadPemKeyManagers

    /** Returns the SHA-256 hash of the server's leaf certificate DER bytes per RFC 5929 tls-server-end-point.
      *
      * Reads the peer certificate chain from the SSLEngine session after handshake completion. Returns Absent when:
      *   - The connection is not TLS (handle.tls is Absent).
      *   - The underlying channel is closed (connection was closed before this was called).
      *   - The engine has no peer certificates (e.g. the connection is a plain-TCP server-side accepted connection).
      */
    private[kyo] def serverCertificateHash(handle: NioHandle): Maybe[Span[Byte]] =
        import java.security.MessageDigest
        // Guard: if the channel is already closed, the connection is torn down, so return Absent.
        if !handle.channel.isOpen then Absent
        else
            handle.tls match
                case Absent => Absent
                case Present(tlsState) =>
                    try
                        val certs = tlsState.engine.getSession.getPeerCertificates
                        if certs == null || certs.isEmpty then Absent
                        else
                            val leafDer = certs(0).getEncoded
                            val digest  = MessageDigest.getInstance("SHA-256")
                            val hash    = digest.digest(leafDer)
                            Present(Span.from(hash))
                        end if
                    catch
                        case _: javax.net.ssl.SSLPeerUnverifiedException => Absent
                        case _: Exception                                => Absent
            end match
        end if
    end serverCertificateHash

    /** Compute the RFC 8446 6.1 / RFC 5246 7.2.1 close reason for a TLS NIO connection from the handle's observed read-side close signals.
      *
      * Reads the `NioTlsState` flags the NIO Selector carrier set on the read path: `peerCleanClose` (the peer's authenticated close_notify was
      * consumed, an orderly close) and `peerEof` (a bare TCP FIN with no close_notify, the truncation-attack condition). While the connection is
      * still open it reports Active; once it has closed with neither peer signal set, it was a local close. Touches no engine, only the `@volatile`
      * flags, so it is safe to call on the caller's carrier after close. Mirrors `PosixTransport.installStatus` so the inline NIO path and the
      * engine driver path report identical close-reason semantics for the same close sequence.
      */
    private[kyo] def statusFor(connection: Connection[NioHandle], tlsState: NioTlsState)(using
        AllowUnsafe
    ): NetConnection.Status =
        if tlsState.peerCleanClose then NetConnection.Status.CleanClose
        else if tlsState.peerEof then NetConnection.Status.Truncated
        else if connection.isOpen then NetConnection.Status.Active
        else NetConnection.Status.LocalClose
    end statusFor

    /** Trust manager that accepts any certificate chain and auth type without validation.
      *
      * WARNING: for development and testing only; disabling certificate verification leaves connections vulnerable to MITM attacks.
      */
    private object NioTrustAllManager extends X509TrustManager:
        def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
        def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
        def getAcceptedIssuers: Array[X509Certificate]                                = Array.empty
    end NioTrustAllManager

end NioTransport

/** Active server-side listener for a NIO TCP or Unix-domain socket. Holds the server channel and tracks closed state. */
final private class NioListener(
    private val serverChannel: ServerSocketChannel,
    val port: Int,
    val host: String,
    private val driver: NioIoDriver,
    val address: NetAddress
) extends NetListener:
    // Unsafe: created at construction with no ambient AllowUnsafe; the danger bridge builds it here and its accesses run under the caller's
    // AllowUnsafe.
    private val closedFlag = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

    def isClosed(using AllowUnsafe): Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            driver.cleanupAccept(serverChannel)
            try serverChannel.close()
            catch case _: IOException => ()
                // serverChannel.close() cancels the channel's SelectionKey but, on JDK 11+, defers the real fd close (kill()) until the selector
                // deregisters the cancelled key on its next select(). The driver loop selects with no timeout, so an idle driver (no other channel
                // activity) never runs that pass and the listen socket leaks in LISTEN indefinitely. Wake the selector unconditionally so the
                // deferred deregistration + kill runs now, whether or not an accept was pending at close.
            end try
            driver.wakeup()
        end if
    end close
end NioListener
