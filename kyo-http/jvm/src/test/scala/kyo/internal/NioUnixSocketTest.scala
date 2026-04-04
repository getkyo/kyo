package kyo.internal

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*

class NioUnixSocketTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8      = StandardCharsets.UTF_8
    private val transport = new NioTransport

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Create a temp directory with a Unix socket server, run the test, then clean up. */
    private def withUnixServer[A](f: (String, ServerSocketChannel) => A < (Async & Abort[HttpException]))(using
        Frame
    ): A < (Async & Abort[HttpException]) =
        Sync.defer {
            val tmpDir   = Files.createTempDirectory("kyo-unix-test")
            val sockPath = tmpDir.resolve("test.sock").toString
            val server   = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(sockPath))
            Abort.recover[HttpException] { cause =>
                server.close()
                Files.deleteIfExists(java.nio.file.Path.of(sockPath))
                Files.deleteIfExists(tmpDir)
                Abort.fail(cause)
            } {
                f(sockPath, server).map { result =>
                    server.close()
                    Files.deleteIfExists(java.nio.file.Path.of(sockPath))
                    Files.deleteIfExists(tmpDir)
                    result
                }
            }
        }

    /** Accept a single connection on the server side (blocking). */
    private def serverAccept(server: ServerSocketChannel): SocketChannel =
        server.configureBlocking(true)
        server.accept()

    /** Read exactly n bytes from a blocking SocketChannel. */
    private def serverRead(ch: SocketChannel, n: Int): Array[Byte] =
        ch.configureBlocking(true)
        val buf = ByteBuffer.allocate(n)
        while buf.hasRemaining do
            val read = ch.read(buf)
            if read < 0 then throw new Exception("EOF before reading all bytes")
        buf.flip()
        val arr = new Array[Byte](n)
        buf.get(arr)
        arr
    end serverRead

    /** Write bytes to a blocking SocketChannel. */
    private def serverWrite(ch: SocketChannel, data: Array[Byte]): Unit =
        ch.configureBlocking(true)
        val buf = ByteBuffer.wrap(data)
        while buf.hasRemaining do
            discard(ch.write(buf))
    end serverWrite

    /** Read at least `limit` bytes from a NioConnection's read stream. */
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

    "connect to Unix socket server" in run {
        withUnixServer { (sockPath, server) =>
            val serverFiber = Fiber.initUnscoped {
                Sync.defer {
                    val accepted = serverAccept(server)
                    accepted.close()
                }
            }
            serverFiber.andThen {
                transport.connect(TransportAddress.Unix(sockPath), Absent).map { conn =>
                    transport.isAlive(conn).map { alive =>
                        assert(alive)
                        transport.closeNow(conn).andThen(succeed)
                    }
                }
            }
        }
    }

    "round-trip data over Unix socket" in run {
        withUnixServer { (sockPath, server) =>
            Sync.defer {
                // Run the server side on a plain Java thread to avoid blocking Kyo worker threads
                val future = new java.util.concurrent.CompletableFuture[String]()
                val runnable: Runnable = () =>
                    try
                        val accepted = serverAccept(server)
                        val received = serverRead(accepted, 5)
                        serverWrite(accepted, "world".getBytes(Utf8))
                        accepted.close()
                        discard(future.complete(new String(received, Utf8)))
                    catch
                        case e: Exception =>
                            discard(future.completeExceptionally(e))
                val serverThread = new Thread(runnable)
                serverThread.setDaemon(true)
                serverThread.start()
                transport.connect(TransportAddress.Unix(sockPath), Absent).map { conn =>
                    conn.write(Span.fromUnsafe("hello".getBytes(Utf8))).andThen {
                        readN(conn, 5).map { bytes =>
                            val response = new String(bytes, Utf8)
                            assert(response == "world")
                            Sync.defer {
                                val serverReceived = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                                assert(serverReceived == "hello")
                            }.andThen {
                                transport.closeNow(conn).andThen(succeed)
                            }
                        }
                    }
                }
            }
        }
    }

    "isAlive on Unix socket" in run {
        withUnixServer { (sockPath, server) =>
            val serverFiber = Fiber.initUnscoped {
                Sync.defer {
                    val accepted = serverAccept(server)
                    accepted.close()
                }
            }
            serverFiber.andThen {
                transport.connect(TransportAddress.Unix(sockPath), Absent).map { conn =>
                    transport.isAlive(conn).map { alive =>
                        assert(alive)
                        transport.closeNow(conn).andThen {
                            transport.isAlive(conn).map { alive2 =>
                                assert(!alive2)
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

    "non-existent path fails" in run {
        Abort.run[HttpException] {
            transport.connect(TransportAddress.Unix("/tmp/nonexistent_kyo_test.sock"), Absent)
        }.map { result =>
            assert(result.isFailure)
            assert(result.failure.exists(_.isInstanceOf[HttpConnectException]))
            succeed
        }
    }

    "path too long fails" in run {
        val longPath = "/tmp/" + "a" * 200 + ".sock"
        Abort.run[HttpException] {
            transport.connect(TransportAddress.Unix(longPath), Absent)
        }.map { result =>
            assert(result.isFailure)
            assert(result.failure.exists(_.isInstanceOf[HttpConnectException]))
            succeed
        }
    }

end NioUnixSocketTest
