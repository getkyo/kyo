package kyo.internal

import kyo.*

/** WebSocket client — separate from HTTP client (different lifecycle, no pooling).
  *
  * Lifecycle:
  *   1. transport.connect → TCP connection
  *   2. WsCodec.requestUpgrade → HTTP upgrade handshake on the stream
  *   3. Three concurrent fibers: read loop, write loop, user function f(ws)
  *   4. When any fiber exits: Sync.ensure closes both channels → loops terminate → connection closed
  *
  * Client frames are masked (mask=true) per RFC 6455 §5.3.
  */
class WsTransportClient(transport: Transport) extends HttpBackend2.WebSocketClient:

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
        transport.connect(host, port, ssl).map { connection =>
            Sync.ensure(transport.closeNow(connection)) {
                transport.stream(connection).map { stream =>
                    WsCodec.requestUpgrade(stream, host, path, headers, config).andThen {
                        // Scope owns the channels — cleaned up when scope exits
                        Scope.run {
                            Channel.init[WebSocketFrame](config.bufferSize).map { inbound =>
                                Channel.init[WebSocketFrame](config.bufferSize).map { outbound =>
                                    AtomicRef.init[Maybe[(Int, String)]](Absent).map { closeReasonRef =>
                                        val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                                            closeReasonRef.set(Present((code, reason))).andThen {
                                                WsCodec.writeClose(stream, code, reason, mask = true)
                                            }
                                        val ws = new WebSocket(inbound, outbound, closeReasonRef, closeFn)

                                        Sync.ensure(inbound.close.unit.andThen(outbound.close.unit)) {
                                            // Read loop: transport → inbound
                                            Fiber.init {
                                                Loop.foreach {
                                                    WsCodec.readFrame(stream).map { frame =>
                                                        inbound.put(frame).andThen(Loop.continue)
                                                    }
                                                }.handle(Abort.run[Closed]).unit
                                            }.andThen {
                                                // Write loop: outbound → transport (masked)
                                                Fiber.init {
                                                    Loop.foreach {
                                                        outbound.take.map { frame =>
                                                            WsCodec.writeFrame(stream, frame, mask = true).andThen(Loop.continue)
                                                        }
                                                    }.handle(Abort.run[Closed]).unit
                                                }.andThen {
                                                    // User function — when it returns, Sync.ensure closes channels,
                                                    // which terminates read/write loops
                                                    f(ws)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

end WsTransportClient
