package kyo.net.internal.backend

import kyo.net.Test

/** What a probe reports, and what a reader gets out of it. The gate selection reads is one bit, but the rest of the type exists so the
  * degrade a host actually hit is recoverable from a log line or an exception message: these leaves pin that each case keeps its own payload
  * and that the three non-applicable cases stay distinguishable rather than collapsing back into one "unavailable".
  */
class CapabilityOutcomeTest extends Test:

    "only Available gates a candidate in" in {
        assert(CapabilityOutcome.Available.isAvailable)
        assert(!CapabilityOutcome.UnsupportedOS.isAvailable)
        assert(!CapabilityOutcome.Unavailable("old kernel").isAvailable)
        assert(!CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64").isAvailable)
        assert(!CapabilityOutcome.VersionTooOld("1.0", "2.0").isAvailable)
        assert(!CapabilityOutcome.ProbeFailed(new RuntimeException("boom")).isAvailable)
    }

    "the three degrades a host can hit are distinct values, not one collapsed 'unavailable'" in {
        // The distinction is the whole point of replacing the Boolean channel: a kernel that cannot serve, a candidate belonging to another
        // OS, and a native that was never staged are three different things to do about it.
        val unavailable = CapabilityOutcome.Unavailable("kernel does not provide io_uring")
        val notBundled  = CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64")
        assert(unavailable != notBundled)
        assert(unavailable != CapabilityOutcome.UnsupportedOS)
        assert(notBundled != CapabilityOutcome.UnsupportedOS)
        assert(unavailable == CapabilityOutcome.Unavailable("kernel does not provide io_uring"))
        assert(notBundled == CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64"))
    }

    "describe carries each case's own payload into the report line" in {
        assert(CapabilityOutcome.Available.describe == "available")
        assert(
            CapabilityOutcome.UnsupportedOS.describe.contains("OS/runtime"),
            s"got ${CapabilityOutcome.UnsupportedOS.describe}"
        )
        val unavailable = CapabilityOutcome.Unavailable("kernel does not provide io_uring").describe
        assert(unavailable.contains("kernel does not provide io_uring"), s"got $unavailable")
        val notBundled = CapabilityOutcome.NotBundled("kyonet_posix_uring", "linux-x86_64").describe
        assert(notBundled.contains("kyonet_posix_uring"), s"got $notBundled")
        assert(notBundled.contains("linux-x86_64"), s"got $notBundled")
        val versionTooOld = CapabilityOutcome.VersionTooOld("1.0", "2.0").describe
        assert(versionTooOld.contains("1.0") && versionTooOld.contains("2.0"), s"got $versionTooOld")
        val probeFailed = CapabilityOutcome.ProbeFailed(new RuntimeException("the ring blew up")).describe
        assert(probeFailed.contains("the ring blew up"), s"got $probeFailed")
    }

end CapabilityOutcomeTest
