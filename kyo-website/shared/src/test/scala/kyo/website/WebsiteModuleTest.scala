package kyo.website

import kyo.*

class WebsiteModuleTest extends Test:

    "full construction" - {
        "fields read back correctly" in {
            val m = WebsiteModule(
                "kyo-core",
                "Foundation",
                "kyo-core",
                "# kyo-core\n...",
                WebsiteModule.Platforms(true, true, false)
            )
            assert(m.slug == "kyo-core")
            assert(m.group == "Foundation")
            assert(m.platforms.native == false)
        }
    }

    "Platforms equality" - {
        "different triples are not equal" in {
            val p1 = WebsiteModule.Platforms(true, true, false)
            val p2 = WebsiteModule.Platforms(true, true, true)
            assert(p1 != p2)
        }

        "same triple is equal" in {
            val p1 = WebsiteModule.Platforms(true, true, false)
            val p2 = WebsiteModule.Platforms(true, true, false)
            assert(p1 == p2)
        }
    }

end WebsiteModuleTest
