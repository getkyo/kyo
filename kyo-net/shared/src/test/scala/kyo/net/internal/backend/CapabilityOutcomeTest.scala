package kyo.net.internal.backend

import kyo.Chunk
import kyo.net.Test

/** Pins what a demoted backend's one line tells the reader.
  *
  * `describe` is the whole of the demotion warning a reader ever sees, so a wording that describes the symptom without naming the remedy
  * sends them looking in the wrong place. "native library 'kyonet_posix_uring' is not bundled for darwin-aarch64" read as a statement about
  * kyo's release contents, and the reader concluded macOS was not a supported host. It was a missing line in their build: kyo-net publishes
  * each platform's native as a classifier artifact, and the message already knew the classifier string it needed.
  */
class CapabilityOutcomeTest extends Test:

    "describe" - {

        // Asserted per runtime because the remedy is per runtime. There is no classpath on Node and no classifier
        // artifact to add there, so the line that helps a JVM reader is the line that misdirects a Node one.
        "NotBundled names the remedy that fixes it on this runtime" in {
            val described = CapabilityOutcome.NotBundled("kyonet_posix_uring", "darwin-aarch64").describe
            assert(described.contains("kyonet_posix_uring"))
            if kyo.internal.Platform.isJS || kyo.internal.Platform.isWasm then
                assert(described.contains("KYO_FFI_KYONET_POSIX_URING_PATH"))
                assert(described.contains("@kyo/ffi-native"))
                assert(!described.contains("classpath"), s"a Node reader has no classpath to add to: $described")
            else
                // The classifier string appears as the value of the `classifier` argument, not only as a platform tag in prose.
                assert(described.contains("""classifier "darwin-aarch64""""))
                assert(described.contains(""""io.getkyo" %% "kyo-net""""))
            end if
        }

        "the other outcomes stay one short line each" in {
            assert(CapabilityOutcome.Available.describe == "available")
            assert(CapabilityOutcome.UnsupportedOS.describe == "not applicable to this OS/runtime")
            assert(CapabilityOutcome.Unavailable("the kernel is too old").describe == "the kernel is too old")
            assert(CapabilityOutcome.VersionTooOld("1.0", "2.0").describe == "native version 1.0 is below the required 2.0")
        }

        // Every caller already frames a non-available outcome as unavailable (the selection report, the demotion and forced-backend
        // warnings, the consumer report), so a describe that restates the status prints it twice: "unavailable (unavailable (...))".
        "never restates the unavailable status its callers already print" in {
            val outcomes = Chunk(
                CapabilityOutcome.UnsupportedOS,
                CapabilityOutcome.Unavailable("the kernel is too old"),
                CapabilityOutcome.CompiledStub("kyo_uring.c", "<liburing.h>", "install liburing-dev and relink"),
                CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64"),
                CapabilityOutcome.VersionTooOld("1.0", "2.0"),
                CapabilityOutcome.ProbeFailed(new RuntimeException("boom"))
            )
            outcomes.foreach { outcome =>
                assert(!outcome.describe.startsWith("unavailable"), s"describe restates the status: ${outcome.describe}")
                assert(outcome.status == s"unavailable (${outcome.describe})", s"got ${outcome.status}")
            }
            succeed
        }

        // The stub body of a header-gated shim is a build fact. The line must say the library is absent from the binary and name the
        // header and the remedy, because "present but its probe failed" sends the reader after a runtime fault that does not exist.
        "CompiledStub says the binary was built without the header, and names the remedy" in {
            val described =
                CapabilityOutcome.CompiledStub("kyo_uring.c", "<liburing.h>", "install liburing's development headers and relink").describe
            assert(described.contains("kyo_uring.c was compiled without <liburing.h>"), described)
            assert(described.contains("stub"), described)
            assert(described.contains("install liburing's development headers and relink"), described)
            assert(!described.contains("present"), described)
        }
    }

    "status" - {
        "is 'available' or 'unavailable (<describe>)', stating the status once" in {
            assert(CapabilityOutcome.Available.status == "available")
            assert(CapabilityOutcome.Unavailable("the kernel is too old").status == "unavailable (the kernel is too old)")
            assert(CapabilityOutcome.UnsupportedOS.status == "unavailable (not applicable to this OS/runtime)")
        }
    }

end CapabilityOutcomeTest
