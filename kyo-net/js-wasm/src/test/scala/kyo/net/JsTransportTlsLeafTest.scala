package kyo.net

import kyo.*
import kyo.net.internal.JsTransport

/** GF-1 REGRESSION guards: the JS transport's `connect(tls)` provider-pin and verifying-client-no-identity checks used to emit the WRONG
  * leaf, `NetTlsHandshakeException`, for a config-truthfulness / TLS-config failure that has nothing to do with an actual handshake. Both
  * checks run BEFORE any Node socket operation, so no real server is needed to exercise them.
  */
class JsTransportTlsLeafTest extends Test:

    import AllowUnsafe.embrace.danger

    def mkTransport()(using Frame): JsTransport =
        JsTransport.init(
            poolSize = 1,
            channelCapacity = 8,
            connectTimeout = Duration.Infinity,
            handshakeTimeout = Duration.Infinity
        )

    "JS provider-pin wrong-leaf REGRESSION guard: non-node provider fails with NetTlsProviderUnavailableException (was NetTlsHandshakeException)" in {
        given Frame      = Frame.internal
        val transport    = mkTransport()
        val pinned       = NetTlsConfig(tlsProvider = Present("boringssl"))
        val connectFiber = transport.connect("127.0.0.1", 1, pinned)
        Abort.run[NetException](connectFiber.safe.get).map { result =>
            transport.close()
            result match
                case Result.Failure(e: NetTlsProviderUnavailableException) =>
                    assert(e.providerId == "boringssl", s"providerId must name the pinned provider, got ${e.providerId}")
                case other => fail(s"expected Result.Failure(NetTlsProviderUnavailableException), got $other")
            end match
        }
    }

    "JS verifying-client wrong-leaf REGRESSION guard: no-identity fails with NetTlsConfigException (was NetTlsHandshakeException)" in {
        given Frame      = Frame.internal
        val transport    = mkTransport()
        val connectFiber = transport.connect("", 1, NetTlsConfig.default)
        Abort.run[NetException](connectFiber.safe.get).map { result =>
            transport.close()
            result match
                case Result.Failure(_: NetTlsConfigException) => succeed
                case other                                    => fail(s"expected Result.Failure(NetTlsConfigException), got $other")
        }
    }

end JsTransportTlsLeafTest
