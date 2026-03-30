package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class NioTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8      = StandardCharsets.UTF_8
    private val transport = new NioTransport

    "connect and echo" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                val buf = new Array[Byte](1024)
                stream.read(buf).map { n =>
                    if n > 0 then stream.write(Span.fromUnsafe(buf).slice(0, n))
                    else Kyo.unit
                }
            }.map { listener =>
                transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                    transport.stream(conn).map { stream =>
                        stream.write(Span.fromUnsafe("hello".getBytes(Utf8))).andThen {
                            val buf = new Array[Byte](1024)
                            stream.read(buf).map { n =>
                                assert(n == 5)
                                assert(new String(buf, 0, n, Utf8) == "hello")
                            }
                        }
                    }
                }
            }
        }
    }

    "connect to non-existent port fails" in run {
        Scope.run {
            Abort.run[HttpException] {
                transport.connect("127.0.0.1", 1, tls = false)
            }.map { result =>
                assert(result.isFailure || result.isPanic)
            }
        }
    }

    "listen on port 0 assigns a port" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { _ => Kyo.unit }.map { listener =>
                assert(listener.port > 0)
            }
        }
    }

    "read/write 1MB" in run {
        val size = 1024 * 1024
        val data = new Array[Byte](size)
        java.util.Arrays.fill(data, 42.toByte)

        Scope.run {
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
                            }
                        }
                    }
                }
            }
        }
    }

    "isAlive after close" in run {
        Scope.run {
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

    "HTTP/1.1 over NioTransport" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                Abort.run[HttpException] {
                    Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                        Http1Protocol.writeResponseHead(
                            stream,
                            HttpStatus(200),
                            HttpHeaders.empty.add("Content-Length", "2")
                        ).andThen {
                            Http1Protocol.writeBody(stream, Span.fromUnsafe("OK".getBytes(Utf8)))
                        }
                    }
                }.unit
            }.map { listener =>
                transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                    transport.stream(conn).map { stream =>
                        Http1Protocol.writeRequestHead(
                            stream,
                            HttpMethod.GET,
                            "/test",
                            HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                        ).andThen {
                            Http1Protocol.readResponse(stream, 65536).map { (status, _, _) =>
                                assert(status.code == 200)
                            }
                        }
                    }
                }
            }
        }
    }

    "HTTP/1.1 keep-alive POST with body" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                Abort.run[HttpException] {
                    val bs = Http1Protocol.buffered(stream)
                    Loop.foreach {
                        Http1Protocol.readRequest(bs, 65536).map { (method, path, headers, body) =>
                            val respBody = body match
                                case HttpBody.Buffered(d) => new String(d.toArrayUnsafe, Utf8)
                                case _                    => "no-body"
                            Http1Protocol.writeResponseHead(
                                stream,
                                HttpStatus(200),
                                HttpHeaders.empty.add("Content-Length", respBody.length.toString)
                            ).andThen {
                                Http1Protocol.writeBody(stream, Span.fromUnsafe(respBody.getBytes(Utf8))).andThen {
                                    if Http1Protocol.isKeepAlive(headers) then Loop.continue
                                    else Loop.done(())
                                }
                            }
                        }
                    }
                }.unit
            }.map { listener =>
                transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                    transport.stream(conn).map { stream =>
                        val hdrs = HttpHeaders.empty.add("Host", "localhost")
                        // Request 1 with body
                        val body1 = "data-0"
                        Http1Protocol.writeRequestHead(
                            stream,
                            HttpMethod.POST,
                            "/echo",
                            hdrs.add("Content-Length", body1.length.toString)
                        ).andThen {
                            Http1Protocol.writeBody(stream, Span.fromUnsafe(body1.getBytes(Utf8))).andThen {
                                Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.POST).map { (s1, _, b1) =>
                                    assert(s1.code == 200)
                                    val rb1 = b1 match
                                        case HttpBody.Buffered(d) => new String(d.toArrayUnsafe, Utf8);
                                        case _                    => ""
                                    assert(rb1 == "data-0")
                                    // Request 2 with different body
                                    val body2 = "data-1"
                                    Http1Protocol.writeRequestHead(
                                        stream,
                                        HttpMethod.POST,
                                        "/echo",
                                        hdrs.add("Content-Length", body2.length.toString)
                                    ).andThen {
                                        Http1Protocol.writeBody(stream, Span.fromUnsafe(body2.getBytes(Utf8))).andThen {
                                            Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.POST).map { (s2, _, b2) =>
                                                assert(s2.code == 200)
                                                val rb2 = b2 match
                                                    case HttpBody.Buffered(d) => new String(d.toArrayUnsafe, Utf8);
                                                    case _                    => ""
                                                assert(rb2 == "data-1")
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

    "HTTP/1.1 keep-alive (2 requests on same connection)" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                Abort.run[HttpException] {
                    Loop.foreach {
                        Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                            val resp = s"path=$path"
                            Http1Protocol.writeResponseHead(
                                stream,
                                HttpStatus(200),
                                HttpHeaders.empty.add("Content-Length", resp.length.toString)
                            ).andThen {
                                Http1Protocol.writeBody(stream, Span.fromUnsafe(resp.getBytes(Utf8))).andThen {
                                    if Http1Protocol.isKeepAlive(headers) then Loop.continue
                                    else Loop.done(())
                                }
                            }
                        }
                    }
                }.unit
            }.map { listener =>
                transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                    transport.stream(conn).map { stream =>
                        val hdrs = HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                        // Request 1
                        Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/first", hdrs).andThen {
                            Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s1, _, b1) =>
                                assert(s1.code == 200)
                                // Request 2 on same connection
                                Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/second", hdrs).andThen {
                                    Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s2, _, b2) =>
                                        assert(s2.code == 200)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "chunked streaming response" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                Abort.run[HttpException] {
                    Http1Protocol.readRequest(stream, 65536).map { (_, _, _, _) =>
                        val body = Stream.init(Seq(
                            Span.fromUnsafe("line1\n".getBytes(Utf8)),
                            Span.fromUnsafe("line2\n".getBytes(Utf8))
                        ))
                        Http1Protocol.writeResponseHead(
                            stream,
                            HttpStatus(200),
                            HttpHeaders.empty.add("Transfer-Encoding", "chunked").add("Content-Type", "text/plain")
                        ).andThen {
                            Http1Protocol.writeStreamingBody(stream, body)
                        }
                    }
                }.unit
            }.map { listener =>
                transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                    transport.stream(conn).map { stream =>
                        Http1Protocol.writeRequestHead(
                            stream,
                            HttpMethod.GET,
                            "/stream",
                            HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                        ).andThen {
                            Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (status, headers, body) =>
                                assert(status.code == 200)
                                body match
                                    case HttpBody.Streamed(chunks) =>
                                        chunks.run.map { spans =>
                                            val text = spans.map(s => new String(s.toArrayUnsafe, Utf8)).mkString
                                            assert(text == "line1\nline2\n")
                                        }
                                    case other =>
                                        fail(s"Expected Streamed, got $other")
                                end match
                            }
                        }
                    }
                }
            }
        }
    }

    "WebSocket handshake over NIO" in run {
        val wsHandler = HttpHandler.webSocket("ws/test") { (_, ws) =>
            // Just accept and wait
            ws.take().handle(Abort.run[Closed]).unit
        }
        val server = new HttpTransportServer(transport, Http1Protocol)
        Scope.run {
            server.bind(Seq(wsHandler), HttpServerConfig.default).map { binding =>
                val wsClient = new WsTransportClient(transport)
                wsClient.connect(
                    HttpUrl.parse(s"ws://127.0.0.1:${binding.port}/ws/test").getOrThrow,
                    HttpHeaders.empty,
                    WebSocketConfig()
                ) {
                    ws =>
                        // Just verify we got here (handshake succeeded)
                        succeed
                }
            }
        }
    }

    "raw bytes after WS handshake over NIO" in run {
        // Raw test: server WS handler writes to stream, client reads from stream
        // to verify NIO stream works after WS upgrade
        transport.listen("127.0.0.1", 0, 5) { stream =>
            Abort.run[HttpException] {
                Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                    // Write WS accept response
                    WsCodec.acceptUpgrade(stream, headers, WebSocketConfig()).andThen {
                        // Write a raw WS text frame manually (unmasked, "test")
                        // opcode=1 (text), FIN=1, len=4, no mask
                        val frame = Array[Byte](0x81.toByte, 0x04.toByte, 't'.toByte, 'e'.toByte, 's'.toByte, 't'.toByte)
                        stream.write(Span.fromUnsafe(frame))
                    }
                }
            }.unit
        }.map { listener =>
            transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                transport.stream(conn).map { clientStream =>
                    WsCodec.requestUpgrade(clientStream, "127.0.0.1", "/ws/raw", HttpHeaders.empty, WebSocketConfig()).andThen {
                        // Read the raw WS frame
                        val buf = new Array[Byte](100)
                        clientStream.read(buf).map { n =>
                            java.lang.System.err.println(s"[DEBUG] client read $n bytes after WS handshake")
                            assert(n > 0, s"Expected bytes, got $n")
                        }
                    }
                }
            }
        }
    }

    "WebSocket client send over NIO" in run {
        // Server just reads one frame, doesn't echo
        val wsHandler = HttpHandler.webSocket("ws/recv") { (_, ws) =>
            ws.take().map { frame =>
                java.lang.System.err.println(s"[DEBUG-SERVER] received frame: $frame")
            }.handle(Abort.run[Closed]).unit
        }
        val server = new HttpTransportServer(transport, Http1Protocol)
        Scope.run {
            server.bind(Seq(wsHandler), HttpServerConfig.default).map { binding =>
                val wsClient = new WsTransportClient(transport)
                wsClient.connect(
                    HttpUrl.parse(s"ws://127.0.0.1:${binding.port}/ws/recv").getOrThrow,
                    HttpHeaders.empty,
                    WebSocketConfig()
                ) {
                    ws =>
                        java.lang.System.err.println("[DEBUG-CLIENT] putting frame")
                        ws.put(WebSocketFrame.Text("hello")).andThen {
                            java.lang.System.err.println("[DEBUG-CLIENT] put done, sleeping")
                            Async.sleep(100.millis).andThen(succeed)
                        }
                }
            }
        }
    }

    "WebSocket direct frame exchange over NIO" in run {
        // Bypass WsTransportClient channels — write/read frames directly on the stream
        transport.listen("127.0.0.1", 0, 5) { serverStream =>
            Abort.run[Any] {
                Http1Protocol.readRequest(serverStream, 65536).map { (_, _, headers, _) =>
                    WsCodec.acceptUpgrade(serverStream, headers, WebSocketConfig()).andThen {
                        // Read one frame from client (masked), echo it back (unmasked)
                        Abort.run[Closed](WsCodec.readFrame(serverStream)).map {
                            case Result.Success(frame) => WsCodec.writeFrame(serverStream, frame, mask = false)
                            case _                     => Kyo.unit
                        }
                    }
                }
            }.unit
        }.map { listener =>
            transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                transport.stream(conn).map { clientStream =>
                    WsCodec.requestUpgrade(clientStream, "127.0.0.1", "/ws/direct", HttpHeaders.empty, WebSocketConfig()).andThen {
                        WsCodec.writeFrame(clientStream, WebSocketFrame.Text("hello"), mask = true).andThen {
                            Abort.run[Closed](WsCodec.readFrame(clientStream)).map {
                                case Result.Success(WebSocketFrame.Text(text)) => assert(text == "hello")
                                case other                                     => fail(s"Expected Text, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

end NioTransportTest
