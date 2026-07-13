package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.tls.TlsProviderPlatform
import kyo.net.internal.tls.TlsTestCert

// Lives in jvm-native/src/test: PosixTransport's TLS handshake path runs on JVM-posix and Native; JS uses the Node transport.

/** The pooled TLS handshake's read-ciphertext park point. Reusing one per-handshake IOPromise across the handshake's read parks is BLOCKED by
  * the `IOPromise.becomeAvailable` contract (it forbids reuse of a promise carrying a registered `onComplete` listener, which the handshake
  * helper promises use); the reuse would require restructuring the handshake CPS state machine into an IOPromise subclass. The fresh-per-park
  * IOPromise the handshake uses today is correct.
  *
  * This leaf exercises the interrupt + lifecycle property on the CURRENT handshake path: a full handshake completes correctly (round-trip echo),
  * and a handshake interrupted mid-flight surfaces a typed failure with clean teardown (no strand, no double-complete, no hang). Real loopback
  * sockets + a real PollerIoDriver + a real TLS provider; the suite cancels where any is absent.
  */
class PosixTransportTlsHandshakeReuseTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

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

    private def assumeTlsReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TLS handshake tests need epoll (Linux) or kqueue (macOS/BSD)")
        if !tlsAvailable then cancel("No TLS provider staged for this host")
    end assumeTlsReady

    private def withTransport[A](body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver    = PollerIoDriver.init(transportConfig)
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        discard(driver.start())
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(transport.close()).andThen(Sync.defer(driver.close())).andThen(Abort.get(result))
        }
    end withTransport

    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    "PosixTransport TLS handshake reuse" - {

        "handshakePromiseReuse" in {
            assumeTlsReady()
            withTransport { transport =>
                for
                    ready <- Channel.init[Unit](1)
                    listener <- transport.listen("127.0.0.1", 0, 16, serverTls) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    ready.put(()).andThen {
                                        // Echo inbound back until close: the handshake must complete before any app data flows.
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
                    // The handshake runs to completion over multiple read-ciphertext parks (the per-handshake promise path); the round-trip proves
                    // it completed correctly.
                    client <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
                    _      <- ready.take
                    msg = "handshake-reuse-roundtrip".getBytes("UTF-8")
                    _      <- client.outbound.safe.put(Span.fromUnsafe(msg))
                    echoed <- collect(client, msg.length)
                yield
                    client.close()
                    assert(echoed.sameElements(msg), s"TLS handshake round-trip mismatch: got '${new String(echoed, "UTF-8")}'")
            }
        }

        "a handshake interrupted mid-flight surfaces a typed failure with clean teardown (no strand)" in {
            assumeTlsReady()
            // Connect with TLS to a PLAINTEXT server that accepts the TCP connection but never speaks TLS: the client's handshake sends its
            // ClientHello and parks awaiting a ServerHello that never arrives, so the handshake provably cannot complete and an interrupt of the
            // connect fiber deterministically wins. The interrupt must surface as a typed abort with clean teardown, not a hang or double-complete.
            withTransport { transport =>
                for
                    accepted <- Channel.init[Unit](1)
                    // A plaintext listener (no serverTls) that accepts and then does nothing: it never produces a ServerHello.
                    listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed](accepted.put(())).unit
                            }
                        })
                    }.safe.get
                    // Run the TLS connect in a fiber so we can interrupt it while its handshake is parked awaiting the (never-coming) ServerHello.
                    fiber  <- Fiber.init(Abort.run[kyo.net.NetException](transport.connect("127.0.0.1", listener.port, clientTls).safe.get))
                    _      <- accepted.take // the server has accepted the TCP connection; the client handshake is now in flight / parked
                    done   <- fiber.interrupt
                    result <- fiber.getResult
                yield
                    // The interrupt is honored (it won the race with the never-completing handshake).
                    assert(done, "fiber.interrupt returned false: the parked handshake fiber was not interrupted")
                    // The fiber unwound to a terminal result (no strand / no hang): a Panic(interrupt) or a typed Failure, never a Success of a
                    // bogus connection. Reaching this assertion at all (fiber.await returned) proves no hang.
                    result match
                        case Result.Success(inner) =>
                            // The connect cannot legitimately succeed against a server that never completed the TLS handshake.
                            inner match
                                case Result.Failure(_) => succeed // typed failure surfaced cleanly
                                case Result.Success(_) => fail("a TLS handshake against a plaintext server must not complete successfully")
                                case Result.Panic(_)   => succeed // interrupt surfaced as a panic at the inner boundary
                            end match
                        case Result.Failure(_) => succeed // typed failure
                        case Result.Panic(_)   => succeed // the interrupt surfaced as a panic (the expected interrupt outcome)
                    end match
            }
        }
    }
end PosixTransportTlsHandshakeReuseTest
