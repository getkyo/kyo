package kyo.website

import kyo.*

class WebsiteModuleTest extends WebsiteTest:

    import WebsiteModule.Environments
    import WebsiteModule.Platforms

    /** Built for the JVM and Native, and for JS and WASM on Node only: the shape of a module a page cannot run, such as kyo-net. */
    private val nodeOnly = Platforms(true, Environments(true, Present(false)), true, Environments(true, Present(false)))

    "full construction" - {
        "fields read back correctly" in {
            val m = WebsiteModule("kyo-net", "Specialized tools", "kyo-net", "# kyo-net\n...", nodeOnly)
            assert(m.slug == "kyo-net")
            assert(m.group == "Specialized tools")
            assert(m.platforms.native)
            assert(m.platforms.js == Environments(true, Present(false)))
            assert(m.platforms.wasm.browser == Present(false))
        }
    }

    "displayName" - {
        "kyo-core strips prefix and capitalizes" in {
            val m = WebsiteModule("kyo-core", "g", "kyo-core", "", Platforms.everywhere)
            assert(m.displayName == "Core")
        }

        "kyo-stats-registry splits on hyphen and capitalizes each segment (no hyphen survives)" in {
            val m = WebsiteModule("kyo-stats-registry", "g", "kyo-stats-registry", "", Platforms.everywhere)
            assert(m.displayName == "Stats Registry")
        }

        "multi-segment slug with an acronym tail leaves no hyphen" in {
            val jvmOnly = Platforms(true, Environments(false, Present(false)), false, Environments(false, Present(false)))
            val m       = WebsiteModule("kyo-logging-slf4j", "g", "kyo-logging-slf4j", "", jvmOnly)
            assert(m.displayName == "Logging Slf4j")
            assert(!m.displayName.contains("-"))
        }

        "non-kyo slug capitalizes first letter only" in {
            val m = WebsiteModule("prelude", "g", "prelude", "", Platforms.everywhere)
            assert(m.displayName == "Prelude")
        }
    }

    "Platforms equality" - {
        "different platform sets are not equal" in {
            assert(nodeOnly != Platforms.everywhere)
        }

        "same platform set is equal" in {
            assert(nodeOnly == Platforms(true, Environments(true, Present(false)), true, Environments(true, Present(false))))
        }

        "a table that does not say whether a target runs in a browser is not one that says it does not" in {
            val unsaid = Platforms(true, Environments(true, Absent), true, Environments(true, Absent))
            assert(unsaid != nodeOnly)
        }
    }

    "labels" - {
        "name every platform and each Scala.js environment, in table order" in {
            assert(Platforms.everywhere.labels == Chunk(
                "JVM",
                "JS on Node",
                "JS in a browser",
                "Native",
                "WASM on Node",
                "WASM in a browser"
            ))
        }

        "leave out the environments a module does not run in" in {
            assert(nodeOnly.labels == Chunk("JVM", "JS on Node", "Native", "WASM on Node"))
        }

        "name the target alone when the table predates the environment columns" in {
            val earlier = Platforms(true, Environments(true, Absent), true, Environments(false, Absent))
            assert(earlier.labels == Chunk("JVM", "JS", "Native"))
        }

        "are empty for a page that is not a module" in {
            assert(Platforms.none.labels.isEmpty)
        }
    }

    "encoded" - {
        "reads back to the same platforms, a browser the table does not state included" in {
            val earlier = Platforms(true, Environments(true, Absent), false, Environments(false, Absent))
            Chunk(Platforms.everywhere, nodeOnly, earlier, Platforms.none).foreach { platforms =>
                assert(Platforms.decode(platforms.encoded) == Present(platforms), s"${platforms.encoded}")
            }
        }

        "is one digit per Boolean, with ? for an unstated browser" in {
            assert(nodeOnly.encoded == "jvm=1 js=10 native=1 wasm=10")
            assert(Platforms.none.encoded == "jvm=0 js=0? native=0 wasm=0?")
        }

        "does not read what it did not write" in {
            assert(Platforms.decode("") == Absent)
            assert(Platforms.decode("jvm=1 js=11 native=1") == Absent)
            assert(Platforms.decode("jvm=2 js=11 native=1 wasm=11") == Absent)
            assert(Platforms.decode("jvm=1 js=1 native=1 wasm=11") == Absent)
            assert(Platforms.decode("jvm=1 js=11 native=1 wasm=1x") == Absent)
        }
    }

end WebsiteModuleTest
