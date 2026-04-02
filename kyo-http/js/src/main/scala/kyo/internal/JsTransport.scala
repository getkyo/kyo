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

    type Connection = JsConnection

    private val net    = js.Dynamic.global.require("net")
    private val tlsMod = js.Dynamic.global.require("tls")

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : JsConnection < (Async & Abort[HttpException]) =
        Promise.init[JsConnection, Async & Abort[HttpException]].map { promise =>
            Sync.defer {
                val (socket, connectEvent) =
                    if tls then
                        val opts = js.Dynamic.literal(
                            host = host,
                            port = port,
                            servername = host, // SNI
                            rejectUnauthorized = true
                        )
                        (tlsMod.connect(opts), "secureConnect")
                    else
                        (net.connect(port, host), "connect")
                discard(socket.setNoDelay(true))
                discard(socket.pause())
                discard(socket.on(
                    connectEvent,
                    { () =>
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.succeed(new JsConnection(socket))))
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

    def isAlive(connection: JsConnection)(using Frame): Boolean < Sync =
        Sync.defer(!connection.socket.destroyed.asInstanceOf[Boolean])

    def closeNow(connection: JsConnection)(using Frame): Unit < Sync =
        Sync.defer(discard(connection.socket.destroy()))

    def close(connection: JsConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(discard(connection.socket.end()))

    def stream(connection: JsConnection)(using Frame): TransportStream < Async =
        Sync.defer(new JsStream(connection))

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using frame: Frame): TransportListener < (Async & Scope) =
        Promise.init[TransportListener, Any].map { ready =>
            val server     = net.createServer()
            val listenHost = host
            // Track active connection sockets so we can destroy them when the server closes.
            // JavaScript is single-threaded so a plain js.Array is safe here.
            val activeSockets = js.Array[js.Dynamic]()
            // Propagate server errors (e.g. EADDRINUSE) so Node.js doesn't crash with an unhandled event.
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
                    given Frame = frame
                    discard(socket.pause())
                    discard(socket.setNoDelay(true))
                    discard(activeSockets.push(socket))
                    val conn = new JsConnection(socket)
                    discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped {
                        Sync.ensure(
                            Sync.defer {
                                val idx = activeSockets.indexOf(socket)
                                if idx >= 0 then discard(activeSockets.splice(idx, 1))
                            }.andThen(closeNow(conn))
                        ) {
                            handler(new JsStream(conn))
                        }
                    }))
                }: js.Function1[js.Dynamic, Unit]
            ))

            Scope.acquireRelease {
                Sync.defer {
                    discard(server.listen(
                        port,
                        host,
                        { () =>
                            import AllowUnsafe.embrace.danger
                            val addr = server.address()
                            discard(ready.unsafe.complete(Result.succeed(new TransportListener:
                                val port = addr.port.asInstanceOf[Int]
                                val host = listenHost)))
                        }: js.Function0[Unit]
                    ))
                }.andThen(ready.get)

            } { _ =>
                // Destroy all active connections so pending read promises complete (via the
                // permanent "close" handler in JsStream) and their fibers can exit.
                // Without this the Node.js event loop stays alive indefinitely.
                Sync.defer {
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

private[kyo] class JsConnection(val socket: js.Dynamic)

/** Node.js stream with permanent socket listeners.
  *
  * Registers "data", "end", "close", "error" handlers ONCE at construction time via socket.on(). A mutable
  * pendingReadPromise/pendingReadBuf pair is set before each socket.resume() and cleared by whichever handler fires first. This eliminates
  * the race window that existed when using per-read once() registration: if "close" or "error" fired between read() calls (after the
  * previous once-handlers had self-removed but before new ones were registered), the pending Promise would never complete.
  */
private[kyo] class JsStream(conn: JsConnection) extends TransportStream:

    // Leftover bytes from a Node.js "data" chunk that didn't fit in the last buf.
    // Served before issuing another socket.resume().
    private var leftoverBuf: Array[Byte] = null
    private var leftoverOff: Int         = 0
    private var leftoverLen: Int         = 0

    // Current pending read state — set in read() before socket.resume(), cleared by whichever
    // handler fires first. JavaScript is single-threaded so plain vars are safe (no concurrent access).
    // Option avoids null comparison issues with the opaque Promise.Unsafe type.
    private var pendingReadBuf: Array[Byte]                          = null
    private var pendingReadPromise: Option[Promise.Unsafe[Int, Any]] = None

    // Completes and clears any pending read promise with EOF (-1).
    // Called from "end", "close", "error" permanent handlers.
    private def completePendingEof(): Unit =
        pendingReadPromise match
            case Some(p) =>
                pendingReadPromise = None
                pendingReadBuf = null
                import AllowUnsafe.embrace.danger
                discard(p.complete(Result.succeed(-1)))
            case None =>

    // Register permanent listeners once at construction time.
    {
        import AllowUnsafe.embrace.danger

        discard(conn.socket.on(
            "data",
            { (chunk: js.Dynamic) =>
                import AllowUnsafe.embrace.danger
                pendingReadPromise match
                    case Some(p) =>
                        discard(conn.socket.pause())
                        val buf = pendingReadBuf
                        pendingReadPromise = None
                        pendingReadBuf = null
                        val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
                        val len        = math.min(nodeBuffer.length, buf.length)
                        var i          = 0
                        while i < len do
                            buf(i) = nodeBuffer(i).toByte
                            i += 1
                        // Buffer excess bytes so they are served on the next read() call.
                        val excess = nodeBuffer.length - len
                        if excess > 0 then
                            leftoverBuf = new Array[Byte](excess)
                            var j = 0
                            while j < excess do
                                leftoverBuf(j) = nodeBuffer(len + j).toByte
                                j += 1
                            leftoverOff = 0
                            leftoverLen = excess
                        end if
                        discard(p.complete(Result.succeed(len)))
                    case None =>
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
        if leftoverLen > 0 then
            Sync.defer {
                val n = math.min(buf.length, leftoverLen)
                java.lang.System.arraycopy(leftoverBuf, leftoverOff, buf, 0, n)
                leftoverOff += n
                leftoverLen -= n
                if leftoverLen == 0 then
                    leftoverBuf = null
                    leftoverOff = 0
                n
            }
        else if conn.socket.destroyed.asInstanceOf[Boolean] then
            // Socket was already destroyed before we could register a new read.
            Sync.defer(-1)
        else
            Promise.init[Int, Any].map { promise =>
                Sync.defer {
                    pendingReadBuf = buf
                    pendingReadPromise = Some(promise.unsafe)
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
