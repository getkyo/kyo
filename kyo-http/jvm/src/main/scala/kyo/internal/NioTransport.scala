package kyo.internal

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager
import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** JVM TCP transport using a non-blocking NIO `Selector`.
  *
  * Manages the full connection lifecycle for both plain TCP and TLS:
  *   - `connect` / `listen` — plain TCP (IPv4/IPv6)
  *   - `connect(tls)` / `listen(tls)` — TLS via `javax.net.ssl.SSLEngine`
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
    val pool: IoDriverPool[NioHandle],
    private val channelCapacity: Int,
    private val readBufferSize: Int
) extends Transport[NioHandle]:

    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[NioHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[NioHandle]]

        try
            val channel = SocketChannel.open()
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
                // Connection in progress, wait for writable
                awaitConnect(channel, host, port, promise)
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"connect() failed to $host:$port: ${e.getMessage}"
                )))
        end try

        promise.asInstanceOf[Fiber.Unsafe[Connection[NioHandle], Abort[Closed]]]
    end connect

    private def awaitConnect(
        channel: SocketChannel,
        host: String,
        port: Int,
        promise: IOPromise[Closed, Connection[NioHandle]]
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
                    promise.completeDiscard(Result.fail(Closed(
                        "NioTransport",
                        summon[Frame],
                        s"finishConnect() failed for $host:$port: ${e.getMessage}"
                    )))
                    true
        end tryFinishConnect

        if !tryFinishConnect() then
            // Not yet connected — register and let the driver handle OP_CONNECT
            val handle = NioHandle.init(channel, readBufferSize)
            if !driver.registerChannel(handle) then
                channel.close()
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"registerChannel failed for $host:$port"
                )))
            else
                val connectPromise = new IOPromise[Closed, Unit]
                connectPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            Log.live.unsafe.debug(s"NioTransport connected channel=${channel.hashCode()}")
                            completeConnect(handle, promise)
                        case Result.Failure(closed) =>
                            channel.close()
                            promise.completeDiscard(Result.fail(closed))
                        case Result.Panic(e) =>
                            channel.close()
                            promise.completeDiscard(Result.panic(e))
                }
                driver.awaitConnect(handle, connectPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end if
    end awaitConnect

    private def completeConnect(
        handle: NioHandle,
        promise: IOPromise[Closed, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.debug(s"NioTransport completeConnect channel=${handle.channel.hashCode()}")
        val connection = Connection.init(handle, driver, channelCapacity)
        connection.start()
        val completed = promise.complete(Result.succeed(connection))
        if !completed then
            // Promise was already interrupted — nobody will use this connection, close it
            connection.close()
        end if
    end completeConnect

    def listen(host: String, port: Int, backlog: Int)(
        handler: Connection[NioHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        try
            val serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
            serverChannel.bind(new InetSocketAddress(host, port), backlog)

            if !driver.registerServerChannel(serverChannel) then
                serverChannel.close()
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"registerServerChannel failed for $host:$port"
                )))
            else
                val actualPort = serverChannel.socket().getLocalPort
                val actualHost = Option(serverChannel.socket().getInetAddress.getHostAddress).getOrElse(host)
                Log.live.unsafe.debug(s"NioTransport listen $host:$actualPort")

                val listener = new NioListener(serverChannel, actualPort, actualHost, driver, HttpAddress.Tcp(actualHost, actualPort))
                startAcceptLoop(serverChannel, handler, listener)
                promise.completeDiscard(Result.succeed(listener))
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"listen() failed on $host:$port: ${e.getMessage}"
                )))
        end try

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listen

    private def startAcceptLoop(
        serverChannel: ServerSocketChannel,
        handler: Connection[NioHandle] => Unit < Async,
        listener: NioListener
    )(using AllowUnsafe, Frame): Unit =

        def scheduleNextAccept(): Unit =
            if !listener.isClosed then
                val acceptPromise = new IOPromise[Closed, Unit]
                acceptPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            acceptAllPending(serverChannel, handler, listener)
                            scheduleNextAccept()
                        case Result.Failure(_) =>
                            // Server closed
                            ()
                        case Result.Panic(e) =>
                            Log.live.unsafe.error(s"Accept loop panic", e)
                }
                driver.awaitAccept(serverChannel, acceptPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end scheduleNextAccept

        scheduleNextAccept()
    end startAcceptLoop

    private def acceptAllPending(
        serverChannel: ServerSocketChannel,
        handler: Connection[NioHandle] => Unit < Async,
        listener: NioListener
    )(using AllowUnsafe, Frame): Unit =
        def tryAcceptOne(): Boolean =
            try
                val clientChannel = serverChannel.accept()
                if clientChannel ne null then
                    clientChannel.configureBlocking(false)
                    // Unix domain sockets do not support TCP_NODELAY
                    listener.address match
                        case _: HttpAddress.Tcp => clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                        case _                  => ()
                    Log.live.unsafe.debug(
                        s"NioTransport accepted client channel=${clientChannel.hashCode()} on server port=${listener.port}"
                    )

                    val handle = NioHandle.init(clientChannel, readBufferSize)
                    discard(driver.registerChannel(handle))
                    val connection = Connection.init(handle, driver, channelCapacity)
                    connection.start()

                    // Spawn handler fiber - connection lifecycle managed by pumps via closeFn
                    // Do NOT close connection when handler returns - UnsafeServerDispatch returns
                    // immediately and manages lifecycle via idle timeout / parser EOF / channel close
                    val handlerFiber = Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run(handler(connection)).map { result =>
                                result match
                                    case Result.Panic(e) =>
                                        Log.live.unsafe.error(s"Connection handler panic", e)
                                    case _ => ()
                                end match
                            }
                        }
                    }
                    discard(handlerFiber)
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

    def connect(host: String, port: Int, tls: HttpTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[NioHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[NioHandle]]

        try
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
            Log.live.unsafe.debug(s"NioTransport TLS connect $host:$port channel=${channel.hashCode()}")

            val connected = channel.connect(new InetSocketAddress(host, port))
            if connected then
                startTlsHandshake(channel, host, port, tls, isServer = false, promise)
            else
                awaitConnectThenTls(channel, host, port, tls, promise)
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"TLS connect() failed to $host:$port: ${e.getMessage}"
                )))
        end try

        promise.asInstanceOf[Fiber.Unsafe[Connection[NioHandle], Abort[Closed]]]
    end connect

    private def awaitConnectThenTls(
        channel: SocketChannel,
        host: String,
        port: Int,
        tls: HttpTlsConfig,
        promise: IOPromise[Closed, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        // For fast localhost, check if already connected.
        // Returns true if the connect was handled (success or error), false if still pending.
        def tryFinishConnect(): Boolean =
            try
                if channel.finishConnect() then
                    startTlsHandshake(channel, host, port, tls, isServer = false, promise)
                    true
                else false
            catch
                case e: IOException =>
                    channel.close()
                    promise.completeDiscard(Result.fail(Closed(
                        "NioTransport",
                        summon[Frame],
                        s"TLS finishConnect() failed for $host:$port: ${e.getMessage}"
                    )))
                    true
        end tryFinishConnect

        if !tryFinishConnect() then
            // Not yet connected — register and let the driver handle OP_CONNECT
            val handle = NioHandle.init(channel, readBufferSize)
            if !driver.registerChannel(handle) then
                channel.close()
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"registerChannel failed for TLS $host:$port"
                )))
            else
                val connectPromise = new IOPromise[Closed, Unit]
                connectPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            Log.live.unsafe.debug(s"NioTransport TCP connected, starting TLS handshake channel=${channel.hashCode()}")
                            startTlsHandshake(channel, host, port, tls, isServer = false, promise, Present(handle))
                        case Result.Failure(closed) =>
                            channel.close()
                            promise.completeDiscard(Result.fail(closed))
                        case Result.Panic(e) =>
                            channel.close()
                            promise.completeDiscard(Result.panic(e))
                }
                driver.awaitConnect(handle, connectPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end if
    end awaitConnectThenTls

    private def startTlsHandshake(
        channel: SocketChannel,
        host: String,
        port: Int,
        tls: HttpTlsConfig,
        isServer: Boolean,
        connectPromise: IOPromise[Closed, Connection[NioHandle]],
        existingHandle: Maybe[NioHandle] = Absent
    )(using AllowUnsafe, Frame): Unit =
        try
            val sslContext = NioTransport.createSslContext(tls, isServer)
            val engine     = sslContext.createSSLEngine(host, port)
            engine.setUseClientMode(!isServer)

            if !isServer then
                val params = engine.getSSLParameters
                params.setEndpointIdentificationAlgorithm("HTTPS")
                tls.sniHostname.foreach { sni =>
                    params.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(sni)))
                }
                engine.setSSLParameters(params)
            end if

            engine.beginHandshake()

            // Create handle in raw mode (tls = Absent) for handshake.
            // The driver reads raw ciphertext during handshake.
            val handle = existingHandle.getOrElse {
                val h = NioHandle.init(channel, readBufferSize)
                discard(driver.registerChannel(h))
                h
            }

            // Create TLS state but don't attach yet — handshake uses raw I/O
            val session   = engine.getSession
            val netInBuf  = ByteBuffer.allocate(session.getPacketBufferSize)
            val netOutBuf = ByteBuffer.allocate(session.getPacketBufferSize)
            val appInBuf  = ByteBuffer.allocate(session.getApplicationBufferSize)
            val tlsState  = NioTlsState(engine, netInBuf, netOutBuf, appInBuf)

            driveHandshake(handle, tlsState, connectPromise)
        catch
            case e: Exception =>
                if existingHandle.isEmpty then
                    try channel.close()
                    catch case _: IOException => ()
                end if
                connectPromise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"TLS handshake init failed: ${e.getMessage}"
                )))
    end startTlsHandshake

    /** Drive TLS handshake via callback-driven promise chains.
      *
      * During handshake, handle.tls is Absent so the driver reads raw ciphertext. We feed ciphertext to the SSLEngine manually via
      * unwrap/wrap. After handshake completes, we set handle.tls = Present(tlsState) so the driver switches to TLS mode for data transfer.
      */
    private def driveHandshake(
        handle: NioHandle,
        tlsState: NioTlsState,
        connectPromise: IOPromise[Closed, Connection[NioHandle]]
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
                        tlsState.netInBuf.compact()
                        val status = res.getStatus
                        if status eq SSLEngineResult.Status.OK then
                            driveHandshake(handle, tlsState, connectPromise)
                            false
                        else if status eq SSLEngineResult.Status.CLOSED then
                            connectPromise.completeDiscard(Result.fail(Closed(
                                "NioTransport",
                                summon[Frame],
                                "TLS handshake: engine closed during buffered unwrap"
                            )))
                            false
                        else if status eq SSLEngineResult.Status.BUFFER_OVERFLOW then
                            connectPromise.completeDiscard(Result.fail(Closed(
                                "NioTransport",
                                summon[Frame],
                                "TLS handshake: buffer overflow during buffered unwrap"
                            )))
                            false
                        else
                            // BUFFER_UNDERFLOW — need more data from network
                            true
                        end if
                    catch
                        case e: Exception =>
                            connectPromise.completeDiscard(Result.fail(Closed(
                                "NioTransport",
                                summon[Frame],
                                s"TLS handshake buffered unwrap failed: ${e.getMessage}"
                            )))
                            false
                    end try
                else
                    tlsState.netInBuf.compact()
                    true
                end if
            end needMoreData

            if needMoreData then
                // Need more data from network
                val readPromise = new IOPromise[Closed, Span[Byte]]
                readPromise.onComplete { result =>
                    result match
                        case Result.Success(bytes) =>
                            if bytes.isEmpty then
                                connectPromise.completeDiscard(Result.fail(Closed(
                                    "NioTransport",
                                    summon[Frame],
                                    "TLS handshake: connection closed during NEED_UNWRAP"
                                )))
                            else
                                // Feed raw ciphertext to engine
                                val arr = bytes.toArrayUnsafe
                                tlsState.netInBuf.put(arr)
                                tlsState.netInBuf.flip()
                                tlsState.appInBuf.clear()
                                try
                                    val res = engine.unwrap(tlsState.netInBuf, tlsState.appInBuf)
                                    tlsState.netInBuf.compact()
                                    val status = res.getStatus
                                    if (status eq SSLEngineResult.Status.OK) || (status eq SSLEngineResult.Status.BUFFER_UNDERFLOW) then
                                        driveHandshake(handle, tlsState, connectPromise)
                                    else if status eq SSLEngineResult.Status.CLOSED then
                                        connectPromise.completeDiscard(Result.fail(Closed(
                                            "NioTransport",
                                            summon[Frame],
                                            "TLS handshake: engine closed during unwrap"
                                        )))
                                    else // BUFFER_OVERFLOW
                                        connectPromise.completeDiscard(Result.fail(Closed(
                                            "NioTransport",
                                            summon[Frame],
                                            "TLS handshake: buffer overflow during unwrap"
                                        )))
                                    end if
                                catch
                                    case e: Exception =>
                                        connectPromise.completeDiscard(Result.fail(Closed(
                                            "NioTransport",
                                            summon[Frame],
                                            s"TLS handshake unwrap failed: ${e.getMessage}"
                                        )))
                                end try
                            end if
                        case Result.Failure(e) =>
                            connectPromise.completeDiscard(Result.fail(e))
                        case Result.Panic(t) =>
                            connectPromise.completeDiscard(Result.panic(t))
                }
                driver.awaitRead(handle, readPromise.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]])
            end if
        else if hs eq SSLEngineResult.HandshakeStatus.NEED_WRAP then
            try
                tlsState.netOutBuf.clear()
                val emptyBuf = ByteBuffer.allocate(0)
                discard(engine.wrap(emptyBuf, tlsState.netOutBuf))
                tlsState.netOutBuf.flip()
                // Try one write — if socket buffer full, await writable asynchronously
                discard(handle.channel.write(tlsState.netOutBuf))
                if tlsState.netOutBuf.hasRemaining then
                    flushHandshakeWrite(handle, tlsState, connectPromise)
                else
                    driveHandshake(handle, tlsState, connectPromise)
                end if
            catch
                case e: Exception =>
                    connectPromise.completeDiscard(Result.fail(Closed(
                        "NioTransport",
                        summon[Frame],
                        s"TLS handshake wrap failed: ${e.getMessage}"
                    )))
        else if hs eq SSLEngineResult.HandshakeStatus.NEED_TASK then
            val taskFiber = Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Sync.Unsafe.defer {
                        @tailrec def runTasks(): Unit =
                            val task = engine.getDelegatedTask
                            if task != null then
                                task.run()
                                runTasks()
                        end runTasks
                        runTasks()
                    }
                }
            }
            taskFiber.unsafe.onComplete { _ =>
                driveHandshake(handle, tlsState, connectPromise)
            }
        else // FINISHED or NOT_HANDSHAKING
            Log.live.unsafe.debug(s"NioTransport TLS handshake complete channel=${handle.channel.hashCode()}")
            engine.setEnableSessionCreation(false) // disable renegotiation
            // Switch handle to TLS mode — driver will now unwrap/wrap inline
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
        connectPromise: IOPromise[Closed, Connection[NioHandle]]
    )(using AllowUnsafe, Frame): Unit =
        val writablePromise = new IOPromise[Closed, Unit]
        writablePromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    try
                        discard(handle.channel.write(tlsState.netOutBuf))
                        if tlsState.netOutBuf.hasRemaining then
                            // Still not flushed — await writable again
                            flushHandshakeWrite(handle, tlsState, connectPromise)
                        else
                            driveHandshake(handle, tlsState, connectPromise)
                        end if
                    catch
                        case e: Exception =>
                            connectPromise.completeDiscard(Result.fail(Closed(
                                "NioTransport",
                                summon[Frame],
                                s"TLS handshake wrap flush failed: ${e.getMessage}"
                            )))
                case Result.Failure(e) =>
                    connectPromise.completeDiscard(Result.fail(e))
                case Result.Panic(t) =>
                    connectPromise.completeDiscard(Result.panic(t))
        }
        driver.awaitWritable(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
    end flushHandshakeWrite

    def listen(host: String, port: Int, backlog: Int, tls: HttpTlsConfig)(
        handler: Connection[NioHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        try
            val serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
            serverChannel.bind(new InetSocketAddress(host, port), backlog)

            if !driver.registerServerChannel(serverChannel) then
                serverChannel.close()
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"registerServerChannel failed for TLS $host:$port"
                )))
            else
                val actualPort = serverChannel.socket().getLocalPort
                val actualHost = Option(serverChannel.socket().getInetAddress.getHostAddress).getOrElse(host)
                Log.live.unsafe.debug(s"NioTransport TLS listen $host:$actualPort")

                val listener = new NioListener(serverChannel, actualPort, actualHost, driver, HttpAddress.Tcp(actualHost, actualPort))
                startTlsAcceptLoop(serverChannel, handler, listener, tls)
                promise.completeDiscard(Result.succeed(listener))
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"TLS listen() failed on $host:$port: ${e.getMessage}"
                )))
        end try

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listen

    private def startTlsAcceptLoop(
        serverChannel: ServerSocketChannel,
        handler: Connection[NioHandle] => Unit < Async,
        listener: NioListener,
        tls: HttpTlsConfig
    )(using AllowUnsafe, Frame): Unit =

        def scheduleNextAccept(): Unit =
            if !listener.isClosed then
                val acceptPromise = new IOPromise[Closed, Unit]
                acceptPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            acceptAllPendingTls(serverChannel, handler, listener, tls)
                            scheduleNextAccept()
                        case Result.Failure(_) =>
                            // Server closed
                            ()
                        case Result.Panic(e) =>
                            Log.live.unsafe.error(s"TLS accept loop panic", e)
                }
                driver.awaitAccept(serverChannel, acceptPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
        end scheduleNextAccept

        scheduleNextAccept()
    end startTlsAcceptLoop

    private def acceptAllPendingTls(
        serverChannel: ServerSocketChannel,
        handler: Connection[NioHandle] => Unit < Async,
        listener: NioListener,
        tls: HttpTlsConfig
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

                    // Create a per-connection promise for the TLS handshake result
                    val connPromise = new IOPromise[Closed, Connection[NioHandle]]
                    connPromise.onComplete { result =>
                        result match
                            case Result.Success(connection) =>
                                // Handshake done — spawn handler fiber
                                val handlerFiber = Sync.Unsafe.evalOrThrow {
                                    Fiber.initUnscoped {
                                        Abort.run(handler(connection)).map { result =>
                                            result match
                                                case Result.Panic(e) =>
                                                    Log.live.unsafe.error(s"TLS connection handler panic", e)
                                                case _ => ()
                                            end match
                                        }
                                    }
                                }
                                discard(handlerFiber)
                            case Result.Failure(closed) =>
                                Log.live.unsafe.warn(s"TLS handshake failed for client: ${closed.getMessage}")
                                try clientChannel.close()
                                catch case _: IOException => ()
                            case Result.Panic(e) =>
                                Log.live.unsafe.error(s"TLS handshake panic", e)
                                try clientChannel.close()
                                catch case _: IOException => ()
                    }

                    startTlsHandshake(
                        clientChannel,
                        listener.host,
                        listener.port,
                        tls,
                        isServer = true,
                        connPromise
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

    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[NioHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[NioHandle]]

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
                awaitConnect(channel, path, 0, promise)
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"connectUnix() failed to $path: ${e.getMessage}"
                )))
        end try

        promise.asInstanceOf[Fiber.Unsafe[Connection[NioHandle], Abort[Closed]]]
    end connectUnix

    def listenUnix(path: String, backlog: Int)(
        handler: Connection[NioHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        try
            val addr          = java.net.UnixDomainSocketAddress.of(path)
            val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            serverChannel.configureBlocking(false)
            serverChannel.bind(addr, backlog)

            if !driver.registerServerChannel(serverChannel) then
                serverChannel.close()
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"registerServerChannel failed for unix:$path"
                )))
            else
                Log.live.unsafe.debug(s"NioTransport listenUnix $path")

                val listener = new NioListener(serverChannel, -1, path, driver, HttpAddress.Unix(path))
                startAcceptLoop(serverChannel, handler, listener)
                promise.completeDiscard(Result.succeed(listener))
            end if
        catch
            case e: IOException =>
                promise.completeDiscard(Result.fail(Closed(
                    "NioTransport",
                    summon[Frame],
                    s"listenUnix() failed on $path: ${e.getMessage}"
                )))
        end try

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listenUnix

    def close()(using AllowUnsafe, Frame): Unit =
        driver.close()

end NioTransport

/** Factory and SSL helpers for `NioTransport`. */
private[kyo] object NioTransport:
    def init(
        channelCapacity: Int,
        readBufferSize: Int
    )(using AllowUnsafe, Frame): NioTransport =
        val driver  = NioIoDriver.init()
        val drivers = Array[IoDriver[NioHandle]](driver)
        val pool    = IoDriverPool.init(drivers)
        discard(driver.start())
        new NioTransport(driver, pool, channelCapacity, readBufferSize)
    end init

    /** Create an SSLContext from HttpTlsConfig.
      *
      * For client: uses trustAll or default trust store. For server: loads key managers from PEM cert/key paths.
      */
    private[internal] def createSslContext(config: HttpTlsConfig, isServer: Boolean)(using AllowUnsafe): SSLContext =
        val ctx = SSLContext.getInstance("TLS")
        val tm: Array[javax.net.ssl.TrustManager] =
            if config.trustAll then Array(NioTrustAllManager)
            else null // use default trust store
        val km: Array[javax.net.ssl.KeyManager] =
            (config.certChainPath, config.privateKeyPath) match
                case (Present(certPath), Present(keyPath)) =>
                    loadPemKeyManagers(certPath, keyPath)
                case _ => null
        ctx.init(km, tm, null)
        ctx
    end createSslContext

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
    val address: HttpAddress
) extends Listener:
    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    def isClosed: Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            driver.cleanupAccept(serverChannel)
            try serverChannel.close()
            catch case _: IOException => ()
        end if
    end close
end NioListener
