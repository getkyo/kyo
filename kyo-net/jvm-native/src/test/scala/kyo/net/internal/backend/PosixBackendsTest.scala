package kyo.net.internal.backend

import kyo.*
import kyo.net.Test
import kyo.net.internal.posix.PosixConstants

/** The real posix backends' identity and probes on the running host.
  *
  * The property under test is honesty: a readiness backend must not report itself available on the strength of a libc syscall that exists
  * everywhere while the bundled shim its data path calls is missing. So the probe exercises the shim, and whatever it finds it must say in a
  * classified outcome, never as an unexplained failure. `CapabilityProbeTest` drives the shim-absent case deterministically; these leaves
  * assert the same code against the host as it actually is.
  */
class PosixBackendsTest extends Test:

    import AllowUnsafe.embrace.danger

    "the readiness backends declare libc first and the bundled shim last" in {
        // The order is what the classification falls back on when a poisoned binding class no longer carries its cause: the candidate's own
        // bundled native is the last declared id.
        assert(EpollBackend.libraryIds.last == "kyonet_posix_uring", s"got ${EpollBackend.libraryIds}")
        assert(KqueueBackend.libraryIds.last == "kyonet_posix_uring", s"got ${KqueueBackend.libraryIds}")
        assert(EpollBackend.libraryIds.size == 2, s"the libc gate must be declared too, got ${EpollBackend.libraryIds}")
        assert(IoUringBackend.libraryIds == Chunk("kyonet_posix_uring"), s"got ${IoUringBackend.libraryIds}")
    }

    "a readiness backend off its own OS reports UnsupportedOS, the benign not-applicable outcome" in {
        if PosixConstants.isLinux then
            assert(KqueueBackend.probe == CapabilityOutcome.UnsupportedOS, s"kqueue on Linux, got ${KqueueBackend.probe}")
        else if PosixConstants.isMacOrBsd then
            assert(EpollBackend.probe == CapabilityOutcome.UnsupportedOS, s"epoll on macOS/BSD, got ${EpollBackend.probe}")
            assert(IoUringBackend.probe == CapabilityOutcome.UnsupportedOS, s"io_uring on macOS/BSD, got ${IoUringBackend.probe}")
        else cancel("neither Linux nor macOS/BSD: no posix readiness backend applies on this host")
    }

    "the probe on the backend's own OS classifies what it found, never an unexplained failure" in {
        // ProbeFailed is the unclassified bucket. Reaching it here would mean the shim exercise threw something the mapping does not
        // recognize, which is exactly the opaque outcome the class-initializer unwrap exists to prevent.
        val (backend, outcome) =
            if PosixConstants.isLinux then ("epoll", EpollBackend.probe)
            else if PosixConstants.isMacOrBsd then ("kqueue", KqueueBackend.probe)
            else cancel("neither Linux nor macOS/BSD: no posix readiness backend applies on this host")
        outcome match
            case CapabilityOutcome.ProbeFailed(cause) =>
                fail(s"$backend probed with an unclassified failure on its own OS: $cause")
            case CapabilityOutcome.UnsupportedOS =>
                fail(s"$backend must apply on its own OS, got UnsupportedOS")
            case _ => succeed
        end match
    }

    "a readiness backend that reports available has exercised the bundled shim, not only the libc gate" in {
        // The positive control for the honest probe: Available is reachable only through the read-only kyo_posix_get_flags call on the probe
        // fd, so on a host where the shim IS staged this leaf proves the shim path runs, and on a host where it is not the probe reports the
        // missing library by name instead of claiming availability.
        val (backend, outcome) =
            if PosixConstants.isLinux then ("epoll", EpollBackend.probe)
            else if PosixConstants.isMacOrBsd then ("kqueue", KqueueBackend.probe)
            else cancel("neither Linux nor macOS/BSD: no posix readiness backend applies on this host")
        outcome match
            case CapabilityOutcome.Available => succeed
            case CapabilityOutcome.NotBundled(id, platform) =>
                cancel(s"$backend demoted because '$id' is not bundled for $platform, which is the honest outcome on this host")
            case other =>
                cancel(s"$backend is not available on this host: ${other.describe}")
        end match
    }

    "the probe is memoized, so the gate syscall runs once for the process" in {
        // Same value on every call, and each backend answers from its own memo rather than re-running an epoll_create1 / kqueue per question.
        assert(EpollBackend.probe == EpollBackend.probe)
        assert(KqueueBackend.probe == KqueueBackend.probe)
        assert(IoUringBackend.probe == IoUringBackend.probe)
    }

    "every posix backend is a registry entry itself, with no wrapper forwarding its identity" in {
        // The entries in the platform registry ARE these objects: `build` is a member the backend carries, not a thunk a wrapper holds, so
        // there is exactly one place each backend's name and priority are declared.
        val posix: Chunk[Entry] = Chunk(IoUringBackend, EpollBackend, KqueueBackend)
        assert(posix.map(_.name) == Chunk("io_uring", "epoll", "kqueue"))
        assert(posix.map(_.priority) == Chunk(30, 20, 20))
    }

end PosixBackendsTest
