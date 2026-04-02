package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Kqueue-based non-blocking TCP Transport2 for macOS/BSD (Scala Native).
  *
  * Stream-first variant of KqueueNativeTransport. Each connection exposes a pull-based `Stream[Span[Byte], Async]` for reads instead of a
  * callback-based `read(buf): Int`. Server listen returns a `TransportListener2` whose `connections` stream yields accepted connections.
  *
  * Design follows KqueueNativeTransport closely:
  *   - Per-connection kqueue fds for read and write to avoid event stealing between concurrent fibers.
  *   - Blocking kqueueWait (awaitReady) — OS thread blocks but Kyo's preemptive scheduler keeps other fibers running.
  *   - TLS via NativeTlsStream wrapping an inner KqueueStream (old-style TransportStream), so the TLS layer is unchanged.
  */
final class KqueueNativeTransport2 extends Transport2:

    import PosixBindings.*

    type Connection = KqueueConnection2

    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): KqueueConnection2 < (Async & Abort[HttpException]) =
        connectPlain(host, port).map { conn =>
            tls match
                case Present(tlsCfg) => connectTls(host, conn, tlsCfg)
                case Absent          => conn
        }

    private def connectPlain(host: String, port: Int)(using
        Frame
    ): KqueueConnection2 < (Async & Abort[HttpException]) =
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
                        Sync.defer(new KqueueConnection2(fd))
                    end if
                }
            else
                Sync.defer(new KqueueConnection2(fd))
            end if
        }

    private def connectTls(host: String, conn: KqueueConnection2, tlsCfg: TlsConfig)(using
        Frame
    ): KqueueConnection2 < (Async & Abort[HttpException]) =
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
                    // NativeTlsStream works with the old-style TransportStream API.
                    // We wrap an inner KqueueConnection/KqueueStream for TLS I/O.
                    val innerConn = new KqueueConnection(conn.fd, conn.closed, Absent)
                    // Share kqueue fds: inner conn uses the same readKq/writeKq as conn
                    // (safe because TLS read/write goes through innerConn's stream exclusively)
                    val tcpStream = new KqueueStream2TlsBridge(conn)
                    val tlsStream = new NativeTlsStream(ssl, ctx, tcpStream)
                    conn.tlsStream = Present(tlsStream)
                    tlsStream.handshake().map(_ => conn)
                end if
            end if
        }
    end connectTls

    def isAlive(c: KqueueConnection2)(using Frame): Boolean < Sync =
        Sync.defer(!c.closed && tcpIsAlive(c.fd) == 1)

    def closeNow(c: KqueueConnection2)(using Frame): Unit < Sync =
        Sync.defer {
            c.closed = true
            c.tlsStream match
                case Present(tls) =>
                    import TlsBindings.*
                    tlsFree(tls.sslPtr)
                    tlsCtxFree(tls.ctxPtr)
                    c.tlsStream = Absent
                case Absent =>
            end match
            tcpClose(c.fd)
            tcpClose(c.readKq)
            tcpClose(c.writeKq)
        }

    def close(c: KqueueConnection2, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(c.fd))

    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): TransportListener2[KqueueConnection2] < (Async & Scope) =
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
                val acceptKq  = kqueueCreate()
                tls match
                    case Absent =>
                        Scope.acquireRelease {
                            val connStream: Stream[KqueueConnection2, Async] =
                                Stream.unfold((), chunkSize = 1) { _ =>
                                    discard(kqueueRegister(acceptKq, serverFd, -1))
                                    awaitReady(acceptKq).andThen {
                                        Sync.defer {
                                            val clientFd = tcpAccept(serverFd)
                                            if clientFd >= 0 then
                                                Maybe((new KqueueConnection2(clientFd), ()))
                                            else
                                                Maybe.empty
                                            end if
                                        }
                                    }
                                }
                            new TransportListener2(
                                boundPort,
                                boundHost,
                                connStream,
                                close = Sync.defer(tcpClose(acceptKq))
                            )
                        } { _ =>
                            Sync.defer {
                                tcpClose(acceptKq)
                                tcpClose(serverFd)
                            }
                        }
                    case Present(tlsCfg) =>
                        import TlsBindings.*
                        val ctx = tlsCtxNew(1)
                        if ctx == 0 then
                            tcpClose(acceptKq)
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
                                tcpClose(acceptKq)
                                tcpClose(serverFd)
                                Abort.panic(HttpBindException(
                                    host,
                                    port,
                                    new Exception("Failed to load TLS certificate/key")
                                ))
                            else
                                Scope.acquireRelease {
                                    val connStream: Stream[KqueueConnection2, Async] =
                                        Stream.unfold((), chunkSize = 1) { _ =>
                                            discard(kqueueRegister(acceptKq, serverFd, -1))
                                            awaitReady(acceptKq).andThen {
                                                Sync.defer {
                                                    val clientFd = tcpAccept(serverFd)
                                                    if clientFd >= 0 then
                                                        val conn = new KqueueConnection2(clientFd)
                                                        val ssl  = Zone { tlsNew(ctx, null) }
                                                        if ssl == 0 then
                                                            tcpClose(clientFd)
                                                            Maybe.empty
                                                        else
                                                            tlsSetAcceptState(ssl)
                                                            val bridge    = new KqueueStream2TlsBridge(conn)
                                                            val tlsStream = new NativeTlsStream(ssl, ctx, bridge)
                                                            conn.tlsStream = Present(tlsStream)
                                                            Maybe((conn, ()))
                                                        end if
                                                    else
                                                        Maybe.empty
                                                    end if
                                                }
                                            }
                                        }
                                    new TransportListener2(
                                        boundPort,
                                        boundHost,
                                        connStream,
                                        close = Sync.defer(tcpClose(acceptKq))
                                    )
                                } { _ =>
                                    Sync.defer {
                                        tlsCtxFree(ctx)
                                        tcpClose(acceptKq)
                                        tcpClose(serverFd)
                                    }
                                }
                            end if
                        end if
                end match
            end if
        }

    /** Block the OS thread until at least one event fires. Kyo's preemptive scheduler handles this. */
    private[internal] def awaitReady(kq: Int)(using Frame): Unit < Async =
        Sync.defer {
            Zone {
                val outFd     = alloc[CInt](1)
                val outFilter = alloc[CInt](1)
                kqueueWait(kq, outFd, outFilter, 1)
            }
        }.unit

end KqueueNativeTransport2

/** A kqueue-backed connection that implements TransportStream2 (stream-based reads).
  *
  * Holds per-connection kqueue fds for read and write, matching the pattern of KqueueConnection. TLS is stored as a NativeTlsStream when
  * the connection is TLS-upgraded.
  */
private[kyo] class KqueueConnection2(
    val fd: Int,
    var closed: Boolean = false,
    var tlsStream: Maybe[NativeTlsStream] = Absent
) extends TransportStream2:

    import PosixBindings.*

    // Per-connection kqueue fds — separate for read and write to avoid event stealing.
    val readKq: Int  = kqueueCreate()
    val writeKq: Int = kqueueCreate()

    private val ChunkSize = 8192

    def read(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            tlsStream match
                case Present(tls) => readTls(tls)
                case Absent       => readPlain()
        }

    private def readPlain()(using Frame): Maybe[(Span[Byte], Unit)] < Async =
        Loop.foreach {
            discard(kqueueRegister(readKq, fd, -1))
            Sync.defer {
                Zone {
                    val outFd     = alloc[CInt](1)
                    val outFilter = alloc[CInt](1)
                    kqueueWait(readKq, outFd, outFilter, 1)
                }
            }.andThen {
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
                            Loop.done(Maybe((Span.fromUnsafe(arr), ())))
                        else if n == 0 then
                            Loop.done(Maybe.empty[(Span[Byte], Unit)]) // EOF: peer closed
                        else
                            Loop.continue // EAGAIN / spurious wakeup
                        end if
                    }
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

    private def writePlain(data: Span[Byte])(using Frame): Unit < Async =
        discard(kqueueRegister(writeKq, fd, -2))
        Sync.defer {
            Zone {
                val outFd     = alloc[CInt](1)
                val outFilter = alloc[CInt](1)
                kqueueWait(writeKq, outFd, outFilter, 1)
            }
        }.andThen {
            Sync.defer {
                Zone {
                    val arr = data.toArrayUnsafe
                    val ptr = alloc[Byte](arr.length)
                    var i   = 0
                    while i < arr.length do
                        ptr(i) = arr(i)
                        i += 1
                    end while
                    tcpWrite(fd, ptr, arr.length)
                }
            }.map { written =>
                if written < 0 then
                    Abort.panic(HttpProtocolException(s"write failed on fd $fd"))
                else if written < data.size then
                    writePlain(data.slice(written, data.size))
                else
                    Kyo.unit
                end if
            }
        }
    end writePlain

end KqueueConnection2

/** Bridges a KqueueConnection2 to the old-style TransportStream API, so NativeTlsStream can use it for TLS I/O.
  *
  * NativeTlsStream requires a TransportStream with `read(buf): Int < Async`. This bridge delegates to the kqueue-backed raw I/O of a
  * KqueueConnection2 without going through the TLS layer (avoids recursion).
  */
private[kyo] class KqueueStream2TlsBridge(conn: KqueueConnection2) extends TransportStream:

    import PosixBindings.*

    private val ChunkSize = 8192

    def read(buf: Array[Byte])(using Frame): Int < Async =
        discard(kqueueRegister(conn.readKq, conn.fd, -1))
        Sync.defer {
            Zone {
                val outFd     = alloc[CInt](1)
                val outFilter = alloc[CInt](1)
                kqueueWait(conn.readKq, outFd, outFilter, 1)
            }
        }.andThen {
            Sync.defer {
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
        if data.isEmpty then Kyo.unit
        else
            discard(kqueueRegister(conn.writeKq, conn.fd, -2))
            Sync.defer {
                Zone {
                    val outFd     = alloc[CInt](1)
                    val outFilter = alloc[CInt](1)
                    kqueueWait(conn.writeKq, outFd, outFilter, 1)
                }
            }.andThen {
                Sync.defer {
                    Zone {
                        val arr = data.toArrayUnsafe
                        val ptr = alloc[Byte](arr.length)
                        var i   = 0
                        while i < arr.length do
                            ptr(i) = arr(i)
                            i += 1
                        end while
                        tcpWrite(conn.fd, ptr, arr.length)
                    }
                }.map { written =>
                    if written < 0 then
                        Abort.panic(HttpProtocolException(s"write failed on fd ${conn.fd}"))
                    else if written < data.size then
                        write(data.slice(written, data.size))
                    else
                        Kyo.unit
                    end if
                }
            }
    end write

end KqueueStream2TlsBridge
