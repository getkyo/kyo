package kyo.net.internal.backend

import kyo.*
import kyo.ffi.FfiLoadError
import kyo.net.Test

/** What a load failure is turned into, which decides the one line a reader gets when a backend demotes.
  *
  * A wrong classification costs more than a vague one, because it names a remedy that cannot work and the reader
  * spends the time. `NotBundled` says to add a classifier artifact; none of them carries a libc, so a system
  * library's failure sent there answers a missing SYMBOL with a packaging instruction.
  */
class CapabilityProbeTest extends Test:

    "classify" - {

        "a system library's failure keeps the loader's message rather than naming a classifier" in {
            val thrown = new FfiLoadError.LibraryNotFound(
                "c",
                Chunk("process default scope"),
                "Symbol 'io_uring_setup' is absent from system library 'c' on linux-x86_64; " +
                    "this is a missing symbol, not a missing library",
                null
            )
            CapabilityProbe.classify(thrown, Chunk("c")) match
                case CapabilityOutcome.Unavailable(reason) =>
                    assert(reason.contains("io_uring_setup"))
                    assert(!reason.contains("classifier"))
                case other =>
                    fail(s"a system library must not classify as a packaging problem: ${other.describe}")
            end match
        }

        "a bundled library's failure names the library to add" in {
            val thrown = new FfiLoadError.LibraryNotFound("kyonet_posix_uring", Chunk("bundled resource"), "not found", null)
            CapabilityProbe.classify(thrown, Chunk("kyonet_posix_uring")) match
                case CapabilityOutcome.NotBundled(id, _) => assert(id == "kyonet_posix_uring")
                case other                               =>
                    fail(s"a bundled library must classify as NotBundled: ${other.describe}")
            end match
        }
    }

    "platform" - {

        // Runs on all four platforms, which is the point: `sys.props` carries no os.arch off the JVM and Native,
        // so reading it there tagged every JS and Wasm host "<os>-unknown" and pointed at a classifier under a
        // name nothing publishes.
        "names this runtime's architecture rather than unknown" in {
            assert(!CapabilityProbe.platform.endsWith("-unknown"), s"platform=${CapabilityProbe.platform}")
            assert(!CapabilityProbe.platform.startsWith("unknown-"), s"platform=${CapabilityProbe.platform}")
        }
    }

end CapabilityProbeTest
