package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.scalanative.unsafe.*

/** Kqueue-based non-blocking TCP transport for macOS/BSD (Scala Native).
  *
  * Architecture:
  *   - One kqueue fd per transport, polled by a daemon thread
  *   - read()/write() register kqueue interest and suspend the fiber via Channel.take
  *   - Poll thread: kqueueWait() → perform I/O → put results into per-fd result channels
  *   - No AllowUnsafe in the API layer. The poll thread uses Sync.Unsafe internally
  *     (genuine OS boundary bridge — the only place unsafe is acceptable).
  */
final class KqueueNativeTransport extends Transport:

    import PosixBindings.*

    type Connection = KqueueConnection

    private val kqfd    = kqueueCreate()
    private val stopped = new AtomicBoolean(false)

    // Per-fd result queues — the poll thread puts results here, fibers take from here.
    // Using ConcurrentLinkedQueue (thread-safe, non-blocking) to avoid AllowUnsafe in the API.
    private val readResults    = new ConcurrentHashMap[Int, ConcurrentLinkedQueue[Int]]()
    private val writeResults   = new ConcurrentHashMap[Int, ConcurrentLinkedQueue[Int]]()
    private val acceptResults  = new ConcurrentHashMap[Int, ConcurrentLinkedQueue[Int]]()
    private val connectResults = new ConcurrentHashMap[Int, ConcurrentLinkedQueue[Boolean]]()

    // Pending I/O data — stored here so poll thread can access without AllowUnsafe
    private val pendingReadBufs  = new ConcurrentHashMap[Int, Array[Byte]]()
    private val pendingWriteData = new ConcurrentHashMap[Int, Span[Byte]]()

    private val pollThread = new Thread(() => pollLoop(), "kqueue-poll")
    pollThread.setDaemon(true)
    pollThread.start()

    private def pollLoop(): Unit =
        val maxEvents  = 64
        val outFds     = new Array[Int](maxEvents)
        val outFilters = new Array[Int](maxEvents)
        while !stopped.get() do
            val n = Zone {
                val fdsPtr     = alloc[CInt](maxEvents)
                val filtersPtr = alloc[CInt](maxEvents)
                val count      = kqueueWait(kqfd, fdsPtr, filtersPtr, maxEvents)
                var i = 0
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
                    val aq = acceptResults.get(fd)
                    if aq != null then
                        val clientFd = tcpAccept(fd)
                        discard(aq.offer(clientFd))
                    else
                        val buf = pendingReadBufs.remove(fd)
                        val rq  = readResults.get(fd)
                        if buf != null && rq != null then
                            val bytesRead = Zone {
                                val ptr = alloc[Byte](buf.length)
                                val n   = tcpRead(fd, ptr, buf.length)
                                if n > 0 then
                                    var j = 0
                                    while j < n do
                                        buf(j) = ptr(j)
                                        j += 1
                                    end while
                                end if
                                n
                            }
                            discard(rq.offer(bytesRead))
                        end if
                    end if
                else if filter == -2 then // EVFILT_WRITE
                    val cq = connectResults.get(fd)
                    if cq != null then
                        discard(connectResults.remove(fd))
                        discard(cq.offer(true))
                    else
                        val data = pendingWriteData.remove(fd)
                        val wq   = writeResults.get(fd)
                        if data != null && wq != null then
                            val written = Zone {
                                val arr = data.toArrayUnsafe
                                val ptr = alloc[Byte](arr.length)
                                var j   = 0
                                while j < arr.length do
                                    ptr(j) = arr(j)
                                    j += 1
                                end while
                                tcpWrite(fd, ptr, arr.length)
                            }
                            discard(wq.offer(written))
                        end if
                    end if
                end if
                i += 1
            end while
        end while

    // ── Transport implementation ────────────────────────────────

    def connect(host: String, port: Int, tls: Boolean)(using Frame)
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
                    // Wait for connect completion by polling
                    val q = new ConcurrentLinkedQueue[Boolean]()
                    connectResults.put(fd, q)
                    discard(kqueueRegister(kqfd, fd, -2))
                    Loop.foreach {
                        Async.sleep(1.millis).andThen {
                            val result = q.poll()
                            if result != null then Loop.done(new KqueueConnection(fd))
                            else Loop.continue
                        }
                    }
                else
                    Sync.defer(new KqueueConnection(fd))
                end if
            }

    def isAlive(connection: KqueueConnection)(using Frame): Boolean < Sync =
        Sync.defer(tcpIsAlive(connection.fd) == 1)

    def closeNow(connection: KqueueConnection)(using Frame): Unit < Sync =
        Sync.defer(tcpClose(connection.fd))

    def close(connection: KqueueConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(tcpShutdown(connection.fd))

    def stream(connection: KqueueConnection)(using Frame): TransportStream < Async =
        Sync.defer {
            val rq = new ConcurrentLinkedQueue[Int]()
            val wq = new ConcurrentLinkedQueue[Int]()
            readResults.put(connection.fd, rq)
            writeResults.put(connection.fd, wq)
            new KqueueStream(connection, this, rq, wq)
        }

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Sync.defer {
            val (serverFd, boundPort) = Zone {
                val outPort = alloc[CInt]()
                val fd      = tcpListen(toCString(host), port, backlog, outPort)
                if fd < 0 then Abort.fail(HttpBindException(host, port, new Exception("bind/listen failed")))
                (fd, !outPort)
            }
            val boundHost = host
            val aq        = new ConcurrentLinkedQueue[Int]()
            acceptResults.put(serverFd, aq)

            Scope.acquireRelease {
                Fiber.init {
                    Loop.foreach {
                        // Register for accept and poll
                        discard(kqueueRegister(kqfd, serverFd, -1))
                        Loop.foreach {
                            Async.sleep(1.millis).andThen {
                                val clientFd = aq.poll()
                                if clientFd != null then Loop.done(clientFd.intValue)
                                else Loop.continue
                            }
                        }.map { clientFd =>
                            if clientFd >= 0 then
                                val conn = new KqueueConnection(clientFd)
                                Fiber.init {
                                    Sync.ensure(Sync.defer(tcpClose(clientFd))) {
                                        stream(conn).map(handler)
                                    }
                                }.unit
                            else
                                Kyo.unit
                        }.andThen(Loop.continue)
                    }
                }.andThen {
                    new TransportListener:
                        val port = boundPort
                        val host = boundHost
                }
            } { _ => Sync.defer(tcpClose(serverFd)) }
        }

end KqueueNativeTransport

private[kyo] class KqueueConnection(val fd: Int)

private[kyo] class KqueueStream(
    conn: KqueueConnection,
    transport: KqueueNativeTransport,
    readQueue: ConcurrentLinkedQueue[Int],
    writeQueue: ConcurrentLinkedQueue[Int]
) extends TransportStream:

    import PosixBindings.*

    def read(buf: Array[Byte])(using Frame): Int < Async =
        transport.pendingReadBufs.put(conn.fd, buf)
        discard(kqueueRegister(transport.kqfd, conn.fd, -1))
        // Poll until result available
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                val result = readQueue.poll()
                if result != null then Loop.done(result.intValue)
                else Loop.continue
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            transport.pendingWriteData.put(conn.fd, data)
            discard(kqueueRegister(transport.kqfd, conn.fd, -2))
            Loop.foreach {
                Async.sleep(1.millis).andThen {
                    val result = writeQueue.poll()
                    if result != null then
                        val written = result.intValue
                        if written < 0 then Abort.fail(new java.io.IOException(s"write failed on fd ${conn.fd}"))
                        else if written < data.size then
                            // Partial write — write remainder
                            write(data.slice(written, data.size)).andThen(Loop.done(()))
                        else
                            Loop.done(())
                    else Loop.continue
                }
            }

end KqueueStream
