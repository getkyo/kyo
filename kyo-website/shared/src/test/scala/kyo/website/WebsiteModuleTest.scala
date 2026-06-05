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

    "displayName" - {
        "kyo-core strips prefix and capitalizes" in {
            val m = WebsiteModule("kyo-core", "g", "kyo-core", "", WebsiteModule.Platforms(true, true, true))
            assert(m.displayName == "Core")
        }

        "kyo-stats-registry splits on hyphen and capitalizes each segment (no hyphen survives)" in {
            val m = WebsiteModule("kyo-stats-registry", "g", "kyo-stats-registry", "", WebsiteModule.Platforms(true, true, true))
            assert(m.displayName == "Stats Registry")
        }

        "multi-segment slug with an acronym tail leaves no hyphen" in {
            val m = WebsiteModule("kyo-logging-slf4j", "g", "kyo-logging-slf4j", "", WebsiteModule.Platforms(true, false, false))
            assert(m.displayName == "Logging Slf4j")
            assert(!m.displayName.contains("-"))
        }

        "non-kyo slug capitalizes first letter only" in {
            val m = WebsiteModule("prelude", "g", "prelude", "", WebsiteModule.Platforms(true, true, true))
            assert(m.displayName == "Prelude")
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
