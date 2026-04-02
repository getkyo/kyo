package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Shared kqueue-based I/O event loop for macOS/BSD (Scala Native).
  *
  * A single kqueue fd is shared across all connections. The poller fiber calls the @blocking kqueueWait once, dispatches all ready events
  * to their per-fd Promises, then loops. This eliminates per-connection kqueue fds and 1ms polling.
  *
  * Thread safety: ConcurrentHashMap for the pending table. The poller is the only reader of ready events; writers only add/replace entries
  * before registering with the kernel.
  *
  * AllowUnsafe is confined to the single point where poller completes Promises.
  */
private[kyo] class KqueueIoLoop extends IoLoop:

    import PosixBindings.*

    private val kq = kqueueCreate()

    // fd → (readPromise, writePromise)
    private val pending =
        new java.util.concurrent.ConcurrentHashMap[Int, (Maybe[Promise.Unsafe[Unit, Any]], Maybe[Promise.Unsafe[Unit, Any]])]()

    /** Start the shared poller fiber. Must be called once before any awaitReadable/awaitWritable. */
    def start()(using Frame): Unit < Async =
        Fiber.initUnscoped {
            Loop.foreach {
                Sync.defer {
                    Zone {
                        val outFds     = alloc[CInt](64)
                        val outFilters = alloc[CInt](64)
                        // @blocking: OS thread blocks here; Kyo scheduler spawns workers as needed
                        val n = kqueueWait(kq, outFds, outFilters, 64)
                        Loop.indexed { i =>
                            if i < n then
                                val fd     = outFds(i)
                                val filter = outFilters(i)
                                Maybe(pending.get(fd)).foreach { entry =>
                                    import AllowUnsafe.embrace.danger
                                    if filter == -1 then // EVFILT_READ
                                        entry._1 match
                                            case Present(p) =>
                                                pending.put(fd, (Absent, entry._2))
                                                p.completeUnitDiscard()
                                            case Absent =>
                                    else if filter == -2 then // EVFILT_WRITE
                                        entry._2 match
                                            case Present(p) =>
                                                pending.put(fd, (entry._1, Absent))
                                                p.completeUnitDiscard()
                                            case Absent =>
                                    end if
                                }
                                Loop.continue
                            else
                                Loop.done(())
                            end if
                        }
                    }
                }.andThen(Loop.continue)
            }
        }.unit

    def awaitReadable(fd: Int)(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { promise =>
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val writeSlot = Maybe(pending.get(fd)).map(_._2).getOrElse(Absent)
                pending.put(fd, (Present(promise.unsafe), writeSlot))
                discard(kqueueRegister(kq, fd, -1)) // EVFILT_READ, EV_ONESHOT
            }.andThen(promise.get)
        }

    def awaitWritable(fd: Int)(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { promise =>
            Sync.defer {
                import AllowUnsafe.embrace.danger
                val readSlot = Maybe(pending.get(fd)).map(_._1).getOrElse(Absent)
                pending.put(fd, (readSlot, Present(promise.unsafe)))
                discard(kqueueRegister(kq, fd, -2)) // EVFILT_WRITE, EV_ONESHOT
            }.andThen(promise.get)
        }

    /** Cancel any pending promises for the given fd and complete them so waiting fibers unblock.
      *
      * Must be called BEFORE closing the fd. Once the fd is closed, kqueue will never fire for it, so any fiber suspended on a pending
      * promise would hang forever.
      */
    def cancelFd(fd: Int)(using AllowUnsafe): Unit =
        val entry = pending.remove(fd)
        if entry != null then
            entry._1 match
                case Present(p) => p.completeUnitDiscard()
                case Absent     =>
            end match
            entry._2 match
                case Present(p) => p.completeUnitDiscard()
                case Absent     =>
            end match
        end if
    end cancelFd

    def close(): Unit =
        tcpClose(kq)

end KqueueIoLoop

/** Kqueue-based non-blocking TCP Transport for macOS/BSD (Scala Native).
  *
  * Connections are distributed across a group of KqueueIoLoop instances via round-robin for multi-core scaling. Each KqueueIoLoop has its
  * own kqueue fd and poller fiber.
  */
final class KqueueNativeTransport extends Transport:

    import PosixBindings.*

    type Connection = KqueueConnection

    private val groupSize = Math.max(1, Runtime.getRuntime.availableProcessors / 2)
    private val group     = new IoLoopGroup((0 until groupSize).map(_ => new KqueueIoLoop))

    // Lazy start: all loops are started on the first connect/listen call via this atomic flag.
    private val loopStarted = new java.util.concurrent.atomic.AtomicBoolean(false)

    private def ensureLoopStarted()(using Frame): Unit < Async =
        if loopStarted.compareAndSet(false, true) then group.startAll()
        else Kyo.unit

    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): KqueueConnection < (Async & Abort[HttpException]) =
        ensureLoopStarted().andThen {
            connectPlain(host, port).map { conn =>
                tls match
                    case Present(tlsCfg) => connectTls(host, conn, tlsCfg)
                    case Absent          => conn
            }
        }

    private def connectPlain(host: String, port: Int)(using
        Frame
    ): KqueueConnection < (Async & Abort[HttpException]) =
        Sync.defer {
            val loop = group.next()
            val (fd, pending) = Zone {
                val outPending = alloc[CInt]()
                val f          = tcpConnect(toCString(host), port, outPending)
                (f, !outPending)
            }
            if fd < 0 then
                Abort.fail(HttpConnectException(host, port, new Exception("connect failed")))
            else if pending == 1 then
                loop.awaitWritable(fd).andThen {
                    val err = tcpConnectError(fd)
                    if err != 0 then
                        tcpClose(fd)
                        Abort.fail(HttpConnectException(host, port, new Exception(s"connect failed: errno=$err")))
                    else
                        Sync.defer(new KqueueConnection(fd, loop))
                    end if
                }
            else
                Sync.defer(new KqueueConnection(fd, loop))
            end if
        }

    private def connectTls(host: String, conn: KqueueConnection, tlsCfg: TlsConfig)(using
        Frame
    ): KqueueConnection < (Async & Abort[HttpException]) =
        import TlsBindings.*
        Sync.defer {
            val ctx = tlsCtxNew(0)
            if ctx == 0 then
                Abort.fail(HttpConnectException(host, 0, new Exception("Failed to create TLS context")))
            else
                val ssl = Zone { tlsNew(ctx, toCString(host)) }
                if ssl == 0 then
                    tlsCtxFree(ctx)
                    Abort.fail(HttpConnectException(host, 0, new Exception("Failed to create TLS session")))
                else
                    tlsSetConnectState(ssl)
                    val tcpStream = new KqueueStreamTlsBridge(conn)
                    val tlsStream = new NativeTlsStream(ssl, ctx, tcpStream)
                    conn.tlsStream = Present(tlsStream)
                    tlsStream.handshake().map(_ => conn)
                end if
            end if
        }
    end connectTls

    def isAlive(c: KqueueConnection)(using Frame): Boolean < Sync =
        Sync.defer(!c.closed && tcpIsAlive(c.fd) == 1)

    def closeNow(c: KqueueConnection)(using Frame): Unit < Sync =
        Sync.defer {
            import AllowUnsafe.embrace.danger
            c.closed = true
            c.loop.cancelFd(c.fd)
            c.tlsStream match
                case Present(tls) =>
                    import TlsBindings.*
                    tlsFree(tls.sslPtr)
                    tlsCtxFree(tls.ctxPtr)
                    c.tlsStream = Absent
                case Absent =>
            end match
            tcpClose(c.fd)
        }

    def close(c: KqueueConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(c.fd))

    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): TransportListener[KqueueConnection] < (Async & Scope) =
        ensureLoopStarted().andThen {
            Sync.defer {
                val (serverFd, boundPort) = Zone {
                    val outPort = alloc[CInt]()
                    val fd      = tcpListen(toCString(host), port, backlog, outPort)
                    (fd, !outPort)
                }
                if serverFd < 0 then
                    Abort.panic(HttpBindException(host, port, new Exception("bind/listen failed")))
                else
                    val boundHost = host
                    // Use the first loop in the group for the accept selector (server fd readiness).
                    val acceptLoop = group.next()
                    tls match
                        case Absent =>
                            Scope.acquireRelease {
                                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                                val connStream: Stream[KqueueConnection, Async] =
                                    Stream.unfold((), chunkSize = 1) { _ =>
                                        if closed.get() then Maybe.empty
                                        else
                                            acceptLoop.awaitReadable(serverFd).andThen {
                                                Sync.defer {
                                                    if closed.get() then Maybe.empty
                                                    else
                                                        val clientFd = tcpAccept(serverFd)
                                                        if clientFd >= 0 then
                                                            Maybe((new KqueueConnection(clientFd, group.next()), ()))
                                                        else
                                                            Maybe.empty
                                                        end if
                                                    end if
                                                }
                                            }
                                        end if
                                    }
                                new TransportListener(
                                    boundPort,
                                    boundHost,
                                    connStream,
                                    close = Sync.defer {
                                        closed.set(true)
                                        import AllowUnsafe.embrace.danger
                                        acceptLoop.cancelFd(serverFd)
                                        tcpClose(serverFd)
                                    }
                                )
                            } { _ =>
                                Sync.defer(tcpClose(serverFd))
                            }
                        case Present(tlsCfg) =>
                            import TlsBindings.*
                            val ctx = tlsCtxNew(1)
                            if ctx == 0 then
                                tcpClose(serverFd)
                                Abort.panic(HttpBindException(host, port, new Exception("Failed to create TLS context")))
                            else
                                val certResult = Zone {
                                    tlsCtxSetCert(
                                        ctx,
                                        toCString(tlsCfg.certChainPath.getOrElse("")),
                                        toCString(tlsCfg.privateKeyPath.getOrElse(""))
                                    )
                                }
                                if certResult != 0 then
                                    tlsCtxFree(ctx)
                                    tcpClose(serverFd)
                                    Abort.panic(HttpBindException(
                                        host,
                                        port,
                                        new Exception("Failed to load TLS certificate/key")
                                    ))
                                else
                                    Scope.acquireRelease {
                                        val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                                        val connStream: Stream[KqueueConnection, Async] =
                                            Stream.unfold((), chunkSize = 1) { _ =>
                                                if closed.get() then Maybe.empty
                                                else
                                                    acceptLoop.awaitReadable(serverFd).andThen {
                                                        Sync.defer {
                                                            if closed.get() then Maybe.empty
                                                            else
                                                                val clientFd = tcpAccept(serverFd)
                                                                if clientFd >= 0 then
                                                                    val connLoop = group.next()
                                                                    val conn     = new KqueueConnection(clientFd, connLoop)
                                                                    val ssl      = Zone { tlsNew(ctx, null) }
                                                                    if ssl == 0 then
                                                                        tcpClose(clientFd)
                                                                        Maybe.empty
                                                                    else
                                                                        tlsSetAcceptState(ssl)
                                                                        val bridge    = new KqueueStreamTlsBridge(conn)
                                                                        val tlsStream = new NativeTlsStream(ssl, ctx, bridge)
                                                                        conn.tlsStream = Present(tlsStream)
                                                                        Maybe((conn, ()))
                                                                    end if
                                                                else
                                                                    Maybe.empty
                                                                end if
                                                            end if
                                                        }
                                                    }
                                                end if
                                            }
                                        new TransportListener(
                                            boundPort,
                                            boundHost,
                                            connStream,
                                            close = Sync.defer {
                                                closed.set(true)
                                                import AllowUnsafe.embrace.danger
                                                acceptLoop.cancelFd(serverFd)
                                                tcpClose(serverFd)
                                            }
                                        )
                                    } { _ =>
                                        Sync.defer {
                                            tlsCtxFree(ctx)
                                            tcpClose(serverFd)
                                        }
                                    }
                                end if
                            end if
                    end match
                end if
            }
        }

end KqueueNativeTransport

/** A kqueue-backed connection that implements TransportStream (stream-based reads).
  *
  * Uses the shared KqueueIoLoop for all read/write readiness. No per-connection kqueue fds.
  */
private[kyo] class KqueueConnection(
    val fd: Int,
    private[internal] val loop: KqueueIoLoop,
    var closed: Boolean = false,
    var tlsStream: Maybe[NativeTlsStream] = Absent
) extends TransportStream:

    import PosixBindings.*

    private val ChunkSize = 8192

    def read(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            tlsStream match
                case Present(tls) => readTls(tls)
                case Absent       => readPlain()
        }

    private def readPlain()(using Frame): Maybe[(Span[Byte], Unit)] < Async =
        loop.awaitReadable(fd).andThen {
            Sync.defer {
                Zone {
                    val ptr = alloc[Byte](ChunkSize)
                    val n   = tcpRead(fd, ptr, ChunkSize)
                    if n > 0 then
                        val arr = new Array[Byte](n)
                        var i   = 0
                        while i < n do
                            arr(i) = ptr(i)
                            i += 1
                        end while
                        Maybe((Span.fromUnsafe(arr), ()))
                    else if n == 0 then
                        Maybe.empty[(Span[Byte], Unit)] // EOF: peer closed
                    else
                        Maybe.empty[(Span[Byte], Unit)] // EAGAIN / error — treat as EOF
                    end if
                }
            }
        }
    end readPlain

    private def readTls(tls: NativeTlsStream)(using Frame): Maybe[(Span[Byte], Unit)] < Async =
        val buf = new Array[Byte](ChunkSize)
        tls.read(buf).map { n =>
            if n > 0 then
                val arr = new Array[Byte](n)
                java.lang.System.arraycopy(buf, 0, arr, 0, n)
                Maybe((Span.fromUnsafe(arr), ()))
            else
                Maybe.empty[(Span[Byte], Unit)]
            end if
        }
    end readTls

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            tlsStream match
                case Present(tls) => tls.write(data)
                case Absent       => writePlain(data)

    private[kyo] def writePlain(data: Span[Byte])(using Frame): Unit < Async =
        loop.awaitWritable(fd).andThen {
            Sync.defer {
                Zone {
                    val arr = data.toArrayUnsafe
                    val ptr = alloc[Byte](arr.length)
                    var i   = 0
                    while i < arr.length do
                        ptr(i) = arr(i)
                        i += 1
                    end while
                    val written = tcpWrite(fd, ptr, arr.length)
                    if written < 0 then
                        Abort.panic(HttpProtocolException(s"write failed on fd $fd"))
                    else if written < data.size then
                        writePlain(data.slice(written, data.size))
                    else
                        Kyo.unit
                    end if
                }
            }
        }
    end writePlain

end KqueueConnection

/** Bridges a KqueueConnection to the RawStream API, so NativeTlsStream can use it for TLS I/O.
  *
  * Delegates to the connection's raw I/O using the shared KqueueIoLoop.
  */
private[kyo] class KqueueStreamTlsBridge(conn: KqueueConnection) extends RawStream:

    def read(buf: Array[Byte])(using Frame): Int < Async =
        conn.loop.awaitReadable(conn.fd).andThen {
            Sync.defer {
                import PosixBindings.*
                Zone {
                    val ptr       = alloc[Byte](buf.length)
                    val bytesRead = tcpRead(conn.fd, ptr, buf.length)
                    if bytesRead > 0 then
                        var i = 0
                        while i < bytesRead do
                            buf(i) = ptr(i)
                            i += 1
                        end while
                    end if
                    if bytesRead == 0 then -1 else bytesRead // 0 = EOF on POSIX, map to -1
                }
            }
        }
    end read

    def write(data: Span[Byte])(using Frame): Unit < Async =
        conn.writePlain(data)

end KqueueStreamTlsBridge
