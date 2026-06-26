package kyo.net.internal

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Connection
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.tls.TlsTestCert

/** NIO per-connection engine ownership: behavioral tests confirming the gate works through real TLS I/O.
  *
  * The NIO driver is the only backend where the selector-carrier read path (dispatchReadTls) and the caller-carrier write path (writeTls) can
  * both touch the same connection's JDK SSLEngine from different threads. The engineGate on NioHandle serializes them.
  *
  * SSLEngine is stateful and not thread-safe. Concurrent calls from the selector thread (during a read-arm completion) and the caller thread
  * (during a write) corrupt the TLS session. The corruption is observable: the decrypted frame no longer matches the original plaintext, or
  * the connection fails with a fatal SSL alert. Tests that exercise real NioTransport TLS connections with concurrent readers and writers
  * would fail if the gate were absent or mis-scoped.
  *
  * Every behavioral leaf sets up a real NioTransport TLS echo server, drives one or more connections with concurrent writers and readers, and
  * verifies echoed frames byte for byte. The close leaf calls the real NioHandle.close and verifies the gate is released.
  */
class NioEngineOwnershipTest extends Test:

    import AllowUnsafe.embrace.danger

    private val serverTlsConfig: NetTlsConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientTlsConfig: NetTlsConfig = NetTlsConfig(trustAll = true)

    private def mkTransport()(using Frame): NioTransport =
        NioTransport.init(
            channelCapacity = 8,
            readBufferSize = NioHandle.DefaultReadBufferSize,
            connectTimeout = Duration.Infinity,
            handshakeTimeout = Duration.Infinity
        )

    /** Server echo fiber for TLS connections. Suspends on each inbound take via the Async effect, so the scheduler (not the NIO selector
      * carrier) resumes the fiber when data arrives. This breaks the synchronous callback chain that would otherwise run writeTls directly on
      * the selector thread while dispatchReadTls still holds the engineGate, causing the write's spinAcquire to deadlock.
      */
    private def startEchoFiber(conn: Connection)(using Frame): Unit =
        discard(Fiber.Unsafe.init {
            Abort.run[Closed] {
                Loop.foreach {
                    conn.inbound.safe.take.map { chunk =>
                        conn.outbound.safe.put(chunk).andThen(Loop.continue)
                    }
                }
            }.unit
        })

    /** Accumulate inbound bytes until at least `target` bytes are available, starting from `acc`. Handles TCP fragmentation across multiple
      * inbound spans.
      */
    private def fillTo(conn: Connection, acc: Array[Byte], target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(acc) { buf =>
            if buf.length >= target then Loop.done(buf)
            else conn.inbound.safe.take.map(chunk => Loop.continue(buf ++ chunk.toArray))
        }

    /** Drive one echo connection with a concurrent writer and reader for `rounds` frames. Returns true when every frame matched. */
    private def driveConnection(conn: Connection, connId: Int, rounds: Int, window: Int)(using
        Frame
    ): Boolean < (Async & Abort[Closed] & Scope) =
        val sizes = Array(1, 64, 200, 1500)
        def reqBytes(round: Int): Array[Byte] =
            val len = sizes(round % sizes.length)
            Array.tabulate[Byte](len)(i => ((connId * 31 + round * 7 + i) & 0xff).toByte)
        Channel.init[Unit](window).map { permits =>
            Kyo.foreach(0 until window)(_ => permits.put(())).andThen {
                val write = Loop.indexed[Unit, Async & Abort[Closed]] { round =>
                    if round >= rounds then Loop.done(())
                    else permits.take.andThen(conn.outbound.safe.put(Span.fromUnsafe(reqBytes(round)))).andThen(Loop.continue)
                }
                val read = Loop.indexed[Array[Byte], Boolean, Async & Abort[Closed]](Array.emptyByteArray) { (round, leftover) =>
                    if round >= rounds then Loop.done(true)
                    else
                        val expected = reqBytes(round)
                        fillTo(conn, leftover, expected.length).map { buf =>
                            val frame  = buf.take(expected.length)
                            val remain = buf.drop(expected.length)
                            permits.put(()).andThen(
                                if java.util.Arrays.equals(frame, expected) then Loop.continue(remain)
                                else Loop.done(false)
                            )
                        }
                }
                Async.zip(write, read).map((_, ok) => ok)
            }
        }
    end driveConnection

    private def openPair(): (SocketChannel, SocketChannel) =
        val ss = ServerSocketChannel.open()
        ss.bind(new InetSocketAddress("127.0.0.1", 0))
        val port = ss.socket().getLocalPort
        val c    = SocketChannel.open()
        c.configureBlocking(false)
        c.connect(new InetSocketAddress("127.0.0.1", port))
        ss.configureBlocking(true)
        val s = ss.accept()
        c.finishConnect()
        ss.close()
        (c, s)
    end openPair

    "NioHandle.engineGate" - {

        // After a TLS handshake completes, the gate must be released (false) so the first post-handshake
        // write and read can proceed. If the gate remained held after the handshake, writeTls would spin
        // forever waiting to acquire it. The test verifies the gate releases by confirming the first
        // write+read round-trip completes with correct bytes.
        "TLS handshake completes and gate is released: first write+read round-trip arrives intact" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listen("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                transport.connect("127.0.0.1", port, clientTlsConfig).safe.get.map { conn =>
                    // Single round-trip: write then read back. If gate is stuck post-handshake, writeTls
                    // spins and the take never resolves.
                    val msg = Span.fromUnsafe("gate-released-check".getBytes("UTF-8"))
                    conn.outbound.safe.put(msg).andThen {
                        conn.inbound.safe.take.map { received =>
                            conn.close()
                            listener.close()
                            transport.close()
                            assert(
                                received.toArray sameElements msg.toArray,
                                "round-trip failed: gate not released after handshake"
                            )
                        }
                    }
                }
            }
        }

        // Two back-to-back writes to the same NIO TLS connection both arrive in order. The second write
        // can only start after the gate is released from the first (writeTls uses spinAcquire, not CAS).
        // If the gate were never released from the first write, the second write spins forever. The test
        // verifies both frames arrive by receiving both echoes and checking bytes.
        "two back-to-back TLS writes to same connection both delivered in order" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listen("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                transport.connect("127.0.0.1", port, clientTlsConfig).safe.get.map { conn =>
                    val msg1 = Span.fromUnsafe("frame-1".getBytes("UTF-8"))
                    val msg2 = Span.fromUnsafe("frame-2-longer".getBytes("UTF-8"))
                    conn.outbound.safe.put(msg1).andThen(conn.outbound.safe.put(msg2)).andThen {
                        fillTo(conn, Array.emptyByteArray, msg1.size + msg2.size).map { received =>
                            conn.close()
                            listener.close()
                            transport.close()
                            val expected = msg1.toArray ++ msg2.toArray
                            assert(
                                received.take(expected.length) sameElements expected,
                                "one or both frames did not arrive correctly (gate release failure would cause this)"
                            )
                        }
                    }
                }
            }
        }

        // Many sequential request/response cycles on a single NIO TLS connection all produce correct
        // data. Across N cycles, dispatchReadTls and writeTls alternate ownership of the gate; the
        // gate must be released after each operation so the next can proceed. A mis-scoped gate (e.g.
        // one acquired but never released on a normal return path) would deadlock after the first cycle.
        "sequential NIO TLS write/read cycles all correct across many rounds" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listen("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                transport.connect("127.0.0.1", port, clientTlsConfig).safe.get.map { conn =>
                    driveConnection(conn, connId = 0, rounds = 60, window = 1).map { ok =>
                        conn.close()
                        listener.close()
                        transport.close()
                        assert(ok, "a NIO TLS echo frame did not match after sequential cycles (gate leak would cause this)")
                    }
                }
            }
        }

        // Real NioHandle.close on a TLS handle must acquire and release the engine gate.
        // After close, the gate is false (released by the finally block in NioHandle.close).
        // This test exercises the real close path, not a manual gate simulation.
        "NioHandle.close acquires and releases gate on TLS handle" in {
            val (client, server) = openPair()
            try
                val engine = javax.net.ssl.SSLContext.getDefault.createSSLEngine()
                engine.setUseClientMode(true)
                val handle = NioHandle.initTls(client, 4096, engine)

                assert(!handle.engineGate.get(), "gate must start unowned")
                NioHandle.close(handle)
                assert(!handle.engineGate.get(), "gate must be released after close (finally block ran)")
                assert(!handle.channel.isOpen, "channel must be closed")
            finally
                try client.close()
                catch case _: Exception => ()
                server.close()
            end try
        }

        // Two independent NIO TLS connections carry concurrent traffic. The gate is per-connection
        // (one engineGate AtomicBoolean per NioHandle), so holding the gate on one handle has no
        // effect on the other. If the gate were shared, one connection holding it while reading would
        // block the other connection's writes. Each connection sends frames concurrently and receives
        // them; both must complete with correct data.
        "gate is per-connection: two independent NIO TLS connections complete concurrently" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listen("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                Async.zip(
                    transport.connect("127.0.0.1", port, clientTlsConfig).safe.get,
                    transport.connect("127.0.0.1", port, clientTlsConfig).safe.get
                ).map { (conn0, conn1) =>
                    Async.zip(
                        driveConnection(conn0, connId = 0, rounds = 20, window = 4),
                        driveConnection(conn1, connId = 1, rounds = 20, window = 4)
                    ).map { (ok0, ok1) =>
                        conn0.close()
                        conn1.close()
                        listener.close()
                        transport.close()
                        assert(ok0, "connection 0: frame mismatch (gate shared across connections would cause this)")
                        assert(ok1, "connection 1: frame mismatch (gate shared across connections would cause this)")
                    }
                }
            }
        }

        // After a connection closes, a new connection to the same server gets a fresh gate (starts
        // unowned). If a leaked gate from the closed connection somehow affected the new one, the new
        // connection's first write would spin forever. The test closes one connection and opens a new
        // one, verifying the new connection completes its first round-trip.
        "new connection after close gets a fresh unowned gate: first round-trip works" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listen("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                transport.connect("127.0.0.1", port, clientTlsConfig).safe.get.map { conn1 =>
                    // Use and close the first connection.
                    val msg1 = Span.fromUnsafe("first-conn".getBytes("UTF-8"))
                    conn1.outbound.safe.put(msg1).andThen(conn1.inbound.safe.take).map { _ =>
                        conn1.close()
                        // Open a second connection; its gate must be fresh (unowned).
                        transport.connect("127.0.0.1", port, clientTlsConfig).safe.get.map { conn2 =>
                            val msg2 = Span.fromUnsafe("second-conn".getBytes("UTF-8"))
                            conn2.outbound.safe.put(msg2).andThen {
                                conn2.inbound.safe.take.map { received =>
                                    conn2.close()
                                    listener.close()
                                    transport.close()
                                    assert(
                                        received.toArray sameElements msg2.toArray,
                                        "second connection's round-trip failed (gate leak from first connection would cause this)"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end NioEngineOwnershipTest
