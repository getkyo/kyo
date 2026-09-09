package kyo.net.internal.backend

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

        "NotBundled names the classifier line that fixes it" in {
            val described = CapabilityOutcome.NotBundled("kyonet_posix_uring", "darwin-aarch64").describe
            assert(described.contains("kyonet_posix_uring"))
            // The classifier string appears as the value of the `classifier` argument, not only as a platform tag in prose.
            assert(described.contains("""classifier "darwin-aarch64""""))
            assert(described.contains(""""io.getkyo" %% "kyo-net""""))
        }

        "the other outcomes stay one short line each" in {
            assert(CapabilityOutcome.Available.describe == "available")
            assert(CapabilityOutcome.UnsupportedOS.describe == "not applicable to this OS/runtime")
            assert(CapabilityOutcome.Unavailable("the kernel is too old").describe == "unavailable (the kernel is too old)")
            assert(CapabilityOutcome.VersionTooOld("1.0", "2.0").describe == "native version 1.0 is below the required 2.0")
        }
    }

end CapabilityOutcomeTest
