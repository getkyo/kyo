package kyo.net

import kyo.*
import scala.scalajs.js as sjs

/** Concurrent-connection / concurrent reader+writer coverage for the JS (Node.js) transport on the single event loop.
  *
  * The JVM/Native concurrent-echo guard ([[kyo.net.internal.posix.PosixTransportTlsConcurrentEchoTest]]) stresses the per-connection TLS
  * engine read-vs-write race over the posix driver's engine FIFO. That exact hazard does NOT apply to JS: Node owns TLS (the TLS engine and
  * the engine FIFO are never instantiated on JS; `NodeTlsProvider.createEngine` throws), and JS is single-threaded so there is no two-carrier
  * engine overlap to serialize. Porting the engine-FIFO test to JS would be vacuous.
  *
  * What IS a real JS concurrency hazard is many connections, each with a concurrent reader fiber and writer fiber, all multiplexed over ONE
  * libuv event loop and ONE [[kyo.net.internal.JsIoDriver]] (pool size 1 on JS). Every connection's read pump re-arms its socket via
  * `resume()` and its write pump drives `socket.write`, and Node interleaves the resulting `"data"`/`"drain"` callbacks cooperatively. A
  * correctness bug in that multiplexing (a misrouted read between handles, a lost re-arm, a dropped write) would corrupt the echo. This test
  * runs many such connections concurrently against a real in-process Node echo server and asserts every connection's response equals its
  * request byte for byte.
  *
  * Determinism: every step is gated on a channel/promise completion, never a sleep. The writer runs at most `window` frames ahead of the
  * reader via a permit channel (`Kyo.foreach` priming + `permits.take`/`permits.put`), so the per-connection read and write overlap on the
  * event loop for the whole run with bounded outstanding bytes, and the test resolves only when every frame has been echoed and verified. There
  * is no real network: server and clients are both in-process Node loopback sockets, so timing is driven entirely by the cooperative event
  * loop, not wall-clock.
  *
  * Both plaintext and TLS are covered (a Node TLS server is feasible in this harness, as `JsTransportTlsTest` shows), so the
  * concurrency is exercised on both the raw `net` path and the Node-owned `tls` path.
  */
class JsConcurrentEchoTest extends Test:

    import AllowUnsafe.embrace.danger

    // Self-signed certificate for CN=localhost with SAN=DNS:localhost,IP:127.0.0.1 (the canonical TlsTestCertShared fixture).
    private val localhostCertPem: String = TlsTestCertShared.certPem

    private val localhostKeyPem: String = TlsTestCertShared.keyPem

    private val fs       = sjs.Dynamic.global.require("fs")
    private val os       = sjs.Dynamic.global.require("os")
    private val nodePath = sjs.Dynamic.global.require("path")

    private def writeTempPem(content: String, name: String): String =
        val dir  = os.tmpdir().asInstanceOf[String]
        val path = nodePath.join(dir, name).asInstanceOf[String]
        fs.writeFileSync(path, content)
        path
    end writeTempPem

    private lazy val localhostCertPath: String = writeTempPem(localhostCertPem, "kyo-js-conc-cert.pem")
    private lazy val localhostKeyPath: String  = writeTempPem(localhostKeyPem, "kyo-js-conc-key.pem")

    private def serverTls: NetTlsConfig =
        NetTlsConfig(certChainPath = Present(localhostCertPath), privateKeyPath = Present(localhostKeyPath))
    private def clientTls: NetTlsConfig = NetTlsConfig(trustAll = true)

    // One libuv event loop multiplexes all of these. Sizes are modest because JS software TLS through Node is slower than the posix path and
    // the whole suite shares the 15s test budget; the multiplexing hazard is exercised by concurrency (many overlapping reader/writer fibers
    // on one loop), not by raw volume.
    private val connections = 12
    private val rounds      = 20
    private val window      = 4
    private val sizes       = Array(1, 7, 64, 200, 1500)

    private def requestBytes(connId: Int, round: Int): Array[Byte] =
        val len = sizes(round % sizes.length)
        Array.tabulate[Byte](len)(i => ((connId * 31 + round * 7 + i) & 0xff).toByte)
    end requestBytes

    /** Fill `buf` to at least `target` bytes by taking more inbound chunks, starting from already-buffered bytes. */
    private def fillTo(conn: Connection, buf: Array[Byte], target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(buf) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Drive one connection: a writer fiber streams request frames and concurrently a reader fiber reads each response frame and checks it
      * byte for byte against the matching request. The permit channel bounds the writer to `window` frames ahead of the reader so read and
      * write overlap on the event loop with bounded outstanding bytes.
      */
    private def driveConnection(conn: Connection, connId: Int, permits: Channel[Unit])(using Frame): Boolean < (Async & Abort[Closed]) =
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
                    else permits.put(()).andThen(Loop.continue(remain))
                }
        }
        Async.zip(write, read).map((_, ok) => ok)
    end driveConnection

    private def runEcho(tls: Boolean)(using Frame): Boolean < (Async & Abort[NetException | Closed] & Scope) =
        val transport = NetPlatform.transport
        val serverHandler: Connection => Unit = serverConn =>
            // Echo loop using the Unsafe API: take a span from inbound, offer it back to outbound, repeat. Each connection's echo runs as its
            // own onComplete chain on the single event loop, interleaved with every other connection's by Node.
            def loopEcho(): Unit =
                serverConn.inbound.takeFiber().onComplete {
                    case Result.Success(bytes) =>
                        discard(serverConn.outbound.offer(bytes.asInstanceOf[Span[Byte]]))
                        loopEcho()
                    case _ => () // connection closed or error
                }
            loopEcho()
        val listenFiber =
            if tls then transport.listen("127.0.0.1", 0, 128, serverTls)(serverHandler)
            else transport.listen("127.0.0.1", 0, 128)(serverHandler)
        for
            listener <- listenFiber.safe.get
            port = listener.port
            results <- Async.fillIndexed(connections, connections) { connId =>
                val connectFiber =
                    if tls then transport.connect("127.0.0.1", port, clientTls)
                    else transport.connect("127.0.0.1", port)
                (connectFiber.safe.get.map { conn =>
                    Channel.init[Unit](window).map { permits =>
                        Kyo.foreach(0 until window)(_ => permits.put(())).andThen {
                            driveConnection(conn, connId, permits).map { ok =>
                                conn.close()
                                ok
                            }
                        }
                    }
                }): Boolean < (Async & Abort[NetException | Closed] & Scope)
            }
        yield
            listener.close()
            results.forall(identity)
        end for
    end runEcho

    "JS concurrent echo over one event loop" - {

        // The plaintext concurrent-echo guarantee is backend-agnostic and now lives in the shared TransportConcurrentEchoTest (which runs on JS
        // too via NetPlatform.transport). This file keeps only the TLS variant: Node owns the TLS layer, so the JS TLS concurrent-echo path
        // exercises Node's own tls multiplexing over the single event loop, which the shared test cannot cover (the shared TLS-concurrent variant
        // is held out because the Native TLS-engine-under-contention path is the open task #232).
        "many TLS connections each echo concurrently and every response matches its request" in {
            runEcho(tls = true).map(ok =>
                assert(ok, "a TLS connection's echo did not match byte for byte under concurrency on the single event loop")
            )
        }
    }

end JsConcurrentEchoTest
