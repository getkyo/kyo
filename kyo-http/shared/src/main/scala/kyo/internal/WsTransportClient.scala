package kyo.internal

import kyo.*

/** WebSocket client using Transport. Uses WsCodec directly with stream threading.
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
        val tls     = if url.ssl then Present(TlsConfig.default) else Absent
        val address = TransportAddress.Tcp(url.host, url.port)
        transport.connect(address, tls).map { connection =>
            Scope.run {
                Scope.acquireRelease(Sync.defer(connection))(c => transport.closeNow(c)).map { connection =>
                    WsCodec.requestUpgrade(connection, url.host, url.path, headers, config).map { wsStream =>
                        Channel.initUnscoped[WebSocketFrame](config.bufferSize).map { inbound =>
                            Channel.initUnscoped[WebSocketFrame](config.bufferSize).map { outbound =>
                                AtomicRef.init[Maybe[(Int, String)]](Absent).map { closeReasonRef =>
                                    val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                                        closeReasonRef.set(Present((code, reason))).andThen {
                                            outbound.close.unit
                                        }
                                    val ws = new WebSocket(inbound, outbound, closeReasonRef, closeFn)

                                    Fiber.initUnscoped {
                                        Loop(wsStream) { stream =>
                                            WsCodec.readFrame(stream, connection).map { case (frame, remaining) =>
                                                inbound.put(frame).andThen(Loop.continue(remaining))
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
                                                            WsCodec.writeFrame(connection, frame, mask = true).andThen(Loop.continue)
                                                        }
                                                    }
                                                }.map { _ =>
                                                    closeReasonRef.get.map {
                                                        case Present((code, reason)) =>
                                                            Abort.run[Any](WsCodec.writeClose(connection, code, reason, mask = true)).unit
                                                        case Absent => Kyo.unit
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
    end connect

end WsTransportClient
