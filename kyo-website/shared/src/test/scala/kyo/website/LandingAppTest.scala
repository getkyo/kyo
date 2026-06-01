package kyo.website

import kyo.*

class LandingAppTest extends Test:

    private val v1        = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0        = WebsiteVersion("v0.9.3", "0.9.3", false)
    private val versions2 = Chunk(v1, v0)

    private val versions5 = Chunk(
        v1,
        v0,
        WebsiteVersion("v0.9.2", "0.9.2", false),
        WebsiteVersion("v0.9.1", "0.9.1", false),
        WebsiteVersion("v0.9.0", "0.9.0", false)
    )

    private def renderLanding(versions: Chunk[WebsiteVersion])(using Frame): String < Async =
        for
            view <- LandingApp.view(versions)
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    "all named sections present with data-section hooks and class=feat-grid (INV-012 consumer)" in run {
        renderLanding(Chunk.empty).map { html =>
            assert(html.contains("data-section=\"hero\""))
            assert(html.contains("data-section=\"problem\""))
            assert(html.contains("data-section=\"promise\""))
            assert(html.contains("data-section=\"outcomes\""))
            assert(html.contains("data-section=\"built-for-ai\""))
            assert(html.contains("data-section=\"one-foundation\""))
            assert(html.contains("data-section=\"depth\""))
            assert(html.contains("data-section=\"final-cta\""))
            assert(html.contains("data-section=\"footer\""))
            assert(html.contains("class=\"feat-grid\""), "feat-grid class hook must be present (INV-012)")
            assert(html.contains("class=\"wrap\""), "root must carry class=\"wrap\" (INV-012)")
        }
    }

    "hero carries the headline copy" in run {
        renderLanding(Chunk.empty).map { html =>
            assert(html.contains("Build with AI."))
            assert(html.contains("Ship something that "))
            assert(html.contains("holds"))
        }
    }

    "outcomes grid has 6 cards each with module attribution hook" in run {
        renderLanding(Chunk.empty).map { html =>
            val cellCount = countOccurrences(html, "class=\"cell\"")
            assert(cellCount == 6, s"expected 6 outcome cells, got $cellCount")
            assert(html.contains("data-module=\"kyo-prelude\""))
            assert(html.contains("data-module=\"kyo-flow\""))
            assert(html.contains("data-module=\"kyo-core\""))
            assert(html.contains("data-module=\"kyo-scheduler\""))
        }
    }

    "platforms band names all three platforms" in run {
        renderLanding(Chunk.empty).map { html =>
            assert(html.contains("JVM"))
            assert(html.contains("Browser and Node"))
            assert(html.contains("Native binary") || html.contains("Native"))
        }
    }

    "dropdown lists one option per version (INV-010)" in run {
        renderLanding(versions2).map { html =>
            assert(html.contains("1.0.0-RC2"))
            assert(html.contains("0.9.3"))
            // kyo-ui dropdown renders option slots as data-kyo-dropdown-opt="N" divs
            val optionCount = countOccurrences(html, "data-kyo-dropdown-opt=")
            assert(optionCount == 2, s"expected 2 dropdown options, got $optionCount")
        }
    }

    "dropdown lists intro-only versions too (INV-010)" in run {
        renderLanding(versions5).map { html =>
            assert(html.contains("1.0.0-RC2"))
            assert(html.contains("0.9.3"))
            assert(html.contains("0.9.2"))
            assert(html.contains("0.9.1"))
            assert(html.contains("0.9.0"))
            // kyo-ui dropdown renders option slots as data-kyo-dropdown-opt="N" divs
            val optionCount = countOccurrences(html, "data-kyo-dropdown-opt=")
            assert(optionCount == 5, s"expected 5 dropdown options, got $optionCount")
        }
    }

    "empty versions yields a valid page with an empty dropdown" in run {
        renderLanding(Chunk.empty).map { html =>
            assert(html.nonEmpty, "page must render with empty versions")
            assert(html.contains("data-section=\"hero\""), "hero section must be present")
            // kyo-ui dropdown renders option slots as data-kyo-dropdown-opt="N" divs
            val optionCount = countOccurrences(html, "data-kyo-dropdown-opt=")
            assert(optionCount == 0, s"expected 0 dropdown options, got $optionCount")
        }
    }

    "all content present without JS (INV-002 consumer)" in run {
        renderLanding(versions2).map { html =>
            // kyo-ui HTML-encodes text: apostrophes become &#39;
            assert(html.contains("Failures don&#39;t stay hidden"))
            assert(html.contains("It can resume after a restart"))
            assert(html.contains("Many things at once, kept orderly"))
            assert(html.contains("Resources get released, even on failure"))
            assert(html.contains("It sheds load before it breaks"))
            assert(html.contains("Flaky calls don&#39;t take you down"))
            assert(html.contains("algebraic effects"))
            assert(html.contains("Build something that holds"))
            assert(html.contains("getkyo.io"))
        }
    }

    "view carries no DOM or IO in effect row" in run {
        // LandingApp.view returns UI < Sync (not UI < Async). Asserting via explicit annotation:
        // if the row widened to include Async the type annotation below would fail to compile.
        val _: Chunk[WebsiteVersion] => Frame ?=> UI < Sync = v => LandingApp.view(v)
        // Also verify the view can be built and rendered without error.
        LandingApp.view(Chunk.empty).map { view =>
            assert(view != null, "view must be a concrete UI value")
        }
    }

    "logo hook present with relative path (not raw.githubusercontent URL)" in run {
        renderLanding(Chunk.empty).map { html =>
            assert(html.contains("data-role=\"logo\""), "logo anchor must carry data-role=logo")
            assert(html.contains("/kyo.png"), "logo src must use relative path /kyo.png")
            assert(!html.contains("raw.githubusercontent"), "logo must NOT use raw.githubusercontent URL")
        }
    }

    "dropdown preserves version order" in run {
        val ordered = Chunk(
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true),
            WebsiteVersion("v0.9.3", "0.9.3", false),
            WebsiteVersion("v0.9.2", "0.9.2", false)
        )
        renderLanding(ordered).map { html =>
            val rc2Idx  = html.indexOf("1.0.0-RC2")
            val v093Idx = html.indexOf("0.9.3")
            val v092Idx = html.indexOf("0.9.2")
            assert(rc2Idx >= 0, "RC2 label must be present")
            assert(v093Idx >= 0, "0.9.3 label must be present")
            assert(v092Idx >= 0, "0.9.2 label must be present")
            assert(rc2Idx < v093Idx, "RC2 must appear before 0.9.3 in document order")
            assert(v093Idx < v092Idx, "0.9.3 must appear before 0.9.2 in document order")
        }
    }

    "boot scenario compatibility via WebsitePage.wrap (INV-008)" in run {
        for
            view <- LandingApp.view(versions2)
            html <- WebsitePage.wrap(
                WebsitePage.Options(
                    title = "Kyo",
                    description = "Build with AI. Ship something that holds.",
                    canonical = "https://getkyo.io/",
                    bundleHref = "main.js",
                    bootScenario = "landing"
                )
            )(view).take(1).run
        yield
            val doc = html.headMaybe.getOrElse("")
            assert(
                doc.contains("data-boot-scenario=\"landing\""),
                "wrapped document must carry data-boot-scenario=landing so WebsiteBundleMain selects the landing arm"
            )
        end for
    }

    // Helper: count non-overlapping occurrences of a substring
    private def countOccurrences(haystack: String, needle: String): Int =
        var count = 0
        var idx   = 0
        while
            val pos = haystack.indexOf(needle, idx)
            if pos >= 0 then
                count += 1
                idx = pos + needle.length
                true
            else
                false
            end if
        do ()
        end while
        count
    end countOccurrences

end LandingAppTest
