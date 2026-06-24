package kyo.net.internal.tls

import kyo.*
import kyo.net.Test
import kyo.net.internal.backend.IoBackend

/** The TLS registry flows through the SAME `IoBackend.select` as the I/O registry (one selection function for both). These tests drive
  * `select` over stub providers shaped like the real `TlsProvider` priorities (boringssl=30, openssl=20, jdk/node=10) to confirm the shared
  * selector picks the right provider and honors `-Dkyo.net.tls` identically.
  */
class TlsProviderRegistryTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private case class StubProvider(name: String, priority: Int, available: Boolean)

    private def select(registered: Chunk[StubProvider], forcedProp: String): StubProvider =
        IoBackend.select[StubProvider](registered, _.name, _.priority, _.available, forcedProp)

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

end TlsProviderRegistryTest
