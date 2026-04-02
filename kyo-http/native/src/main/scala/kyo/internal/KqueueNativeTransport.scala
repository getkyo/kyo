package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Kqueue-based non-blocking TCP transport for macOS/BSD (Scala Native).
  *
  * No poll thread, no mutable Java collections, no AllowUnsafe. Each fiber does its own non-blocking kqueue poll with a per-operation
  * kqueue fd to avoid event stealing between concurrent fibers.
  */
// TODO isn't this very expensive? we should reuse a single or a few fibers to handle multiple operations no?
final class KqueueNativeTransport extends Transport:

    import PosixBindings.*

    type Connection = KqueueConnection

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : KqueueConnection < (Async & Abort[HttpException]) =
        connectPlain(host, port).map { conn =>
            if tls then connectTls(host, conn)
            else conn
        }

    private def connectPlain(host: String, port: Int)(using
        Frame
    ): KqueueConnection < (Async & Abort[HttpException]) =
        Sync.defer {
            val (fd, pending) = Zone {
                val outPending = alloc[CInt]()
                val f          = tcpConnect(toCString(host), port, outPending)
                (f, !outPending)
            }
            if fd < 0 then
                Abort.fail(HttpConnectException(host, port, new Exception("connect failed")))
            else if pending == 1 then
                val kq = kqueueCreate()
                discard(kqueueRegister(kq, fd, -2))
                awaitReady(kq).andThen {
                    tcpClose(kq)
                    val err = tcpConnectError(fd)
                    if err != 0 then
                        tcpClose(fd)
                        Abort.fail(HttpConnectException(host, port, new Exception(s"connect failed: errno=$err")))
                    else
                        Sync.defer(new KqueueConnection(fd))
                    end if
                }
            else
                Sync.defer(new KqueueConnection(fd))
            end if
        }

    private def connectTls(host: String, conn: KqueueConnection)(using
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
                    val tcpStream = new KqueueStream(conn)
                    val tlsStream = new NativeTlsStream(ssl, ctx, tcpStream)
                    conn.tlsStream = Present(tlsStream)
                    tlsStream.handshake().map(_ => conn)
                end if
            end if
        }
    end connectTls

    def isAlive(connection: KqueueConnection)(using Frame): Boolean < Sync =
        Sync.defer(!connection.closed && tcpIsAlive(connection.fd) == 1)

    def closeNow(connection: KqueueConnection)(using Frame): Unit < Sync =
        Sync.defer {
            connection.closed = true
            connection.tlsStream match
                case Present(tls) =>
                    import TlsBindings.*
                    tlsFree(tls.sslPtr)
                    tlsCtxFree(tls.ctxPtr)
                    connection.tlsStream = Absent
                case Absent =>
            end match
            tcpClose(connection.fd)
            tcpClose(connection.readKq)
            tcpClose(connection.writeKq)
        }

    def close(connection: KqueueConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(connection.fd))

    def stream(connection: KqueueConnection)(using Frame): TransportStream < Async =
        Sync.defer {
            connection.tlsStream match
                case Present(tls) => tls
                case Absent       => new KqueueStream(connection)
        }

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Sync.defer {
            val (serverFd, boundPort) = Zone {
                val outPort = alloc[CInt]()
                val fd      = tcpListen(toCString(host), port, backlog, outPort)
                (fd, !outPort)
            }
            if serverFd < 0 then
                Abort.panic(HttpBindException(host, port, new Exception("bind/listen failed")))
            else
                val boundHost  = host
                val acceptKq   = kqueueCreate()
                val connFibers = new java.util.concurrent.ConcurrentLinkedQueue[Fiber[Unit, Any]]()
                Scope.acquireRelease {
                    Fiber.initUnscoped {
                        Loop.foreach {
                            discard(kqueueRegister(acceptKq, serverFd, -1))
                            awaitReady(acceptKq).andThen {
                                Sync.defer {
                                    val clientFd = tcpAccept(serverFd)
                                    if clientFd >= 0 then
                                        val conn = new KqueueConnection(clientFd)
                                        Fiber.initUnscoped {
                                            Sync.ensure(closeNow(conn)) {
                                                stream(conn).map(handler)
                                            }
                                        }.map { fiber =>
                                            connFibers.add(fiber)
                                            Kyo.unit
                                        }
                                    else
                                        Kyo.unit
                                    end if
                                }
                            }.andThen(Loop.continue)
                        }
                    }.map { acceptFiber =>
                        (
                            acceptFiber,
                            new TransportListener:
                                val port = boundPort
                                val host = boundHost
                        )
                    }
                } { case (acceptFiber, _) =>
                    acceptFiber.interrupt.andThen(Abort.run(acceptFiber.get)).andThen {
                        // Snapshot all active connection fibers, then interrupt and await each.
                        // This prevents fd exhaustion from accumulated sleeping server-side fibers
                        // when tests create many short-lived client connections.
                        Sync.defer {
                            val buf  = new java.util.ArrayList[Fiber[Unit, Any]]()
                            val iter = connFibers.iterator()
                            while iter.hasNext do discard(buf.add(iter.next()))
                            buf
                        }.map { fiberList =>
                            Kyo.foreach((0 until fiberList.size()).toSeq) { i =>
                                val f = fiberList.get(i)
                                f.interrupt.andThen(Abort.run(f.get))
                            }.andThen {
                                Sync.defer {
                                    tcpClose(acceptKq)
                                    tcpClose(serverFd)
                                }
                            }
                        }
                    }
                }.map(_._2)
            end if
        }

    /** Poll kqueue with zero timeout until at least one event fires. Fiber yields between polls. */
    private def awaitReady(kq: Int)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                val ready = Zone {
                    val outFd     = alloc[CInt](1)
                    val outFilter = alloc[CInt](1)
                    kqueueWaitNonBlock(kq, outFd, outFilter, 1) > 0
                }
                if ready then Loop.done(())
                else Loop.continue
            }
        }

    override def listenTls(host: String, port: Int, backlog: Int, tlsConfig: TlsConfig)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        import TlsBindings.*
        val ctx = tlsCtxNew(1)
        if ctx == 0 then
            Abort.panic(HttpBindException(host, port, new Exception("Failed to create TLS context")))
        else
            val certResult = Zone {
                tlsCtxSetCert(
                    ctx,
                    toCString(tlsConfig.certChainPath.getOrElse("")),
                    toCString(tlsConfig.privateKeyPath.getOrElse(""))
                )
            }
            if certResult != 0 then
                tlsCtxFree(ctx)
                Abort.panic(HttpBindException(host, port, new Exception("Failed to load TLS certificate/key")))
            else
                listen(host, port, backlog) { tcpStream =>
                    val ssl = Zone { tlsNew(ctx, null) }
                    if ssl == 0 then
                        Abort.panic(HttpProtocolException("Failed to create TLS session for accepted connection"))
                    else
                        tlsSetAcceptState(ssl)
                        // Create a dummy connection just to carry ssl/ctx for cleanup
                        // We use a wrapper stream that knows the ssl/ctx pointers
                        val tlsStream = new NativeTlsStream(ssl, ctx, tcpStream)
                        Sync.ensure(Sync.defer { tlsFree(ssl) }) {
                            Abort.run[HttpException](tlsStream.handshake()).map {
                                case Result.Success(_) => handler(tlsStream)
                                case _                 => Kyo.unit
                            }
                        }
                    end if
                }
            end if
        end if
    end listenTls

end KqueueNativeTransport

private[kyo] class KqueueConnection(
    val fd: Int,
    var closed: Boolean = false,
    var tlsStream: Maybe[NativeTlsStream] = Absent
):
    import PosixBindings.*
    // Per-connection kqueue fds — separate for read and write to avoid event stealing.
    // Closed in closeNow (safe because Fiber.initUnscoped ensures closeNow fires only after
    // all I/O on this connection has finished).
    val readKq: Int  = kqueueCreate()
    val writeKq: Int = kqueueCreate()
end KqueueConnection

private[kyo] class KqueueStream(conn: KqueueConnection) extends TransportStream:

    import PosixBindings.*

    def read(buf: Array[Byte])(using Frame): Int < Async =
        discard(kqueueRegister(conn.readKq, conn.fd, -1))
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                val ready = Zone {
                    val outFd     = alloc[CInt](1)
                    val outFilter = alloc[CInt](1)
                    kqueueWaitNonBlock(conn.readKq, outFd, outFilter, 1) > 0
                }
                if ready then
                    val n = Zone {
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
                    Loop.done(n)
                else
                    Loop.continue
                end if
            }
        }
    end read

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            discard(kqueueRegister(conn.writeKq, conn.fd, -2))
            Loop.foreach {
                Async.sleep(1.millis).andThen {
                    val ready = Zone {
                        val outFd     = alloc[CInt](1)
                        val outFilter = alloc[CInt](1)
                        kqueueWaitNonBlock(conn.writeKq, outFd, outFilter, 1) > 0
                    }
                    if ready then
                        val written = Zone {
                            val arr = data.toArrayUnsafe
                            val ptr = alloc[Byte](arr.length)
                            var i   = 0
                            while i < arr.length do
                                ptr(i) = arr(i)
                                i += 1
                            end while
                            tcpWrite(conn.fd, ptr, arr.length)
                        }
                        if written < 0 then
                            Abort.panic(HttpProtocolException(s"write failed on fd ${conn.fd}"))
                        else if written < data.size then
                            write(data.slice(written, data.size)).andThen(Loop.done(()))
                        else
                            Loop.done(())
                        end if
                    else
                        Loop.continue
                    end if
                }
            }

end KqueueStream
