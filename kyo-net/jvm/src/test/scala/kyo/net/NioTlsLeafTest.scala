package kyo.net

import kyo.*
import kyo.net.internal.NioHandle
import kyo.net.internal.NioTransport
import kyo.net.internal.tls.SslEngineProvider

/** D-006 table (GF-1): the NIO transport's TLS-provider-pin and verifying-client-no-identity checks surface as the typed leaves
  * [[NetTlsProviderUnavailableException]] and [[NetTlsConfigException]], never `IllegalStateException` / `Closed`.
  */
class NioTlsLeafTest extends Test:

    import AllowUnsafe.embrace.danger

    def mkTransport()(using Frame): NioTransport =
        NioTransport.init(
            channelCapacity = 8,
            readBufferSize = NioHandle.DefaultReadBufferSize,
            connectTimeout = Duration.Infinity,
            handshakeTimeout = Duration.Infinity
        )

    "JVM provider-pin to a non-jdk provider fails with NetTlsProviderUnavailableException" in {
        given Frame     = Frame.internal
        val transport   = mkTransport()
        val listenFiber = transport.listen("127.0.0.1", 0, 50)(_ => ())
        listenFiber.safe.get.map { listener =>
            val pinned       = NetTlsConfig(tlsProvider = Present("boringssl"))
            val connectFiber = transport.connect("127.0.0.1", listener.port, pinned)
            Abort.run[NetException](connectFiber.safe.get).map { result =>
                listener.close()
                transport.close()
                result match
                    case Result.Failure(e: NetTlsProviderUnavailableException) =>
                        assert(e.providerId == "boringssl", s"providerId must name the pinned provider, got ${e.providerId}")
                    case other => fail(s"expected Result.Failure(NetTlsProviderUnavailableException), got $other")
                end match
            }
        }
    }

    "JVM verifying-client with no reference identity fails with NetTlsConfigException" in {
        given Frame = Frame.internal
        val ex = intercept[NetTlsConfigException] {
            SslEngineProvider.createEngine(NetTlsConfig.default, hostname = "", isServer = false)
        }
        assert(
            ex.getMessage.contains("no reference identity"),
            s"the failure must carry the fixed in-file no-reference-identity message, got ${ex.getMessage}"
        )
    }

end NioTlsLeafTest
