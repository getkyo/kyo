package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Baseline TLS tests at the transport level (no HTTP). Exercises NativeTlsStream via KqueueNativeTransport. */
class NativeTlsBaselineTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8    = StandardCharsets.UTF_8
    private val isMacOS = java.lang.System.getProperty("os.name", "").toLowerCase.contains("mac")

    private def onMacOS(
        f: KqueueNativeTransport => Assertion < (Async & Abort[HttpException] & Scope)
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        if !isMacOS then succeed
        else f(new KqueueNativeTransport)

    private def listenerPort(listener: TransportListener[?]): Int =
        listener.address match
            case HttpAddress.Tcp(_, port) => port
            case HttpAddress.Unix(_)      => -1

    // ── helpers ──────────────────────────────────────────────

    private def withTlsServer(
        transport: KqueueNativeTransport
    )(using Frame): TransportListener[KqueueConnection] < Async =
        transport.listen(HttpAddress.Tcp("127.0.0.1", 0), 128, Present(TlsTestHelper.serverTlsConfig))

    private def acceptOne(
        listener: TransportListener[KqueueConnection]
    )(using Frame): KqueueConnection < (Async & Abort[HttpException]) =
        listener.connections.take(1).run.map { chunk =>
            if chunk.isEmpty then
                Abort.panic(new Exception("No connection accepted"))
            else
                chunk(0)
        }

    private def readN(
        conn: KqueueConnection,
        limit: Int
    )(using Frame): Array[Byte] < Async =
        val acc = new java.io.ByteArrayOutputStream()
        Loop.foreach {
            if acc.size() >= limit then
                Loop.done(acc.toByteArray)
            else
                conn.read.take(1).run.map { chunk =>
                    if chunk.isEmpty then
                        Loop.done(acc.toByteArray)
                    else
                        val span = chunk(0)
                        acc.write(span.toArrayUnsafe, 0, span.size)
                        if acc.size() >= limit then Loop.done(acc.toByteArray)
                        else Loop.continue
                }
        }
    end readN

    // ─────────────────────────────────────────────────────────────────────────
    // TLS baseline tests
    // ─────────────────────────────────────────────────────────────────────────

    "single TLS connection" in run {
        onMacOS { transport =>
            withTlsServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        serverConn.write(Span.fromUnsafe("hello".getBytes(Utf8))).andThen {
                            readN(serverConn, 5).map { bytes =>
                                assert(new String(bytes, Utf8) == "world")
                                transport.closeNow(serverConn)
                            }
                        }
                    }
                }
                serverFiber.andThen {
                    transport.connect(
                        HttpAddress.Tcp("127.0.0.1", listenerPort(listener)),
                        Present(TlsTestHelper.clientTlsConfig)
                    ).map {
                        clientConn =>
                            readN(clientConn, 5).map { bytes =>
                                assert(new String(bytes, Utf8) == "hello")
                                clientConn.write(Span.fromUnsafe("world".getBytes(Utf8))).andThen {
                                    transport.closeNow(clientConn).andThen(succeed)
                                }
                            }
                    }
                }
            }
        }
    }

    "5 sequential TLS connections" in run {
        onMacOS { transport =>
            withTlsServer(transport).map { listener =>
                Loop.indexed { i =>
                    if i >= 5 then Loop.done(succeed)
                    else
                        val serverFiber = Fiber.initUnscoped {
                            acceptOne(listener).map { serverConn =>
                                serverConn.write(Span.fromUnsafe(s"hello$i".getBytes(Utf8))).andThen {
                                    readN(serverConn, 6).map { bytes =>
                                        assert(new String(bytes, Utf8) == s"world$i")
                                        transport.closeNow(serverConn)
                                    }
                                }
                            }
                        }
                        serverFiber.andThen {
                            transport.connect(
                                HttpAddress.Tcp("127.0.0.1", listenerPort(listener)),
                                Present(TlsTestHelper.clientTlsConfig)
                            ).map {
                                clientConn =>
                                    readN(clientConn, 6).map { bytes =>
                                        assert(new String(bytes, Utf8) == s"hello$i")
                                        clientConn.write(Span.fromUnsafe(s"world$i".getBytes(Utf8))).andThen {
                                            transport.closeNow(clientConn).andThen(Loop.continue)
                                        }
                                    }
                            }
                        }
                    end if
                }
            }
        }
    }

    "20 sequential TLS connections" in run {
        onMacOS { transport =>
            withTlsServer(transport).map { listener =>
                Loop.indexed { i =>
                    if i >= 20 then Loop.done(succeed)
                    else
                        val msg = s"m$i"
                        val serverFiber = Fiber.initUnscoped {
                            acceptOne(listener).map { serverConn =>
                                serverConn.write(Span.fromUnsafe(msg.getBytes(Utf8))).andThen {
                                    readN(serverConn, msg.length).map { bytes =>
                                        assert(new String(bytes, Utf8) == msg)
                                        transport.closeNow(serverConn)
                                    }
                                }
                            }
                        }
                        serverFiber.andThen {
                            transport.connect(
                                HttpAddress.Tcp("127.0.0.1", listenerPort(listener)),
                                Present(TlsTestHelper.clientTlsConfig)
                            ).map {
                                clientConn =>
                                    readN(clientConn, msg.length).map { bytes =>
                                        assert(new String(bytes, Utf8) == msg)
                                        clientConn.write(Span.fromUnsafe(msg.getBytes(Utf8))).andThen {
                                            transport.closeNow(clientConn).andThen(Loop.continue)
                                        }
                                    }
                            }
                        }
                    end if
                }
            }
        }
    }

    "50 sequential TLS connections" in run {
        onMacOS { transport =>
            withTlsServer(transport).map { listener =>
                Loop.indexed { i =>
                    if i >= 50 then Loop.done(succeed)
                    else
                        val msg = s"x$i"
                        val serverFiber = Fiber.initUnscoped {
                            acceptOne(listener).map { serverConn =>
                                serverConn.write(Span.fromUnsafe(msg.getBytes(Utf8))).andThen {
                                    readN(serverConn, msg.length).map { bytes =>
                                        assert(new String(bytes, Utf8) == msg)
                                        transport.closeNow(serverConn)
                                    }
                                }
                            }
                        }
                        serverFiber.andThen {
                            transport.connect(
                                HttpAddress.Tcp("127.0.0.1", listenerPort(listener)),
                                Present(TlsTestHelper.clientTlsConfig)
                            ).map {
                                clientConn =>
                                    readN(clientConn, msg.length).map { bytes =>
                                        assert(new String(bytes, Utf8) == msg)
                                        clientConn.write(Span.fromUnsafe(msg.getBytes(Utf8))).andThen {
                                            transport.closeNow(clientConn).andThen(Loop.continue)
                                        }
                                    }
                            }
                        }
                    end if
                }
            }
        }
    }

    "connection with large payload" in run {
        val size = 100 * 1024 // 100KB
        val data = new Array[Byte](size)
        java.util.Arrays.fill(data, 0x42.toByte)

        onMacOS { transport =>
            withTlsServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        readN(serverConn, size).map { received =>
                            assert(received.length == size)
                            assert(received.forall(_ == 0x42.toByte))
                            serverConn.write(Span.fromUnsafe("ok".getBytes(Utf8))).andThen {
                                transport.closeNow(serverConn)
                            }
                        }
                    }
                }
                serverFiber.andThen {
                    transport.connect(
                        HttpAddress.Tcp("127.0.0.1", listenerPort(listener)),
                        Present(TlsTestHelper.clientTlsConfig)
                    ).map {
                        clientConn =>
                            clientConn.write(Span.fromUnsafe(data)).andThen {
                                readN(clientConn, 2).map { bytes =>
                                    assert(new String(bytes, Utf8) == "ok")
                                    transport.closeNow(clientConn).andThen(succeed)
                                }
                            }
                    }
                }
            }
        }
    }

    "connection with many small exchanges" in run {
        onMacOS { transport =>
            withTlsServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        Loop.indexed { i =>
                            if i >= 100 then Loop.done(())
                            else
                                readN(serverConn, 4).map { bytes =>
                                    assert(new String(bytes, Utf8) == "ping")
                                    serverConn.write(Span.fromUnsafe("pong".getBytes(Utf8))).andThen {
                                        Loop.continue
                                    }
                                }
                        }.andThen(transport.closeNow(serverConn))
                    }
                }
                serverFiber.andThen {
                    transport.connect(
                        HttpAddress.Tcp("127.0.0.1", listenerPort(listener)),
                        Present(TlsTestHelper.clientTlsConfig)
                    ).map {
                        clientConn =>
                            Loop.indexed { i =>
                                if i >= 100 then Loop.done(succeed)
                                else
                                    clientConn.write(Span.fromUnsafe("ping".getBytes(Utf8))).andThen {
                                        readN(clientConn, 4).map { bytes =>
                                            assert(new String(bytes, Utf8) == "pong")
                                            Loop.continue
                                        }
                                    }
                            }.map { result =>
                                transport.closeNow(clientConn).andThen(result)
                            }
                    }
                }
            }
        }
    }

end NativeTlsBaselineTest
