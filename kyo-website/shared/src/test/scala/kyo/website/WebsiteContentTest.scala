package kyo.website

import kyo.*

class WebsiteContentTest extends Test:

    val version0 = WebsiteVersion("v0.1.0", "0.1.0", false)
    val version1 = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)

    val moduleA = WebsiteModule("kyo-data", "Foundation", "kyo-data", "# kyo-data", WebsiteModule.Platforms(true, true, true))
    val moduleB = WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", "# kyo-kernel", WebsiteModule.Platforms(true, true, true))
    val moduleC = WebsiteModule("kyo-core", "Application runtime", "kyo-core", "# kyo-core", WebsiteModule.Platforms(true, true, false))

    "empty-groups WebsiteContent is valid (INV-007 model-shape half)" - {
        "groups.isEmpty is true" in {
            val c = WebsiteContent("intro text", Chunk.empty, version0)
            assert(c.groups.isEmpty == true)
        }

        "intro is preserved" in {
            val c = WebsiteContent("intro text", Chunk.empty, version0)
            assert(c.intro == "intro text")
        }
    }

    "non-empty grouped content" - {
        "groups.head.modules.size is correct" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA, moduleB))
            val g2 = WebsiteContent.Group("Application runtime", Chunk(moduleC))
            val c  = WebsiteContent("intro", Chunk(g1, g2), version1)
            assert(c.groups.head.modules.size == 2)
        }

        "group order is preserved" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA, moduleB))
            val g2 = WebsiteContent.Group("Application runtime", Chunk(moduleC))
            val c  = WebsiteContent("intro", Chunk(g1, g2), version1)
            assert(c.groups.map(_.name) == Chunk("Foundation", "Application runtime"))
        }
    }

    "in-memory render input carries no IO (INV-006 signature half)" - {
        "all fields are plain values" in {
            val c = WebsiteContent("intro", Chunk.empty, version1)
            // Accessing fields requires no effect row: they are plain String/Chunk/WebsiteVersion values.
            val _: String                      = c.intro
            val _: Chunk[WebsiteContent.Group] = c.groups
            val _: WebsiteVersion              = c.version
            succeed
        }
    }

    "Group equality and CanEqual" - {
        "two equal Groups are equal" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA))
            val g2 = WebsiteContent.Group("Foundation", Chunk(moduleA))
            assert(g1 == g2)
        }

        "a Group with a different module is not equal" in {
            val g1 = WebsiteContent.Group("Foundation", Chunk(moduleA))
            val g2 = WebsiteContent.Group("Foundation", Chunk(moduleB))
            assert(g1 != g2)
        }
    }

end WebsiteContentTest
