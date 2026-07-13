package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test
import kyo.net.internal.tls.TlsProviderPlatform
import kyo.net.internal.tls.TlsTestCert

/** Connect-time TLS config-failure typing over the real [[PosixTransport]] backend (epoll/kqueue): the connect-path `buildEngine` catch
  * propagates a [[NetTlsConfigException]] AS-IS, never re-wrapping it in a [[kyo.net.NetTlsHandshakeException]] carrying an untyped `Closed`
  * the way the pre-fix catch used to.
  *
  * The real engine-construction decision for an empty reference identity differs by registered provider:
  * [[kyo.net.internal.tls.SslEngineProvider]] and the inline NIO path reject BEFORE the handshake starts, while the native
  * [[kyo.net.internal.tls.SslLibProvider]] providers (BoringSSL, system OpenSSL) instead bind an unmatchable identity and let the real
  * handshake reject it (so on Native, no registered provider throws pre-flight at all). Rather than depending on which concrete provider a
  * host happens to have staged, the client side of this scenario is driven through [[PosixTransport.testEngineFactory]], so the catch-site
  * behavior this phase changed is pinned deterministically on every platform; the server side still builds a real engine (any staged
  * provider), so the scenario stays a genuine loopback connect, not a fully synthetic one. The connect target is a real, resolvable address
  * (127.0.0.1): an empty host is deliberately NOT used here, since it exercises DNS resolution differently per platform (`getaddrinfo("")`
  * fails on Native while the JVM resolver treats it as loopback) through a layer this phase does not touch, unrelated to the TLS catch site
  * under test.
  *
  * The accept side of `listen` carries the same invariant in the other direction: a `buildEngine` failure while accepting one connection
  * must close only that connection's fd and never crash or wedge the shared accept loop, since the loop keeps serving every later
  * connection on the same listener. `testEngineFactory` is reused for that direction by throwing only for `isServer = true`, so a listener
  * whose certificate is otherwise valid stays reachable by a second, unaffected connection attempt right after the first one fails.
  */
class PosixTransportTlsConfigTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default

    private val serverTls = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val verifyingClientTls = NetTlsConfig(caCertPath = Present(TlsTestCert.certPath))

    private def tlsAvailable: Boolean =
        try
            discard(TlsProviderPlatform.engine(serverTls, "localhost", isServer = true))
            true
        catch case _: Throwable => false

    private def assumeReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport TLS needs epoll (Linux) or kqueue (macOS/BSD)")
        if !tlsAvailable then cancel("No TLS provider staged for this host")
    end assumeReady

    /** Mirrors [[PosixTransportTlsTest.withTransport]]: a fresh real poller driver + transport, closed after `body` completes. */
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

    "listen(tls) whose accept-side buildEngine throws closes only that connection and keeps the accept loop alive for the next one" in {
        assumeReady()
        withTransport { transport =>
            val serverEngineThrows = AtomicBoolean.Unsafe.init(true)
            transport.testEngineFactory = Present { (cfg, host, isServer) =>
                if isServer && serverEngineThrows.get() then
                    throw NetTlsConfigException("synthetic accept-side engine construction failure (test)")
                else TlsProviderPlatform.engine(cfg, host, isServer)
            }
            transport.listen("127.0.0.1", 0, 16, serverTls)(_ => ()).safe.get.map { listener =>
                Abort.run[NetException | Closed](transport.connect("127.0.0.1", listener.port, verifyingClientTls).safe.get).map {
                    firstOutcome =>
                        serverEngineThrows.set(false)
                        Abort.run[NetException | Closed](transport.connect("127.0.0.1", listener.port, verifyingClientTls).safe.get).map {
                            secondOutcome =>
                                listener.close()
                                assert(
                                    firstOutcome.isFailure,
                                    s"the first connection must fail, not hang, when the accept-side engine build throws, got $firstOutcome"
                                )
                                secondOutcome match
                                    case Result.Success(conn) =>
                                        discard(conn.close())
                                        succeed
                                    case other =>
                                        fail(
                                            s"the accept loop must still serve a second connection after the first connection's engine setup failed, got $other"
                                        )
                                end match
                        }
                }
            }
        }
    }

    "connect(tls) propagates a buildEngine NetTlsConfigException as-is, never re-wrapped as a handshake failure" in {
        assumeReady()
        withTransport { transport =>
            // The client role's engine build fails deterministically (the same condition and message SslEngineProvider/NioTransport reject
            // for); the server role still builds a real engine, so the accept side completes normally and only the client connect fails.
            transport.testEngineFactory = Present { (cfg, host, isServer) =>
                if !isServer then
                    throw NetTlsConfigException(
                        "verifying client has no reference identity: a hostname is required to verify the server certificate (set trustAll " +
                            "or hostnameVerification = false to opt out of name verification)"
                    )
                else TlsProviderPlatform.engine(cfg, host, isServer)
            }
            transport.listen("127.0.0.1", 0, 16, serverTls)(_ => ()).safe.get.map { listener =>
                Abort.run[NetException | Closed](transport.connect("127.0.0.1", listener.port, verifyingClientTls).safe.get).map {
                    outcome =>
                        listener.close()
                        outcome match
                            case Result.Failure(_: NetTlsConfigException) => succeed
                            case other                                    => fail(s"expected a NetTlsConfigException failure, got $other")
                }
            }
        }
    }

end PosixTransportTlsConfigTest
