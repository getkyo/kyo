package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class TestTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    "channel put/take across fibers" in run {
        Scope.run {
            Channel.init[String](8).map { ch =>
                val fiber = Fiber.init {
                    ch.take.map { msg =>
                        ch.put("got:" + msg)
                    }
                }
                fiber.andThen {
                    ch.put("hello").andThen {
                        ch.take.map { reply =>
                            assert(reply == "got:hello")
                        }
                    }
                }
            }
        }
    }

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
                assert(listener.port > 0)
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

    "HTTP/1.1 over TestTransport" in run {
        val transport = new TestTransport
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                Abort.run[HttpException] {
                    Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                        Http1Protocol.writeResponseHead(stream, HttpStatus(200), HttpHeaders.empty.add("Content-Length", "2")).andThen {
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

end TestTransportTest
