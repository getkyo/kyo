package kyo.website

import kyo.*

class WebsiteVersionTest extends Test:

    "equality and CanEqual" - {
        "two identical WebsiteVersion values are equal" in {
            val v1 = WebsiteVersion("v1.0.0", "1.0.0", true)
            val v2 = WebsiteVersion("v1.0.0", "1.0.0", true)
            assert(v1 == v2)
        }

        "a version with latest=false is not equal to latest=true" in {
            val v1 = WebsiteVersion("v1.0.0", "1.0.0", true)
            val v2 = WebsiteVersion("v1.0.0", "1.0.0", false)
            assert(v1 != v2)
        }

        "derives CanEqual permits == comparison at compile time" in {
            val v = WebsiteVersion("v0.9.3", "0.9.3", false)
            assert(v == WebsiteVersion("v0.9.3", "0.9.3", false))
        }
    }

end WebsiteVersionTest
