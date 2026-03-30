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

    private val net = js.Dynamic.global.require("net")

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : JsConnection < (Async & Abort[HttpException]) =
        if tls then Abort.fail(HttpConnectException(host, port, new Exception("TLS not yet supported on JS")))
        else
            Promise.init[JsConnection, Any].map { promise =>
                Sync.defer {
                    val socket = net.connect(port, host)
                    discard(socket.setNoDelay(true))
                    discard(socket.pause())
                    discard(socket.on(
                        "connect",
                        { () =>
                            import AllowUnsafe.embrace.danger
                            discard(promise.unsafe.complete(Result.succeed(new JsConnection(socket))))
                        }: js.Function0[Unit]
                    ))
                    discard(socket.on(
                        "error",
                        { (err: js.Dynamic) =>
                            import AllowUnsafe.embrace.danger
                            discard(promise.unsafe.complete(Result.Panic(
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
            val server = net.createServer()
            discard(server.on(
                "connection",
                { (socket: js.Dynamic) =>
                    import AllowUnsafe.embrace.danger
                    given Frame = frame
                    discard(socket.pause())
                    discard(socket.setNoDelay(true))
                    val conn = new JsConnection(socket)
                    discard(Fiber.initUnscoped {
                        Sync.ensure(closeNow(conn)) {
                            handler(new JsStream(conn))
                        }
                    })
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
                                val host = addr.address.asInstanceOf[String])))
                        }: js.Function0[Unit]
                    ))
                }.andThen(ready.get)
            } { _ =>
                Sync.defer(discard(server.close()))
            }
        }

end JsTransport

private[kyo] class JsConnection(val socket: js.Dynamic)

private[kyo] class JsStream(conn: JsConnection) extends TransportStream:

    def read(buf: Array[Byte])(using Frame): Int < Async =
        Promise.init[Int, Any].map { promise =>
            Sync.defer {
                discard(conn.socket.once(
                    "data",
                    { (chunk: js.Dynamic) =>
                        import AllowUnsafe.embrace.danger
                        discard(conn.socket.pause())
                        val nodeBuffer = chunk.asInstanceOf[js.typedarray.Uint8Array]
                        val len        = Math.min(nodeBuffer.length, buf.length)
                        var i          = 0
                        while i < len do
                            buf(i) = nodeBuffer(i).toByte
                            i += 1
                        discard(promise.unsafe.complete(Result.succeed(len)))
                    }: js.Function1[js.Dynamic, Unit]
                ))
                discard(conn.socket.once(
                    "end",
                    { () =>
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.succeed(-1)))
                    }: js.Function0[Unit]
                ))
                discard(conn.socket.resume())
            }.andThen(promise.get)
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
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
                        discard(conn.socket.once(
                            "drain",
                            { () =>
                                import AllowUnsafe.embrace.danger
                                discard(promise.unsafe.complete(Result.succeed(())))
                            }: js.Function0[Unit]
                        ))
                    end if
                }.andThen(promise.get)
            }

end JsStream
