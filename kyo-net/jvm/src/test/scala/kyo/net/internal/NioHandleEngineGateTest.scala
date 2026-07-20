package kyo.net.internal

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*
import kyo.net.Connection
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsTestCert

/** NIO engine-gate exclusion: concurrent read and write on the same TLS connection never corrupt data.
  *
  * For one NIO TLS connection, the selector-carrier read path (dispatchReadTls) and the caller-carrier write path (writeTls) must
  * never hold the per-connection engineGate simultaneously. The NioHandle.close path acquires and releases the gate.
  *
  * The NIO gate (engineGate: AtomicBoolean on NioHandle) serializes all SSLEngine calls for one connection. SSLEngine is stateful and not
  * thread-safe: concurrent calls from the selector thread (dispatchReadTls) and the caller thread (writeTls) corrupt the TLS state, producing
  * garbled records or a fatal SSL alert. A missing or broken gate makes this observable as byte-level corruption or a connection error.
  *
  * The concurrent-I/O leaves set up real NioTransport TLS connections and run a writer fiber and a reader fiber simultaneously on the same
  * connection. Every echoed frame is verified byte for byte against the request. Corruption from an unguarded concurrent SSLEngine call fails
  * the frame-equality assertion. The tests also verify per-connection gate independence (two connections must not interfere with each other).
  *
  * The close leaf exercises the real NioHandle.close path on a TLS handle and verifies the gate is false after close.
  */
class NioHandleEngineGateTest extends Test:

    import AllowUnsafe.embrace.danger

    private val serverTlsConfig: NetTlsConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientTlsConfig: NetTlsConfig = NetTlsConfig(trustAll = true)

    private def mkTransport()(using Frame): NioTransport =
        NioTransport.init()

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

    /** Accumulate inbound bytes until at least `target` bytes are available, starting from `acc`. Loops over multiple inbound spans to handle
      * TCP fragmentation.
      */
    private def fillTo(conn: Connection, acc: Array[Byte], target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(acc) { buf =>
            if buf.length >= target then Loop.done(buf)
            else conn.inbound.safe.take.map(chunk => Loop.continue(buf ++ chunk.toArray))
        }

    /** Drive one echo round-trip loop: a writer fiber and a reader fiber run concurrently on `conn` for `rounds` frames. Returns true when all
      * frames matched byte for byte, false on any mismatch.
      */
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

    "NIO engine gate exclusion under concurrent read, write, and close" - {

        // A writer fiber and a reader fiber run concurrently on the same NIO TLS connection. The
        // writer calls the writeTls path (caller-carrier) while the selector thread calls the
        // dispatchReadTls path on the same connection. Both paths call SSLEngine methods. The gate
        // serializes them so only one carrier touches the SSLEngine at a time. Without the gate,
        // concurrent SSLEngine calls corrupt the TLS state and the frame-equality assertion fails.
        "concurrent read and write on same NIO TLS connection: all echoed frames arrive intact" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listenTls("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                transport.connectTls("127.0.0.1", port, clientTlsConfig).safe.get.map { conn =>
                    driveConnection(conn, connId = 0, rounds = 40, window = 4).map { ok =>
                        conn.close()
                        listener.close()
                        assert(ok, "an echoed frame did not match its request byte for byte (engine gate violation would cause this)")
                    }
                }
            }
        }

        // Two NIO TLS connections carry concurrent traffic simultaneously. The gate is per-connection
        // (engineGate is a field on NioHandle, one instance per handle), so the two connections are
        // independent: each can hold its own gate while the other holds its own. If the gate were
        // shared across connections, holding it on one connection would block the other's engine calls.
        // Both connections echo concurrently; each frame from both is verified byte for byte.
        "two NIO TLS connections operate concurrently: both echo intact, neither blocks the other" in {
            given Frame   = Frame.internal
            val transport = mkTransport()
            transport.listenTls("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                Async.zip(
                    transport.connectTls("127.0.0.1", port, clientTlsConfig).safe.get,
                    transport.connectTls("127.0.0.1", port, clientTlsConfig).safe.get
                ).map { (conn0, conn1) =>
                    Async.zip(
                        driveConnection(conn0, connId = 0, rounds = 20, window = 4),
                        driveConnection(conn1, connId = 1, rounds = 20, window = 4)
                    ).map { (ok0, ok1) =>
                        conn0.close()
                        conn1.close()
                        listener.close()
                        assert(ok0, "connection 0: echoed frame did not match request (gate interference or engine corruption)")
                        assert(ok1, "connection 1: echoed frame did not match request (gate interference or engine corruption)")
                    }
                }
            }
        }

        // Many NIO TLS connections simultaneously, each with a concurrent writer and reader. This
        // stresses the selector thread (dispatching many handles) against many concurrent writeTls
        // calls. A broken gate (missing CAS, wrong acquire scope) corrupts records and fails the
        // per-frame assertion. The test exercises the alternating read/write pattern from many
        // callers, which is the same load the selector loop sees in production.
        "many NIO TLS connections with concurrent read/write: all frames intact under load" in {
            given Frame     = Frame.internal
            val transport   = mkTransport()
            val connections = 8
            transport.listenTls("127.0.0.1", 0, 50, serverTlsConfig)(startEchoFiber).safe.get.map { listener =>
                val port = listener.port
                Async.fillIndexed(connections, connections) { connId =>
                    transport.connectTls("127.0.0.1", port, clientTlsConfig).safe.get.map { conn =>
                        driveConnection(conn, connId, rounds = 20, window = 4).map { ok =>
                            conn.close()
                            ok
                        }
                    }
                }.map { results =>
                    listener.close()
                    assert(results.forall(identity), "a NIO TLS echo frame did not match its request byte for byte (engine gate violation)")
                }
            }
        }

        // Real close path: NioHandle.close acquires the engineGate before calling
        // engine.closeOutbound + wrap (best-effort close_notify), then releases it in a finally block
        // so the gate is never permanently locked even when closeOutbound or wrap throws.
        //
        // This test calls the REAL NioHandle.close on a TLS-enabled handle so the gate path is
        // actually exercised (unlike a simulation). After close, the gate must be false (the finally
        // block released it). A missing or broken finally would leave the gate permanently true and
        // any subsequent acquisition attempt would spin forever.
        "gate acquired and released by real NioHandle.close on TLS handle" in {
            val (c, s) = openPair()
            try
                val engine = javax.net.ssl.SSLContext.getDefault.createSSLEngine()
                engine.setUseClientMode(true)
                val handle = NioHandle.initTls(c, 4096, engine)

                assert(!handle.engineGate.get(), "gate must start false (unowned) before close")

                NioHandle.close(handle)

                assert(!handle.engineGate.get(), "gate must be false after close (released by finally)")
                assert(!handle.channel.isOpen, "channel must be closed after NioHandle.close")
            finally
                try c.close()
                catch case _: Exception => ()
                s.close()
            end try
        }
    }

end NioHandleEngineGateTest
