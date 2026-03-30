package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Epoll transport tests — only meaningful on Linux. On macOS, tests pass trivially (succeed). */
class EpollNativeTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8    = StandardCharsets.UTF_8
    private val isLinux = java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    private def onLinux(f: EpollNativeTransport => Assertion < (Async & Abort[HttpException | Closed] & Scope))(using
        Frame
    ): Assertion < (Async & Abort[HttpException | Closed] & Scope) =
        if !isLinux then succeed
        else f(new EpollNativeTransport)

    "connect to listening server" in run {
        Scope.run {
            onLinux { transport =>
                transport.listen("127.0.0.1", 0, 5) { stream =>
                    val buf = new Array[Byte](1024)
                    stream.read(buf).map { n =>
                        if n > 0 then stream.write(Span.fromUnsafe(buf).slice(0, n))
                        else Kyo.unit
                    }
                }.map { listener =>
                    transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                        transport.stream(conn).map { stream =>
                            val msg = "hello".getBytes(Utf8)
                            stream.write(Span.fromUnsafe(msg)).andThen {
                                val buf = new Array[Byte](1024)
                                stream.read(buf).map { n =>
                                    assert(n == 5)
                                    assert(new String(buf, 0, n, Utf8) == "hello")
                                    transport.close(conn, Duration.Zero).andThen(succeed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "connect to non-existent host fails" in run {
        Scope.run {
            onLinux { transport =>
                Abort.run[HttpException] {
                    transport.connect("127.0.0.1", 1, tls = false)
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    "listen on port 0 assigns a port" in run {
        Scope.run {
            onLinux { transport =>
                transport.listen("127.0.0.1", 0, 5) { _ => Kyo.unit }.map { listener =>
                    assert(listener.port > 0)
                    assert(listener.host == "127.0.0.1")
                }
            }
        }
    }

    "read/write 1MB" in run {
        val size = 1024 * 1024
        val data = new Array[Byte](size)
        java.util.Arrays.fill(data, 42.toByte)

        Scope.run {
            onLinux { transport =>
                transport.listen("127.0.0.1", 0, 5) { stream =>
                    val accum = new java.io.ByteArrayOutputStream()
                    val buf   = new Array[Byte](8192)
                    Loop.foreach {
                        stream.read(buf).map { n =>
                            if n <= 0 then Loop.done(())
                            else
                                accum.write(buf, 0, n)
                                if accum.size() >= size then Loop.done(())
                                else Loop.continue
                        }
                    }.andThen {
                        stream.write(Span.fromUnsafe(accum.toByteArray))
                    }
                }.map { listener =>
                    transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                        transport.stream(conn).map { stream =>
                            stream.write(Span.fromUnsafe(data)).andThen {
                                val accum = new java.io.ByteArrayOutputStream()
                                val buf   = new Array[Byte](8192)
                                Loop.foreach {
                                    stream.read(buf).map { n =>
                                        if n <= 0 then Loop.done(())
                                        else
                                            accum.write(buf, 0, n)
                                            if accum.size() >= size then Loop.done(())
                                            else Loop.continue
                                    }
                                }.andThen {
                                    assert(accum.size() == size)
                                    assert(accum.toByteArray.forall(_ == 42.toByte))
                                    transport.close(conn, Duration.Zero).andThen(succeed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "isAlive after close" in run {
        Scope.run {
            onLinux { transport =>
                transport.listen("127.0.0.1", 0, 5) { _ => Kyo.unit }.map { listener =>
                    transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                        transport.isAlive(conn).map { alive =>
                            assert(alive)
                            transport.closeNow(conn).map { _ =>
                                transport.isAlive(conn).map { alive2 =>
                                    assert(!alive2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "full HTTP roundtrip via HttpTransportServer" in run {
        Scope.run {
            onLinux { transport =>
                val route   = HttpRoute.getRaw("hello").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
                val server  = new HttpTransportServer(transport, Http1Protocol)
                server.bind(Seq(handler), HttpServerConfig.default).map { binding =>
                    transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                        transport.stream(conn).map { stream =>
                            val hdrs = HttpHeaders.empty
                                .add("Host", "localhost")
                                .add("Content-Length", "0")
                            Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/hello", hdrs).andThen {
                                Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (status, _, body) =>
                                    assert(status.code == 200)
                                    body match
                                        case HttpBody.Buffered(d) =>
                                            assert(new String(d.toArrayUnsafe, Utf8).contains("world"))
                                        case _ => fail("Expected Buffered")
                                    end match
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "full WebSocket echo via WsTransportClient" in run {
        Scope.run {
            onLinux { transport =>
                val wsHandler = HttpHandler.webSocket("ws/echo") { (_, ws) =>
                    Loop.foreach {
                        ws.take().map { frame =>
                            ws.put(frame).andThen(Loop.continue)
                        }
                    }.handle(Abort.run[Closed]).unit
                }
                val server = new HttpTransportServer(transport, Http1Protocol)
                server.bind(Seq(wsHandler), HttpServerConfig.default).map { binding =>
                    val wsClient = new WsTransportClient(transport)
                    wsClient.connect(
                        "127.0.0.1",
                        binding.port,
                        "/ws/echo",
                        ssl = false,
                        HttpHeaders.empty,
                        WebSocketConfig()
                    ) { ws =>
                        ws.put(WebSocketFrame.Text("hello-epoll")).andThen {
                            ws.take().map { frame =>
                                frame match
                                    case WebSocketFrame.Text(text) => assert(text == "hello-epoll")
                                    case other                     => fail(s"Expected Text, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

end EpollNativeTransportTest
