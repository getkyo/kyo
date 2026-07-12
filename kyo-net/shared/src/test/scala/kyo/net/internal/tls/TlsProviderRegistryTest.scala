package kyo.net.internal.tls

import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.Test
import kyo.net.internal.backend.IoBackend

/** The TLS registry flows through the SAME `IoBackend.select` as the I/O registry (one selection function for both). These tests drive
  * `select` over stub providers shaped like the real `TlsProvider` priorities (boringssl=30, openssl=20, jdk/node=10) to confirm the shared
  * selector picks the right provider and honors `-Dkyo.net.tls` identically, and drive `TlsProvider.selectFor` to confirm a
  * [[NetTlsConfig.tlsProvider]] pin is honored or fails closed (never silently substituting a different TLS implementation).
  */
class TlsProviderRegistryTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    // A minimal TlsProvider registry entry: name/priority/isAvailable and nothing else. Both selection paths under test read only those three
    // fields, so it carries no faked behavior; it is a data entry for the pure selection logic. It backs BOTH the generic IoBackend.select (via
    // the field accessors) and TlsProvider.selectFor (as an actual TlsProvider), so the two suites share one stub rather than two near-copies.
    private case class StubProvider(name: String, priority: Int, available: Boolean) extends TlsProvider:
        def isAvailable(using AllowUnsafe): Boolean = available

    private def select(registered: Chunk[StubProvider], forcedProp: String): Result[NetException, StubProvider] =
        IoBackend.select[StubProvider](registered, _.name, _.priority, _.available, forcedProp)

    private def selectFor(registered: Chunk[StubProvider], config: NetTlsConfig): Result[NetException, StubProvider] =
        TlsProvider.selectFor[StubProvider](registered, config)

    "TLS selection picks the highest-priority available provider via the shared select" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(select(list, "kyo.net.test.tls.empty").getOrThrow.name == "boringssl")
    }

    "forced -Dkyo.net.tls overrides priority through the same select" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        withProp("kyo.net.test.tls.forced", "jdk") {
            assert(select(list, "kyo.net.test.tls.forced").getOrThrow.name == "jdk")
        }
    }

    "TLS selection falls through an unavailable primary to the floor" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("openssl", 20, true), StubProvider("jdk", 10, true))
        assert(select(list, "kyo.net.test.tls.empty").getOrThrow.name == "openssl")
    }

    "forced-but-unavailable TLS provider fails NetBackendUnavailableException, never throws" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("jdk", 10, true))
        withProp("kyo.net.test.tls.forced2", "boringssl") {
            select(list, "kyo.net.test.tls.forced2") match
                case Result.Failure(e) => assert(e.getMessage.contains("boringssl"))
                case other             => fail(s"expected a Failure, got $other")
        }
    }

    "selectFor with no pin defers to the shared select (highest-priority available provider)" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig.default).getOrThrow.name == "boringssl")
    }

    "selectFor honors an available pin over the higher-priority provider" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig(tlsProvider = Present("jdk"))).getOrThrow.name == "jdk")
    }

    "selectFor fails NetTlsProviderUnavailableException when the pinned provider is registered but unavailable (never substitutes)" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("jdk", 10, true))
        selectFor(list, NetTlsConfig(tlsProvider = Present("boringssl"))) match
            case Result.Failure(e: NetTlsProviderUnavailableException) =>
                assert(e.providerId == "boringssl")
                assert(e.getMessage.contains("boringssl"), s"message must name the pinned provider, got ${e.getMessage}")
            case other => fail(s"expected NetTlsProviderUnavailableException, got $other")
        end match
    }

    "selectFor fails NetTlsProviderUnavailableException when the pinned provider is not registered (never substitutes)" in {
        val list = Chunk(StubProvider("jdk", 10, true))
        selectFor(list, NetTlsConfig(tlsProvider = Present("boringssl"))) match
            case Result.Failure(e: NetTlsProviderUnavailableException) =>
                assert(e.providerId == "boringssl")
                assert(e.getMessage.contains("boringssl"), s"message must name the pinned provider, got ${e.getMessage}")
            case other => fail(s"expected NetTlsProviderUnavailableException, got $other")
        end match
    }

end TlsProviderRegistryTest
