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

end NioTransportTest
