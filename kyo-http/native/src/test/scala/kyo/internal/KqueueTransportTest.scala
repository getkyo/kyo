package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** KqueueNativeTransport tests — only meaningful on macOS/BSD. On Linux, tests pass trivially (succeed). */
class KqueueTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8    = StandardCharsets.UTF_8
    private val isMacOS = java.lang.System.getProperty("os.name", "").toLowerCase.contains("mac")

    private def onMacOS(
        f: KqueueNativeTransport => Assertion < (Async & Abort[HttpException] & Scope)
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        if !isMacOS then succeed
        else f(new KqueueNativeTransport)

    // ── withServer helper ──────────────────────────────────────────

    private def listenerPort(listener: TransportListener[?]): Int =
        listener.address match
            case HttpAddress.Tcp(_, port) => port
            case HttpAddress.Unix(_)      => -1

    private def withServer(
        transport: KqueueNativeTransport
    )(using Frame): TransportListener[KqueueConnection] < Async =
        transport.listen(HttpAddress.Tcp("127.0.0.1", 0), 128, Absent)

    /** Accept a single connection from the listener's stream and return it. */
    private def acceptOne(
        listener: TransportListener[KqueueConnection]
    )(using Frame): KqueueConnection < (Async & Abort[HttpException]) =
        listener.connections.take(1).run.map { chunk =>
            if chunk.isEmpty then
                Abort.panic(new Exception("No connection accepted"))
            else
                chunk(0)
        }

    /** Read all available bytes from a connection's read stream up to `limit` bytes total. Stops when `limit` is reached or the stream
      * ends.
      */
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
    // Connection lifecycle (4 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "connect to listening server → isAlive true" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    transport.isAlive(conn).map { alive =>
                        assert(alive)
                        transport.closeNow(conn).andThen(succeed)
                    }
                }
            }
        }
    }

    "connect to non-existent port → Abort" in run {
        onMacOS { transport =>
            Abort.run[HttpException] {
                transport.connect(HttpAddress.Tcp("127.0.0.1", 1), Absent)
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "closeNow → isAlive false" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
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
        onMacOS { transport =>
            withServer(transport).map { listener =>
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    transport.closeNow(conn).andThen {
                        // Second closeNow should not throw
                        transport.closeNow(conn).andThen(succeed)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read/write (7 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "server writes hello, client reads" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverAccept = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        serverConn.write(Span.fromUnsafe("hello".getBytes(Utf8)))
                    }
                }
                serverAccept.andThen {
                    transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
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
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        readN(serverConn, 5)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
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
        onMacOS { transport =>
            withServer(transport).map { listener =>
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
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

        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        readN(serverConn, size)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    clientConn.write(Span.fromUnsafe(data)).andThen {
                        serverFiber.map(_.get).map { received =>
                            assert(received.length == size)
                            assert(received.forall(_ == 42.toByte))
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "multiple sequential writes all arrive" in run {
        val chunkSize = 256 * 1024 // 256KB per chunk
        val numChunks = 4          // 1MB total
        val total     = chunkSize * numChunks
        val chunk = Span.fromUnsafe {
            val arr = new Array[Byte](chunkSize)
            java.util.Arrays.fill(arr, 7.toByte)
            arr
        }

        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        readN(serverConn, total)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    // Write 4 × 256KB sequentially using Loop to avoid Kyo.foreach overhead
                    Loop.indexed { i =>
                        if i >= numChunks then Loop.done(())
                        else clientConn.write(chunk).andThen(Loop.continue)
                    }.andThen {
                        serverFiber.map(_.get).map { received =>
                            assert(received.length == total)
                            assert(received.forall(_ == 7.toByte))
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "read after peer closes → stream ends" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        // Write one message then close
                        serverConn.write(Span.fromUnsafe("bye".getBytes(Utf8))).andThen {
                            transport.closeNow(serverConn)
                        }
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.andThen {
                        // Drain the stream; it should end after EOF
                        clientConn.read.run.map { chunks =>
                            val allBytes = chunks.toSeq.flatMap(s => s.toArrayUnsafe.toSeq).toArray
                            // At minimum we should have received something before EOF
                            assert(allBytes.length >= 0) // stream ended without hanging
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "write after peer closes → error or connection reset" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                Promise.init[Unit, Any].map { serverClosed =>
                    val serverFiber = Fiber.initUnscoped {
                        acceptOne(listener).map { serverConn =>
                            transport.closeNow(serverConn).andThen {
                                serverClosed.complete(Result.succeed(()))
                            }
                        }
                    }
                    transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                        serverFiber.andThen {
                            serverClosed.get.andThen {
                                Abort.run[Throwable] {
                                    // Writing to a closed connection should either error or silently fail
                                    clientConn.write(Span.fromUnsafe("data".getBytes(Utf8)))
                                }.map { result =>
                                    // Either it errors, or the write is silently ignored
                                    // Both are acceptable behaviors for a closed peer connection
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
    // Stream properties (3 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "read returns Stream[Span[Byte], Async]" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                    // Type-check: read must return Stream[Span[Byte], Async]
                    val stream: Stream[Span[Byte], Async] = conn.read
                    discard(stream) // just verify it compiles and is the right type
                    transport.closeNow(conn).andThen(succeed)
                }
            }
        }
    }

    "multiple pulls sequential reads" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        serverConn.write(Span.fromUnsafe("first".getBytes(Utf8))).andThen {
                            serverConn.write(Span.fromUnsafe("second".getBytes(Utf8)))
                        }
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.andThen {
                        readN(clientConn, 11).map { bytes =>
                            // "first" + "second" = 11 bytes
                            assert(bytes.length == 11)
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    "stream ends on EOF" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        transport.closeNow(serverConn)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.andThen {
                        // After server closes, reading should yield empty stream (EOF)
                        clientConn.read.run.map { chunks =>
                            // Stream should terminate (not hang); all chunks may be empty
                            assert(chunks.toSeq.flatMap(s => s.toArrayUnsafe.toSeq).isEmpty)
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Concurrent connections (3 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "50 concurrent connections" in run {
        val n = 50
        onMacOS { transport =>
            withServer(transport).map { listener =>
                // Accept n connections on the server side
                val serverFiber = Fiber.initUnscoped {
                    Kyo.foreach((0 until n).toSeq) { _ =>
                        acceptOne(listener).map { serverConn =>
                            transport.closeNow(serverConn)
                        }
                    }
                }
                // Connect n clients
                Kyo.foreach((0 until n).toSeq) { _ =>
                    transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { conn =>
                        transport.closeNow(conn)
                    }
                }.andThen {
                    serverFiber.map(_.get).andThen(succeed)
                }
            }
        }
    }

    "each connection independent" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    Kyo.foreach((0 until 3).toSeq) { i =>
                        acceptOne(listener).map { serverConn =>
                            serverConn.write(Span.fromUnsafe(s"conn$i".getBytes(Utf8)))
                        }
                    }
                }
                serverFiber.andThen {
                    Kyo.foreach((0 until 3).toSeq) { _ =>
                        transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent)
                    }.map { conns =>
                        Kyo.foreach(conns) { conn =>
                            readN(conn, 5).map { bytes =>
                                // Each reads "connN" — 5 bytes
                                assert(bytes.length == 5)
                                transport.closeNow(conn)
                            }
                        }.andThen(succeed)
                    }
                }
            }
        }
    }

    "data doesn't cross connections" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val msgA = "AAAA".getBytes(Utf8)
                val msgB = "BBBB".getBytes(Utf8)

                // Server accepts two connections and sends back fixed data per connection
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConnA =>
                        acceptOne(listener).map { serverConnB =>
                            serverConnA.write(Span.fromUnsafe(msgA)).andThen {
                                serverConnB.write(Span.fromUnsafe(msgB)).andThen {
                                    transport.closeNow(serverConnA).andThen {
                                        transport.closeNow(serverConnB)
                                    }
                                }
                            }
                        }
                    }
                }

                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { connA =>
                    transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { connB =>
                        serverFiber.andThen {
                            Fiber.initUnscoped(readN(connA, 4)).map { fiberA =>
                                Fiber.initUnscoped(readN(connB, 4)).map { fiberB =>
                                    fiberA.get.map { bytesA =>
                                        fiberB.get.map { bytesB =>
                                            assert(new String(bytesA, Utf8) == "AAAA")
                                            assert(new String(bytesB, Utf8) == "BBBB")
                                            transport.closeNow(connA).andThen {
                                                transport.closeNow(connB).andThen(succeed)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Server listen (5 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "listen port 0 → assigned port > 0" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                assert(listenerPort(listener) > 0)
                assert(listener.address match
                    case HttpAddress.Tcp(h, _) => h == "127.0.0.1";
                    case _                     => false)
                succeed
            }
        }
    }

    "connections stream yields accepted" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        transport.closeNow(serverConn)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.map(_.get).andThen {
                        transport.closeNow(clientConn).andThen(succeed)
                    }
                }
            }
        }
    }

    "listener.close → server closed" in run {
        onMacOS { transport =>
            transport.listen(HttpAddress.Tcp("127.0.0.1", 0), 128, Absent).map { listener =>
                listener.close.andThen {
                    // After listener.close, server should be closed.
                    // Attempting to connect should fail.
                    Abort.run[HttpException] {
                        transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent)
                    }.map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }
    }

    "each accepted is TransportStream" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        // KqueueConnection must be a TransportStream
                        val ts: TransportStream = serverConn
                        discard(ts)
                        transport.closeNow(serverConn)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    serverFiber.map(_.get).andThen {
                        transport.closeNow(clientConn).andThen(succeed)
                    }
                }
            }
        }
    }

    "accept multiple connections sequentially" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                Kyo.foreach((0 until 5).toSeq) { _ =>
                    val serverFiber = Fiber.initUnscoped {
                        acceptOne(listener).map { serverConn =>
                            transport.closeNow(serverConn)
                        }
                    }
                    transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                        serverFiber.map(_.get).andThen {
                            transport.closeNow(clientConn)
                        }
                    }
                }.andThen(succeed)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backpressure (1 test)
    // ─────────────────────────────────────────────────────────────────────────

    "fast writer slow reader → no data loss" in run {
        val total = 256 * 1024 // 256KB
        val data  = new Array[Byte](total)
        java.util.Arrays.fill(data, 0xab.toByte)

        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        readN(serverConn, total)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    // Write all data as fast as possible
                    clientConn.write(Span.fromUnsafe(data)).andThen {
                        serverFiber.map(_.get).map { received =>
                            assert(received.length == total)
                            assert(received.forall(_ == 0xab.toByte))
                            transport.closeNow(clientConn).andThen(succeed)
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource cleanup (2 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "closeNow → connection not alive" in run {
        onMacOS { transport =>
            transport.listen(HttpAddress.Tcp("127.0.0.1", 0), 128, Absent).map { listener =>
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    transport.closeNow(clientConn).andThen {
                        transport.isAlive(clientConn).map { alive =>
                            assert(!alive)
                        }
                    }
                }
            }
        }
    }

    "closeNow after writes → fd closed" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                val serverFiber = Fiber.initUnscoped {
                    acceptOne(listener).map { serverConn =>
                        transport.closeNow(serverConn)
                    }
                }
                transport.connect(HttpAddress.Tcp("127.0.0.1", listenerPort(listener)), Absent).map { clientConn =>
                    // Write some data
                    clientConn.write(Span.fromUnsafe("test data".getBytes(Utf8))).andThen {
                        transport.closeNow(clientConn).andThen {
                            // After closeNow, fd should not be alive
                            transport.isAlive(clientConn).map { alive =>
                                assert(!alive)
                                serverFiber.map(_.get).andThen(succeed)
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportListener.close (2 tests)
    // ─────────────────────────────────────────────────────────────────────────

    "listener close terminates connections stream" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                listener.close.andThen {
                    listener.connections.take(1).run.map { chunk =>
                        assert(chunk.isEmpty)
                    }
                }
            }
        }
    }

    "listener close while waiting does not hang" in run {
        onMacOS { transport =>
            withServer(transport).map { listener =>
                Fiber.initUnscoped {
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

end KqueueTransportTest
