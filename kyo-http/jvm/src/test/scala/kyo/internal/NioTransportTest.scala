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

end NioTransportTest
