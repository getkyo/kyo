package kyo.website

import kyo.*

class LandingAppTest extends WebsiteTest:

    private val home = "/latest/kyo-core/"

    private def renderLanding(using Frame): String < Async =
        for
            view <- LandingApp.body(home)
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    "all named sections present with data-section hooks and structural class hooks" in {
        renderLanding.map { html =>
            assert(html.contains("data-section=\"hero\""))
            assert(html.contains("data-section=\"gap\""))
            assert(html.contains("data-section=\"ladder\""))
            assert(html.contains("data-section=\"one-foundation\""))
            assert(html.contains("data-section=\"adopt\""))
            assert(html.contains("data-section=\"platforms\""))
            assert(html.contains("data-section=\"why-exists\""))
            assert(html.contains("data-section=\"final-cta\""))
            assert(html.contains("data-section=\"footer\""))
            assert(html.contains("class=\"feat-grid\""), "one-foundation feat-grid hook must be present")
            assert(html.contains("class=\"wrap\""), "sections must carry the layout wrap")
        }
    }

    "body renders no header (the header is owned by SiteApp)" in {
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

    "hero renders a decorative arc backdrop (aria-hidden, behind the content)" in {
        renderLanding.map { html =>
            // A quiet concentric-arc motif sits behind the hero content. It is decorative, so it is
            // aria-hidden, and it is an inline <svg> of circles sharing the top-right corner (cx=620 cy=0).
            assert(html.contains("class=\"hero-bg\""), s"the hero must render the .hero-bg backdrop: $html")
            assert(html.contains("aria-hidden=\"true\""), "the decorative backdrop must be aria-hidden")
            assert(html.contains("cx=\"620\""), "the arcs share the top-right corner center cx=620")
        }
    }

    "hero right column renders a polished editor-style code card" in {
        renderLanding.map { html =>
            // The hero's right column is a CODE CARD: an editor panel reusing the site's `.code` surface and the
            // shared `tok-*` syntax palette, under editor chrome (window dots + a "SCALA" language label).
            assert(html.contains("class=\"hero-sig\""), s"the hero must render the signature column: $html")
            // The hero card now uses the shared `.code-card` editor chrome (the same family the adoption code
            // receipt uses), so the hero and adopt panels stay visually identical.
            assert(html.contains("class=\"code-card\""), s"the column must render the shared .code-card editor card: $html")
            assert(html.contains("data-section=\"signature-card\""), "the card carries its section hook")
            // Editor chrome: three muted window dots at the left of a header bar, and an uppercase muted SCALA
            // language label at the right. There are TWO editor cards on the page now (hero + adopt), so the
            // shared chrome classes appear twice each: six window dots, two SCALA labels.
            assert(html.contains("class=\"code-bar\""), "the card renders its editor chrome bar")
            assert(countOccurrences(html, "class=\"code-dot\"") == 6, s"both cards together show six window dots: $html")
            assert(html.contains("class=\"code-lang\""), "the chrome carries the language label class")
            assert(countOccurrences(html, ">SCALA<") == 2, s"both editor cards label the language SCALA: $html")
            // The card body is the shared `.code` panel: a <pre><code> of hand-tokenized tok-* spans.
            assert(html.contains("class=\"code\""), "the card body reuses the .code panel")
            assert(html.contains("<pre"), "the snippet renders inside a <pre>")
            // The signature lives in the type, tokenized with the shared palette: `val`/`for`/`yield` keywords,
            // the Receipt/Async/Abort/Declined types, and a for-comprehension body binding with `<-`. The
            // `direct` style is no longer recommended, so neither `direct` nor `.now` appears.
            assert(html.contains("class=\"tok-keyword\">val<"), "val renders as a keyword token")
            assert(html.contains("class=\"tok-keyword\">for<"), "for renders as a keyword token")
            assert(html.contains("class=\"tok-keyword\">yield<"), "yield renders as a keyword token")
            assert(html.contains("class=\"tok-type\">Receipt<"), "Receipt renders as a type token")
            assert(html.contains("class=\"tok-type\">Async<"), "Async renders as a type token")
            assert(html.contains("class=\"tok-type\">Abort<"), "Abort renders as a type token")
            assert(html.contains("class=\"tok-type\">Declined<"), "Declined renders as a type token")
            assert(html.contains("bank.authorize(card, total)"), "the body calls bank.authorize")
            assert(!html.contains(">direct<"), "the direct syntax is no longer recommended and must be gone")
            assert(!html.contains(".now"), "no .now suspension points anywhere (direct style removed)")
            // The effect-row operators render as operator tokens, HTML-escaped.
            assert(html.contains("class=\"tok-operator\">&lt;<"), "the pending operator < renders as an operator token")
            assert(html.contains("class=\"tok-operator\">&amp;<"), "the effect-union & renders as an operator token")
            // The closing line is a muted code comment that carries the page's promise (no separate caption).
            assert(
                html.contains("// every effect and failure, in the type"),
                s"the card closes on the muted promise comment: $html"
            )
            assert(html.contains("class=\"tok-comment\">// every effect"), "the closing line renders as a comment token")
            // None of the prior annotated-diagram forms remain: no SVG line diagram, no leaders, no callouts,
            // no closing caption, no anatomy card.
            assert(!html.contains("class=\"sig-diagram\""), "the SVG diagram wrapper must be gone")
            assert(!html.contains("class=\"sig-leader\""), "the leader lines must be gone")
            assert(!html.contains("id=\"sig-leader-0\""), "the leader ids must be gone")
            assert(!html.contains("class=\"sig-glyph\""), "the drawn signature glyphs must be gone")
            assert(!html.contains("class=\"sig-callout\""), "the drawn callouts must be gone")
            assert(!html.contains("class=\"sig-node\""), "the annotation nodes must be gone")
            assert(!html.contains("class=\"sig-closing\""), "the separate closing line must be gone")
            assert(!html.contains("Composable, reliable, concurrent"), "the old closing sell line must be gone")
            assert(!html.contains("Anatomy of a Kyo computation"), "the eyebrow title must be gone")
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

    "one-foundation lead sells the one-import and derive-once payoff" in {
        renderLanding.map { html =>
            // The foundation lead carries the felt first-hour ergonomics: one import for the whole
            // vocabulary, and one `derives Schema` that yields serialization, validation, field access,
            // and diffs from a single definition (grounded in README one-import + kyo-schema).
            assert(html.contains("One import brings the whole vocabulary"), s"foundation lead must sell one import: $html")
            assert(html.contains("derives Schema"), "foundation lead must name derives Schema")
            assert(
                html.contains("JSON, Protobuf, Ion, and YAML codecs"),
                "foundation lead must name the four codecs one schema yields"
            )
            assert(
                html.contains("a diff you can serialize, send, and replay"),
                "foundation lead must sell the transmittable diff"
            )
        }
    }

    "adoption band sells keeping the existing stack with four grounded paths and a code receipt" in {
        renderLanding.map { html =>
            // The adoption band answers "do I rewrite?" with four entry points: bidirectional ZIO
            // interop, the drop-in scheduler, the standalone modules used as ordinary libraries, and a
            // fresh-start import. Each is a factual interop claim.
            assert(html.contains("Keep what you already have."), s"adoption heading must render: $html")
            assert(html.contains("Adopting Kyo is not a rewrite"), "adoption lead must open on the no-rewrite benefit")
            assert(html.contains("cross the boundary in both directions"), "adoption lead must state bidirectional semantics")
            // Four paths, each a styled card.
            val paths = countOccurrences(html, "class=\"path\"")
            assert(paths == 4, s"expected 4 adoption paths, got $paths")
            assert(html.contains("Already on ZIO?"), "path 1 names the ZIO interop")
            assert(html.contains("Want only the runtime?"), "path 2 names the drop-in runtime")
            assert(html.contains("Want no runtime?"), "path 3 names the standalone-modules path")
            assert(html.contains("Starting fresh?"), "path 4 names the fresh-start import")
            // The no-runtime path is framed on the scheduler BEING the runtime: kyo-prelude carries real
            // effects with no scheduler, and kyo-data / kyo-schema / kyo-parse are plain libraries.
            assert(
                html.contains("Not every module needs the scheduler"),
                "the no-runtime path must frame the runtime as the scheduler"
            )
            assert(
                html.contains("kyo-prelude handles typed errors"),
                "the no-runtime path must name kyo-prelude's runtime-free effects"
            )
            // The interop library is named as a factual interop claim.
            assert(html.contains("ZIO"), "the ZIO interop fact must be named")
            // The code receipt shows TWO conversions: ZIO lifts into Kyo (`.get`) and runs back out of
            // Kyo (`.run`). ZIOs is the bridge object.
            assert(html.contains("ZIOs"), "the receipt must name the ZIOs bridge")
            assert(html.contains(".get(loadUser)"), "the receipt must show ZIOs.get lifting a ZIO into a Kyo row")
            assert(html.contains(".run(checkout)"), "the receipt must show .run turning a Kyo computation back out")
            assert(
                html.contains("Kyo composes with ZIO, both ways"),
                "the receipt comment must state the both-directions framing"
            )
            // The old one-directional scheduler-as-ExecutionContext line is gone from the receipt.
            assert(!html.contains(".get.asExecutionContext"), "the receipt no longer shows the scheduler ExecutionContext line")
            // The receipt is now a polished editor card centered in the section: the shared `.code-card`
            // chrome (the same as the hero) wrapped in a `.code-card-wrap` centering container.
            assert(html.contains("class=\"code-card-wrap\""), "the adopt receipt is wrapped in the centering container")
            assert(html.contains("data-section=\"adopt-code\""), "the adopt receipt carries its editor-card section hook")
            // Each path lists the modules it covers, grounded in the real module set (runtime = scheduler).
            // Path 1 (effect interop): kyo-zio.
            assert(html.contains("/latest/kyo-zio/"), "path 1 lists kyo-zio")
            // Path 2 (the scheduler runtime + its drop-in integrations).
            assert(html.contains("/latest/kyo-scheduler/"), "path 2 lists kyo-scheduler")
            assert(html.contains("/latest/kyo-scheduler-zio/"), "path 2 lists the ZIO scheduler integration")
            assert(html.contains("/latest/kyo-scheduler-pekko/"), "path 2 lists the Pekko scheduler integration")
            assert(html.contains("/latest/kyo-scheduler-finagle/"), "path 2 lists the Finagle scheduler integration")
            // Path 3 (no scheduler): kyo-prelude + the plain libraries.
            assert(html.contains("/latest/kyo-prelude/"), "path 3 lists kyo-prelude")
            assert(html.contains("/latest/kyo-data/"), "path 3 lists kyo-data")
            assert(html.contains("/latest/kyo-schema/"), "path 3 lists kyo-schema")
            assert(html.contains("/latest/kyo-parse/"), "path 3 lists kyo-parse")
            // Path 4 (start fresh): kyo-core + high-value modules.
            assert(html.contains("/latest/kyo-http/"), "path 4 lists kyo-http")
            assert(html.contains("/latest/kyo-actor/"), "path 4 lists kyo-actor")
            assert(html.contains("/latest/kyo-stm/"), "path 4 lists kyo-stm")
        }
    }

    "the ladder rung-1 receipt shows the true HTML/SVG boundary, not the false ul/li claim" in {
        renderLanding.map { html =>
            // Rung 1's compile-boundary receipt must be the source-true one: an HTML container rejects an
            // SVG primitive child (UIContentModelTest proves div(Svg.circle(...)) does not compile). The
            // prior false claim (ul rejecting a div as a non-list-item) must be gone: ul(div(...)) compiles.
            assert(html.contains("an SVG primitive is not an HTML child"), s"rung 1 must state the true HTML/SVG boundary: $html")
            assert(
                html.contains("does not compile until the route declares the header it reads"),
                "rung 1 must carry the typed-HTTP-filter compile boundary"
            )
            assert(!html.contains("ul only accepts list items"), "the false ul/li compile claim must be gone")
            assert(!html.contains("single-consumer queue cannot be shared"), "the false queue-sharing claim must be gone")
        }
    }

    "the ladder rung-3 sells the runtime steadying itself with the CPU-sampled blocking fact" in {
        renderLanding.map { html =>
            // Rung 3's receipt is the specific, source-grounded blocking-detection fact (BlockingMonitor):
            // the scheduler catches a blocking call by watching it stop using the CPU, defeating the
            // socket/NIO calls that still report as running threads to a plain thread-state check. Plus the
            // 10ms slice and exactly-once scope release. The earlier over-automatic latency-shed claim stays gone.
            assert(html.contains("watching it stop using the CPU"), s"rung 3 must give the CPU-sampled blocking fact: $html")
            assert(html.contains("still look like running threads"), "rung 3 must state the thread-state lie it defeats")
            assert(html.contains("10ms slice"), "rung 3 must give the concrete 10ms slice")
            assert(html.contains("released exactly once"), "rung 3 must state exactly-once scope release")
            assert(!html.contains("watches its own latency to shed load"), "the over-automatic latency-shed claim must stay gone")
        }
    }

    "the ladder rung-4 sells durable recovery as a restart, not the work, with the lease and version guard" in {
        renderLanding.map { html =>
            // Rung 4 is the sales high point: per-step checkpoint before advancing, a time-limited lease so
            // another executor resumes orphaned work, a durable sleep that survives a restart, and saga
            // compensation. It must not overclaim exactly-once (source guarantees resume-from-last).
            assert(html.contains("A crash costs a restart, not the work."), s"rung 4 lead must sell the benefit: $html")
            assert(html.contains("records each step before the next begins"), "rung 4 must state per-step checkpointing")
            assert(html.contains("time-limited lease"), "rung 4 must state the lease")
            assert(html.contains("claimed by another and carried forward"), "rung 4 must state the second-machine handoff")
            assert(html.contains("one-hour sleep survives a restart"), "rung 4 must state the durable sleep")
            assert(html.contains("unwinds itself in reverse"), "rung 4 must state saga compensation")
            assert(html.contains("No separate workflow server"), "rung 4 must state durability needs no separate server")
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
            // The lead frames the platform choice as "where it runs", not "which features you get": almost
            // every module cross-compiles to all four, so the cards are runtime differences, not narrow uses.
            assert(
                html.contains("compiles to all four targets from one source"),
                "the platforms lead must state the cross-platform completeness thesis"
            )
        }
    }

    "platforms renders a one-codebase source box and a fan-down connector to the four cards" in {
        renderLanding.map { html =>
            // The abstract chip-and-dots diagram is replaced by a MEANINGFUL connector: a centered
            // "one codebase" SOURCE BOX (a dark editor-style box of code-glyph lines + a quiet label) with
            // lines fanning down to the four platform cards.
            assert(html.contains("class=\"pf-source\""), s"the source box wrapper must render: $html")
            assert(html.contains("class=\"pf-source-box\""), "the editor-style source box must render")
            assert(html.contains("one codebase"), "the source box carries the quiet 'one codebase' label")
            // The connector is a full-width SVG between the box and the cards with one line to each of the
            // four card centers (x = 127 / 397 / 667 / 937 in the 1064-wide matched-aspect viewBox), so it
            // carries four line paths.
            assert(html.contains("class=\"pf-connect\""), s"the connector wrapper must render: $html")
            assert(html.contains("id=\"pf-connect\""), "the connector wrapper carries the id the observer watches")
            assert(html.contains("id=\"pf-line-0\""), "the connector renders a line to card 1")
            assert(html.contains("id=\"pf-line-1\""), "the connector renders a line to card 2")
            assert(html.contains("id=\"pf-line-2\""), "the connector renders a line to card 3")
            assert(html.contains("id=\"pf-line-3\""), "the connector renders a line to card 4")
            // The lines reuse the gap chart's scroll-draw mechanism: pathLength=1 normalizes each line so a
            // single keyframe (gated behind `.chart-drawn` on `#pf-connect`) tweens stroke-dashoffset 1 -> 0,
            // and the inline dash base renders them fully drawn without scripting or with reduced motion.
            assert(html.contains("pathLength=\"1\""), "the connector lines normalize their length via pathLength=1")
            assert(html.contains("stroke-dashoffset=\"0\""), "the connector lines' inline base renders them fully drawn")
            // The SVG stretches to the cards row so the x-fractions land on the card centers at any width.
            assert(html.contains("preserveAspectRatio=\"none"), "the connector SVG stretches to the row width")
            // The old abstract diagram is gone.
            assert(!html.contains("class=\"pf-diagram\""), "the old abstract fan-out diagram must be gone")
        }
    }

    "in-body CTAs and footer doc links target the overview (the main README), not getkyo.io anchors" in {
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
            // The footer Docs links point at real sections of the rendered overview (its headings carry slug
            // anchors), not three duplicate links to the same page or a dead "Modules" menu.
            assert(
                html.contains(s"""href="$overview#getting-started""""),
                "footer 'Get started' targets the overview's Getting Started section"
            )
            assert(html.contains(s"""href="$overview#modules""""), "footer 'Modules' targets the overview's Modules section")
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
            // "4 in 5" peak and draws itself in (a CSS stroke-dashoffset keyframe) when scrolled into view.
            assert(html.contains("class=\"stat-chart\""), s"stat-chart wrapper must render: $html")
            assert(html.contains("<svg"), s"the chart must render as inline SVG: $html")
            assert(html.contains("Small errors compound."), "the stat caption must state that errors compound")
            assert(html.contains(">4 in 5<"), "the chart must label the four-in-five failure peak")
            assert(html.contains("chance of failure"), "the chart must caption the failure dimension")
            assert(html.contains("85%"), "the copy must give the 85% per-step reliability")
            assert(html.contains("var(--red)"), "the climbing line and its peak render in red")
        }
    }

    "the gap chart's line carries the scroll-reveal hooks: a #gap-chart wrapper and a #gap-line path with pathLength=1" in {
        renderLanding.map { html =>
            // The bundle observes `#gap-chart` and adds `.chart-drawn` when it scrolls into view; the CSS
            // keyframe then tweens `#gap-line`'s stroke-dashoffset. `pathLength="1"` normalizes the geometry
            // so the keyframe (1 -> 0) is length-independent. The inline dash base (stroke-dasharray="1",
            // stroke-dashoffset="0") renders the line fully drawn without JS or with reduced motion.
            assert(html.contains("id=\"gap-chart\""), s"the chart wrapper must carry id=gap-chart for the observer: $html")
            assert(html.contains("id=\"gap-line\""), s"the climbing line must carry id=gap-line for the draw keyframe: $html")
            assert(html.contains("pathLength=\"1\""), s"the line must normalize its length via pathLength=1: $html")
            assert(html.contains("stroke-dashoffset=\"0\""), s"the line's inline base must render it fully drawn: $html")
            // The draw is CSS-driven now, not SMIL: there is no <animate> tween in the chart.
            assert(!html.contains("attributeName=\"stroke-dashoffset\""), s"the chart must not use a SMIL animate: $html")
        }
    }

    "key copy is present without JS (SSR)" in {
        renderLanding.map { html =>
            // Hero lead, the gap framing and its stat, the layered-safety ladder, the platforms heading,
            // the manifesto pointer, and the closing line all render server-side (no client JS), so the
            // page is meaningful with scripting disabled.
            assert(html.contains("AI can write the code"))
            assert(html.contains("you can depend on it"))
            assert(html.contains("four in five"))
            assert(html.contains("As you write. As it compiles. As it runs. When it fails."))
            assert(html.contains("One codebase. Four platforms."))
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
