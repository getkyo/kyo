package kyo.net.internal.posix

import kyo.*
import kyo.net.Connection
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.tls.TlsProviderPlatform
import kyo.net.internal.tls.TlsTestCert

/** Load regression for the per-connection TLS engine read-vs-write race (the `cipherIn>0 plainOut=0` deadlock).
  *
  * The bug: a TLS engine is stateful and not thread-safe, yet the posix driver touches one connection's engine from two independent fibers,
  * the read pump (decrypt path: feedCiphertext / readPlain) and the write pump (encrypt path: writePlain / drainCiphertext). The corruption
  * surfaced as the engine producing zero plaintext from a full valid ciphertext record, the connection deadlocking, and under load the whole
  * driver freezing. The fix serializes each connection's engine ops so read, write, and handshake never run concurrently on the same engine,
  * while distinct connections stay independent.
  *
  * This drives a TLS echo server with many concurrent clients, each reusing one keep-alive connection for many request/response frames, with
  * a writer fiber and a reader fiber per connection so read and write overlap on the same engine the whole run. Each frame asserts the echoed
  * bytes equal the request byte for byte, so a corrupted record fails the test.
  *
  * Test naming: PosixTransport (source kyo-net/shared/src/main/scala/kyo/net/internal/posix/PosixTransport.scala) is a prefix of the test
  * name.
  */
class PosixTransportTlsConcurrentEchoTest extends Test:

    import AllowUnsafe.embrace.danger

    private val serverTls = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientTls = NetTlsConfig(trustAll = true)

    private def tlsAvailable: Boolean =
        try
            discard(TlsProviderPlatform.engine(clientTls, "localhost", isServer = false))
            true
        catch case _: Throwable => false

    private def assumeReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TLS needs epoll (Linux) or kqueue (macOS/BSD)")
        if !tlsAvailable then cancel("No TLS provider staged for this host")
    end assumeReady

    /** Stack-safe server echo: take one inbound span, offer it to outbound, repeat. Each iteration's next step is started as a fresh scheduler
      * task via Fiber.Unsafe.init, so the loop never self-recurses inline. takeFiber().onComplete fires SYNCHRONOUSLY when a span is already
      * buffered in inbound (the common case under load, where the read pump fills inbound faster than the echo drains it), so a direct
      * self-recursive call would grow the carrier stack once per buffered span and overflow it under sustained load. Re-entering through a fresh
      * Fiber.Unsafe.init runs each iteration on a fresh stack frame, bounding the stack to one iteration regardless of how much is buffered.
      */
    private def echoLoop(serverConn: Connection)(using Frame): Unit =
        serverConn.inbound.takeFiber().onComplete {
            case Result.Success(bytes) =>
                discard(serverConn.outbound.offer(bytes.asInstanceOf[Span[Byte]]))
                discard(Fiber.Unsafe.init(echoLoop(serverConn)))
            case _ => () // connection closed or error
        }
    end echoLoop

    // Each connection runs two independent fibers, a writer and a reader, against the SAME connection (and so the same engine), looping
    // `rounds` request/response frames. The writer runs up to `window` frames ahead of the reader (a bounded write-ahead permit pool), so
    // the server side decrypts an inbound frame (unwrap, read pump) while it re-encrypts a prior frame (wrap, write pump) on the same
    // engine continuously.
    private val connections = 48
    private val rounds      = 120
    private val window      = 12
    private val sizes       = Array(1, 7, 64, 200, 1500, 8192, 17000)

    private def requestBytes(connId: Int, round: Int): Array[Byte] =
        val len = sizes(round % sizes.length)
        val arr = new Array[Byte](len)
        var i   = 0
        while i < len do
            arr(i) = ((connId * 31 + round * 7 + i) & 0xff).toByte
            i += 1
        end while
        arr
    end requestBytes

    /** Fill `buf` to at least `target` bytes by taking more inbound chunks, starting from already-buffered bytes. */
    private def fillTo(conn: Connection, buf: Array[Byte], target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(buf) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Drive one connection: a writer fiber streams request frames and concurrently a reader fiber reads each response frame and checks it
      * byte for byte against the matching request.
      *
      * When `certExpected` is `Present(exp)`, the reader also reads `serverCertificateHash` after each assembled frame and verifies the
      * returned hash equals `exp`. This interleaves the cert read with the live decrypt (read pump) and races the concurrent encrypt (write
      * pump) on the same engine, paced naturally by the echo, one check per frame, for the whole connection lifetime. No extra fibers are
      * spawned. On a native engine, an unguarded cert read here touches the same ssl object the FIFO engine ops mutate; the fix under test
      * serializes those ops so the hash read sees stable state. The native-engine use-after-free is what BoringSSL pins; JDK is incidentally
      * safe because the JDK provider copies cert state at handshake time.
      *
      * When `certExpected` is `Absent` the behavior is identical to the pre-fold version: no cert read, no extra assertions.
      */
    private def driveConnection(
        conn: Connection,
        connId: Int,
        permits: Channel[Unit],
        certExpected: Maybe[Array[Byte]] = Absent
    )(using Frame): Boolean < (Async & Abort[Closed]) =
        val write = Loop.indexed[Unit, Async & Abort[Closed]] { round =>
            if round >= rounds then Loop.done(())
            else permits.take.andThen(conn.outbound.safe.put(Span.fromUnsafe(requestBytes(connId, round)))).andThen(Loop.continue)
        }
        val read = Loop.indexed[Array[Byte], Boolean, Async & Abort[Closed]](Array.emptyByteArray) { (round, leftover) =>
            if round >= rounds then Loop.done(true)
            else
                val expected = requestBytes(connId, round)
                fillTo(conn, leftover, expected.length).map { acc =>
                    val frame  = acc.take(expected.length)
                    val remain = acc.drop(expected.length)
                    if !java.util.Arrays.equals(frame, expected) then permits.put(()).andThen(Loop.done(false))
                    else
                        certExpected match
                            case Absent => permits.put(()).andThen(Loop.continue(remain))
                            case Present(exp) =>
                                Sync.defer(conn.serverCertificateHash).map {
                                    case Present(h) if java.util.Arrays.equals(h.toArray, exp) =>
                                        permits.put(()).andThen(Loop.continue(remain))
                                    case _ =>
                                        // Hash was Absent (connection closed mid-read) or mismatched: cert state is inconsistent.
                                        permits.put(()).andThen(Loop.done(false))
                                }
                    end if
                }
        }
        Async.zip(write, read).map((_, ok) => ok)
    end driveConnection

    "PosixTransport TLS concurrent echo" - {
        "serverCertificateHash is consistent while open under concurrent reads and writes, and Absent after close" in {
            assumeReady()
            val transport = NetPlatform.transport
            for
                listener <- transport.listen("127.0.0.1", 0, 128, serverTls) { serverConn =>
                    echoLoop(serverConn)
                }.safe.get
                port = listener.port
                // Many connections so the per-frame cert read races real, ongoing FIFO read/write engine ops on a freshly negotiated
                // native engine. The cert read is folded into the reader loop: after each echoed frame is assembled and verified, the
                // reader also calls serverCertificateHash and checks it equals the hash captured at handshake. This interleaves the
                // cert read with live decrypt and races the concurrent encrypt (writer fiber) on the same engine, paced naturally by
                // the echo. No extra fibers are spawned, so no scheduler starvation from a separate hammering probe loop.
                results <- Async.fillIndexed(connections, connections) { connId =>
                    transport.connect("127.0.0.1", port, clientTls).safe.get.map { conn =>
                        val expected = conn.serverCertificateHash match
                            case Present(h) => h.toArray
                            case Absent     => Array.emptyByteArray
                        Channel.init[Unit](window).map { permits =>
                            Kyo.foreach(0 until window)(_ => permits.put(())).andThen {
                                // The cert read races live decrypt + concurrent encrypt on the same engine, per frame, for the whole
                                // connection lifetime. On a native engine an unguarded cert read here would touch the same ssl the
                                // FIFO engine ops mutate; the fix under test serializes those ops.
                                driveConnection(conn, connId, permits, Present(expected)).map { ok =>
                                    conn.close()
                                    // After close the cache gate must report Absent (no engine touch): a Present here is the racy
                                    // failure the closed-returns-Absent guard pins down, surfaced under live concurrency.
                                    val closedAbsent = conn.serverCertificateHash.isEmpty
                                    expected.nonEmpty && ok && closedAbsent
                                }
                            }
                        }
                    }
                }
            yield
                listener.close()
                assert(
                    results.forall(identity),
                    s"a TLS connection's cert hash was inconsistent under concurrency, or non-Absent after close, results=${results.toList}"
                )
            end for
        }

        "many connections each streaming concurrent reads and writes never deadlock the engine" in {
            assumeReady()
            val transport = NetPlatform.transport
            for
                listener <- transport.listen("127.0.0.1", 0, 128, serverTls) { serverConn =>
                    echoLoop(serverConn)
                }.safe.get
                port = listener.port
                results <- Async.fillIndexed(connections, connections) { connId =>
                    transport.connect("127.0.0.1", port, clientTls).safe.get.map { conn =>
                        Channel.init[Unit](window).map { permits =>
                            Kyo.foreach(0 until window)(_ => permits.put(())).andThen {
                                driveConnection(conn, connId, permits, Absent).map { ok =>
                                    conn.close()
                                    ok
                                }
                            }
                        }
                    }
                }
            yield
                listener.close()
                assert(
                    results.forall(identity),
                    s"a TLS echo frame returned bytes that did not match the request (engine corruption), results=${results.toList}"
                )
            end for
        }
    }

end PosixTransportTlsConcurrentEchoTest
