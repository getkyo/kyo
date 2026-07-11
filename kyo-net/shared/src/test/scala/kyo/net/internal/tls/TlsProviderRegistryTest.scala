package kyo.net.internal.tls

import kyo.*
import kyo.net.NetTlsConfig
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

    private case class StubProvider(name: String, priority: Int, available: Boolean)

    private def select(registered: Chunk[StubProvider], forcedProp: String): StubProvider =
        IoBackend.select[StubProvider](registered, _.name, _.priority, _.available, forcedProp)

    // A minimal TlsProvider registry entry (same shape as StubProvider, but an actual TlsProvider so it can flow through selectFor). selectFor
    // reads only name/priority/isAvailable, so this carries no faked behavior; it is a data entry for the pure selection logic.
    private case class StubTlsProvider(name: String, priority: Int, avail: Boolean) extends TlsProvider:
        def isAvailable(using AllowUnsafe): Boolean = avail

    private def selectFor(registered: Chunk[StubTlsProvider], config: NetTlsConfig): StubTlsProvider =
        TlsProvider.selectFor[StubTlsProvider](registered, config)

    private def withProp[A](prop: String, value: String)(body: => A): A =
        val previous = Option(java.lang.System.getProperty(prop))
        java.lang.System.setProperty(prop, value)
        try body
        finally previous match
                case Some(v) => discard(java.lang.System.setProperty(prop, v))
                case None    => discard(java.lang.System.clearProperty(prop))
        end try
    end withProp

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
        val list = Chunk(StubTlsProvider("boringssl", 30, true), StubTlsProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig.default).name == "boringssl")
    }

    "selectFor honors an available pin over the higher-priority provider" in {
        val list = Chunk(StubTlsProvider("boringssl", 30, true), StubTlsProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig(tlsProvider = Present("jdk"))).name == "jdk")
    }

    "selectFor fails closed when the pinned provider is registered but unavailable (never substitutes)" in {
        val list = Chunk(StubTlsProvider("boringssl", 30, false), StubTlsProvider("jdk", 10, true))
        val ex   = intercept[Closed](selectFor(list, NetTlsConfig(tlsProvider = Present("boringssl"))))
        assert(ex.getMessage.contains("boringssl"), s"message must name the pinned provider, got ${ex.getMessage}")
        assert(ex.getMessage.contains("not available"), s"message must state the unavailable reason, got ${ex.getMessage}")
    }

    "selectFor fails closed when the pinned provider is not registered (never substitutes)" in {
        val list = Chunk(StubTlsProvider("jdk", 10, true))
        val ex   = intercept[Closed](selectFor(list, NetTlsConfig(tlsProvider = Present("boringssl"))))
        assert(ex.getMessage.contains("boringssl"), s"message must name the pinned provider, got ${ex.getMessage}")
        assert(ex.getMessage.contains("not supported"), s"message must state the unsupported reason, got ${ex.getMessage}")
    }

end TlsProviderRegistryTest
