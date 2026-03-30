package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class TestTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    "echo roundtrip" in run {
        val transport = new TestTransport
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                val buf = new Array[Byte](1024)
                stream.read(buf).map { n =>
                    if n > 0 then stream.write(Span.fromUnsafe(buf).slice(0, n))
                    else Sync.defer(())
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

    "HTTP/1.1 request/response over TestTransport" in run {
        val transport = new TestTransport
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
                            Http1Protocol.readResponse(stream, 65536).map { (status, headers, body) =>
                                assert(status.code == 200)
                            }
                        }
                    }
                }
            }
        }
    }

    "multiple requests on same connection (keep-alive)" in run {
        val transport = new TestTransport
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                Abort.run[HttpException] {
                    Loop.foreach {
                        Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                            val responseBody = s"path=$path"
                            Http1Protocol.writeResponseHead(
                                stream,
                                HttpStatus(200),
                                HttpHeaders.empty.add("Content-Length", responseBody.length.toString)
                            ).andThen {
                                Http1Protocol.writeBody(stream, Span.fromUnsafe(responseBody.getBytes(Utf8))).andThen {
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
                        // Request 1
                        Http1Protocol.writeRequestHead(
                            stream,
                            HttpMethod.GET,
                            "/first",
                            HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                        ).andThen {
                            Http1Protocol.readResponse(stream, 65536).map { (status1, _, body1) =>
                                assert(status1.code == 200)
                                // Request 2 on same connection
                                Http1Protocol.writeRequestHead(
                                    stream,
                                    HttpMethod.GET,
                                    "/second",
                                    HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                                ).andThen {
                                    Http1Protocol.readResponse(stream, 65536).map { (status2, _, body2) =>
                                        assert(status2.code == 200)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end TestTransportTest
