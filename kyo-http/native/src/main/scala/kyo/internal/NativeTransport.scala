package kyo.internal

import kyo.*
import kyo.internal.transport.*
import kyo.scheduler.IOPromise
import scala.annotation.tailrec
import scala.scalanative.unsafe.*

/** Scala Native TCP transport using epoll (Linux) or kqueue (macOS/BSD) via `PollerBackend`.
  *
  * Manages the full connection lifecycle for both plain TCP/Unix and TLS:
  *   - `connect` / `listen` — non-blocking TCP (IPv4/IPv6) using POSIX sockets via `PosixBindings`
  *   - `connectUnix` / `listenUnix` — Unix-domain sockets
  *   - `connect(tls)` / `listen(tls)` — TLS via OpenSSL memory BIOs (`TlsBindings`)
  *
  * TLS handshakes are driven through a chain of async callbacks (`driveHandshake`, `flushTlsOutputAsync`, `continueAfterFlush`) without
  * blocking any thread. `kyo_tls_handshake` returns a simple status code (1=done, 0=want_read, -1=want_write, -2=error) that drives the
  * next step.
  *
  * The `acceptDriverCounter` round-robins accepted connections across `NativeIoDriver` instances so the I/O load is spread across poll
  * loops when `poolSize > 1`.
  *
  * Note: TCP_NODELAY and non-blocking mode are set inside the C helpers (`kyo_tcp_accept`, `kyo_tcp_connect`) so the transport does not
  * need to repeat those calls.
  */
final private[kyo] class NativeTransport private (
    val pool: IoDriverPool[NativeHandle],
    private val nativeDrivers: Array[NativeIoDriver],
    private val channelCapacity: Int,
    private val readBufferSize: Int
) extends Transport[NativeHandle]:

    private val acceptDriverCounter = new java.util.concurrent.atomic.AtomicLong(0)

    private def nextNativeDriver(): NativeIoDriver =
        val idx = (acceptDriverCounter.getAndIncrement() % nativeDrivers.length).toInt
        nativeDrivers(Math.abs(idx))

    import PosixBindings.*

    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[NativeHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[NativeHandle]]
        val driver  = pool.next()

        Zone {
            val outPending = alloc[CInt](1)
            val fd         = tcpConnect(toCString(host), port, outPending)
            if fd < 0 then
                promise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], s"connect() failed to $host:$port")))
            else if outPending(0) == 0 then
                // Immediate connection (localhost)
                Log.live.unsafe.debug(s"NativeTransport connect immediate fd=$fd")
                val handle = NativeHandle.init(fd, readBufferSize)
                completeConnect(handle, driver, promise)
            else
                // Connection in progress, wait for writable
                Log.live.unsafe.debug(s"NativeTransport connect $host:$port fd=$fd pending=${outPending(0)}")
                awaitConnect(fd, driver, host, port, promise)
            end if
        }

        promise.asInstanceOf[Fiber.Unsafe[Connection[NativeHandle], Abort[Closed]]]
    end connect

    private def awaitConnect(
        fd: Int,
        driver: IoDriver[NativeHandle],
        host: String,
        port: Int,
        promise: IOPromise[Closed, Connection[NativeHandle]]
    )(using AllowUnsafe, Frame): Unit =
        // Create a writable promise to wait for connect completion
        val writablePromise = new IOPromise[Closed, Unit]

        val handle = NativeHandle.init(fd, readBufferSize)
        Log.live.unsafe.debug(s"NativeTransport awaitConnect fd=$fd")

        writablePromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    // Check if connection succeeded
                    val err = tcpConnectError(fd)
                    if err != 0 then
                        tcpClose(fd)
                        promise.completeDiscard(Result.fail(Closed(
                            "NativeTransport",
                            summon[Frame],
                            s"connect() failed to $host:$port, errno=$err"
                        )))
                    else
                        completeConnect(handle, driver, promise)
                    end if
                case Result.Failure(closed) =>
                    tcpClose(fd)
                    promise.completeDiscard(Result.fail(closed))
                case Result.Panic(e) =>
                    tcpClose(fd)
                    promise.completeDiscard(Result.panic(e))
        }

        driver.awaitConnect(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
    end awaitConnect

    private def completeConnect(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        promise: IOPromise[Closed, Connection[NativeHandle]]
    )(using
        AllowUnsafe,
        Frame
    ): Unit =
        Log.live.unsafe.debug(s"NativeTransport completeConnect fd=${handle.fd}")
        val connection = Connection.init(handle, driver, channelCapacity)
        connection.start()
        val completed = promise.complete(Result.succeed(connection))
        if !completed then
            // Promise was already interrupted — nobody will use this connection, close it
            Log.live.unsafe.warn(s"NativeTransport completeConnect REJECTED (promise already done) fd=${handle.fd}")
            connection.close()
        end if
    end completeConnect

    def listen(host: String, port: Int, backlog: Int)(
        handler: Connection[NativeHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        Zone {
            val outPort  = alloc[CInt](1)
            val serverFd = tcpListen(toCString(host), port, backlog, outPort)
            if serverFd < 0 then
                promise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], s"listen() failed on $host:$port")))
            else
                val actualPort   = outPort(0)
                val acceptDriver = nextNativeDriver()
                Log.live.unsafe.debug(s"NativeTransport listen $host:$actualPort")
                val listener = new NativeListener(serverFd, actualPort, host, acceptDriver, HttpAddress.Tcp(host, actualPort))
                startAcceptLoop(serverFd, handler, listener, acceptDriver)
                promise.completeDiscard(Result.succeed(listener))
            end if
        }

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listen

    private def startAcceptLoop(
        serverFd: Int,
        handler: Connection[NativeHandle] => Unit < Async,
        listener: NativeListener,
        acceptDriver: NativeIoDriver
    )(using AllowUnsafe, Frame): Unit =

        def scheduleNextAccept(): Unit =
            if !listener.isClosed then
                val acceptPromise = new IOPromise[Closed, Unit]
                acceptPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            acceptAllPending(serverFd, handler, listener)
                            scheduleNextAccept()
                        case Result.Failure(_) =>
                            // Server closed
                            ()
                        case Result.Panic(e) =>
                            Log.live.unsafe.error(s"Accept loop panic", e)
                }
                acceptDriver.awaitAccept(serverFd, acceptPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end scheduleNextAccept

        scheduleNextAccept()
    end startAcceptLoop

    @tailrec
    private def acceptAllPending(
        serverFd: Int,
        handler: Connection[NativeHandle] => Unit < Async,
        listener: NativeListener
    )(using AllowUnsafe, Frame): Unit =
        if listener.isClosed then ()
        else
            val clientFd = tcpAccept(serverFd)
            if clientFd < 0 then ()
            else
                // tcpAccept already sets non-blocking and TCP_NODELAY
                Log.live.unsafe.debug(s"NativeTransport accepted client fd=$clientFd on server port=${listener.port}")
                val connDriver = pool.next()
                val handle     = NativeHandle.init(clientFd, readBufferSize)
                val connection = Connection.init(handle, connDriver, channelCapacity)
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
                acceptAllPending(serverFd, handler, listener)
            end if
    end acceptAllPending

    def connect(host: String, port: Int, tls: HttpTlsConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection[NativeHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[NativeHandle]]
        val driver  = pool.next()

        Zone {
            val outPending = alloc[CInt](1)
            val fd         = tcpConnect(toCString(host), port, outPending)
            if fd < 0 then
                promise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], s"TLS connect() failed to $host:$port")))
            else if outPending(0) == 0 then
                // Immediate connection (localhost)
                Log.live.unsafe.debug(s"NativeTransport TLS connect immediate fd=$fd")
                val handle = NativeHandle.init(fd, readBufferSize)
                startTlsHandshake(handle, driver, host, tls, isServer = false, promise)
            else
                // Connection in progress, wait for writable then start TLS
                Log.live.unsafe.debug(s"NativeTransport TLS connect $host:$port fd=$fd pending=${outPending(0)}")
                awaitConnectThenTls(fd, driver, host, port, tls, promise)
            end if
        }

        promise.asInstanceOf[Fiber.Unsafe[Connection[NativeHandle], Abort[Closed]]]
    end connect

    private def awaitConnectThenTls(
        fd: Int,
        driver: IoDriver[NativeHandle],
        host: String,
        port: Int,
        tls: HttpTlsConfig,
        promise: IOPromise[Closed, Connection[NativeHandle]]
    )(using AllowUnsafe, Frame): Unit =
        val writablePromise = new IOPromise[Closed, Unit]
        val handle          = NativeHandle.init(fd, readBufferSize)
        Log.live.unsafe.debug(s"NativeTransport awaitConnectThenTls fd=$fd")

        writablePromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    val err = tcpConnectError(fd)
                    if err != 0 then
                        tcpClose(fd)
                        promise.completeDiscard(Result.fail(Closed(
                            "NativeTransport",
                            summon[Frame],
                            s"TLS connect() failed to $host:$port, errno=$err"
                        )))
                    else
                        Log.live.unsafe.debug(s"NativeTransport TCP connected, starting TLS handshake fd=$fd")
                        startTlsHandshake(handle, driver, host, tls, isServer = false, promise)
                    end if
                case Result.Failure(closed) =>
                    tcpClose(fd)
                    promise.completeDiscard(Result.fail(closed))
                case Result.Panic(e) =>
                    tcpClose(fd)
                    promise.completeDiscard(Result.panic(e))
        }

        driver.awaitConnect(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
    end awaitConnectThenTls

    /** Initialize OpenSSL context and SSL object, then drive the handshake.
      *
      * During handshake, handle.tls is Absent so the driver reads raw ciphertext. After handshake completes, we set handle.tls =
      * Present(tlsState) so the driver switches to TLS mode for data transfer.
      */
    private def startTlsHandshake(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        host: String,
        tls: HttpTlsConfig,
        isServer: Boolean,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]]
    )(using AllowUnsafe, Frame): Unit =
        val ctx = TlsBindings.tlsCtxNew(if isServer then 1 else 0)
        if ctx == 0L then
            connectPromise.completeDiscard(Result.fail(Closed(
                "NativeTransport",
                summon[Frame],
                "TLS handshake init failed: kyo_tls_ctx_new returned 0"
            )))
        else
            initTlsCtx(handle, driver, host, tls, isServer, connectPromise, ctx)
        end if
    end startTlsHandshake

    private def initTlsCtx(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        host: String,
        tls: HttpTlsConfig,
        isServer: Boolean,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        ctx: CLong
    )(using AllowUnsafe, Frame): Unit =
        // Configure context
        if tls.trustAll && !isServer then
            // Disable verification for trustAll
            discard(TlsBindings.tlsCtxSetVerify(ctx, 0))
        end if

        // Set server cert if configured
        (tls.certChainPath, tls.privateKeyPath) match
            case (Present(certPath), Present(keyPath)) =>
                val certOk = Zone {
                    TlsBindings.tlsCtxSetCert(ctx, toCString(certPath), toCString(keyPath)) == 0
                }
                if !certOk then
                    TlsBindings.tlsCtxFree(ctx)
                    connectPromise.completeDiscard(Result.fail(Closed(
                        "NativeTransport",
                        summon[Frame],
                        "TLS handshake init failed: kyo_tls_ctx_set_cert failed"
                    )))
                else
                    initTlsCtxCa(handle, driver, host, tls, isServer, connectPromise, ctx)
                end if
            case _ =>
                initTlsCtxCa(handle, driver, host, tls, isServer, connectPromise, ctx)
        end match
    end initTlsCtx

    private def initTlsCtxCa(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        host: String,
        tls: HttpTlsConfig,
        isServer: Boolean,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        ctx: CLong
    )(using AllowUnsafe, Frame): Unit =
        // Set CA trust store if configured
        tls.trustStorePath match
            case Present(caPath) =>
                val caOk = Zone {
                    TlsBindings.tlsCtxSetCa(ctx, toCString(caPath)) == 0
                }
                if !caOk then
                    TlsBindings.tlsCtxFree(ctx)
                    connectPromise.completeDiscard(Result.fail(Closed(
                        "NativeTransport",
                        summon[Frame],
                        "TLS handshake init failed: kyo_tls_ctx_set_ca failed"
                    )))
                else
                    initSsl(handle, driver, host, tls, isServer, connectPromise, ctx)
                end if
            case Absent =>
                initSsl(handle, driver, host, tls, isServer, connectPromise, ctx)
        end match
    end initTlsCtxCa

    private def initSsl(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        host: String,
        tls: HttpTlsConfig,
        isServer: Boolean,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        ctx: CLong
    )(using AllowUnsafe, Frame): Unit =
        // Configure client auth for server
        if isServer then
            val verifyMode = tls.clientAuth match
                case HttpTlsConfig.ClientAuth.None     => 0
                case HttpTlsConfig.ClientAuth.Optional => 1
                case HttpTlsConfig.ClientAuth.Required => 2
            discard(TlsBindings.tlsCtxSetVerify(ctx, verifyMode))
        end if

        // Create SSL object
        val ssl = Zone {
            val hostname = if isServer || tls.trustAll then "" else tls.sniHostname.getOrElse(host)
            TlsBindings.tlsNew(ctx, toCString(hostname))
        }
        if ssl == 0L then
            TlsBindings.tlsCtxFree(ctx)
            connectPromise.completeDiscard(Result.fail(Closed(
                "NativeTransport",
                summon[Frame],
                "TLS handshake init failed: kyo_tls_new returned 0"
            )))
        else
            // Set client/server mode
            if isServer then
                TlsBindings.tlsSetAcceptState(ssl)
            else
                TlsBindings.tlsSetConnectState(ssl)
            end if

            driveHandshake(handle, driver, ssl, ctx, connectPromise)
        end if
    end initSsl

    /** Drive TLS handshake via callback-driven promise chains.
      *
      * During handshake, handle.tls is Absent so the driver reads raw ciphertext. We feed ciphertext to OpenSSL manually via
      * kyo_tls_handshake/feed_input/get_output. After handshake completes, we attach TLS state to handle so the driver switches to TLS
      * mode.
      */
    private def driveHandshake(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        ssl: CLong,
        ctx: CLong,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]]
    )(using AllowUnsafe, Frame): Unit =
        val rc = TlsBindings.tlsHandshake(ssl)
        // SSL_do_handshake generates output (ClientHello, ServerHello, etc.) that must be sent
        // to the peer. Flush asynchronously — if EAGAIN, await writable then continue.
        flushTlsOutputAsync(handle, driver, ssl, ctx, connectPromise, rc)
    end driveHandshake

    /** Flush OpenSSL write BIO to socket, then continue handshake based on rc. Non-blocking: on EAGAIN, registers for write-readiness and
      * continues in callback.
      */
    private def flushTlsOutputAsync(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        ssl: CLong,
        ctx: CLong,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        handshakeRc: Int
    )(using AllowUnsafe, Frame): Unit =
        Zone {
            val bufSize = 32768
            val buf     = alloc[Byte](bufSize)
            val n       = TlsBindings.tlsGetOutput(ssl, buf, bufSize)
            if n > 0 then
                val written = PosixBindings.tcpWrite(handle.fd, buf, n)
                if written == n then
                    // Fully written — continue flushing (may have more BIO data)
                    flushTlsOutputAsync(handle, driver, ssl, ctx, connectPromise, handshakeRc)
                else if written > 0 then
                    // Partial write — save remainder and await writable
                    val remaining = new Array[Byte](n - written)
                    @tailrec def copyRemaining(i: Int): Unit =
                        if i < remaining.length then
                            remaining(i) = buf(written + i)
                            copyRemaining(i + 1)
                    copyRemaining(0)
                    drainBioAndAwaitWritable(handle, driver, ssl, ctx, connectPromise, handshakeRc, remaining)
                else if written == 0 then
                    // EAGAIN — save all and await writable
                    val pending = new Array[Byte](n)
                    @tailrec def copyPending(i: Int): Unit =
                        if i < n then
                            pending(i) = buf(i)
                            copyPending(i + 1)
                    copyPending(0)
                    drainBioAndAwaitWritable(handle, driver, ssl, ctx, connectPromise, handshakeRc, pending)
                else
                    // Error
                    failHandshake(handle, ssl, ctx, connectPromise, s"tcpWrite failed during TLS handshake flush fd=${handle.fd}")
                end if
            else
                // No more BIO output — proceed with handshake state
                continueAfterFlush(handle, driver, ssl, ctx, connectPromise, handshakeRc)
            end if
        }
    end flushTlsOutputAsync

    /** Drain remaining BIO data, combine with pending bytes, then await writable to send everything. */
    private def drainBioAndAwaitWritable(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        ssl: CLong,
        ctx: CLong,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        handshakeRc: Int,
        pending: Array[Byte]
    )(using AllowUnsafe, Frame): Unit =
        // Drain any remaining BIO data into the pending buffer
        val bioBuf = new scala.collection.mutable.ArrayBuilder.ofByte
        bioBuf ++= pending
        Zone {
            val bufSize = 32768
            val buf     = alloc[Byte](bufSize)
            @tailrec def drainBio(): Unit =
                val moreN = TlsBindings.tlsGetOutput(ssl, buf, bufSize)
                if moreN > 0 then
                    val arr = new Array[Byte](moreN)
                    @tailrec def copy(j: Int): Unit =
                        if j < moreN then
                            arr(j) = buf(j)
                            copy(j + 1)
                    copy(0)
                    bioBuf ++= arr
                    drainBio()
                end if
            end drainBio
            drainBio()
        }
        val allPending = bioBuf.result()
        // Await writable, then write pending and continue
        val writablePromise = new IOPromise[Closed, Unit]
        writablePromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    writePendingAndContinue(handle, driver, ssl, ctx, connectPromise, handshakeRc, allPending, 0)
                case Result.Failure(e) =>
                    failHandshake(handle, ssl, ctx, connectPromise, s"awaitWritable failed during TLS handshake fd=${handle.fd}")
                case Result.Panic(t) =>
                    TlsBindings.tlsFree(ssl)
                    TlsBindings.tlsCtxFree(ctx)
                    connectPromise.completeDiscard(Result.panic(t))
        }
        driver.awaitWritable(handle, writablePromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
    end drainBioAndAwaitWritable

    /** Write pending ciphertext bytes to socket. On EAGAIN, await writable again. */
    private def writePendingAndContinue(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        ssl: CLong,
        ctx: CLong,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        handshakeRc: Int,
        pending: Array[Byte],
        offset: Int
    )(using AllowUnsafe, Frame): Unit =
        Zone {
            val remaining = pending.length - offset
            val ptr       = alloc[Byte](remaining)
            @tailrec def copyToPtr(i: Int): Unit =
                if i < remaining then
                    ptr(i) = pending(offset + i)
                    copyToPtr(i + 1)
            copyToPtr(0)
            val written = PosixBindings.tcpWrite(handle.fd, ptr, remaining)
            if written == remaining then
                // All written — continue flushing BIO (may have accumulated more)
                flushTlsOutputAsync(handle, driver, ssl, ctx, connectPromise, handshakeRc)
            else if written > 0 then
                // Partial — await writable for remainder
                drainBioAndAwaitWritable(
                    handle,
                    driver,
                    ssl,
                    ctx,
                    connectPromise,
                    handshakeRc,
                    pending.drop(offset + written)
                )
            else if written == 0 then
                // Still EAGAIN — await writable again
                drainBioAndAwaitWritable(
                    handle,
                    driver,
                    ssl,
                    ctx,
                    connectPromise,
                    handshakeRc,
                    if offset == 0 then pending else pending.drop(offset)
                )
            else
                failHandshake(handle, ssl, ctx, connectPromise, s"tcpWrite failed during TLS handshake fd=${handle.fd}")
            end if
        }
    end writePendingAndContinue

    /** Continue handshake after flush completes. */
    private def continueAfterFlush(
        handle: NativeHandle,
        driver: IoDriver[NativeHandle],
        ssl: CLong,
        ctx: CLong,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        handshakeRc: Int
    )(using AllowUnsafe, Frame): Unit =
        if handshakeRc == 1 then
            // Handshake complete — attach TLS state, complete connection
            Log.live.unsafe.debug(s"NativeTransport TLS handshake complete fd=${handle.fd}")
            val tlsBufSize  = 32768
            val tlsReadBuf  = scala.scalanative.libc.stdlib.malloc(tlsBufSize.toLong).asInstanceOf[Ptr[Byte]]
            val tlsWriteBuf = scala.scalanative.libc.stdlib.malloc(tlsBufSize.toLong).asInstanceOf[Ptr[Byte]]
            val tlsState    = NativeTlsState(ssl, ctx, tlsReadBuf, tlsWriteBuf, tlsBufSize)
            handle.tls = Present(tlsState)
            completeConnect(handle, driver, connectPromise)
        else if handshakeRc == 0 then
            // Want read — need more ciphertext from socket
            val readPromise = new IOPromise[Closed, Span[Byte]]
            readPromise.onComplete { result =>
                result match
                    case Result.Success(bytes) =>
                        if bytes.isEmpty then
                            failHandshake(handle, ssl, ctx, connectPromise, "TLS handshake: connection closed during want_read")
                        else
                            // Feed ciphertext to OpenSSL
                            Zone {
                                val arr = bytes.toArrayUnsafe
                                val ptr = alloc[Byte](arr.length)
                                @tailrec def copyInput(i: Int): Unit =
                                    if i < arr.length then
                                        ptr(i) = arr(i)
                                        copyInput(i + 1)
                                copyInput(0)
                                discard(TlsBindings.tlsFeedInput(ssl, ptr, arr.length))
                            }
                            driveHandshake(handle, driver, ssl, ctx, connectPromise)
                    case Result.Failure(e) =>
                        TlsBindings.tlsFree(ssl)
                        TlsBindings.tlsCtxFree(ctx)
                        connectPromise.completeDiscard(Result.fail(e))
                    case Result.Panic(t) =>
                        TlsBindings.tlsFree(ssl)
                        TlsBindings.tlsCtxFree(ctx)
                        connectPromise.completeDiscard(Result.panic(t))
            }
            driver.awaitRead(handle, readPromise.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]])
        else if handshakeRc == -1 then
            // Want write — output already flushed, retry handshake
            driveHandshake(handle, driver, ssl, ctx, connectPromise)
        else
            // Error (-2)
            val errMsg = Zone {
                val cstr = TlsBindings.tlsErrorString()
                fromCString(cstr)
            }
            failHandshake(handle, ssl, ctx, connectPromise, s"TLS handshake failed: $errMsg")
        end if
    end continueAfterFlush

    private def failHandshake(
        handle: NativeHandle,
        ssl: CLong,
        ctx: CLong,
        connectPromise: IOPromise[Closed, Connection[NativeHandle]],
        message: String
    )(using AllowUnsafe, Frame): Unit =
        TlsBindings.tlsFree(ssl)
        TlsBindings.tlsCtxFree(ctx)
        NativeHandle.close(handle)
        connectPromise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], message)))
    end failHandshake

    def listen(host: String, port: Int, backlog: Int, tls: HttpTlsConfig)(
        handler: Connection[NativeHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        Zone {
            val outPort  = alloc[CInt](1)
            val serverFd = tcpListen(toCString(host), port, backlog, outPort)
            if serverFd < 0 then
                promise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], s"TLS listen() failed on $host:$port")))
            else
                val actualPort   = outPort(0)
                val acceptDriver = nextNativeDriver()
                Log.live.unsafe.debug(s"NativeTransport TLS listen $host:$actualPort")
                val listener = new NativeListener(serverFd, actualPort, host, acceptDriver, HttpAddress.Tcp(host, actualPort))
                startTlsAcceptLoop(serverFd, handler, listener, acceptDriver, tls)
                promise.completeDiscard(Result.succeed(listener))
            end if
        }

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listen

    private def startTlsAcceptLoop(
        serverFd: Int,
        handler: Connection[NativeHandle] => Unit < Async,
        listener: NativeListener,
        acceptDriver: NativeIoDriver,
        tls: HttpTlsConfig
    )(using AllowUnsafe, Frame): Unit =

        def scheduleNextAccept(): Unit =
            if !listener.isClosed then
                val acceptPromise = new IOPromise[Closed, Unit]
                acceptPromise.onComplete { result =>
                    result match
                        case Result.Success(_) =>
                            acceptAllPendingTls(serverFd, handler, listener, tls)
                            scheduleNextAccept()
                        case Result.Failure(_) =>
                            // Server closed
                            ()
                        case Result.Panic(e) =>
                            Log.live.unsafe.error(s"TLS accept loop panic", e)
                }
                acceptDriver.awaitAccept(serverFd, acceptPromise.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]])
            end if
        end scheduleNextAccept

        scheduleNextAccept()
    end startTlsAcceptLoop

    private def acceptAllPendingTls(
        serverFd: Int,
        handler: Connection[NativeHandle] => Unit < Async,
        listener: NativeListener,
        tls: HttpTlsConfig
    )(using AllowUnsafe, Frame): Unit =
        if listener.isClosed then ()
        else
            val clientFd = tcpAccept(serverFd)
            if clientFd < 0 then ()
            else
                Log.live.unsafe.debug(
                    s"NativeTransport TLS accepted client fd=$clientFd on server port=${listener.port}"
                )
                val connDriver = pool.next()
                val handle     = NativeHandle.init(clientFd, readBufferSize)

                // Create a per-connection promise for the TLS handshake result
                val connPromise = new IOPromise[Closed, Connection[NativeHandle]]
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
                            Log.live.unsafe.warn(s"TLS handshake failed for client fd=$clientFd: ${closed.getMessage}")
                            tcpClose(clientFd)
                        case Result.Panic(e) =>
                            Log.live.unsafe.error(s"TLS handshake panic fd=$clientFd", e)
                            tcpClose(clientFd)
                }

                startTlsHandshake(handle, connDriver, listener.host, tls, isServer = true, connPromise)
                acceptAllPendingTls(serverFd, handler, listener, tls)
            end if
    end acceptAllPendingTls

    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[NativeHandle], Abort[Closed]] =
        val promise = new IOPromise[Closed, Connection[NativeHandle]]
        val driver  = pool.next()

        Zone {
            val outPending = alloc[CInt](1)
            val fd         = unixConnect(toCString(path), outPending)
            if fd < 0 then
                promise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], s"connectUnix() failed to $path")))
            else if outPending(0) == 0 then
                Log.live.unsafe.debug(s"NativeTransport connectUnix immediate fd=$fd")
                val handle = NativeHandle.init(fd, readBufferSize)
                completeConnect(handle, driver, promise)
            else
                Log.live.unsafe.debug(s"NativeTransport connectUnix $path fd=$fd pending=${outPending(0)}")
                awaitConnect(fd, driver, path, 0, promise)
            end if
        }

        promise.asInstanceOf[Fiber.Unsafe[Connection[NativeHandle], Abort[Closed]]]
    end connectUnix

    def listenUnix(path: String, backlog: Int)(
        handler: Connection[NativeHandle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]] =
        val promise = new IOPromise[Closed, Listener]

        Zone {
            val outPort  = alloc[CInt](1)
            val serverFd = unixListen(toCString(path), backlog, outPort)
            if serverFd < 0 then
                promise.completeDiscard(Result.fail(Closed("NativeTransport", summon[Frame], s"listenUnix() failed on $path")))
            else
                val acceptDriver = nextNativeDriver()
                Log.live.unsafe.debug(s"NativeTransport listenUnix $path")
                val listener = new NativeListener(serverFd, -1, path, acceptDriver, HttpAddress.Unix(path))
                startAcceptLoop(serverFd, handler, listener, acceptDriver)
                promise.completeDiscard(Result.succeed(listener))
            end if
        }

        promise.asInstanceOf[Fiber.Unsafe[Listener, Abort[Closed]]]
    end listenUnix

    def close()(using AllowUnsafe, Frame): Unit =
        pool.close()

end NativeTransport

/** Factory for `NativeTransport`. Creates `poolSize` `NativeIoDriver` instances sharing one `PollerBackend`. */
private[kyo] object NativeTransport:
    def init(
        backend: PollerBackend,
        poolSize: Int,
        channelCapacity: Int,
        readBufferSize: Int
    )(using AllowUnsafe, Frame): NativeTransport =
        val nativeDrivers = Array.tabulate[NativeIoDriver](poolSize)(i => NativeIoDriver.init(backend))
        val drivers       = nativeDrivers.map(d => d: IoDriver[NativeHandle])
        val pool          = IoDriverPool.init(drivers)
        pool.start()
        new NativeTransport(pool, nativeDrivers, channelCapacity, readBufferSize)
    end init
end NativeTransport

/** Active server-side listener wrapping a native server file descriptor. Closing cancels the pending accept promise via the driver and
  * closes the server fd.
  */
final private class NativeListener(
    private val serverFd: Int,
    val port: Int,
    val host: String,
    private val acceptDriver: NativeIoDriver,
    val address: HttpAddress
) extends Listener:
    private val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

    def isClosed: Boolean = closedFlag.get()

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            acceptDriver.cleanupAccept(serverFd)
            PosixBindings.tcpClose(serverFd)
        end if
    end close
end NativeListener
