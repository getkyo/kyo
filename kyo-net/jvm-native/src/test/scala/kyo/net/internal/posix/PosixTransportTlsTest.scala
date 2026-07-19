package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.TlsTestCert

/** Connect-time TLS round-trip over the unified [[PosixTransport]] (`connect(tls)` / `listen(tls)`) on a real loopback pair, driven by the same
  * [[PosixTransport.driveHandshake]] loop the STARTTLS path uses (no separate OpenSSL handshake code path).
  *
  * The server `listen(tls)`s with the embedded test cert; the client `connect(tls)`s with `trustAll`. Each side completes its handshake before
  * the connection reaches the handler / caller, then a known plaintext message round-trips encrypted end to end and is asserted byte-equal after
  * decryption. The client also reports the server's RFC 5929 channel-binding hash, proving the engine is attached to the handle. The suite
  * cancels where there is no real poller or no staged TLS provider.
  */
class PosixTransportTlsTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.NetConfig.default

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

    /** Build a transport over a fresh real poller driver, run `body`, then close the transport and the driver. `transport.close()` tears the
      * accept loop down synchronously: with the poller (epoll/kqueue) the accept loop never parks in a blocking `accept`, it is
      * readiness-driven, so closing every listener deregisters the accept interest via `driver.cancel`, which inline-completes the parked
      * accept promise with `Closed` and runs the accept loop's exit branch (`IOPromise.flush`) before `transport.close()` returns.
      *
      * The driver's own poll-loop thread is a separate story: `driver.close()` only requests teardown (`submitEngineOp` + `triggerWake()`)
      * and returns immediately, without waiting for the poll-loop carrier to actually run it. Awaiting the driver's own exit fiber after
      * `close()` (rather than discarding it) makes the underlying thread provably gone before this computation completes, closing the window
      * where, under kyo-test's concurrent leaf scheduling, a not-yet-fully-torn-down driver from an earlier leaf could still be
      * alive when the next leaf's own transport starts.
      */
    private def withTransport[A](body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver     = PollerIoDriver.init()
        val transport  = TestTransports.forTesting(driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        val driverDone = driver.start()
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(transport.close()).andThen(Sync.defer(driver.close())).andThen(
                Abort.run(driverDone.safe.get).unit
            ).andThen(Abort.get(result))
        }
    end withTransport

    /** Read exactly `target` bytes from a connection's inbound channel, concatenated. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Bind a plain socket to an ephemeral loopback port, then close it, returning the now free (no-listener) port. No transport listener, so no
      * blocking-accept loop is parked here: connecting to this port must be refused.
      */
    private def deadPort()(using Frame, kyo.test.AssertScope): Int < Async =
        val sockets = Ffi.load[SocketBindings]
        val fd      = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sockets.bind(fd, a, l).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sockets.getsockname(fd, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            sockets.close(fd).safe.get.map(_ => port)
        }
    end deadPort

    "PosixTransport TLS" - {
        "connect(tls) + listen(tls) round-trips an encrypted message through an echo handler" in {
            assumeReady()
            withTransport { transport =>
                for
                    accepted <- Channel.init[Unit](1)
                    listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                        // TLS echo handler: the handshake already completed before this runs; echo each decrypted chunk back encrypted.
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    accepted.put(()).andThen {
                                        Loop.foreach {
                                            serverConn.inbound.safe.take.map { chunk =>
                                                serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                            }
                                        }
                                    }
                                }.unit
                            }
                        })
                    }.safe.get
                    client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                    message = "posix-tls-encrypted-roundtrip".getBytes("UTF-8")
                    _      <- client.outbound.safe.put(Span.fromUnsafe(message))
                    _      <- accepted.take
                    echoed <- collect(client, message.length)
                    certHash = client.serverCertificateHash
                yield
                    client.close()
                    assert(echoed.sameElements(message), s"TLS echo mismatch: got '${new String(echoed, "UTF-8")}'")
                    val hashBytes = certHash match
                        case Present(h) => h.toArray
                        case Absent     => fail("client never observed the server certificate hash")
                    assert(
                        hashBytes.sameElements(TlsTestCert.certGoldenSha256),
                        s"server cert channel-binding hash mismatch: ${hashBytes.toList}"
                    )
                end for
            }
        }

        "connect(tls) to a non-listening port fails Closed" in {
            assumeReady()
            withTransport { transport =>
                deadPort().map { port =>
                    Abort.run[NetException | Closed](transport.connectTls("127.0.0.1", port, clientTls).safe.get).map { outcome =>
                        assert(outcome.isFailure, s"expected Closed connecting TLS to dead port $port, got $outcome")
                    }
                }
            }
        }
    }

end PosixTransportTlsTest
