package kyo.net.internal.backend

import kyo.*
import kyo.net.Test

class CapabilityProbePlatformTest extends Test:

    "the platform tag names this host's architecture" in {
        assert(!CapabilityProbe.platform.endsWith("-unknown"), s"architecture missing from ${CapabilityProbe.platform}")
    }

end CapabilityProbePlatformTest
