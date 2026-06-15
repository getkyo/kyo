// PUBLIC landing app view
package kyo.website

import kyo.*
import kyo.UI.Href
import kyo.UI.ImgSrc
import kyo.UI.Target
import scala.language.implicitConversions

/** The evergreen landing content as a kyo-ui `UI` value. AI-first is the deliberate thesis: the page
  * leads with the AI framing (AI can write the code; making it hold up under real use is the part that
  * has not changed), and Kyo is the foundation that takes that on, with the mechanism (layered safety,
  * the same runtime and durability) explained right below so the AI claim reads as proof, not a slogan.
  * Sections, in order: hero, the gap, platforms and the engine, one foundation, the ladder (the
  * four-layer safety story with its mechanism receipts and code), keep what you already have, why this
  * exists (the manifesto), and the closing call to action plus footer.
  *
  * This is the content body ONLY: the persistent header is owned by `SiteApp`, so `body` renders no
  * header. Elements opt into `WebsiteStyles.sheet` rules via `UI.cssClass(...)`; no raw CSS, no raw
  * HTML. In-body calls to action and documentation links target the local docs home and module pages
  * (client-routed by `UILocation`, no reload); genuinely external links (GitHub, Discord, releases,
  * the javadoc API) stay external.
  *
  * Vocabulary is bound by the positioning rules: never "guardrails"; "safety" only with its span
  * ("from the first line of code to crash recovery", or the four-beat form); "effects", never
  * "capabilities", for what the type row tracks; no model or provider names; no marketing adjectives;
  * performance only as a mechanism or a number.
  */
object LandingApp:

    /** The landing content body (header excluded, owned by `SiteApp`).
      *
      * @param docsHome
      *   The local docs home (`/<prefix>/<firstSlug>/`). Its first path segment is the active prefix
      *   (`latest` or a version tag), used to build the overview, module, and manifesto links. The
      *   in-body call-to-action buttons ("Start building", "Get started", "Documentation") target the
      *   overview (`/<prefix>/`, the root-README intro), not a specific module.
      */
    def body(docsHome: String)(using Frame): UI < Sync =
        // The body is a pure `UI` value. The widening to `< Sync` keeps a stable signature for the page shell
        // (and is what `LandingAppTest` pins), even though no step here touches the DOM or IO.
        Sync.defer {
            val prefix       = docsHome.split('/').iterator.filter(_.nonEmpty).nextOption().getOrElse("latest")
            val mod          = (slug: String) => s"/$prefix/$slug/"
            val overviewHome = s"/$prefix/"
            UI.div.cssClass("wrap").data("section", "page")(
                hero(overviewHome),
                gap,
                platforms,
                oneFoundation(mod),
                ladder(mod),
                adopt(mod),
                whyExists(mod("manifesto")),
                finalCta(overviewHome),
                pageFooter(overviewHome, mod("manifesto"))
            )
        }

    // Lift a dynamically-built `Seq[UI]` into the splat type an HTML container accepts (the implicit
    // UI -> HtmlChildVal conversion does not apply element-wise through a splat).
    private def html(cs: Seq[UI]): Seq[UI.Ast.HtmlChildVal] = cs.map(n => UI.Ast.HtmlChildVal.lift(n))

    // ---- 1. Hero ----

    // A quiet geometric backdrop for the hero: concentric arcs radiating from the top-right corner, in the
    // accent color at a low overall opacity (set on `.hero-bg`), clipped by the section's overflow so they
    // read as soft layers fanning out behind the code sample. Decorative only (aria-hidden), and it sits on
    // `z-index: 0` under the content's `z-index: 1`, so it never intercepts a click or a text selection.
    private def heroBackdrop(using Frame): UI =
        val accent = Svg.Paint.Color(Style.Color.variable("accent"))
        val rings  = Chunk(150.0, 232.0, 314.0, 396.0, 478.0, 560.0)
        UI.div.cssClass("hero-bg").aria("hidden", "true")(
            Svg.svg.viewBox(Svg.ViewBox(0, 0, 620, 540)).width(620).height(540)(
                Svg.g(
                    rings.map(r =>
                        Svg.circle.cx(620.0).cy(0.0).r(r).fill(Svg.Paint.None).stroke(accent).strokeWidth(1.4)
                    )*
                )
            )
        )
    end heroBackdrop

    private def hero(home: String)(using Frame): UI =
        UI.section.cssClass("hero").id("top").data("section", "hero")(
            heroBackdrop,
            UI.div.cssClass("wrap")(
                UI.div.cssClass("hero-grid")(
                    UI.div.cssClass("hero-text")(
                        UI.h1(
                            "Build something that ",
                            UI.span.cssClass("accent").cssClass("serif")("holds"),
                            "."
                        ),
                        UI.p.cssClass("lead")(
                            "AI can write the code. Making it hold up under real use is the part that has not changed. Kyo is the foundation that takes that on, with layered safety from the first line of code to crash recovery, so what you build survives errors, restarts, and real traffic."
                        ),
                        UI.div.cssClass("hero-cta")(
                            UI.a.cssClass("btn").cssClass("btn-primary").href(Href.Path(home))("Start building"),
                            UI.a.cssClass("btn").href(Href.Fragment("ladder"))("How it works")
                        ),
                        // Each non-first item carries its `·` separator leading its label, joined by a
                        // non-breaking space so the two never split. The `.trust` row wraps between whole
                        // items, so a wrap puts the middot at the START of the next line, never dangling at the
                        // end of the previous one. The first item has no separator.
                        UI.div.cssClass("trust")(
                            UI.span.cssClass("trust-item")("Open source"),
                            UI.span.cssClass("trust-item")("· Apache-2.0"),
                            UI.span.cssClass("trust-item")("· Scala 3"),
                            UI.span.cssClass("trust-item")("· Server, browser, native, and Wasm")
                        )
                    ),
                    heroSignature
                )
            )
        )
    end hero

    // The hero's right column as a polished, editor-style CODE CARD: the proven dev-tool hero pattern, and the
    // one the rest of the site already speaks (the ladder's `.code` panels and the docs `.code-block` use the
    // same dark surface and the shared `tok-*` syntax palette). The card carries editor chrome (three muted
    // window dots at the left, an uppercase "SCALA" language label at the right) over a dark rounded body whose
    // snippet shows a real Kyo signature: the effect row `Receipt < (Async & Abort[Declined])` lives in the
    // type, with a for-comprehension as the body, and a muted trailing comment naming the page's promise (every
    // effect and failure lives in the type). It is the visual anchor of the right column, balanced against
    // the left text by the `.hero-grid` center alignment.
    private def heroSignature(using Frame): UI =
        UI.div.cssClass("hero-sig").data("section", "signature")(
            signatureCard
        )
    end heroSignature

    // The code card itself, built from the shared `editorCard` chrome: a `.code-card` editor panel (dark
    // surface, subtle border, soft shadow) with a `.code-bar` header (three `.code-dot` window dots left, the
    // uppercase `.code-lang` "SCALA" label right) over the `.code` body the rest of the site reuses (a
    // `<pre><code>` of hand-tokenized `tok-*` spans). The snippet is laid out line by line with two-space
    // indentation, broken by `UI.br`; the closing line is a single muted comment so the card carries its own
    // one-line sell with no separate caption. The same `editorCard` chrome wraps the adoption code receipt,
    // so the two panels are visually identical.
    private def signatureCard(using Frame): UI =
        editorCard("signature-card")(
            UI.pre(
                UI.code(
                    tKey("val"),
                    " checkout: ",
                    tType("Receipt"),
                    " ",
                    tOp("<"),
                    " (",
                    tType("Async"),
                    " ",
                    tOp("&"),
                    " ",
                    tType("Abort"),
                    "[",
                    tType("Declined"),
                    "]) ",
                    tOp("="),
                    UI.br,
                    "  ",
                    tKey("for"),
                    UI.br,
                    "    auth ",
                    tOp("<-"),
                    " bank.authorize(card, total)",
                    UI.br,
                    "    done ",
                    tOp("<-"),
                    " orders.record(auth)",
                    UI.br,
                    "  ",
                    tKey("yield"),
                    " done",
                    UI.br,
                    tCom("// every effect and failure, in the type")
                )
            )
        )
    end signatureCard

    // The shared editor card chrome: a `.code-card` panel (dark surface, hairline border, soft shadow) made
    // of a `.code-bar` header (three `.code-dot` window dots at the left, an uppercase `.code-lang` "SCALA"
    // label at the right) over the site's shared `.code` body. Both the hero signature and the adoption code
    // receipt render through this, so the two panels stay visually identical. `sectionData` is the element's
    // `data-section` hook; `body` is the `<pre><code>` of hand-tokenized `tok-*` spans.
    private def editorCard(sectionData: String)(body: UI)(using Frame): UI =
        UI.div.cssClass("code-card").data("section", sectionData)(
            UI.div.cssClass("code-bar")(
                UI.div.cssClass("code-dots")(
                    UI.span.cssClass("code-dot"),
                    UI.span.cssClass("code-dot"),
                    UI.span.cssClass("code-dot")
                ),
                UI.span.cssClass("code-lang")("SCALA")
            ),
            UI.div.cssClass("code")(body)
        )
    end editorCard

    // ---- 2. The gap ----

    private def gap(using Frame): UI =
        UI.section.cssClass("band").cssClass("problem").id("gap").data("section", "gap")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("gap-grid")(
                    UI.div.cssClass("gap-text")(
                        UI.div.cssClass("sec-head")(
                            UI.div.cssClass("eyebrow")("The gap"),
                            UI.h2("From \"it works\" to \"you can depend on it.\"")
                        ),
                        UI.p(
                            "The demo works. Then an error slips through unnoticed, a restart wipes the night's work, or a little real traffic shows up and the whole thing buckles. The distance between software that works once and software you can depend on is the gap most projects fall into, and the more of the code an AI wrote, the more the ground underneath has to hold."
                        )
                    ),
                    UI.div.cssClass("stat")(
                        failureChart,
                        UI.div.cssClass("stat-body")(
                            UI.div.cssClass("stat-cap")("Small errors compound."),
                            UI.div.cssClass("stat-txt")(
                                "Each step works 85% of the time, a model call, a tool, a flaky external API. Chain ten of them and the chance the whole run fails somewhere climbs to about four in five. That is why software that demos perfectly comes apart in real use."
                            )
                        )
                    )
                )
            )
        )
    end gap

    /** The compounding-failure chart for the gap stat, drawn entirely on the kyo-ui `Svg` DSL (no raw
      * markup). It plots the chance the whole run has failed somewhere after chaining n steps that each
      * work 85% of the time (`1 - 0.85^n`): about one in seven at a single step, climbing to four in five
      * by ten. The line climbs up and to the right into red, the visual argument the section makes, that
      * small per-step errors compound fast. Colors are CSS-variable paints, so the chart follows the light
      * and dark themes. The line draws itself in (a `stroke-dashoffset` CSS keyframe) the moment the chart
      * scrolls into view rather than on load, where the motion would play below the fold and be missed: the
      * bundle adds `.chart-drawn` to the `#gap-chart` wrapper via an IntersectionObserver. With scripting or
      * reduced-motion the line renders fully drawn (its inline dash base), so the chart is always complete.
      */
    private def failureChart(using Frame): UI =
        val n       = 10
        val failure = Chunk.from(1 to n).map(s => 1.0 - math.pow(0.85, s.toDouble))

        val w      = 340.0
        val h      = 152.0
        val left   = 16.0
        val right  = 16.0
        val top    = 26.0
        val bottom = 30.0
        val plotW  = w - left - right
        val plotH  = h - top - bottom
        val baseY  = top + plotH

        def xOf(i: Int): Double    = left + (i.toDouble / (n - 1).toDouble) * plotW
        def yOf(f: Double): Double = top + (1.0 - f) * plotH

        val pts = failure.zipWithIndex.map((f, i) => (xOf(i), yOf(f)))
        val linePath =
            pts.drop(1).foldLeft(Svg.PathData.from(pts.head._1, pts.head._2))((acc, p) => acc.lineTo(p._1, p._2))
        val areaPath = linePath.lineTo(pts.last._1, baseY).lineTo(pts.head._1, baseY).close

        val accent = Svg.Paint.Color(Style.Color.variable("accent"))
        val red    = Svg.Paint.Color(Style.Color.variable("red"))
        val dim    = Svg.Paint.Color(Style.Color.variable("dim"))
        val lineC  = Svg.Paint.Color(Style.Color.variable("line"))

        val baseline = Svg.line.x1(left).y1(baseY).x2(w - right).y2(baseY).stroke(lineC).strokeWidth(1.0)
        val area     = Svg.path.d(areaPath).fill(red).fillOpacity(0.12).stroke(Svg.Paint.None)
        // The line draws in when the chart scrolls into view, not on load: `pathLength=1` normalizes the
        // geometry so the CSS keyframe (`#gap-line` under the wrapper's `.chart-drawn`) tweens
        // stroke-dashoffset 1 (the dash shifted off, hidden) -> 0 (drawn) without knowing the real length.
        // The inline dasharray/offset render the line fully drawn by default, so it is present with the
        // scripting OR motion disabled; the bundle adds `.chart-drawn` via an IntersectionObserver.
        val line = Svg.path.d(linePath).id("gap-line").pathLength(1.0)
            .fill(Svg.Paint.None).stroke(red).strokeWidth(2.5)
            .strokeLinecap(Svg.StrokeLinecap.Round)
            .strokeDasharray(Seq(1.0)).strokeDashoffset(Svg.SvgLength.user(0.0))
        // The start is the brand accent (a single step still mostly works); the line climbs into red.
        val startDot = Svg.circle.cx(pts.head._1).cy(pts.head._2).r(4.0).fill(accent)
        val endDot   = Svg.circle.cx(pts.last._1).cy(pts.last._2).r(5.0).fill(red)
        val endLabel = Svg.text.x(pts.last._1).y(pts.last._2 - 10.0).textAnchor(Svg.TextAnchor.End)
            .fill(red).fontSize(Svg.SvgLength.px(15.0))("4 in 5")
        val caption = Svg.text.x(w / 2).y(h - 8).textAnchor(Svg.TextAnchor.Middle)
            .fill(dim).fontSize(Svg.SvgLength.px(11.0))("chance of failure, 1 to 10 chained steps")

        UI.div.cssClass("stat-chart").id("gap-chart")(
            Svg.svg.viewBox(Svg.ViewBox(0, 0, w, h)).width(w.toInt).height(h.toInt)(
                baseline,
                area,
                line,
                startDot,
                endDot,
                endLabel,
                caption
            )
        )
    end failureChart

    // ---- 3. The ladder ----

    private def ladder(mod: String => String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").cssClass("ladder").id("ladder").data("section", "ladder")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("Layered safety"),
                    UI.h2("As you write. As it compiles. As it runs. When it fails."),
                    UI.p(
                        "One foundation carries your code from the first line you write to a crash and back, and it catches mistakes earlier at every step: as you write, as it compiles, as it runs, and when it fails anyway. You opt into none of it. There are no conventions to follow, no annotations to add, and no cleanup to wire, because the safe path is the default the API hands you, whether a developer wrote the code or an AI agent generated it."
                    )
                ),
                UI.div.cssClass("rungs")(
                    rung(
                        1,
                        "As you write",
                        "Whole categories of bug never reach the keyboard.",
                        Seq(
                            UI.p(
                                "The shapes that break a program cannot be expressed in the first place, so you spend your time on logic instead of guarding against invalid states. The UI layer turns the wrong markup back right where you write it: an SVG primitive is not an HTML child, and the compiler says so before it reaches the screen."
                            ),
                            uiCode,
                            UI.p(
                                "The same check reaches the request: an auth filter does not compile until the route declares the header it reads, then hands the verified user to the next handler as a typed field."
                            )
                        ),
                        Seq(mod("kyo-ui") -> "kyo-ui · UI", mod("kyo-http") -> "kyo-http · Filter")
                    ),
                    rung(
                        2,
                        "As it compiles",
                        "The compiler catches what slipped past you.",
                        Seq(
                            UI.p("Every effect a piece of code uses, and every way it can fail, is part of its type:"),
                            chargeCode,
                            UI.p(
                                "This function can suspend, and it can be declined. Nothing else. Forget to handle that failure and it does not compile, because the unhandled effect stays in the type until you deal with it. Every rejection points at the exact line, so whoever wrote it, a person or a model, corrects it in a step instead of a rewrite."
                            )
                        ),
                        Seq(mod("kyo-prelude") -> "kyo-prelude · Abort", mod("kyo-core") -> "kyo-core · Sync")
                    ),
                    rung(
                        3,
                        "As it runs",
                        "The runtime steadies itself, so your code stays simple.",
                        Seq(
                            UI.p(
                                "Work started together is torn down together, and anything acquired in a scope, a connection, a file, a handle, is released exactly once: on success, on failure, and on interruption alike."
                            ),
                            UI.p(
                                "The scheduler keeps every task moving on a 10ms slice and catches a blocking call by watching it stop using the CPU, the socket reads and native I/O that still look like running threads to every other runtime. It sizes its own pool to the machine and sheds load by a stable per-user decision before the queue can OOM, and flaky calls retry on a policy and time out cleanly instead of hanging."
                            )
                        ),
                        Seq(
                            mod("kyo-core")      -> "kyo-core · Async",
                            mod("kyo-core")      -> "kyo-core · Scope",
                            mod("kyo-scheduler") -> "kyo-scheduler · Pool",
                            mod("kyo-core")      -> "kyo-core · Retry"
                        )
                    ),
                    rung(
                        4,
                        "When it fails anyway",
                        "A crash costs a restart, not the work.",
                        Seq(
                            UI.p(
                                "Long-running work records each step before the next begins, so the process can go down anywhere and resume from the last completed step, replaying nothing it already finished."
                            ),
                            UI.p(
                                "It does not even need the same machine: each run is held on a time-limited lease, so a stalled executor's work is claimed by another and carried forward. Even a one-hour sleep survives a restart, and a half-finished transaction unwinds itself in reverse. No separate workflow server to stand up; the durability is part of the same foundation as the rest."
                            )
                        ),
                        Seq(mod("kyo-flow") -> "kyo-flow")
                    )
                ),
                UI.div.cssClass("agent")(
                    UI.h3("Agents get all four layers, in both directions."),
                    UI.p(
                        "The code an AI writes is held by the same APIs and contracts as yours, and the agent it builds runs inside the same runtime and durability: a failed tool call is a typed error, a multi-step run is a durable workflow, a runaway loop is preempted on its time slice. Nothing about AI is a special case."
                    )
                ),
                UI.blockquote.cssClass("pull")("The same type system that checks your code checks your agents."),
                UI.p.cssClass("honest")(
                    "Kyo doesn't make bugs impossible. What it does is take the failure modes that wreck most projects off the table, and catch many of the rest before they ship."
                )
            )
        )
    end ladder

    /** One rung of the ladder: a left-column beat label, a bold lead, the body blocks, and the module
      * receipt tags. Stacks to a single column on narrow viewports (see WebsiteStyles `.rung`).
      */
    private def rung(num: Int, beat: String, lead: String, bodyBlocks: Seq[UI], tags: Seq[(String, String)])(using Frame): UI =
        val tagChips =
            UI.div.cssClass("tags")(html(tags.map((href, label) => UI.a.cssClass("tag").href(Href.Path(href))(label)))*)
        val blocks = UI.h3.cssClass("rung-lead")(lead) +: bodyBlocks :+ tagChips
        // The numbered node sits on the `.rungs` rail (left gutter), turning the four rungs into a visible
        // layered-safety ladder ordered by when each layer catches a mistake.
        UI.div.cssClass("rung")(
            UI.div.cssClass("rung-node")(num.toString),
            UI.div.cssClass("beat")(beat),
            UI.div.cssClass("rung-body")(html(blocks)*)
        )
    end rung

    // Highlighted code snippets, hand-tokenized with the same `tok-*` colors the docs highlighter uses
    // so the landing reads consistently with the documentation. No copy button (that is a docs affordance).
    private def uiCode(using Frame): UI =
        UI.div.cssClass("code")(
            UI.pre(
                UI.code(
                    tType("div"),
                    "(",
                    tType("span"),
                    "(",
                    tStr("\"ok\""),
                    "))         ",
                    tCom("// compiles"),
                    UI.br,
                    tType("div"),
                    "(",
                    tType("Svg"),
                    ".circle(...))   ",
                    tCom("// does not: an SVG primitive is not an HTML child")
                )
            )
        )

    private def chargeCode(using Frame): UI =
        UI.div.cssClass("code")(
            UI.pre(
                UI.code(
                    tKey("def"),
                    " charge(card: ",
                    tType("Card"),
                    ", amount: ",
                    tType("Money"),
                    "): ",
                    tType("Receipt"),
                    " ",
                    tOp("<"),
                    " (",
                    tType("Async"),
                    " ",
                    tOp("&"),
                    " ",
                    tType("Abort"),
                    "[",
                    tType("Declined"),
                    "])"
                )
            )
        )

    private def tKey(s: String)(using Frame): UI  = UI.span.cssClass("tok-keyword")(s)
    private def tType(s: String)(using Frame): UI = UI.span.cssClass("tok-type")(s)
    private def tStr(s: String)(using Frame): UI  = UI.span.cssClass("tok-string")(s)
    private def tCom(s: String)(using Frame): UI  = UI.span.cssClass("tok-comment")(s)
    private def tOp(s: String)(using Frame): UI   = UI.span.cssClass("tok-operator")(s)

    // ---- 4. One foundation ----

    private def oneFoundation(mod: String => String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("build").id("build").data("section", "one-foundation")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("One foundation"),
                    UI.h2("Everything you need, on the same ground."),
                    UI.p(
                        "Not a dozen libraries that each break in their own way. One import brings the whole vocabulary, and adding a module adds its types to that same import with nothing new to wire. Describe a type once with derives Schema and you get JSON, Protobuf, Ion, and YAML codecs, validation, typed field access, and a diff you can serialize, send, and replay, all from that one definition, so the parts you assemble already speak the same language instead of needing glue between them."
                    )
                ),
                UI.div.cssClass("feat-grid")(
                    fcat(
                        webIcon,
                        "Web",
                        mod("kyo-http"),
                        Seq(
                            "HTTP client and server, one codebase on JVM, Node, and native",
                            "Typed routes, WebSockets, SSE and NDJSON streaming, OpenAPI both ways",
                            "GraphQL, and web UIs as pure values: SPA, server-push, or SSR"
                        )
                    ),
                    fcat(
                        concurrencyIcon,
                        "Concurrency",
                        mod("kyo-actor"),
                        Seq(
                            "Fibers and structured concurrency that cannot leak",
                            "Channels, hubs, queues, typed actors, and STM",
                            "Bounded fan-out, so a huge job cannot exhaust the machine"
                        )
                    ),
                    fcat(
                        reliabilityIcon,
                        "Reliability",
                        mod("kyo-flow"),
                        Seq(
                            "Typed errors you cannot forget to handle, with retries and timeouts",
                            "Durable workflows that resume after a crash, with no server",
                            "An adaptive scheduler that detects blocking by watching the CPU"
                        )
                    ),
                    fcat(
                        dataIcon,
                        "Data",
                        mod("kyo-schema"),
                        Seq(
                            "One derive: JSON, Protobuf, Ion, YAML, validation, lenses, and diffs",
                            "Schema-evolution-safe Protobuf with no .proto files",
                            "Low-allocation data types, and off-heap memory as an effect"
                        )
                    ),
                    fcat(
                        operationsIcon,
                        "Operations",
                        mod("kyo-config"),
                        Seq(
                            "Config and feature flags with a percentage-rollout DSL",
                            "Zero-code OpenTelemetry export, metrics, and tracing",
                            "Cross-platform file IO, processes, and a Docker client"
                        )
                    )
                )
            )
        )
    end oneFoundation

    private def fcat(icon: UI, title: String, href: String, items: Seq[String])(using Frame): UI =
        UI.a.cssClass("fcat").href(Href.Path(href))(
            icon,
            UI.h4(title),
            UI.ul(html(items.map(s => UI.li(s)))*)
        )

    // ---- 4b. Keep what you already have (adoption) ----

    // The adoption band sits between the foundation and the platforms: it answers the objection the
    // foundation raises ("so do I rewrite everything?") with four concrete entry points. Each path is a
    // factual interop claim grounded in source: bidirectional ZIO and Cats Effect interop
    // (ZIOs.get/ZIOs.run, Cats.get/Cats.run, failure and interruption preserved both ways), the
    // drop-in scheduler (Scheduler.get.asExecutionContext, the standalone work-stealing pool under an
    // existing service), the standalone modules used as ordinary libraries with no effect system
    // (kyo-data's allocation-light types, one `derives Schema` for JSON/Protobuf/and more), and the
    // single `import kyo.*` for a fresh start. The code receipt shows ZIO and Kyo composing both ways:
    // ZIOs.get lifts a ZIO value into a Kyo row, and ZIOs.run turns a Kyo computation back into a ZIO.
    private def adopt(mod: String => String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("build").id("adopt").data("section", "adopt")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("Start where you are"),
                    UI.h2("Keep what you already have."),
                    UI.p(
                        "Adopting Kyo is not a rewrite. Bring it in next to the code you run today, one call at a time, and the two compose in a single program. Failures and cancellation cross the boundary in both directions, so nothing leaks when you mix them."
                    )
                ),
                UI.div.cssClass("paths")(
                    adoptPath(
                        "Already on ZIO or Cats Effect?",
                        "Call into Kyo from your effect type and run Kyo as either, both ways. Failure and interruption semantics are preserved across the boundary, so a cancel on one side cancels the other.",
                        Seq(mod("kyo-zio") -> "zio", mod("kyo-cats") -> "cats")
                    ),
                    adoptPath(
                        "Want only the runtime?",
                        "Install the work-stealing scheduler as the execution context your service already runs on, a drop-in for Cats Effect, ZIO, Pekko, or Finagle. Future-based code gets the same time-slicing and blocking detection underneath, on a pool that sizes itself to the machine.",
                        Seq(
                            mod("kyo-scheduler")         -> "scheduler",
                            mod("kyo-scheduler-cats")    -> "cats",
                            mod("kyo-scheduler-zio")     -> "zio",
                            mod("kyo-scheduler-pekko")   -> "pekko",
                            mod("kyo-scheduler-finagle") -> "finagle"
                        )
                    ),
                    adoptPath(
                        "Want no runtime?",
                        "Not every module needs the scheduler. kyo-prelude handles typed errors, environment, and state in place with no runtime, and kyo-data, kyo-schema, and kyo-parse are plain libraries.",
                        Seq(
                            mod("kyo-prelude") -> "prelude",
                            mod("kyo-data")    -> "data",
                            mod("kyo-schema")  -> "schema",
                            mod("kyo-parse")   -> "parse"
                        )
                    ),
                    adoptPath(
                        "Starting fresh?",
                        "The whole foundation is one import away. import kyo.* brings the entire vocabulary, and adding a module adds its types to that same import.",
                        Seq(
                            mod("kyo-core")  -> "core",
                            mod("kyo-http")  -> "http",
                            mod("kyo-actor") -> "actor",
                            mod("kyo-stm")   -> "stm"
                        )
                    )
                ),
                UI.div.cssClass("code-card-wrap")(adoptCode)
            )
        )
    end adopt

    private def adoptPath(title: String, body: String, tags: Seq[(String, String)])(using Frame): UI =
        UI.div.cssClass("path")(
            UI.h4(title),
            UI.p(body),
            UI.div.cssClass("tags")(
                tags.map((href, label) => UI.a.cssClass("tag").href(Href.Path(href))(label))*
            )
        )

    // A four-line interop receipt, rendered through the shared `editorCard` chrome so it reads as the same
    // polished editor panel as the hero signature card. Kyo composes with ZIO and Cats Effect BOTH ways:
    // `ZIOs.get` / `Cats.get` lift a foreign value into a Kyo effect row, and `ZIOs.run` / `Cats.run` turn a
    // Kyo computation back into a `ZIO[Any, E, A]` or a `cats.effect.IO[A]`. ZIO's typed error survives the
    // lift (`Abort[E]`); a cats.effect.IO carries no typed error, so its lift is just `Async`. The `=` column
    // is hand-aligned across the four rows; the leading-name and pre-`=` padding strings hold that alignment.
    private def adoptCode(using Frame): UI =
        editorCard("adopt-code")(
            UI.pre(
                UI.code(
                    tCom("// Kyo composes with ZIO and Cats Effect, both ways"),
                    UI.br,
                    UI.br,
                    // ZIO -> Kyo: the typed error E and Async land in the row
                    tKey("val"),
                    " fromZio:  ",
                    tType("User"),
                    " ",
                    tOp("<"),
                    " (",
                    tType("Abort"),
                    "[",
                    tType("E"),
                    "] ",
                    tOp("&"),
                    " ",
                    tType("Async"),
                    ")",
                    " ",
                    tOp("="),
                    " ",
                    tType("ZIOs"),
                    ".get(loadUser)",
                    UI.br,
                    // Kyo -> ZIO
                    tKey("val"),
                    " toZio:    ",
                    tType("ZIO"),
                    "[",
                    tType("Any"),
                    ", ",
                    tType("E"),
                    ", ",
                    tType("Receipt"),
                    "]",
                    "      ",
                    tOp("="),
                    " ",
                    tType("ZIOs"),
                    ".run(checkout)",
                    UI.br,
                    // Cats Effect -> Kyo: a cats.effect.IO has no typed error, so the lift is just Async
                    tKey("val"),
                    " fromCats: ",
                    tType("User"),
                    " ",
                    tOp("<"),
                    " ",
                    tType("Async"),
                    "              ",
                    tOp("="),
                    " ",
                    tType("Cats"),
                    ".get(cachedUser)",
                    UI.br,
                    // Kyo -> Cats Effect
                    tKey("val"),
                    " toCats:   ",
                    tType("IO"),
                    "[",
                    tType("Receipt"),
                    "]",
                    "               ",
                    tOp("="),
                    " ",
                    tType("Cats"),
                    ".run(checkout)"
                )
            )
        )

    // Category glyphs for the feature cards, drawn on the kyo-ui `Svg` DSL as a single-weight line set.
    // The 24-unit viewBox is rendered 1:1 (the `.fcat-ic svg` rule sizes it to 24px), so the scale factor
    // is exactly the device-pixel ratio and strokes land on the pixel grid (no fractional-scale blur). The
    // 2px (even) stroke keeps horizontal/vertical edges on whole pixels; `currentColor` themes to the accent.
    private def catIcon(parts: Svg.SvgChild*)(using Frame): UI =
        UI.span.cssClass("fcat-ic")(
            Svg.svg.viewBox(Svg.ViewBox(0, 0, 24, 24)).width(24).height(24)(
                Svg.g.fill(Svg.Paint.None).stroke(Svg.Paint.CurrentColor).strokeWidth(2.0)
                    .strokeLinecap(Svg.StrokeLinecap.Round).strokeLinejoin(Svg.StrokeLinejoin.Round)(parts*)
            )
        )

    // Web: a globe (outline + equator + meridian). The equator is a whole-y horizontal stroke.
    private def webIcon(using Frame): UI = catIcon(
        Svg.circle.cx(12).cy(12).r(8.5),
        Svg.line.x1(4).y1(12).x2(20).y2(12),
        Svg.ellipse.cx(12).cy(12).rx(4.0).ry(8.5)
    )

    // Concurrency: two rightward arrows running in parallel (whole-y shafts).
    private def concurrencyIcon(using Frame): UI = catIcon(
        Svg.line.x1(4).y1(8).x2(16).y2(8),
        Svg.polyline.points(Svg.Points((13.0, 5.0), (16.0, 8.0), (13.0, 11.0))),
        Svg.line.x1(8).y1(16).x2(20).y2(16),
        Svg.polyline.points(Svg.Points((17.0, 13.0), (20.0, 16.0), (17.0, 19.0)))
    )

    // Reliability: a shield with a check.
    private def reliabilityIcon(using Frame): UI = catIcon(
        Svg.path.d(
            Svg.PathData.from(12.0, 2.8).lineTo(19.0, 5.8).lineTo(19.0, 11.2)
                .quadTo(19.0, 17.0, 12.0, 20.6).quadTo(5.0, 17.0, 5.0, 11.2).lineTo(5.0, 5.8).close
        ),
        Svg.polyline.points(Svg.Points((8.8, 11.6), (11.0, 13.8), (15.0, 9.4)))
    )

    // Data: a database cylinder.
    private def dataIcon(using Frame): UI = catIcon(
        Svg.ellipse.cx(12).cy(6).rx(7.0).ry(2.8),
        Svg.path.d(Svg.PathData.from(5.0, 6.0).lineTo(5.0, 18.0).arcTo(7.0, 2.8, 0.0, false, false, 19.0, 18.0).lineTo(19.0, 6.0)),
        Svg.path.d(Svg.PathData.from(5.0, 12.0).arcTo(7.0, 2.8, 0.0, false, false, 19.0, 12.0))
    )

    // Operations: three sliders with knobs.
    private def operationsIcon(using Frame): UI =
        val knob = Svg.Paint.CurrentColor
        catIcon(
            Svg.line.x1(4).y1(6).x2(20).y2(6),
            Svg.circle.cx(15).cy(6).r(2.3).fill(knob),
            Svg.line.x1(4).y1(12).x2(20).y2(12),
            Svg.circle.cx(9).cy(12).r(2.3).fill(knob),
            Svg.line.x1(4).y1(18).x2(20).y2(18),
            Svg.circle.cx(15).cy(18).r(2.3).fill(knob)
        )
    end operationsIcon

    // ---- 5. Platforms and the engine ----

    private def platforms(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").id("platforms").data("section", "platforms")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("Platforms and the engine"),
                    UI.h2("One codebase. Four platforms. One process that carries the load."),
                    UI.p(
                        "Almost every module compiles to all four targets from one source, so the choice below is where your program runs, not which features you get. The same APIs come with it; only the runtime underneath changes."
                    )
                ),
                platformsSource,
                platformsConnect,
                UI.div.cssClass("pf-cards")(
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Multi-threaded"),
                        UI.div.cssClass("pf-n")("JVM"),
                        UI.p(
                            "The mature platform most production already runs on, where the work-stealing scheduler spreads fibers across every core. It has the widest library reach of the four."
                        )
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Browser and Node"),
                        UI.div.cssClass("pf-n")("JavaScript"),
                        UI.p(
                            "One source runs in the browser and on Node, so a web UI, an edge function, and a backend can share the same code and the same APIs."
                        )
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Instant startup"),
                        UI.div.cssClass("pf-n")("Native"),
                        UI.p(
                            "Compiles to a standalone binary that starts in milliseconds with no runtime to install, for command-line tools and long-running services alike."
                        )
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Near-native"),
                        UI.div.cssClass("pf-n")("WebAssembly"),
                        UI.p("The Scala.js WasmGC backend runs the same code at near-native speed, in the browser or any Wasm host.")
                    )
                ),
                UI.p.cssClass("floor")(
                    "And the engine under all of it: compiled code, an effect runtime built around allocation discipline, and an adaptive work-stealing scheduler that keeps every core fed. One process holds thousands of concurrent computations, where the default stack of the AI ecosystem scales by adding worker fleets. Latency and throughput per process are the serving bill, and the serving bill is what decides whether you can afford to run what you built."
                )
            )
        )
    end platforms

    // The "one codebase" SOURCE BOX: a small dark editor-style panel centered above the four platform
    // cards, carrying three muted code-glyph lines and a quiet "one codebase" label. It is the head of the
    // connector: the `platformsConnect` lines fan down from its center to the four cards. The band is always
    // dark, so the glyph strokes are fixed light values (not theme vars); the 120x46 viewBox renders ~1:1.
    private def platformsSource(using Frame): UI =
        val faint = Svg.Paint.Color(Style.Color.rgba(255, 255, 255, 0.32))
        val frame = Svg.Paint.Color(Style.Color.rgba(255, 255, 255, 0.18))
        val codeLines = Seq(64.0, 44.0, 54.0).zipWithIndex.map { (lineW, i) =>
            val ly = 16.0 + i * 9.0
            Svg.line.x1(16).y1(ly).x2(16.0 + lineW).y2(ly).stroke(faint).strokeWidth(2.0).strokeLinecap(Svg.StrokeLinecap.Round)
        }
        val panel = Svg.rect.x(1).y(1).width(118).height(44).rx(8.0).fill(Svg.Paint.None).stroke(frame).strokeWidth(1.5)
        UI.div.cssClass("pf-source")(
            UI.div.cssClass("pf-source-box")(
                Svg.svg.viewBox(Svg.ViewBox(0, 0, 120, 46)).width(120).height(46)(
                    (panel +: codeLines)*
                )
            ),
            UI.div.cssClass("pf-source-cap")("one codebase")
        )
    end platformsSource

    // The connector: a full-width SVG between the source box and the four platform cards, with one line
    // fanning from the box center down to each card center. The cards are four equal `flexBasis(220)` columns
    // with a 16px gap in the 1064px content row, so each card is 254px wide and their centers sit at x =
    // 127 / 397 / 667 / 937; the box centers at 532. The viewBox (1064 x 52) MATCHES the connector's rendered
    // box (the 1064px row x the 52px CSS height), so `preserveAspectRatio=none` stretches it at a ~1:1 scale:
    // strokes stay even and the landing dots stay round, while each endpoint still tracks its card center at
    // any width (both the dots and the cards scale with the row, so the x positions stay aligned).
    //
    // The lines DRAW themselves in when the platforms section scrolls into view (not on load, where the
    // motion would play below the fold), reusing the gap chart's exact mechanism: `pathLength=1` normalizes
    // each line so the shared `gapdraw` keyframe tweens stroke-dashoffset 1 (hidden) -> 0 (drawn) without
    // knowing the real length, and the keyframe is gated behind a `.chart-drawn` class on the `#pf-connect`
    // wrapper, the SAME class and observer the gap chart uses. The bundle's reveal wiring adds `.chart-drawn`
    // to a wrapper the first time it scrolls into view; pointing it at `#pf-connect` (alongside `#gap-chart`)
    // arms this draw. The inline dasharray/offset render the lines fully drawn by default, so the connector is
    // complete with scripting OR motion disabled, exactly like the gap chart.
    private def platformsConnect(using Frame): UI =
        val line    = Svg.Paint.Color(Style.Color.rgba(255, 255, 255, 0.22))
        val node    = Svg.Paint.Color(Style.Color.Hex("#9D97F0"))
        val srcX    = 532.0
        val targets = Seq(127.0, 397.0, 667.0, 937.0)
        val topY    = 3.0
        val botY    = 49.0
        val midY    = (topY + botY) / 2.0
        val fans = targets.zipWithIndex.map { (tx, i) =>
            // A vertical-tangent cubic: it leaves the box straight down, curves out across the middle band,
            // and arrives at the card straight down, so the four strokes fan symmetrically with no hard angle.
            val d = Svg.PathData.from(srcX, topY).cubicTo(srcX, midY, tx, midY, tx, botY)
            Svg.path.d(d).id(s"pf-line-$i").pathLength(1.0)
                .fill(Svg.Paint.None).stroke(line).strokeWidth(1.2)
                .strokeLinecap(Svg.StrokeLinecap.Round)
                .strokeDasharray(Seq(1.0)).strokeDashoffset(Svg.SvgLength.user(0.0))
        }
        val landings = targets.map(tx => Svg.circle.cx(tx).cy(botY).r(2.6).fill(node))
        UI.div.cssClass("pf-connect").id("pf-connect")(
            Svg.svg.viewBox(Svg.ViewBox(0, 0, 1064, 52)).preserveAspectRatio(Svg.PreserveAspectRatio(Svg.Align.None))(
                (fans ++ landings)*
            )
        )
    end platformsConnect

    // ---- 6. Why this exists ----

    private def whyExists(manifestoHref: String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("whyx").data("section", "why-exists")(
            UI.div.cssClass("wrap")(
                UI.p.cssClass("whyx-line")(
                    "This project exists because of a specific belief about what software is for and who gets to build it. ",
                    UI.a.cssClass("whyx-link").href(Href.Path(manifestoHref))("Read the manifesto"),
                    "."
                )
            )
        )
    end whyExists

    // ---- 8. Call to action + footer ----

    private def finalCta(home: String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").cssClass("cta-band").data("section", "final-cta")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("cta-final").cssClass("on-dark")(
                    UI.h2("Build something that holds."),
                    UI.div.cssClass("hero-cta")(
                        UI.a.cssClass("btn").cssClass("btn-primary").href(Href.Path(home))("Start building"),
                        UI.a
                            .cssClass("btn")
                            .href(Href.External("https", "//discord.gg/KxxkBbW8bq"))
                            .target(Target.Blank)("Join the community")
                    )
                )
            )
        )
    end finalCta

    // Canonical brand marks (Simple Icons, 24x24), embedded via the kyo-ui `Svg` DSL's raw-path escape and
    // filled with `currentColor` so they take the link color. Used to brand the GitHub and Discord links.
    private[website] val githubMark =
        "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
    private val discordMark =
        "M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"

    private[website] def brandGlyph(d: String)(using Frame): UI =
        UI.span.cssClass("brand-ic")(
            Svg.svg.viewBox(Svg.ViewBox(0, 0, 24, 24)).width(16).height(16)(
                Svg.path.d(Svg.PathData.raw(d)).fill(Svg.Paint.CurrentColor)
            )
        )

    private def pageFooter(home: String, manifestoHref: String)(using Frame): UI =
        UI.footer.data("section", "footer")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("foot")(
                    UI.div(
                        UI.a.cssClass("brand").href(Href.Fragment("top"))(
                            UI.img(ImgSrc.Path("/kyo.svg"), "Kyo").cssClass("mark"),
                            UI.span("kyo")
                        ),
                        UI.p.cssClass("note")(
                            "A foundation with layered safety, from the first line of code to crash recovery, so what you build holds up under real use, from the browser to the server."
                        )
                    ),
                    UI.div(
                        UI.h5("Docs"),
                        // The overview (`/<prefix>/`) renders the full root README; its headings carry slug
                        // anchors (`#getting-started`, `#modules`), so these link to real sections instead of
                        // three duplicate links to the same page or a "Modules" menu that no longer exists.
                        UI.a("Get started").href(Href.Path(s"${home}#getting-started")),
                        UI.a("Documentation").href(Href.Path(home)),
                        UI.a("Modules").href(Href.Path(s"${home}#modules")),
                        UI.a("API reference")
                            .href(Href.External("https", "//javadoc.io/doc/io.getkyo/kyo-core_3"))
                            .target(Target.Blank)
                    ),
                    UI.div(
                        UI.h5("Project"),
                        UI.a.cssClass("soc")(brandGlyph(githubMark), "GitHub")
                            .href(Href.External("https", "//github.com/getkyo/kyo"))
                            .target(Target.Blank),
                        UI.a("Releases")
                            .href(Href.External("https", "//github.com/getkyo/kyo/releases"))
                            .target(Target.Blank),
                        UI.a("Contributing")
                            .href(Href.External("https", "//github.com/getkyo/kyo/blob/main/CONTRIBUTING.md"))
                            .target(Target.Blank)
                    ),
                    UI.div(
                        UI.h5("Community"),
                        UI.a.cssClass("soc")(brandGlyph(discordMark), "Discord")
                            .href(Href.External("https", "//discord.gg/KxxkBbW8bq"))
                            .target(Target.Blank),
                        UI.a("Discussions")
                            .href(Href.External("https", "//github.com/getkyo/kyo/discussions"))
                            .target(Target.Blank),
                        UI.a("Manifesto").href(Href.Path(manifestoHref))
                    )
                ),
                UI.div.cssClass("foot-bottom")(
                    UI.span("© 2026 Kyo contributors · Apache-2.0"),
                    UI.span("Build something that holds.")
                )
            )
        )
    end pageFooter

end LandingApp
