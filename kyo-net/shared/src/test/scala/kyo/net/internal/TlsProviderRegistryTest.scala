package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.Test
import kyo.net.internal.backend.CapabilityOutcome
import kyo.net.internal.backend.IoBackend
import kyo.net.internal.transport.RecordingLog

/** The TLS registry flows through the SAME `IoBackend.select` as the I/O registry (one selection function for both). These tests drive
  * `select` over stub providers shaped like the real `TlsProvider` priorities (boringssl=30, openssl=20, jdk/node=10) to confirm the shared
  * selector picks the right provider and honors `-Dkyo.net.tls` identically, and drive `TlsProvider.selectFor` to confirm a
  * [[NetTlsConfig.tlsProvider]] pin is honored or fails closed (never silently substituting a different TLS implementation) and that the
  * pinned path, which resolves without going through `select`, still contributes its own diagnosis.
  */
class TlsProviderRegistryTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    // A minimal TlsProvider registry entry: the shared capability identity and nothing else. Both selection paths under test read only that
    // identity, so it carries no faked behavior; it is a data entry for the pure selection logic. It backs BOTH the generic IoBackend.select
    // and TlsProvider.selectFor, so the two suites share one stub rather than two near-copies.
    final private class StubProvider(val name: String, val priority: Int, outcome: CapabilityOutcome) extends TlsProvider:
        def libraryIds: Chunk[String]                                  = Chunk.empty
        private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = outcome
    end StubProvider

    private object StubProvider:
        def apply(name: String, priority: Int, available: Boolean): StubProvider =
            val outcome = if available then CapabilityOutcome.Available else CapabilityOutcome.Unavailable("stub probe reported no")
            new StubProvider(name, priority, outcome)
        end apply

        def apply(name: String, priority: Int, outcome: CapabilityOutcome): StubProvider = new StubProvider(name, priority, outcome)
    end StubProvider

    private def select(registered: Chunk[StubProvider], forced: Maybe[String]): Result[NetTlsProviderUnavailableException, StubProvider] =
        IoBackend.select[StubProvider, NetTlsProviderUnavailableException](
            registered,
            forced = forced,
            onUnavailable = (name, report) => NetTlsProviderUnavailableException(name.getOrElse("<default>"), report.render)
        )

    private def selectFor(registered: Chunk[StubProvider], config: NetTlsConfig): StubProvider =
        TlsProvider.selectFor[StubProvider](registered, config)

    "TLS selection picks the highest-priority available provider via the shared select" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        select(list, Absent) match
            case Result.Success(p) => assert(p.name == "boringssl")
            case other             => fail(other.toString)
    }

    "forced -Dkyo.net.tls overrides priority through the same select" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        select(list, Present("jdk")) match
            case Result.Success(p) => assert(p.name == "jdk")
            case other             => fail(other.toString)
    }

    "TLS selection falls through an unavailable primary to the floor" in {
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("openssl", 20, true), StubProvider("jdk", 10, true))
        select(list, Absent) match
            case Result.Success(p) => assert(p.name == "openssl")
            case other             => fail(other.toString)
    }

    "forced-but-unavailable TLS provider surfaces NetTlsProviderUnavailableException carrying the forced name" in {
        // The forced name reaches the failure by name, the way the I/O side has always reported it. The platform wrap used to discard it and
        // report "<default>" for every unforced AND forced failure alike, so an operator who forced a provider learned only that some default
        // was missing. "<default>" is now reserved for the genuinely unforced case, which has no name to report.
        val list = Chunk(StubProvider("boringssl", 30, false), StubProvider("jdk", 10, true))
        select(list, Present("boringssl")) match
            case Result.Failure(e: NetTlsProviderUnavailableException) =>
                assert(e.provider == "boringssl", s"the forced name must be carried, got ${e.provider}")
                assert(e.getMessage.contains("boringssl"))
            case other => fail(other.toString)
        end match
    }

    "selectFor with no pin defers to the shared select (highest-priority available provider)" in {
        val list = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        assert(selectFor(list, NetTlsConfig.default).name == "boringssl")
    }

    "selectFor with no pin and no available provider surfaces NetTlsProviderUnavailableException for the default fallback" in {
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

    "an unregistered pin reports 'not registered', a registered-but-unavailable pin reports what its probe found" in {
        // The pinned path bypasses `select` entirely, so it used to produce no diagnosis at all. Both fail-closed legs now carry a report,
        // and the two read differently: an operator who typed the wrong id and an operator whose library is not staged are looking for
        // different fixes.
        val unregistered = intercept[NetTlsProviderUnavailableException] {
            selectFor(Chunk(StubProvider("jdk", 10, true)), NetTlsConfig(tlsProvider = Present("boringssl")))
        }
        assert(unregistered.getMessage.contains("boringssl not registered"), s"got ${unregistered.getMessage}")
        assert(unregistered.getMessage.contains("jdk[10]"), s"the report must still list the registry, got ${unregistered.getMessage}")

        val registeredButMissing = intercept[NetTlsProviderUnavailableException] {
            selectFor(
                Chunk(
                    StubProvider("boringssl", 30, CapabilityOutcome.NotBundled("kyonet_boringssl", "linux-x86_64")),
                    StubProvider("jdk", 10, true)
                ),
                NetTlsConfig(tlsProvider = Present("boringssl"))
            )
        }
        assert(
            !registeredButMissing.getMessage.contains("not registered"),
            s"a registered pin must not read as unregistered, got ${registeredButMissing.getMessage}"
        )
        assert(registeredButMissing.getMessage.contains("kyonet_boringssl"), s"got ${registeredButMissing.getMessage}")
        assert(registeredButMissing.getMessage.contains("linux-x86_64"), s"got ${registeredButMissing.getMessage}")
    }

    "an honored pin logs its own report line, so the pinned path is as diagnosable as the gradient" in {
        val recordingLog = new RecordingLog(Log.live.unsafe)
        val list         = Chunk(StubProvider("boringssl", 30, true), StubProvider("jdk", 10, true))
        val chosen       = TlsProvider.selectFor[StubProvider](list, NetTlsConfig(tlsProvider = Present("jdk")), recordingLog)
        assert(chosen.name == "jdk")
        assert(recordingLog.infoCount.get() == 1, s"expected one report line for the pinned path, got ${recordingLog.infoCount.get()}")
        assert(recordingLog.warnCount.get() == 0, s"an honored pin is not a warning, got ${recordingLog.warnCount.get()}")
    }

end TlsProviderRegistryTest
