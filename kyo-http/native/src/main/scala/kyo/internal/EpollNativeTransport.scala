package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Epoll-based non-blocking TCP transport for Linux (Scala Native).
  *
  * Same architecture as KqueueNativeTransport: per-operation epoll fd to avoid event stealing between concurrent fibers. Each read/write
  * creates its own epoll instance, registers EPOLLONESHOT interest, polls until ready, then performs the I/O.
  */
final class EpollNativeTransport extends Transport:

    import PosixBindings.*

    type Connection = EpollConnection

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : EpollConnection < (Async & Abort[HttpException]) =
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
                    val epfd = epollCreate()
                    discard(epollRegister(epfd, fd, 2)) // wait for write-ready = connect complete
                    awaitReady(epfd).andThen {
                        tcpClose(epfd)
                        val err = tcpConnectError(fd)
                        if err != 0 then
                            tcpClose(fd)
                            Abort.fail(HttpConnectException(host, port, new Exception(s"connect failed: errno=$err")))
                        else
                            Sync.defer(new EpollConnection(fd))
                        end if
                    }
                else
                    Sync.defer(new EpollConnection(fd))
                end if
            }

    def isAlive(connection: EpollConnection)(using Frame): Boolean < Sync =
        Sync.defer(!connection.closed && tcpIsAlive(connection.fd) == 1)

    def closeNow(connection: EpollConnection)(using Frame): Unit < Sync =
        Sync.defer {
            connection.closed = true
            tcpClose(connection.fd)
        }

    def close(connection: EpollConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(connection.fd))

    def stream(connection: EpollConnection)(using Frame): TransportStream < Async =
        Sync.defer(new EpollStream(connection))

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
                val acceptEpfd = epollCreate()
                Scope.acquireRelease {
                    Fiber.init {
                        Loop.foreach {
                            discard(epollRegister(acceptEpfd, serverFd, 1)) // read-ready = accept-ready
                            awaitReady(acceptEpfd).andThen {
                                Sync.defer {
                                    val clientFd = tcpAccept(serverFd)
                                    if clientFd >= 0 then
                                        val conn = new EpollConnection(clientFd)
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
                        tcpClose(acceptEpfd)
                        tcpClose(serverFd)
                    }
                }
            end if
        }

    /** Poll epoll with zero timeout until at least one event fires. Fiber yields between polls. */
    private def awaitReady(epfd: Int)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                val ready = Zone {
                    val outFd     = alloc[CInt](1)
                    val outEvents = alloc[CInt](1)
                    epollWaitNonBlock(epfd, outFd, outEvents, 1) > 0
                }
                if ready then Loop.done(())
                else Loop.continue
            }
        }

end EpollNativeTransport

private[kyo] class EpollConnection(val fd: Int, var closed: Boolean = false)

private[kyo] class EpollStream(conn: EpollConnection) extends TransportStream:

    import PosixBindings.*

    // Per-stream epoll fds — avoids event stealing between read and write fibers
    private val readEpfd  = epollCreate()
    private val writeEpfd = epollCreate()

    def read(buf: Array[Byte])(using Frame): Int < Async =
        discard(epollRegister(readEpfd, conn.fd, 1)) // EPOLLIN
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                val ready = Zone {
                    val outFd     = alloc[CInt](1)
                    val outEvents = alloc[CInt](1)
                    epollWaitNonBlock(readEpfd, outFd, outEvents, 1) > 0
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
            discard(epollRegister(writeEpfd, conn.fd, 2)) // EPOLLOUT
            Loop.foreach {
                Async.sleep(1.millis).andThen {
                    val ready = Zone {
                        val outFd     = alloc[CInt](1)
                        val outEvents = alloc[CInt](1)
                        epollWaitNonBlock(writeEpfd, outFd, outEvents, 1) > 0
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

end EpollStream
