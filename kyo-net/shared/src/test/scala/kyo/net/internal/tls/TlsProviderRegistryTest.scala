package kyo.net.internal.tls

import kyo.*
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

    private def select(registered: Chunk[StubProvider], forcedProp: String): StubProvider =
        IoBackend.select[StubProvider](registered, _.name, _.priority, _.available, forcedProp)

    private def selectFor(registered: Chunk[StubProvider], config: NetTlsConfig): StubProvider =
        TlsProvider.selectFor[StubProvider](registered, config)

    "TLS selection picks the highest-priority available provider via the shared select" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(select(list, "kyo.net.test.tls.empty").name == "boringssl")
    }

    "forced -Dkyo.net.tls overrides priority through the same select" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        withProp("kyo.net.test.tls.forced", "jdk") {
            assert(select(list, "kyo.net.test.tls.forced").name == "jdk")
        }
    }

    "TLS selection falls through an unavailable primary to the floor" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("openssl", 20, true), StubProvider("jdk", 10, true))
        assert(select(list, "kyo.net.test.tls.empty").name == "openssl")
    }

    "forced-but-unavailable TLS provider aborts Closed" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("jdk", 10, true))
        withProp("kyo.net.test.tls.forced2", "boringssl") {
            val ex = intercept[Closed](select(list, "kyo.net.test.tls.forced2"))
            assert(ex.getMessage.contains("boringssl"))
        }
    }

    "selectFor with no pin defers to the shared select (highest-priority available provider)" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig.default).name == "boringssl")
    }

    "selectFor with no pin and no available provider wraps the shared select's Closed into NetTlsProviderUnavailableException" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("jdk", 10, false))
        val ex   = intercept[NetTlsProviderUnavailableException](selectFor(list, NetTlsConfig.default))
        assert(ex.provider == "<default>", s"the unpinned wrap must name the default fallback, got ${ex.provider}")
    }

    "selectFor honors an available pin over the higher-priority provider" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig(tlsProvider = Present("jdk"))).name == "jdk")
    }

    "selectFor fails closed when the pinned provider is registered but unavailable (never substitutes)" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("jdk", 10, true))
        val ex   = intercept[NetTlsProviderUnavailableException](selectFor(list, NetTlsConfig(tlsProvider = Present("boringssl"))))
        assert(ex.provider == "boringssl", s"exception must carry the pinned provider id, got ${ex.provider}")
        assert(ex.getMessage.contains("not available"), s"message must state the unavailable reason, got ${ex.getMessage}")
    }

    "selectFor fails closed when the pinned provider is not registered (never substitutes)" in {
        val list = Chunk(StubProvider("jdk", 10, true))
        val ex   = intercept[NetTlsProviderUnavailableException](selectFor(list, NetTlsConfig(tlsProvider = Present("boringssl"))))
        assert(ex.provider == "boringssl", s"exception must carry the pinned provider id, got ${ex.provider}")
        assert(ex.getMessage.contains("not available"), s"message must state the unavailable reason, got ${ex.getMessage}")
    }

end TlsProviderRegistryTest
