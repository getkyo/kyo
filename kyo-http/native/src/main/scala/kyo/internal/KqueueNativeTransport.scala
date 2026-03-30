package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.scalanative.unsafe.*

/** Kqueue-based non-blocking TCP transport for macOS/BSD (Scala Native).
  *
  * Architecture:
  *   - One kqueue fd per transport, polled by a daemon thread
  *   - All socket fds set to O_NONBLOCK by the C layer
  *   - read()/write() register EVFILT_READ/EVFILT_WRITE (one-shot), suspend fiber via Promise
  *   - Poll thread: kqueueWait() → perform I/O → complete Promises
  */
final class KqueueNativeTransport extends Transport:

    import PosixBindings.*

    type Connection = KqueueConnection

    private val kqfd    = kqueueCreate()
    private val stopped = new AtomicBoolean(false)

    private val reads    = new ConcurrentHashMap[Int, PendingRead]()
    private val writes   = new ConcurrentHashMap[Int, PendingWrite]()
    private val accepts  = new ConcurrentHashMap[Int, PendingAccept]()
    private val connects = new ConcurrentHashMap[Int, PendingConnect]()

    private class PendingRead(val promise: Promise.Unsafe[Int, Any], val buf: Array[Byte])
    private class PendingWrite(val promise: Promise.Unsafe[Unit, Any], val data: Span[Byte])
    private class PendingAccept(val promise: Promise.Unsafe[Int, Any])
    private class PendingConnect(val promise: Promise.Unsafe[Unit, Any])

    private val pollThread = new Thread(() => pollLoop(), "kqueue-poll")
    pollThread.setDaemon(true)
    pollThread.start()

    private def pollLoop(): Unit =
        import AllowUnsafe.embrace.danger
        val maxEvents  = 64
        val outFds     = new Array[Int](maxEvents)
        val outFilters = new Array[Int](maxEvents)
        while !stopped.get() do
            val n = Zone {
                val fdsPtr     = alloc[CInt](maxEvents)
                val filtersPtr = alloc[CInt](maxEvents)
                val count      = kqueueWait(kqfd, fdsPtr, filtersPtr, maxEvents)
                var i          = 0
                while i < count do
                    outFds(i) = fdsPtr(i)
                    outFilters(i) = filtersPtr(i)
                    i += 1
                end while
                count
            }
            var i = 0
            while i < n do
                val fd     = outFds(i)
                val filter = outFilters(i)

                if filter == -1 then // EVFILT_READ
                    val pa = accepts.remove(fd)
                    if pa != null then
                        val clientFd = tcpAccept(fd)
                        discard(pa.promise.complete(Result.succeed(clientFd)))
                    else
                        val pr = reads.remove(fd)
                        if pr != null then
                            val bytesRead = Zone {
                                val ptr = alloc[Byte](pr.buf.length)
                                val n   = tcpRead(fd, ptr, pr.buf.length)
                                if n > 0 then
                                    var j = 0
                                    while j < n do
                                        pr.buf(j) = ptr(j)
                                        j += 1
                                    end while
                                end if
                                n
                            }
                            discard(pr.promise.complete(Result.succeed(bytesRead)))
                        end if
                    end if
                else if filter == -2 then // EVFILT_WRITE
                    val pc = connects.remove(fd)
                    if pc != null then
                        discard(pc.promise.complete(Result.succeed(())))
                    else
                        val pw = writes.remove(fd)
                        if pw != null then
                            Zone {
                                val arr = pw.data.toArrayUnsafe
                                val ptr = alloc[Byte](arr.length)
                                var j   = 0
                                while j < arr.length do
                                    ptr(j) = arr(j)
                                    j += 1
                                end while
                                discard(tcpWrite(fd, ptr, arr.length))
                            }
                            discard(pw.promise.complete(Result.succeed(())))
                        end if
                    end if
                end if
                i += 1
            end while
        end while
    end pollLoop

    // ── Transport implementation ────────────────────────────────

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
                    Promise.init[Unit, Any].map { ready =>
                        import AllowUnsafe.embrace.danger
                        connects.put(fd, new PendingConnect(ready.unsafe))
                        discard(kqueueRegister(kqfd, fd, -2))
                        ready.get.andThen(Sync.defer(new KqueueConnection(fd)))
                    }
                else
                    Sync.defer(new KqueueConnection(fd))
                end if
            }

    def isAlive(connection: KqueueConnection)(using AllowUnsafe): Boolean =
        tcpIsAlive(connection.fd) == 1

    def closeNowUnsafe(connection: KqueueConnection)(using AllowUnsafe): Unit =
        tcpClose(connection.fd)

    def close(connection: KqueueConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(connection.fd))

    def stream(connection: KqueueConnection)(using Frame): TransportStream < Async =
        Sync.defer(new KqueueStream(connection, this))

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Sync.defer {
            val actualPort = Zone {
                val outPort = alloc[CInt]()
                val fd      = tcpListen(toCString(host), port, backlog, outPort)
                if fd < 0 then throw HttpBindException(host, port, new Exception("bind/listen failed"))
                (fd, !outPort)
            }
            val serverFd  = actualPort._1
            val boundPort = actualPort._2
            val boundHost = host

            Scope.acquireRelease {
                discard(Fiber.init {
                    Loop.foreach {
                        Promise.init[Int, Any].map { accepted =>
                            import AllowUnsafe.embrace.danger
                            accepts.put(serverFd, new PendingAccept(accepted.unsafe))
                            discard(kqueueRegister(kqfd, serverFd, -1)) // EVFILT_READ
                            accepted.get.map { clientFd =>
                                if clientFd >= 0 then
                                    val conn = new KqueueConnection(clientFd)
                                    discard(Fiber.init {
                                        Sync.ensure(Sync.defer(tcpClose(clientFd))) {
                                            handler(new KqueueStream(conn, KqueueNativeTransport.this))
                                        }
                                    })
                                end if
                            }.andThen(Loop.continue)
                        }
                    }
                })
                new TransportListener:
                    val port = boundPort
                    val host = boundHost
            } { _ => Sync.defer(tcpClose(serverFd)) }
        }

    // Exposed for KqueueStream
    private[kyo] def doRegisterRead(fd: Int, promise: Promise.Unsafe[Int, Any], buf: Array[Byte]): Unit =
        reads.put(fd, new PendingRead(promise, buf))
        discard(kqueueRegister(kqfd, fd, -1)) // EVFILT_READ

    private[kyo] def doRegisterWrite(fd: Int, promise: Promise.Unsafe[Unit, Any], data: Span[Byte]): Unit =
        writes.put(fd, new PendingWrite(promise, data))
        discard(kqueueRegister(kqfd, fd, -2)) // EVFILT_WRITE

end KqueueNativeTransport

private[kyo] class KqueueConnection(val fd: Int)

private[kyo] class KqueueStream(conn: KqueueConnection, transport: KqueueNativeTransport) extends TransportStream:

    def read(buf: Array[Byte])(using Frame): Int < Async =
        Promise.init[Int, Any].map { ready =>
            import AllowUnsafe.embrace.danger
            transport.doRegisterRead(conn.fd, ready.unsafe, buf)
            ready.get
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { ready =>
            import AllowUnsafe.embrace.danger
            transport.doRegisterWrite(conn.fd, ready.unsafe, data)
            ready.get
        }
end KqueueStream
