package kyo.internal

import kyo.{WebSocketFrame as WsFrame, *}
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** Handles WebSocket upgrade events on a Node.js HTTP server. */
private[kyo] object NodeWebSocketServer:

    private val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** Handle WebSocket upgrade from within the request handler using res.writeHead(101). */
    def handleRequestUpgrade(
        req: IncomingMessage,
        res: ServerResponse,
        wsHandler: WebSocketHttpHandler,
        uri: String,
        path: String,
        pathEnd: Int
    )(using AllowUnsafe, Frame): Unit =
        val headers = extractHeaders(req.headers)
        headers.get("Sec-WebSocket-Key") match
            case Absent =>
                val jsHeaders = js.Dictionary[js.Any]()
                discard(res.writeHead(400, jsHeaders))
                res.endEmpty()
            case Present(key) =>
                val config = wsHandler.wsConfig

                // Compute Sec-WebSocket-Accept
                val crypto = js.Dynamic.global.require("crypto")
                val sha1   = crypto.createHash("sha1")
                discard(sha1.update(key + WS_GUID))
                val acceptKey = sha1.digest("base64").asInstanceOf[String]

                // Send 101 via the response object (proper HTTP layer integration)
                val responseHeaders = js.Dictionary[js.Any](
                    "Upgrade"              -> "websocket",
                    "Connection"           -> "Upgrade",
                    "Sec-WebSocket-Accept" -> acceptKey
                )
                discard(res.writeHead(101, responseHeaders))

                // Get the raw socket from the response
                val socket = res.asInstanceOf[js.Dynamic].socket.asInstanceOf[NetSocket]
                discard(socket.setNoDelay(true))
                discard(socket.setTimeout(0))

                // Build kyo request
                val queryFn =
                    if pathEnd < 0 then Absent
                    else Present(HttpUrl.fromUri(uri))
                val kyoUrl     = queryFn.getOrElse(HttpUrl(Absent, "", 0, path, Absent))
                val kyoRequest = HttpRequest(HttpMethod.GET, kyoUrl, headers, Record.empty)

                startWebSocketSession(socket, wsHandler, kyoRequest, config)
        end match
    end handleRequestUpgrade

    def handleUpgrade(
        req: IncomingMessage,
        socket: NetSocket,
        head: Uint8Array,
        wsHandlerMap: Map[String, WebSocketHttpHandler]
    )(using AllowUnsafe, Frame): Unit =
        val uri     = req.url
        val pathEnd = uri.indexOf('?')
        val path    = if pathEnd >= 0 then uri.substring(0, pathEnd) else uri

        wsHandlerMap.get(path) match
            case None =>
                socket.end()
            case Some(wsHandler) =>
                val headers = extractHeaders(req.headers)
                headers.get("Sec-WebSocket-Key") match
                    case Absent =>
                        socket.end()
                    case Present(key) =>
                        performHandshake(socket, key, wsHandler, headers, uri, path, pathEnd, head)
                end match
        end match
    end handleUpgrade

    private def performHandshake(
        socket: NetSocket,
        key: String,
        wsHandler: WebSocketHttpHandler,
        headers: HttpHeaders,
        uri: String,
        path: String,
        pathEnd: Int,
        head: Uint8Array
    )(using AllowUnsafe, Frame): Unit =
        val config = wsHandler.wsConfig

        // Compute Sec-WebSocket-Accept
        val crypto = js.Dynamic.global.require("crypto")
        val sha1   = crypto.createHash("sha1")
        discard(sha1.update(key + WS_GUID))
        val acceptKey = sha1.digest("base64").asInstanceOf[String]

        // Subprotocol negotiation
        val requestedProtocols = headers.get("Sec-WebSocket-Protocol")
            .map(_.split(",").map(_.trim).toSeq).getOrElse(Seq.empty)
        val selectedProtocol = requestedProtocols.find(p => config.subprotocols.contains(p))

        // Write 101 response
        val sb = new StringBuilder
        sb.append("HTTP/1.1 101 Switching Protocols\r\n")
        sb.append("Upgrade: websocket\r\n")
        sb.append("Connection: Upgrade\r\n")
        sb.append(s"Sec-WebSocket-Accept: $acceptKey\r\n")
        selectedProtocol.foreach(p => sb.append(s"Sec-WebSocket-Protocol: $p\r\n"))
        sb.append("\r\n")
        discard(socket.write(sb.toString))

        // Build kyo request
        val queryFn =
            if pathEnd < 0 then Absent
            else Present(HttpUrl.fromUri(uri))
        val kyoUrl     = queryFn.getOrElse(HttpUrl(Absent, "", 0, path, Absent))
        val kyoRequest = HttpRequest(HttpMethod.GET, kyoUrl, headers, Record.empty)

        // Configure socket for WebSocket — disable Nagle, clear timeout, inject head buffer
        discard(socket.setNoDelay(true))
        discard(socket.setTimeout(0))
        if head.length > 0 then socket.unshift(head)

        startWebSocketSession(socket, wsHandler, kyoRequest, config)
    end performHandshake

    private def startWebSocketSession(
        socket: NetSocket,
        wsHandler: WebSocketHttpHandler,
        kyoRequest: HttpRequest[Any],
        config: WebSocketConfig
    )(using AllowUnsafe, Frame): Unit =
        // Create channels and WebSocket handle
        val inbound        = Channel.Unsafe.init[WsFrame](config.bufferSize)
        val outbound       = Channel.Unsafe.init[WsFrame](config.bufferSize)
        val closeReasonRef = AtomicRef.Unsafe.init[Maybe[(Int, String)]](Absent)
        val parser         = new NodeWebSocketFramer.Parser()

        val closeFn: (Int, String) => Unit < Async = (code, reason) =>
            Sync.defer {
                import AllowUnsafe.embrace.danger
                discard(closeReasonRef.set(Present((code, reason))))
                discard(socket.write(NodeWebSocketFramer.encodeClose(code, reason)))
            }

        val ws = new WebSocket(inbound.safe, outbound.safe, closeReasonRef.safe, closeFn)

        // Incoming frames via parser
        discard(socket.onData(
            "data",
            { (data: Uint8Array) =>
                import AllowUnsafe.embrace.danger
                val frames = parser.feed(data)
                frames.foreach { frame =>
                    frame.opcode match
                        case NodeWebSocketFramer.OPCODE_TEXT =>
                            val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
                            val text    = decoder.decode(frame.payload).asInstanceOf[String]
                            deliverToChannel(inbound, WsFrame.Text(text), socket)
                        case NodeWebSocketFramer.OPCODE_BINARY =>
                            val bytes = uint8ArrayToBytes(frame.payload)
                            deliverToChannel(inbound, WsFrame.Binary(Span.fromUnsafe(bytes)), socket)
                        case NodeWebSocketFramer.OPCODE_CLOSE =>
                            val code =
                                if frame.payload.length >= 2
                                then ((frame.payload(0) & 0xff) << 8) | (frame.payload(1) & 0xff)
                                else 1005
                            val reason =
                                if frame.payload.length > 2
                                then
                                    val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
                                    decoder.decode(frame.payload.subarray(2)).asInstanceOf[String]
                                else ""
                            discard(closeReasonRef.set(Present((code, reason))))
                            discard(socket.write(NodeWebSocketFramer.encodeClose(code, reason)))
                            discard(inbound.close())
                            discard(outbound.close())
                        case NodeWebSocketFramer.OPCODE_PING =>
                            discard(socket.write(NodeWebSocketFramer.encodePong(frame.payload)))
                        case _ => () // ignore pongs, unknown opcodes
                }
            }
        ))

        discard(socket.on(
            "close",
            { () =>
                import AllowUnsafe.embrace.danger
                discard(inbound.close())
                discard(outbound.close())
            }
        ))

        discard(socket.on(
            "error",
            { () =>
                import AllowUnsafe.embrace.danger
                discard(inbound.close())
                discard(outbound.close())
            }
        ))

        // Track socket state — set to true by socket close event
        var socketClosed = false
        discard(socket.on("close", { () => socketClosed = true }))

        // Outbound drain fiber — closes channels on exit to unblock the handler
        val drainFiber = launchFiber {
            Sync.Unsafe.ensure {
                discard(inbound.close())
                discard(outbound.close())
            } {
                Loop.foreach {
                    outbound.safe.takeWith { frame =>
                        Sync.Unsafe.defer {
                            if socketClosed then Loop.done(())
                            else
                                frame match
                                    case WsFrame.Text(data) =>
                                        discard(socket.write(NodeWebSocketFramer.encodeText(data)))
                                    case WsFrame.Binary(data) =>
                                        discard(socket.write(NodeWebSocketFramer.encodeBinary(data.toArrayUnsafe)))
                                end match
                                Loop.continue
                        }
                    }
                }.handle(Abort.run[Closed]).unit
            }
        }

        // Handler fiber
        val handlerFiber = launchFiber {
            Sync.ensure {
                Sync.defer {
                    import AllowUnsafe.embrace.danger
                    discard(outbound.close())
                    discard(socket.write(NodeWebSocketFramer.encodeClose(1000, "")))
                    socket.end()
                    discard(inbound.close())
                    discard(drainFiber.unsafe.interrupt(Result.Panic(new Exception("handler done"))))
                }
            } {
                wsHandler.wsHandler(kyoRequest, ws).handle(Abort.run[Any]).unit
            }
        }

        // Interrupt handler fiber on client disconnect (same pattern as NodeServerBackend)
        discard(socket.on(
            "close",
            { () =>
                import AllowUnsafe.embrace.danger
                discard(handlerFiber.unsafe.interrupt(Result.Panic(new Exception("client disconnected"))))
            }
        ))
    end startWebSocketSession

    private def deliverToChannel(
        ch: Channel.Unsafe[WsFrame],
        value: WsFrame,
        socket: NetSocket
    )(using AllowUnsafe, Frame): Unit =
        ch.offer(value) match
            case Result.Success(true) =>
            case Result.Success(false) =>
                discard(socket.pause())
                val fiber = ch.putFiber(value)
                fiber.onComplete { _ =>
                    discard(socket.resume())
                }
            case _ => ()

    private def uint8ArrayToBytes(arr: Uint8Array): Array[Byte] =
        val bytes = new Array[Byte](arr.length)
        var i     = 0
        while i < arr.length do
            bytes(i) = arr(i).toByte
            i += 1
        bytes
    end uint8ArrayToBytes

    private def extractHeaders(jsHeaders: js.Dictionary[js.Any]): HttpHeaders =
        val builder = ChunkBuilder.init[String]
        jsHeaders.foreach { (key, value) =>
            value match
                case arr: js.Array[?] =>
                    arr.foreach { v =>
                        discard(builder += key)
                        discard(builder += v.toString)
                    }
                case v =>
                    discard(builder += key)
                    discard(builder += v.toString)
        }
        HttpHeaders.fromChunk(builder.result())
    end extractHeaders

    private def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

end NodeWebSocketServer
