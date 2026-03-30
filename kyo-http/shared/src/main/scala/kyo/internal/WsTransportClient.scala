package kyo.internal

import kyo.*

/** WebSocket client — separate from HTTP client (different lifecycle, no pooling).
  *
  * All WS resources (channels, fibers) are unscoped to prevent Scope.run from closing them prematurely. Cleanup ordering via Sync.ensure:
  *   1. f(ws) returns
  *   2. Interrupt read/write/monitor fibers
  *   3. Close outbound (unblocks ws.put if server disconnected)
  *   4. Monitor closes inbound AFTER read fiber exits
  *   5. Transport connection closed by outer ensure
  *
  * Client frames are masked (mask=true) per RFC 6455 §5.3.
  */
class WsTransportClient(transport: Transport) extends HttpBackend.WebSocketClient:

    def connect[A, S](
        url: HttpUrl,
        headers: HttpHeaders,
        config: WebSocketConfig
    )(
        f: WebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        transport.connect(url.host, url.port, url.ssl).map { connection =>
            Sync.ensure(transport.closeNow(connection)) {
                transport.stream(connection).map { stream =>
                    WsCodec.requestUpgrade(stream, url.host, url.path, headers, config).andThen {
                        Channel.initUnscoped[WebSocketFrame](config.bufferSize).map { inbound =>
                            Channel.initUnscoped[WebSocketFrame](config.bufferSize).map { outbound =>
                                AtomicRef.init[Maybe[(Int, String)]](Absent).map { closeReasonRef =>
                                    val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                                        closeReasonRef.set(Present((code, reason))).andThen {
                                            WsCodec.writeClose(stream, code, reason, mask = true)
                                        }
                                    val ws = new WebSocket(inbound, outbound, closeReasonRef, closeFn)

                                    Fiber.initUnscoped {
                                        Loop.foreach {
                                            WsCodec.readFrame(stream).map { frame =>
                                                inbound.put(frame).andThen(Loop.continue)
                                            }
                                        }
                                    }.map { readFiber =>
                                        Fiber.initUnscoped {
                                            readFiber.getResult.map { _ =>
                                                inbound.close.unit
                                            }
                                        }.map { monitorFiber =>
                                            Fiber.initUnscoped {
                                                Abort.run[Closed] {
                                                    Loop.foreach {
                                                        outbound.take.map { frame =>
                                                            WsCodec.writeFrame(stream, frame, mask = true).andThen(Loop.continue)
                                                        }
                                                    }
                                                }.andThen(outbound.close).unit
                                            }.map { writeFiber =>
                                                Sync.ensure(
                                                    readFiber.interrupt.unit
                                                        .andThen(writeFiber.interrupt.unit)
                                                        .andThen(monitorFiber.interrupt.unit)
                                                        .andThen(inbound.close.unit)
                                                        .andThen(outbound.close.unit)
                                                ) {
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
