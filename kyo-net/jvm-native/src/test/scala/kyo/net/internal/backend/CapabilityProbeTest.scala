package kyo.net.internal.backend

import kyo.*
import kyo.ffi.FfiLoadError
import kyo.net.NetBackendUnavailableException
import kyo.net.Test

/** The regression guard for the incident this whole consolidation exists to close, plus the rest of the FFI-failure mapping.
  *
  * A readiness backend whose libc gate passes on a host with NO bundled shim used to win selection and then die per connection, past the
  * point any fallback could engage. The probe now touches the bundled shim, but that alone is not enough: the generated binding resolves its
  * library in the COMPANION's static initializer, so the loader's `FfiLoadError.LibraryNotFound` never reaches a probe as itself. The JVM
  * wraps the first touch in `ExceptionInInitializerError(cause = ...)` and answers every touch after with a cause-less
  * `NoClassDefFoundError`. Without the unwrap the incident case classifies as an opaque `ProbeFailed` and reads like a bug in the probe
  * rather than a native that was never staged.
  *
  * Driven deterministically by handing the classification the exact throwable shapes a real load produces, rather than by unstaging a native
  * mid-run: the shapes are what the mapping is about, and a synthesized one is the same object the runtime would hand over.
  */
class CapabilityProbeTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val shimIds = Chunk("c", "kyonet_posix_uring")

    private def libraryNotFound = new FfiLoadError.LibraryNotFound(
        "kyonet_posix_uring",
        Chunk("resource path: /META-INF/native/linux-x86_64/libkyonet_posix_uring.so", "system library: kyonet_posix_uring"),
        null
    )

    "a LibraryNotFound raised from a binding's class initializer classifies as NotBundled, not ProbeFailed" in {
        val thrown = new ExceptionInInitializerError(libraryNotFound)
        CapabilityProbe.classify(thrown, shimIds) match
            case CapabilityOutcome.NotBundled(id, platform) =>
                assert(id == "kyonet_posix_uring", s"the missing library must be named, got $id")
                assert(platform == CapabilityProbe.platform, s"the platform tag must be this host's, got $platform")
            case other => fail(s"the class-initializer wrapper was not unwrapped: got $other")
        end match
    }

    "an unwrapped LibraryNotFound classifies identically (the shape Native raises, which does not wrap initializer failures)" in {
        CapabilityProbe.classify(libraryNotFound, shimIds) match
            case CapabilityOutcome.NotBundled(id, _) => assert(id == "kyonet_posix_uring")
            case other                               => fail(s"expected NotBundled, got $other")
    }

    "a cause-less NoClassDefFoundError, the already-poisoned repeat, still classifies as NotBundled against the declared native" in {
        CapabilityProbe.classify(new NoClassDefFoundError("kyo/net/internal/posix/PosixShimBindingsImpl$"), shimIds) match
            case CapabilityOutcome.NotBundled(id, _) =>
                assert(id == "kyonet_posix_uring", s"the candidate's own bundled native is declared last, got $id")
            case other => fail(s"expected NotBundled, got $other")
    }

    "an AbiMismatch from the class initializer classifies as VersionTooOld carrying both versions" in {
        // AbiMismatch(expected, actual): the binding wants 2.0 and the staged native reports 1.0.
        val thrown = new ExceptionInInitializerError(new FfiLoadError.AbiMismatch("2.0", "1.0"))
        CapabilityProbe.classify(thrown, shimIds) match
            case CapabilityOutcome.VersionTooOld(have, need) =>
                assert(have == "1.0", s"the version found must be reported as 'have', got $have")
                assert(need == "2.0", s"the version required must be reported as 'need', got $need")
            case other => fail(s"expected VersionTooOld, got $other")
        end match
    }

    "a runtime-level refusal classifies as UnsupportedOS, the benign not-applicable outcome" in {
        val thrown = new FfiLoadError.Unsupported("32-bit host")
        assert(CapabilityProbe.classify(thrown, shimIds) == CapabilityOutcome.UnsupportedOS)
    }

    "a missing generated impl stays in the unclassified bucket: it is a build problem, not a host degrade" in {
        val thrown = new FfiLoadError.ImplNotFound("kyo.net.internal.posix.PosixShimBindings", null)
        CapabilityProbe.classify(thrown, shimIds) match
            case CapabilityOutcome.ProbeFailed(cause) => assert(cause eq thrown)
            case other                                => fail(s"expected ProbeFailed, got $other")
    }

    "an unrelated throwable classifies as ProbeFailed carrying itself" in {
        val boom = new RuntimeException("the syscall blew up")
        CapabilityProbe.classify(boom, shimIds) match
            case CapabilityOutcome.ProbeFailed(cause) => assert(cause eq boom)
            case other                                => fail(s"expected ProbeFailed, got $other")
    }

    "run classifies what the probe body throws instead of letting it escape" in {
        val outcome = CapabilityProbe.run(shimIds)(throw new ExceptionInInitializerError(libraryNotFound))
        outcome match
            case CapabilityOutcome.NotBundled(id, _) => assert(id == "kyonet_posix_uring")
            case other                               => fail(s"expected NotBundled, got $other")
    }

    "the incident end to end: a backend whose bundled shim is absent demotes to the floor instead of winning selection" in {
        // The readiness backend's libc gate passes (its syscall exists on this host regardless of what was bundled) and the shim call then
        // raises the wrapped LibraryNotFound. The backend must lose selection HERE, so the floor builds, rather than reporting available and
        // failing at the first connection.
        val shimless = new CapabilityDescriptor:
            def name: String              = "epoll"
            def priority: Int             = 20
            def libraryIds: Chunk[String] = shimIds
            private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
                CapabilityProbe.run(libraryIds)(throw new ExceptionInInitializerError(libraryNotFound))
        val floor = new CapabilityDescriptor:
            def name: String                                               = "nio"
            def priority: Int                                              = 10
            def libraryIds: Chunk[String]                                  = Chunk.empty
            private[net] def doProbe(using AllowUnsafe): CapabilityOutcome = CapabilityOutcome.Available

        shimless.probe match
            case CapabilityOutcome.NotBundled(id, _) => assert(id == "kyonet_posix_uring")
            case other                               => fail(s"the shimless backend must probe NotBundled, got $other")

        val registered: Chunk[CapabilityDescriptor] = Chunk(shimless, floor)
        IoBackend.select[CapabilityDescriptor, NetBackendUnavailableException](
            registered,
            forced = Absent,
            onUnavailable = (name, report) => NetBackendUnavailableException(name, report.render)
        ) match
            case Result.Success(chosen) => assert(chosen.name == "nio", s"selection must demote to the floor, got ${chosen.name}")
            case other                  => fail(other.toString)
        end match
    }

    "the platform tag reads as <os>-<arch>" in {
        assert(CapabilityProbe.platform.contains("-"), s"expected an <os>-<arch> tag, got ${CapabilityProbe.platform}")
        assert(CapabilityProbe.platform.nonEmpty)
    }

end CapabilityProbeTest
