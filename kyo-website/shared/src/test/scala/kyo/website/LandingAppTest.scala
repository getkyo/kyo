package kyo.website

import kyo.*

class LandingAppTest extends WebsiteTest:

    private val home = "/latest/kyo-core/"

    private def renderLanding(using Frame): String < Async =
        for
            view <- LandingApp.body(home)
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    "all named sections present with data-section hooks and structural class hooks (INV-012 consumer)" in {
        renderLanding.map { html =>
            assert(html.contains("data-section=\"hero\""))
            assert(html.contains("data-section=\"gap\""))
            assert(html.contains("data-section=\"ladder\""))
            assert(html.contains("data-section=\"one-foundation\""))
            assert(html.contains("data-section=\"platforms\""))
            assert(html.contains("data-section=\"social-proof\""))
            assert(html.contains("data-section=\"why-exists\""))
            assert(html.contains("data-section=\"final-cta\""))
            assert(html.contains("data-section=\"footer\""))
            assert(html.contains("class=\"feat-grid\""), "one-foundation feat-grid hook must be present (INV-012)")
            assert(html.contains("class=\"wrap\""), "sections must carry the layout wrap (INV-012)")
        }
    }

    "body renders no header (the header is owned by SiteApp, D5)" in {
        renderLanding.map { html =>
            // The landing body must NOT carry its own header chrome: no data-section="header",
            // no version dropdown, no search input. Those belong to the unified SiteApp shell.
            assert(!html.contains("data-section=\"header\""), "body must not render a header section")
            assert(!html.contains("data-kyo-dropdown"), "body must not render a version dropdown")
            assert(!html.contains("search-input"), "body must not render the search input")
        }
    }

    "hero carries the headline and the AI lead copy" in {
        renderLanding.map { html =>
            // The headline is "Build something that <accent>holds</accent>.", so the run before the
            // accent span and the accent word both appear in the rendered HTML.
            assert(html.contains("Build something that "), s"hero headline: $html")
            assert(html.contains("holds"))
            // The lead opens on the AI framing the whole landing is built around.
            assert(html.contains("AI can write the code"), "hero lead must carry the AI framing")
        }
    }

    "one-foundation feat-grid names the five capability categories" in {
        renderLanding.map { html =>
            assert(html.contains("Everything you need, on the same ground."), "foundation heading must render")
            val cards = countOccurrences(html, "class=\"fcat\"")
            assert(cards == 5, s"expected 5 capability cards, got $cards")
            // Each card's <h4> title renders as >Title< (the li bullets read ">Web frontends<", etc.,
            // so the exact >Web< form pins the title, not a bullet).
            assert(html.contains(">Web<"))
            assert(html.contains(">Concurrency<"))
            assert(html.contains(">Reliability<"))
            assert(html.contains(">Data<"))
            assert(html.contains(">Operations<"))
        }
    }

    "platforms band names all four platforms" in {
        renderLanding.map { html =>
            assert(html.contains("One codebase. Four platforms."), "platforms heading must render")
            val cards = countOccurrences(html, "class=\"pf\"")
            assert(cards == 4, s"expected 4 platform cards, got $cards")
            // The prominent label on each card is the compile target, so the four big labels read as
            // one parallel set (the targets in `Platforms(jvm, js, native, wasm)`), not a mix of role,
            // environment, and target. The `>Label<` form pins the pf-n div text, not a body mention.
            assert(html.contains(">JVM<"))
            assert(html.contains(">JavaScript<"))
            assert(html.contains(">Native<"))
            assert(html.contains(">WebAssembly<"))
            // The WasmGC backend detail stays in the WebAssembly card body.
            assert(html.contains("WasmGC"))
        }
    }

    "in-body CTAs and footer doc links target the overview (the main README), not getkyo.io anchors (D2)" in {
        renderLanding.map { html =>
            // The hero/final-CTA/footer CTAs ("Start building", "Get started", "Documentation") route to
            // the overview /<prefix>/ (the root-README intro), derived from the docs home's prefix, not to
            // the first module page. `href="/latest/"` (exact, with the trailing quote) matches only the
            // overview CTA, not the `/latest/kyo-http/` module links the feature cards carry.
            val overview = "/latest/"
            assert(html.contains(s"""href="$overview""""), s"in-body CTAs must target the overview $overview: $html")
            // No internal getkyo.io anchor links remain in the body.
            assert(!html.contains("getkyo.io#getting-started"), "Get started must be local, not a getkyo.io anchor")
            assert(!html.contains("getkyo.io#modules"), "Modules must be local, not a getkyo.io anchor")
        }
    }

    "footer carries the external GitHub and Discord identity links" in {
        renderLanding.map { html =>
            // The footer's external identity is the GitHub repo and the Discord community (the prior
            // getkyo.io identity link was dropped in the builder-first rewrite).
            assert(html.contains("github.com/getkyo/kyo"), "footer must link the GitHub repo")
            assert(html.contains("discord.gg"), "footer must link the Discord community")
        }
    }

    "the GitHub and Discord links carry inline brand glyphs filled with currentColor" in {
        renderLanding.map { html =>
            // Each identity link is a `.soc` row wrapping a `.brand-ic` glyph span + the text label. The
            // glyph is the canonical filled brand mark, embedded as an inline <svg> via the Svg DSL raw-path
            // escape and painted with `currentColor` so it follows the link color across themes.
            assert(html.contains("class=\"soc\""), "identity links must use the .soc glyph-row layout")
            assert(html.contains("class=\"brand-ic\""), "identity links must carry a .brand-ic glyph span")
            assert(html.contains("fill=\"currentColor\""), "brand glyphs must paint with currentColor")
            // The first 8 chars of each canonical path pin the GitHub octocat and Discord marks.
            assert(html.contains("M12 .297"), "the GitHub octocat path must render")
            assert(html.contains("M20.317 4.3698"), "the Discord mark path must render")
        }
    }

    "the gap stat renders the compounding-failure line chart as inline SVG (no raw markup)" in {
        renderLanding.map { html =>
            // The stat callout is a chart card: the SVG line chart (the chance the run has failed, climbing
            // as steps chain) stacked on top of the "compounds" caption and explanatory copy. The chart is
            // server-rendered (present without JS); the line climbs from the accent start dot up into the red
            // "4 in 5" peak and draws itself in via a SMIL stroke-dashoffset tween.
            assert(html.contains("class=\"stat-chart\""), s"stat-chart wrapper must render: $html")
            assert(html.contains("<svg"), s"the chart must render as inline SVG: $html")
            assert(html.contains("Small errors compound."), "the stat caption must state that errors compound")
            assert(html.contains(">4 in 5<"), "the chart must label the four-in-five failure peak")
            assert(html.contains("chance of failure"), "the chart must caption the failure dimension")
            assert(html.contains("85%"), "the copy must give the 85% per-step reliability")
            assert(html.contains("var(--red)"), "the climbing line and its peak render in red")
            assert(html.contains("attributeName=\"stroke-dashoffset\""), "the line draws in via a SMIL stroke-dashoffset tween")
        }
    }

    "key copy is present without JS (SSR, INV-002 consumer)" in {
        renderLanding.map { html =>
            // Hero lead, the gap framing and its stat, the layered-safety ladder, the platforms heading,
            // social proof, the manifesto pointer, and the closing line all render server-side (no client
            // JS), so the page is meaningful with scripting disabled.
            assert(html.contains("AI can write the code"))
            assert(html.contains("you can depend on it"))
            assert(html.contains("four in five"))
            assert(html.contains("As you write. As it compiles. As it runs. When it fails."))
            assert(html.contains("One codebase. Four platforms."))
            assert(html.contains("Presented at Scalar"))
            assert(html.contains("Read the manifesto"))
            assert(html.contains("Build something that holds"))
        }
    }

    "body carries no DOM or IO in effect row" in {
        // LandingApp.body returns UI < Sync (not UI < Async). Asserting via explicit annotation:
        // if the row widened to include Async the type annotation below would fail to compile.
        val _: String => Frame ?=> UI < Sync = h => LandingApp.body(h)
        // Also verify the body can be built and rendered without error.
        LandingApp.body(home).map { view =>
            assert(view != null, "body must be a concrete UI value")
        }
    }

    "footer logo uses the relative vector path (not a raw.githubusercontent URL)" in {
        renderLanding.map { html =>
            assert(html.contains("/kyo.svg"), "logo src must use the relative vector path /kyo.svg")
            assert(!html.contains("raw.githubusercontent"), "logo must NOT use a raw.githubusercontent URL")
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
