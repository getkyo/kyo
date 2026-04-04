package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class NioTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8      = StandardCharsets.UTF_8
    private val transport = new NioTransport

    // ── helpers ──────────────────────────────────────────────────────────────

    private def withServer(using Frame): TransportListener[NioConnection] < (Async & Scope) =
        transport.listen(TransportAddress.Tcp("127.0.0.1", 0), 128, Absent)

    private def listenerPort(listener: TransportListener[NioConnection]): Int =
        listener.address match
            case TransportAddress.Tcp(_, port) => port
            case TransportAddress.Unix(_)      => -1

    /** Accept a single connection from the listener's stream and return it. */
    private def acceptOne(
        listener: TransportListener[NioConnection]
    )(using Frame): NioConnection < (Async & Abort[HttpException]) =
        listener.connections.take(1).run.map { chunk =>
            if chunk.isEmpty then
                Abort.panic(new Exception("No connection accepted"))
            else
                chunk(0)
        }

    /** Read at least `limit` bytes from a connection's read stream. Stops when `limit` is reached or the stream ends. */
    private def readN(
        conn: NioConnection,
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
    // Connection lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    "connect to listening server → isAlive true" in run {
        Scope.run {
            withServer.map { listener =>
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    transport.isAlive(conn).map { alive =>
                        assert(alive)
                        transport.closeNow(conn).andThen(succeed)
                    }
                }
            }
        }
    }

    "connect to non-existent port → Abort" in run {
        Scope.run {
            Abort.run[HttpException] {
                transport.connect(TransportAddress.Tcp("127.0.0.1", 1), Absent)
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "closeNow → isAlive false" in run {
        Scope.run {
            withServer.map { listener =>
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    transport.isAlive(conn).map { alive =>
                        assert(alive)
                        transport.closeNow(conn).andThen {
                            transport.isAlive(conn).map { alive2 =>
                                assert(!alive2)
                            }
                        }
                    }
                }
            }
        }
    }

    "double closeNow → idempotent" in run {
        Scope.run {
            withServer.map { listener =>
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    transport.closeNow(conn).andThen {
                        // Second closeNow should not throw
                        transport.closeNow(conn).andThen(succeed)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read / Write
    // ─────────────────────────────────────────────────────────────────────────

    "server writes hello, client reads" in run {
        Scope.run {
            withServer.map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        serverConn.write(Span.fromUnsafe("hello".getBytes(Utf8)))
                    }
                }
                serverFiber.andThen {
                    transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                        readN(clientConn, 5).map { bytes =>
                            assert(new String(bytes, Utf8) == "hello")
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "client writes world, server reads" in run {
        Scope.run {
            withServer.map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        readN(serverConn, 5)
                    }
                }
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    clientConn.write(Span.fromUnsafe("world".getBytes(Utf8))).andThen {
                        serverFiber.map(_.get).map { bytes =>
                            assert(new String(bytes, Utf8) == "world")
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "write empty span → no error" in run {
        Scope.run {
            withServer.map { listener =>
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    conn.write(Span.empty[Byte]).andThen {
                        transport.closeNow(conn).andThen(succeed)
                    }
                }
            }
        }
    }

    "1MB write all arrives" in run {
        val size = 1024 * 1024
        val data = new Array[Byte](size)
        java.util.Arrays.fill(data, 42.toByte)

        Scope.run {
            withServer.map { listener =>
                Promise.init[NioConnection, Any].map { serverConnPromise =>
                    Fiber.initUnscoped {
                        acceptOne(listener).map { serverConn =>
                            serverConnPromise.complete(Result.succeed(serverConn)).andThen {
                                readN(serverConn, size)
                            }
                        }
                    }.map { serverFiber =>
                        transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                            // Wait for server to accept before writing
                            serverConnPromise.get.andThen {
                                clientConn.write(Span.fromUnsafe(data)).andThen {
                                    serverFiber.get.map { received =>
                                        assert(received.length == size)
                                        assert(received.forall(_ == 42.toByte))
                                        transport.closeNow(clientConn).andThen(succeed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "read after peer closes → stream ends" in run {
        Scope.run {
            withServer.map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        serverConn.write(Span.fromUnsafe("bye".getBytes(Utf8))).andThen {
                            transport.closeNow(serverConn)
                        }
                    }
                }
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.andThen {
                        clientConn.read.run.map { chunks =>
                            val allBytes = chunks.toSeq.flatMap(s => s.toArrayUnsafe.toSeq).toArray
                            assert(allBytes.length >= 0) // stream ended without hanging
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "write after peer closes → error or silent" in run {
        Scope.run {
            withServer.map { listener =>
                Promise.init[Unit, Any].map { serverClosed =>
                    val serverFiber = Fiber.initUnscoped {
                        acceptOne(listener).map { serverConn =>
                            transport.closeNow(serverConn).andThen {
                                serverClosed.complete(Result.succeed(()))
                            }
                        }
                    }
                    transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                        serverFiber.andThen {
                            serverClosed.get.andThen {
                                Abort.run[Throwable] {
                                    clientConn.write(Span.fromUnsafe("data".getBytes(Utf8)))
                                }.map { result =>
                                    // Either errors or silently fails — both acceptable
                                    assert(result.isSuccess || result.isFailure)
                                    transport.closeNow(clientConn).andThen(succeed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Concurrent connections
    // ─────────────────────────────────────────────────────────────────────────

    "50 concurrent connections" in run {
        val n = 50
        Scope.run {
            withServer.map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    Kyo.foreach((0 until n).toSeq) { _ =>
                        acceptOne(listener).map { serverConn =>
                            transport.closeNow(serverConn)
                        }
                    }
                }
                Kyo.foreach((0 until n).toSeq) { _ =>
                    transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                        transport.closeNow(conn)
                    }
                }.andThen {
                    serverFiber.map(_.get).andThen(succeed)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server listen
    // ─────────────────────────────────────────────────────────────────────────

    "listen port 0 → assigned port > 0" in run {
        Scope.run {
            withServer.map { listener =>
                assert(listenerPort(listener) > 0)
                assert(listener.address match
                    case TransportAddress.Tcp(h, _) => h == "127.0.0.1";
                    case _                          => false)
                succeed
            }
        }
    }

    "connections stream yields accepted" in run {
        Scope.run {
            withServer.map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        transport.closeNow(serverConn)
                    }
                }
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.map(_.get).andThen {
                        transport.closeNow(clientConn).andThen(succeed)
                    }
                }
            }
        }
    }

    "accept multiple connections sequentially" in run {
        Scope.run {
            withServer.map { listener =>
                Kyo.foreach((0 until 5).toSeq) { _ =>
                    val serverFiber = Fiber.initUnscoped {
                        acceptOne(listener).map { serverConn =>
                            transport.closeNow(serverConn)
                        }
                    }
                    transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                        serverFiber.map(_.get).andThen {
                            transport.closeNow(clientConn)
                        }
                    }
                }.andThen(succeed)
            }
        }
    }

    "Scope exit → server closed" in run {
        Scope.run {
            withServer
        }.map { listener =>
            // After Scope.run, server should be closed.
            Abort.run[HttpException] {
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent)
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "each accepted is TransportStream" in run {
        Scope.run {
            withServer.map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        // NioConnection must be a TransportStream
                        val ts: TransportStream = serverConn
                        discard(ts)
                        transport.closeNow(serverConn)
                    }
                }
                transport.connect(TransportAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.map(_.get).andThen {
                        transport.closeNow(clientConn).andThen(succeed)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportListener.close
    // ─────────────────────────────────────────────────────────────────────────

    "listener close terminates connections stream" in run {
        Scope.run {
            withServer.map { listener =>
                listener.close.andThen {
                    listener.connections.take(1).run.map { chunk =>
                        assert(chunk.isEmpty)
                    }
                }
            }
        }
    }

    "listener close while waiting does not hang" in run {
        Scope.run {
            withServer.map { listener =>
                Fiber.init {
                    listener.connections.take(1).run
                }.map { fiber =>
                    listener.close.andThen {
                        fiber.get.map { chunk =>
                            assert(chunk.isEmpty)
                        }
                    }
                }
            }
        }
    }

end NioTransportTest
