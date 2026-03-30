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

    /** Start a WS server with a custom handler. */
    private def withWsServer(path: String)(
        handler: (HttpRequest[Any], WebSocket) => Unit < (Async & Abort[Closed])
    )(
        f: (TestTransport, Int) => Assertion < (Async & Abort[HttpException | Closed])
    )(using Frame): Assertion < (Async & Abort[HttpException | Closed] & Scope) =
        val transport = new TestTransport
        val wsHandler = HttpHandler.webSocket(path) { (request, ws) =>
            handler(request, ws)
        }
        val server = new HttpTransportServer(transport, Http1Protocol)
        server.bind(Seq(wsHandler), HttpServerConfig.default).map { binding =>
            f(transport, binding.port)
        }
    end withWsServer

    // ── Basic messaging ─────────────────────────────────────────

    "client sends text, server echoes" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
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
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
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
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
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

    "five messages round-trip" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text("m1")).andThen {
                    ws.take().map { f1 =>
                        assert(f1 == WebSocketFrame.Text("m1"))
                        ws.put(WebSocketFrame.Text("m2")).andThen {
                            ws.take().map { f2 =>
                                assert(f2 == WebSocketFrame.Text("m2"))
                                ws.put(WebSocketFrame.Text("m3")).andThen {
                                    ws.take().map { f3 =>
                                        assert(f3 == WebSocketFrame.Text("m3"))
                                        ws.put(WebSocketFrame.Text("m4")).andThen {
                                            ws.take().map { f4 =>
                                                assert(f4 == WebSocketFrame.Text("m4"))
                                                ws.put(WebSocketFrame.Text("m5")).andThen {
                                                    ws.take().map { f5 =>
                                                        assert(f5 == WebSocketFrame.Text("m5"))
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
        }
    }

    // ── Edge cases ──────────────────────────────────────────────

    "empty text message" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text("")).andThen {
                    ws.take().map { frame =>
                        assert(frame == WebSocketFrame.Text(""))
                    }
                }
            }
        }
    }

    "empty binary message" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Binary(Span.empty[Byte])).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Binary(data) => assert(data.isEmpty)
                            case other                       => fail(s"Expected Binary, got $other")
                    }
                }
            }
        }
    }

    "alternating text and binary messages" in run {
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            val binData  = Span.fromUnsafe(Array[Byte](0x42, 0x43))
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text("text1")).andThen {
                    ws.take().map { f1 =>
                        assert(f1 == WebSocketFrame.Text("text1"))
                        ws.put(WebSocketFrame.Binary(binData)).andThen {
                            ws.take().map { f2 =>
                                f2 match
                                    case WebSocketFrame.Binary(d) =>
                                        assert(d.toArrayUnsafe.sameElements(binData.toArrayUnsafe))
                                    case other => fail(s"Expected Binary, got $other")
                                end match
                                ws.put(WebSocketFrame.Text("text2")).andThen {
                                    ws.take().map { f3 =>
                                        assert(f3 == WebSocketFrame.Text("text2"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Close handshake ───────────────────────────────────────

    "client close terminates session" in run {
        val transport = new TestTransport
        val wsHandler = HttpHandler.webSocket("ws/close") { (_, ws) =>
            Loop.foreach {
                ws.take().map { frame =>
                    ws.put(frame).andThen(Loop.continue)
                }
            }.handle(Abort.run[Closed]).unit
        }
        val server = new HttpTransportServer(transport, Http1Protocol)
        server.bind(Seq(wsHandler), HttpServerConfig.default).map { binding =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:${binding.port}/ws/close").getOrThrow, HttpHeaders.empty, WebSocketConfig()) {
                ws =>
                    ws.put(WebSocketFrame.Text("hi")).andThen {
                        ws.take().map { frame =>
                            assert(frame == WebSocketFrame.Text("hi"))
                            ws.close(1000, "bye").andThen(succeed)
                        }
                    }
            }
        }
    }

    // ── Large message ───────────────────────────────────────────

    "large text message (64KB)" in run {
        val largeText = "x" * 65536
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text(largeText)).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Text(text) => assert(text.length == 65536)
                            case other                     => fail(s"Expected Text, got $other")
                    }
                }
            }
        }
    }

    "large binary message (32KB)" in run {
        val largeData = Span.fromUnsafe(Array.tabulate[Byte](32768)(i => (i % 256).toByte))
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Binary(largeData)).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Binary(d) => assert(d.size == 32768)
                            case other                    => fail(s"Expected Binary, got $other")
                    }
                }
            }
        }
    }

    // ── Unicode ─────────────────────────────────────────────────

    "unicode text preserved" in run {
        val unicode = "café-日本語-🎉"
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text(unicode)).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Text(text) => assert(text == unicode)
                            case other                     => fail(s"Expected Text, got $other")
                    }
                }
            }
        }
    }

    "emoji-only message" in run {
        val emojis = "🎉🎊🎈🎁🎂"
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Text(emojis)).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Text(text) => assert(text == emojis)
                            case other                     => fail(s"Expected Text, got $other")
                    }
                }
            }
        }
    }

    // ── Server-initiated messages ───────────────────────────────

    "server sends message first" in run {
        withWsServer("ws/greet") { (_, ws) =>
            // Server sends greeting, then echoes until closed
            ws.put(WebSocketFrame.Text("welcome")).andThen {
                Loop.foreach {
                    ws.take().map { frame =>
                        ws.put(frame).andThen(Loop.continue)
                    }
                }.handle(Abort.run[Closed]).unit
            }
        } { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/greet").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.take().map { greeting =>
                    assert(greeting == WebSocketFrame.Text("welcome"))
                }
            }
        }
    }

    // ── Binary with all byte values ─────────────────────────────

    "binary with all byte values (0x00-0xFF)" in run {
        val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(_.toByte))
        withWsEchoServer { (transport, port) =>
            val wsClient = new WsTransportClient(transport)
            wsClient.connect(HttpUrl.parse(s"ws://127.0.0.1:$port/ws/echo").getOrThrow, HttpHeaders.empty, WebSocketConfig()) { ws =>
                ws.put(WebSocketFrame.Binary(allBytes)).andThen {
                    ws.take().map { frame =>
                        frame match
                            case WebSocketFrame.Binary(d) =>
                                assert(d.size == 256)
                                assert(d.toArrayUnsafe.sameElements(allBytes.toArrayUnsafe))
                            case other => fail(s"Expected Binary, got $other")
                    }
                }
            }
        }
    }

end WebSocketTransportTest
