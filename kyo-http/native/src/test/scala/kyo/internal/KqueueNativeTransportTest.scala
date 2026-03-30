package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class KqueueNativeTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    private val transport = new KqueueNativeTransport

    "connect to listening server" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                // Echo server: read then write back
                val buf = new Array[Byte](1024)
                stream.read(buf).map { n =>
                    if n > 0 then stream.write(Span.fromUnsafe(buf).slice(0, n))
                    else Sync.defer(())
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

    "connect to non-existent port fails" in run {
        Scope.run {
            Abort.run[HttpException] {
                transport.connect("127.0.0.1", 1, tls = false)
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "listen on port 0 assigns a port" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { _ => Sync.defer(()) }.map { listener =>
                assert(listener.port > 0)
                assert(listener.host == "127.0.0.1")
            }
        }
    }

    "read/write 1MB" in run {
        val size = 1024 * 1024
        val data = new Array[Byte](size)
        java.util.Arrays.fill(data, 42.toByte)

        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { stream =>
                // Read all then write all back
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

    "isAlive after close" in run {
        Scope.run {
            transport.listen("127.0.0.1", 0, 5) { _ => Sync.defer(()) }.map { listener =>
                transport.connect("127.0.0.1", listener.port, tls = false).map { conn =>
                    import AllowUnsafe.embrace.danger
                    assert(transport.isAlive(conn))
                    transport.closeNowUnsafe(conn)
                    assert(!transport.isAlive(conn))
                }
            }
        }
    }

end KqueueNativeTransportTest
