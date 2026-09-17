package kyo.net.internal.backend

import kyo.*
import kyo.net.Test

class CapabilityProbePlatformTest extends Test:

    // The platform tag is built from the host's OS and architecture, which come from process.platform and process.arch. A page has
    // neither, so the tag ends in "-unknown" there by design and this suite's assertion is about the other case.
    override protected def hostFilters = kyo.Chunk(kyo.test.HostFilter.NotBrowser)

    "the platform tag names this host's architecture" in {
        assert(!CapabilityProbe.platform.endsWith("-unknown"), s"architecture missing from ${CapabilityProbe.platform}")
    }

end CapabilityProbePlatformTest
