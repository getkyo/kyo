package kyo.net.internal.backend

import kyo.*
import kyo.net.internal.posix.PosixConstants

/** The JS/Wasm I/O registry's probes, read as outcomes rather than through the backend fan-out.
  *
  * The fan-out in `kyo.net.Test` CANCELS a leaf whose backend is unavailable, so a posix backend that stops
  * probing available takes every one of its leaves out of the run and the suite still reports success. An FFI
  * loader failure that reaches this registry as a platform verdict is invisible there: every leaf of that
  * backend cancels and nothing goes red.
  *
  * So this suite asserts on the probe itself. It separates a backend that is correctly absent (nothing bundled,
  * no syscall, kernel too old) from one claiming the OS it exists for does not apply, which is the shape a
  * swallowed loader error takes on its way to the report.
  */
class IoBackendPlatformTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    "the Node floor is always available" in {
        assert(NodeBackend.probe == CapabilityOutcome.Available)
        assert(NodeBackend.name == "node")
    }

    "the OS-appropriate posix backend never denies its own OS" in {
        def assertApplicable(name: String, outcome: CapabilityOutcome) =
            outcome match
                case CapabilityOutcome.UnsupportedOS =>
                    fail(s"$name probed as inapplicable on the OS it exists for: ${outcome.describe}")
                case CapabilityOutcome.ProbeFailed(cause) =>
                    fail(s"$name probe failed rather than classifying: $cause")
                case _ => succeed
        if PosixConstants.isMacOrBsd then assertApplicable("kqueue", KqueueBackend.probe)
        else if PosixConstants.isLinux then assertApplicable("epoll", EpollBackend.probe)
        else succeed
        end if
    }

end IoBackendPlatformTest
