package kyo.internal

import kyo.*
import scala.scalajs.js

/** Node.js net-based transport for Scala.js.
  *
  * AllowUnsafe is used ONLY in Node.js event callbacks (the OS boundary). These callbacks fire from the Node.js event loop, outside the kyo
  * fiber system. Promise.unsafe.complete bridges from the callback into kyo. This is analogous to the kqueue poll thread on Native — the
  * minimum unsafe boundary required to bridge OS events to kyo fibers.
  */
final class JsTransport extends Transport:

    private val net = js.Dynamic.global.require("net")
    private val tls = js.Dynamic.global.require("tls")

    type Connection = JsConnection

    def connect(host: String, port: Int, tlsConfig: Maybe[TlsConfig])(using
        Frame
    )
        : JsConnection < (Async & Abort[HttpException]) =
        Promise.init[JsConnection, Async & Abort[HttpException]].map { promise =>
            Sync.defer {
                val (socket, connectEvent) =
                    if tlsConfig.isDefined then
                        val opts = js.Dynamic.literal(
                            host = host,
                            port = port,
                            servername = host,
                            rejectUnauthorized = true
                        )
                        (tls.connect(opts), "secureConnect")
                    else
                        (net.connect(port, host), "connect")
                discard(socket.setNoDelay(true))
                discard(socket.pause())
                discard(socket.on(
                    connectEvent,
                    { () =>
                        import AllowUnsafe.embrace.danger
                        val conn = new JsRawConnection(socket)
                        discard(promise.unsafe.complete(Result.succeed(new JsConnection(conn, new JsStream(conn)))))
                    }: js.Function0[Unit]
                ))
                discard(socket.on(
                    "error",
                    { (err: js.Dynamic) =>
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.fail(
                            HttpConnectException(host, port, new Exception(err.message.toString))
                        )))
                    }: js.Function1[js.Dynamic, Unit]
                ))
            }.andThen(promise.get)
        }

    def isAlive(c: JsConnection)(using Frame): Boolean < Sync =
        Sync.defer(!c.conn.socket.destroyed.asInstanceOf[Boolean])

    def closeNow(c: JsConnection)(using Frame): Unit < Sync =
        Sync.defer(discard(c.conn.socket.destroy()))

    def close(c: JsConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(discard(c.conn.socket.end()))

    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        frame: Frame
    )
        : TransportListener[JsConnection] < (Async & Scope) =
        Promise.init[TransportListener[JsConnection], Any].map { ready =>
            val server        = net.createServer()
            val listenHost    = host
            val activeSockets = js.Array[js.Dynamic]()

            // Single-threaded JS queue: incoming connections are buffered here.
            // pendingPull holds a waiting stream consumer's promise (at most one at a time).
            val connQueue                                             = scala.collection.mutable.Queue.empty[JsConnection]
            var pendingPull: Maybe[Promise.Unsafe[JsConnection, Any]] = Absent

            discard(server.on(
                "error",
                { (err: js.Dynamic) =>
                    import AllowUnsafe.embrace.danger
                    discard(ready.unsafe.complete(Result.Panic(
                        HttpBindException(listenHost, port, new Exception(err.message.asInstanceOf[String]))
                    )))
                }: js.Function1[js.Dynamic, Unit]
            ))

            discard(server.on(
                "connection",
                { (socket: js.Dynamic) =>
                    import AllowUnsafe.embrace.danger
                    discard(socket.pause())
                    discard(socket.setNoDelay(true))
                    discard(activeSockets.push(socket))
                    val jsConn = new JsRawConnection(socket)
                    val conn   = new JsConnection(jsConn, new JsStream(jsConn))
                    pendingPull match
                        case Present(p) =>
                            pendingPull = Absent
                            discard(p.complete(Result.succeed(conn)))
                        case Absent =>
                            connQueue.enqueue(conn)
                    end match
                }: js.Function1[js.Dynamic, Unit]
            ))

            val connStream: Stream[JsConnection, Async] =
                Stream.unfold((), chunkSize = 1) { _ =>
                    given Frame = frame
                    if connQueue.nonEmpty then
                        Maybe((connQueue.dequeue(), ()))
                    else
                        Promise.init[JsConnection, Any].map { p =>
                            pendingPull = Present(p.unsafe)
                            p.get.map(c => Maybe((c, ())))
                        }
                    end if
                }

            Scope.acquireRelease {
                Sync.defer {
                    discard(server.listen(
                        port,
                        host,
                        { () =>
                            import AllowUnsafe.embrace.danger
                            val addr       = server.address()
                            val actualPort = addr.port.asInstanceOf[Int]
                            discard(ready.unsafe.complete(Result.succeed(
                                new TransportListener(actualPort, listenHost, connStream)
                            )))
                        }: js.Function0[Unit]
                    ))
                }.andThen(ready.get)
            } { _ =>
                Sync.defer {
                    import AllowUnsafe.embrace.danger
                    // Complete any pending pull so the connections stream fiber unblocks
                    pendingPull match
                        case Present(p) =>
                            discard(p.complete(Result.Panic(new Exception("Server closed"))))
                            pendingPull = Absent
                        case Absent =>
                    end match
                    // Destroy active sockets
                    var i = 0
                    while i < activeSockets.length do
                        discard(activeSockets(i).destroy())
                        i += 1
                    discard(activeSockets.length = 0)
                    discard(server.close())
                }
            }
        }

end JsTransport

private[kyo] class JsRawConnection(val socket: js.Dynamic)

/** Node.js stream with permanent socket listeners.
  *
  * Registers "data", "end", "close", "error" handlers ONCE at construction time via socket.on(). A mutable
  * pendingReadPromise/pendingReadBuf pair is set before each socket.resume() and cleared by whichever handler fires first. This eliminates
  * the race window that existed when using per-read once() registration: if "close" or "error" fired between read() calls (after the
  * previous once-handlers had self-removed but before new ones were registered), the pending Promise would never complete.
  */
private[kyo] class JsStream(conn: JsRawConnection) extends RawStream:

    // Leftover bytes from a Node.js "data" chunk that didn't fit in the last buf.
    // Served before issuing another socket.resume().
    private var leftover: Maybe[(Array[Byte], Int, Int)] = Absent

    // Current pending read state — set in read() before socket.resume(), cleared by whichever
    // handler fires first. JavaScript is single-threaded so plain vars are safe (no concurrent access).
    private var pendingReadBuf: Maybe[Array[Byte]]                  = Absent
    private var pendingReadPromise: Maybe[Promise.Unsafe[Int, Any]] = Absent

    // Completes and clears any pending read promise with EOF (-1).
    // Called from "end", "close", "error" permanent handlers.
    private def completePendingEof(): Unit =
        pendingReadPromise match
            case Present(p) =>
                pendingReadPromise = Absent
                pendingReadBuf = Absent
                import AllowUnsafe.embrace.danger
                discard(p.complete(Result.succeed(-1)))
            case Absent =>

    // Register permanent listeners once at construction time.
    {
        import AllowUnsafe.embrace.danger

        discard(conn.socket.on(
            "data",
            { (chunk: js.Dynamic) =>
                import AllowUnsafe.embrace.danger
                pendingReadPromise match
                    case Present(p) =>
                        discard(conn.socket.pause())
                        val buf = pendingReadBuf.get
                        pendingReadPromise = Absent
                        pendingReadBuf = Absent
                        val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
                        val len        = math.min(nodeBuffer.length, buf.length)
                        var i          = 0
                        while i < len do
                            buf(i) = nodeBuffer(i).toByte
                            i += 1
                        // Buffer excess bytes so they are served on the next read() call.
                        val excess = nodeBuffer.length - len
                        if excess > 0 then
                            val excessBuf = new Array[Byte](excess)
                            var j         = 0
                            while j < excess do
                                excessBuf(j) = nodeBuffer(len + j).toByte
                                j += 1
                            leftover = Present((excessBuf, 0, excess))
                        end if
                        discard(p.complete(Result.succeed(len)))
                    case Absent =>
                end match
            }: js.Function1[js.Dynamic, Unit]
        ))

        discard(conn.socket.on(
            "end",
            { () =>
                completePendingEof()
            }: js.Function0[Unit]
        ))

        // "close" fires when the socket is destroyed (e.g. server.closeAllConnections() or peer disconnect).
        discard(conn.socket.on(
            "close",
            { () =>
                completePendingEof()
            }: js.Function0[Unit]
        ))

        // "error" fires on ECONNRESET (peer called socket.destroy()). Without this handler
        // Node.js throws an unhandled error event. Treat as EOF so the read loop exits cleanly.
        discard(conn.socket.on(
            "error",
            { (err: js.Dynamic) =>
                completePendingEof()
            }: js.Function1[js.Dynamic, Unit]
        ))
    }

    def read(buf: Array[Byte])(using Frame): Int < Async =
        // Drain leftover from a previous oversized chunk before touching the socket.
        leftover match
            case Present((leftoverBuf, leftoverOff, leftoverLen)) =>
                Sync.defer {
                    val n = math.min(buf.length, leftoverLen)
                    java.lang.System.arraycopy(leftoverBuf, leftoverOff, buf, 0, n)
                    val remainingLen = leftoverLen - n
                    if remainingLen == 0 then
                        leftover = Absent
                    else
                        leftover = Present((leftoverBuf, leftoverOff + n, remainingLen))
                    end if
                    n
                }
            case Absent =>
                if conn.socket.destroyed.asInstanceOf[Boolean] then
                    // Socket was already destroyed before we could register a new read.
                    Sync.defer(-1)
                else
                    Promise.init[Int, Any].map { promise =>
                        Sync.defer {
                            pendingReadBuf = Present(buf)
                            pendingReadPromise = Present(promise.unsafe)
                            discard(conn.socket.resume())
                        }.andThen(promise.get)
                    }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else if conn.socket.destroyed.asInstanceOf[Boolean] then
            Kyo.unit
        else
            Promise.init[Unit, Any].map { promise =>
                Sync.defer {
                    val arr     = data.toArrayUnsafe
                    val jsArr   = js.Array[Int](arr.map(_.toInt)*)
                    val nodeBuf = js.Dynamic.global.Buffer.from(jsArr)
                    val flushed = conn.socket.write(nodeBuf).asInstanceOf[Boolean]
                    if flushed then
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.succeed(())))
                    else
                        // Backpressured — wait for drain with mutual cleanup to avoid stale listeners.
                        // null.asInstanceOf is unavoidable here: these three callbacks form a mutual
                        // cross-reference cycle (each removes the other two), so they must be declared
                        // as vars before any can be assigned. Scala.js js.Function types are JS interop
                        // types with no sensible "empty" value, and Maybe would add overhead on every
                        // callback invocation in a hot path.
                        var drainCb: js.Function0[Unit]             = null.asInstanceOf[js.Function0[Unit]]
                        var closeCb: js.Function0[Unit]             = null.asInstanceOf[js.Function0[Unit]]
                        var errorCb: js.Function1[js.Dynamic, Unit] = null.asInstanceOf[js.Function1[js.Dynamic, Unit]]
                        drainCb = () =>
                            import AllowUnsafe.embrace.danger
                            discard(conn.socket.removeListener("close", closeCb))
                            discard(conn.socket.removeListener("error", errorCb))
                            discard(promise.unsafe.complete(Result.succeed(())))
                        closeCb = () =>
                            import AllowUnsafe.embrace.danger
                            discard(conn.socket.removeListener("drain", drainCb))
                            discard(conn.socket.removeListener("error", errorCb))
                            discard(promise.unsafe.complete(Result.succeed(())))
                        errorCb = (err: js.Dynamic) =>
                            import AllowUnsafe.embrace.danger
                            discard(conn.socket.removeListener("drain", drainCb))
                            discard(conn.socket.removeListener("close", closeCb))
                            discard(promise.unsafe.complete(Result.succeed(())))
                        discard(conn.socket.once("drain", drainCb))
                        discard(conn.socket.once("close", closeCb))
                        discard(conn.socket.once("error", errorCb))
                    end if
                }.andThen(promise.get)
            }

end JsStream

/** TransportStream adapter for JsRawConnection. Exposes `Stream[Span[Byte], Async]` reads by delegating to JsStream.read(buf).
  *
  * Used by JsTransport and WsTransportClient.
  */
private[kyo] class JsConnection(val conn: JsRawConnection, private val stream: JsStream) extends TransportStream:
    private val ChunkSize = 4096

    def read(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            val buf = new Array[Byte](ChunkSize)
            stream.read(buf).map { n =>
                if n <= 0 then Maybe.empty
                else
                    val arr = new Array[Byte](n)
                    java.lang.System.arraycopy(buf, 0, arr, 0, n)
                    Maybe((Span.fromUnsafe(arr), ()))
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        stream.write(data)

end JsConnection
