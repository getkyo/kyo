package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** WebSocket tests over TestTransport — full server + client stack. Tests WsCodec + WsTransportClient + HttpTransportServer serveWebSocket.
  */
class WebSocketTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    /** Start a WS server with an echo handler. */
    private def withWsEchoServer(
        f: (TestTransport, Int) => Assertion < (Async & Abort[HttpException | Closed])
    )(using Frame): Assertion < (Async & Abort[HttpException | Closed] & Scope) =
        val transport = new TestTransport
        val wsHandler = HttpHandler.webSocket("ws/echo") { (request, ws) =>
            Loop.foreach {
                ws.take().map { frame =>
                    ws.put(frame).andThen(Loop.continue)
                }
            }.handle(Abort.run[Closed]).unit
        }
        val server = new HttpTransportServer(transport, Http1Protocol)
        server.bind(Seq(wsHandler), HttpServerConfig.default).map { binding =>
            f(transport, binding.port)
        }
    end withWsEchoServer

    // ── Basic messaging ─────────────────────────────────────────

    "client sends text, server echoes" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect("127.0.0.1", port, "/ws/echo", ssl = false, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text("hello")).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Text(text) => assert(text == "hello")
                            case other                     => fail(s"Expected Text, got $other")
                    }
                }
            }
        }
    }

    "client sends binary, server echoes" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            val data     = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            wsClient.connect("127.0.0.1", port, "/ws/echo", ssl = false, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Binary(data)).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Binary(received) =>
                                assert(received.toArrayUnsafe.sameElements(data.toArrayUnsafe))
                            case other => fail(s"Expected Binary, got $other")
                    }
                }
            }
        }
    }

    "multiple messages in sequence" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect("127.0.0.1", port, "/ws/echo", ssl = false, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text("one")).andThen {
                    ws.take().map { f1 =>
                        assert(f1 == WebSocketFrame.Text("one"))
                        ws.put(WebSocketFrame.Text("two")).andThen {
                            ws.take().map { f2 =>
                                assert(f2 == WebSocketFrame.Text("two"))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Edge cases ──────────────────────────────────────────────

    "empty text message" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect("127.0.0.1", port, "/ws/echo", ssl = false, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text("")).andThen {
                    ws.take().map { frame =>
                        assert(frame == WebSocketFrame.Text(""))
                    }
                }
            }
        }
    }

end WebSocketTransportTest
