package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Kqueue-based non-blocking TCP transport for macOS/BSD (Scala Native).
  *
  * No poll thread, no mutable Java collections, no AllowUnsafe. Each fiber does its own non-blocking kqueue poll with a per-operation
  * kqueue fd to avoid event stealing between concurrent fibers.
  */
final class KqueueNativeTransport extends Transport:

    import PosixBindings.*

    type Connection = KqueueConnection

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : KqueueConnection < (Async & Abort[HttpException]) =
        if tls then Abort.fail(HttpConnectException(host, port, new Exception("TLS not yet supported on native")))
        else
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

    def isAlive(connection: KqueueConnection)(using Frame): Boolean < Sync =
        Sync.defer(!connection.closed && tcpIsAlive(connection.fd) == 1)

    def closeNow(connection: KqueueConnection)(using Frame): Unit < Sync =
        Sync.defer {
            connection.closed = true
            tcpClose(connection.fd)
        }

    def close(connection: KqueueConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(connection.fd))

    def stream(connection: KqueueConnection)(using Frame): TransportStream < Async =
        Sync.defer(new KqueueStream(connection))

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
                val boundHost = host
                val acceptKq  = kqueueCreate()
                Scope.acquireRelease {
                    Fiber.init {
                        Loop.foreach {
                            discard(kqueueRegister(acceptKq, serverFd, -1))
                            awaitReady(acceptKq).andThen {
                                Sync.defer {
                                    val clientFd = tcpAccept(serverFd)
                                    if clientFd >= 0 then
                                        val conn = new KqueueConnection(clientFd)
                                        Fiber.init {
                                            Sync.ensure(closeNow(conn)) {
                                                stream(conn).map(handler)
                                            }
                                        }.unit
                                    else
                                        Kyo.unit
                                    end if
                                }
                            }.andThen(Loop.continue)
                        }
                    }.andThen {
                        new TransportListener:
                            val port = boundPort
                            val host = boundHost
                    }
                } { _ =>
                    Sync.defer {
                        tcpClose(acceptKq)
                        tcpClose(serverFd)
                    }
                }
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

end KqueueNativeTransport

private[kyo] class KqueueConnection(val fd: Int, var closed: Boolean = false)

private[kyo] class KqueueStream(conn: KqueueConnection) extends TransportStream:

    import PosixBindings.*

    // Per-stream kqueue fds — avoids event stealing between read and write fibers
    private val readKq  = kqueueCreate()
    private val writeKq = kqueueCreate()

    def read(buf: Array[Byte])(using Frame): Int < Async =
        discard(kqueueRegister(readKq, conn.fd, -1))
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                val ready = Zone {
                    val outFd     = alloc[CInt](1)
                    val outFilter = alloc[CInt](1)
                    kqueueWaitNonBlock(readKq, outFd, outFilter, 1) > 0
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
                        bytesRead
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
            discard(kqueueRegister(writeKq, conn.fd, -2))
            Loop.foreach {
                Async.sleep(1.millis).andThen {
                    val ready = Zone {
                        val outFd     = alloc[CInt](1)
                        val outFilter = alloc[CInt](1)
                        kqueueWaitNonBlock(writeKq, outFd, outFilter, 1) > 0
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
