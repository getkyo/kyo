package kyo.website

import kyo.*

class LandingAppTest extends Test:

    private val home = "/latest/kyo-core/"

    private def renderLanding(using Frame): String < Async =
        for
            view <- LandingApp.body(home)
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    "all named sections present with data-section hooks and class=feat-grid (INV-012 consumer)" in run {
        renderLanding.map { html =>
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

    "body renders no header (the header is owned by SiteApp, D5)" in run {
        renderLanding.map { html =>
            // The landing body must NOT carry its own header chrome: no data-section="header",
            // no version dropdown, no search input. Those belong to the unified SiteApp shell.
            assert(!html.contains("data-section=\"header\""), "body must not render a header section")
            assert(!html.contains("data-kyo-dropdown"), "body must not render a version dropdown")
            assert(!html.contains("search-input"), "body must not render the search input")
        }
    }

    "hero carries the headline copy" in run {
        renderLanding.map { html =>
            assert(html.contains("Build with AI."))
            assert(html.contains("Ship something that "))
            assert(html.contains("holds"))
        }
    }

    "outcomes grid has 6 cards each with module attribution hook" in run {
        renderLanding.map { html =>
            val cellCount = countOccurrences(html, "class=\"cell\"")
            assert(cellCount == 6, s"expected 6 outcome cells, got $cellCount")
            assert(html.contains("data-module=\"kyo-prelude\""))
            assert(html.contains("data-module=\"kyo-flow\""))
            assert(html.contains("data-module=\"kyo-core\""))
            assert(html.contains("data-module=\"kyo-scheduler\""))
        }
    }

    "platforms band names all three platforms" in run {
        renderLanding.map { html =>
            assert(html.contains("JVM"))
            assert(html.contains("Browser and Node"))
            assert(html.contains("Native binary") || html.contains("Native"))
        }
    }

    "in-body CTAs and footer doc links target the local docs home (Href.Path), not getkyo.io (D2)" in run {
        renderLanding.map { html =>
            // The hero/depth/final-CTA/footer internal links now route locally to docsHome.
            assert(html.contains(s"""href="$home""""), s"in-body CTAs must target $home: $html")
            // No internal getkyo.io anchor links remain in the body CTAs (the Community footer
            // getkyo.io identity link stays external and is asserted separately below).
            assert(!html.contains("getkyo.io#getting-started"), "footer Get started must be local, not getkyo.io#getting-started")
            assert(!html.contains("getkyo.io#modules"), "footer Modules must be local, not getkyo.io#modules")
        }
    }

    "footer keeps the external Community getkyo.io identity link (D2)" in run {
        renderLanding.map { html =>
            // The Community column's getkyo.io link is an external identity link and stays external.
            assert(html.contains("getkyo.io"), "footer Community getkyo.io identity link must remain")
        }
    }

    "all content present without JS (INV-002 consumer)" in run {
        renderLanding.map { html =>
            // kyo-ui HTML-encodes text: apostrophes become &#39;
            assert(html.contains("Failures don&#39;t stay hidden"))
            assert(html.contains("It can resume after a restart"))
            assert(html.contains("Many things at once, kept orderly"))
            assert(html.contains("Resources get released, even on failure"))
            assert(html.contains("It sheds load before it breaks"))
            assert(html.contains("Flaky calls don&#39;t take you down"))
            assert(html.contains("algebraic effects"))
            assert(html.contains("Build something that holds"))
        }
    }

    "body carries no DOM or IO in effect row" in run {
        // LandingApp.body returns UI < Sync (not UI < Async). Asserting via explicit annotation:
        // if the row widened to include Async the type annotation below would fail to compile.
        val _: String => Frame ?=> UI < Sync = h => LandingApp.body(h)
        // Also verify the body can be built and rendered without error.
        LandingApp.body(home).map { view =>
            assert(view != null, "body must be a concrete UI value")
        }
    }

    "logo hook present with relative path (footer brand, not raw.githubusercontent URL)" in run {
        renderLanding.map { html =>
            assert(html.contains("/kyo.png"), "logo src must use relative path /kyo.png")
            assert(!html.contains("raw.githubusercontent"), "logo must NOT use raw.githubusercontent URL")
        }
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
