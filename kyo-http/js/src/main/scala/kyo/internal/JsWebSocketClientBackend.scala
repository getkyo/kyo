package kyo.internal

import kyo.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** JS WebSocket client using the browser/Node.js WebSocket API. */
final class JsWebSocketClientBackend extends HttpBackend.WebSocketClient:

    private def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

    def connect[A, S](
        host: String,
        port: Int,
        path: String,
        ssl: Boolean,
        headers: HttpHeaders,
        config: WebSocketConfig
    )(
        f: WebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val scheme = if ssl then "wss" else "ws"
            val url    = s"$scheme://$host:$port$path"
            val protocols =
                if config.subprotocols.isEmpty then js.Array[String]()
                else js.Array(config.subprotocols*)

            val handshakePromise                     = Promise.Unsafe.init[WebSocket, Any]()
            val inbound                              = Channel.Unsafe.init[WebSocketFrame](config.bufferSize)
            val outbound                             = Channel.Unsafe.init[WebSocketFrame](config.bufferSize)
            val closeReasonRef                       = AtomicRef.Unsafe.init[Maybe[(Int, String)]](Absent)
            var drainFiber: Option[Fiber[Unit, Any]] = None

            // Node.js's undici WebSocket supports custom headers via options parameter.
            // Browser WebSocket does not — but kyo-http JS runs on Node.js.
            val jsHeaders = js.Dynamic.literal()
            headers.foreach((name, value) => jsHeaders.updateDynamic(name)(value))
            val options = js.Dynamic.literal(headers = jsHeaders)

            val ws =
                if protocols.isEmpty then
                    js.Dynamic.newInstance(js.Dynamic.global.WebSocket)(url, options).asInstanceOf[dom.WebSocket]
                else
                    js.Dynamic.newInstance(js.Dynamic.global.WebSocket)(url, protocols, options).asInstanceOf[dom.WebSocket]

            ws.binaryType = "arraybuffer"

            ws.onopen = (_: dom.Event) =>
                import AllowUnsafe.embrace.danger

                val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                    Sync.defer {
                        import AllowUnsafe.embrace.danger
                        discard(closeReasonRef.set(Present((code, reason))))
                        ws.close(code, reason)
                    }

                val kyoWs = new WebSocket(inbound.safe, outbound.safe, closeReasonRef.safe, closeFn)

                // Outbound drain fiber
                drainFiber = Some(launchFiber {
                    Loop.foreach {
                        outbound.safe.takeWith {
                            case WebSocketFrame.Text(data) =>
                                Sync.defer {
                                    import AllowUnsafe.embrace.danger
                                    ws.send(data)
                                }.andThen(Loop.continue)
                            case WebSocketFrame.Binary(data) =>
                                Sync.defer {
                                    import AllowUnsafe.embrace.danger
                                    val buf  = new ArrayBuffer(data.size)
                                    val view = new Int8Array(buf)
                                    var i    = 0
                                    while i < data.size do
                                        view(i) = data(i)
                                        i += 1
                                    ws.send(buf)
                                }.andThen(Loop.continue)
                        }
                    }.handle(Abort.run[Closed]).unit
                })

                discard(handshakePromise.complete(Result.succeed(kyoWs)))

            ws.onmessage = (event: dom.MessageEvent) =>
                import AllowUnsafe.embrace.danger
                val frame = event.data match
                    case s: String =>
                        WebSocketFrame.Text(s)
                    case buf: ArrayBuffer =>
                        val view  = new Int8Array(buf)
                        val bytes = new Array[Byte](view.length)
                        var i     = 0
                        while i < view.length do
                            bytes(i) = view(i).toByte
                            i += 1
                        WebSocketFrame.Binary(Span.fromUnsafe(bytes))
                    case other =>
                        throw new IllegalStateException(s"Unexpected WebSocket message data type: ${other.getClass}")

                // Browser WebSocket has no pause mechanism for backpressure.
                // If channel is full, use putFiber (non-blocking enqueue).
                inbound.offer(frame) match
                    case Result.Success(false) => discard(inbound.putFiber(frame))
                    case _                     => ()

            ws.onerror = (_: dom.Event) =>
                import AllowUnsafe.embrace.danger
                if !handshakePromise.done() then
                    discard(handshakePromise.complete(
                        Result.Panic(HttpConnectException(host, port, new Exception("WebSocket error")))
                    ))
                end if
                discard(inbound.close())
                discard(outbound.close())

            ws.onclose = (event: dom.CloseEvent) =>
                import AllowUnsafe.embrace.danger
                discard(closeReasonRef.set(Present((event.code, event.reason))))
                discard(inbound.close())
                discard(outbound.close())

            // Wait for handshake, run user function, cleanup
            handshakePromise.safe.use { kyoWs =>
                Sync.ensure {
                    Sync.defer {
                        import AllowUnsafe.embrace.danger
                        if ws.readyState == dom.WebSocket.OPEN || ws.readyState == dom.WebSocket.CONNECTING then
                            ws.close(1000, "")
                        discard(inbound.close())
                        discard(outbound.close())
                        drainFiber.foreach(f =>
                            discard(f.unsafe.interrupt(Result.Panic(new Exception("closing"))))
                        )
                    }
                } {
                    f(kyoWs)
                }
            }
        }
end JsWebSocketClientBackend
