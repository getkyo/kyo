// PUBLIC landing app view
package kyo.website

import kyo.*
import kyo.UI.Href
import kyo.UI.ImgSrc
import kyo.UI.Target
import scala.language.implicitConversions

/** The evergreen landing content as a kyo-ui `UI` value. The protagonist is the builder, not the AI:
  * the page leads with what Kyo makes possible (software that holds up under real use), explains the
  * mechanism second, and treats the AI workload as one case of the same foundation rather than the
  * identity. Sections, in order: hero, the gap, the ladder (the four-layer safety story with its
  * mechanism receipts and code), one foundation, platforms and the floor, social proof, why this
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
        Sync.defer {
            val prefix       = docsHome.split('/').iterator.filter(_.nonEmpty).nextOption().getOrElse("latest")
            val mod          = (slug: String) => s"/$prefix/$slug/"
            val overviewHome = s"/$prefix/"
            UI.div.cssClass("wrap").data("section", "page")(
                hero(overviewHome),
                gap,
                ladder(mod),
                oneFoundation(mod),
                platforms,
                socialProof,
                whyExists(mod("manifesto")),
                finalCta(overviewHome),
                pageFooter(overviewHome, mod("manifesto"))
            )
        }

    // Lift a dynamically-built `Seq[UI]` into the splat type an HTML container accepts (the implicit
    // UI -> HtmlChildVal conversion does not apply element-wise through a splat).
    private def html(cs: Seq[UI]): Seq[UI.Ast.HtmlChildVal] = cs.map(n => UI.Ast.HtmlChildVal.lift(n))

    // ---- 1. Hero ----

    private def hero(home: String)(using Frame): UI =
        UI.section.cssClass("hero").id("top").data("section", "hero")(
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
                        UI.div.cssClass("trust")(
                            UI.span("Open source"),
                            UI.span("·"),
                            UI.span("Apache-2.0"),
                            UI.span("·"),
                            UI.span("Scala 3"),
                            UI.span("·"),
                            UI.span("Server, browser, native, and Wasm")
                        )
                    ),
                    heroCode
                )
            )
        )
    end hero

    // A real, type-checked Kyo program in the hero: the pending type lists every effect, handlers
    // discharge them in any order, and `.eval` yields a Result. Same `tok-*` palette as the docs.
    //
    // Each line is its own `.hl` block so the lines can stagger in on load (WebsiteStyles `.hero-code .hl`
    // + the `heroline` keyframes), the final result-type comment arriving on a beat as the punchline. The
    // stagger delay is set per line; the animation itself is gated behind `prefers-reduced-motion:
    // no-preference`, so a reduced-motion (or no-CSS-animation) reader gets the full code, still, at once.
    private def heroLine(delayMs: Int)(content: UI.Ast.HtmlChildVal*)(using Frame): UI =
        UI.span.cssClass("hl").style(Style.animationDelay(delayMs))(content*)

    private def heroCode(using Frame): UI =
        // step 40ms + the result line's +120ms beat puts the last delay at 360ms; with the .hl animation's
        // 340ms duration the reveal ends at ~700ms, matched to the gap chart's 0.7s draw (they finish together).
        val step = 40
        UI.div.cssClass("code").cssClass("hero-code")(
            UI.pre(
                UI.code(
                    heroLine(step * 0)(tCom("// effects and failures are part of the type")),
                    heroLine(step * 1)(
                        tKey("val"),
                        " program: ",
                        tType("Int"),
                        " ",
                        tOp("<"),
                        " (",
                        tType("Abort"),
                        "[",
                        tType("String"),
                        "] ",
                        tOp("&"),
                        " ",
                        tType("Env"),
                        "[",
                        tType("Int"),
                        "]) ="
                    ),
                    heroLine(step * 2)("  ", tType("Env"), ".get[", tType("Int"), "].map(n =>"),
                    heroLine(step * 3)(
                        "    ",
                        tKey("if"),
                        " n > 0 ",
                        tKey("then"),
                        " n ",
                        tKey("else"),
                        " ",
                        tType("Abort"),
                        ".fail(",
                        tStr("\"oops\""),
                        "))"
                    ),
                    heroLine(step * 4)(" "),
                    heroLine(step * 5)(tCom("// handlers discharge them, in any order")),
                    heroLine(step * 6)(
                        "program.handle(",
                        tType("Abort"),
                        ".run, ",
                        tType("Env"),
                        ".run(",
                        tNum("10"),
                        ")).eval"
                    ),
                    // The result-type line lands on a longer beat, the payoff of the whole snippet.
                    heroLine(step * 6 + 120)(tCom("// Result[String, Int]"))
                )
            )
        )
    end heroCode

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
      * and dark themes; the line draws in with a SMIL `stroke-dashoffset` tween (browser-driven, no
      * JavaScript).
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
        val total =
            (1 until pts.length).foldLeft(0.0) { (acc, i) =>
                acc + math.hypot(pts(i)._1 - pts(i - 1)._1, pts(i)._2 - pts(i - 1)._2)
            }

        val accent = Svg.Paint.Color(Style.Color.variable("accent"))
        val red    = Svg.Paint.Color(Style.Color.variable("red"))
        val dim    = Svg.Paint.Color(Style.Color.variable("dim"))
        val lineC  = Svg.Paint.Color(Style.Color.variable("line"))

        val baseline = Svg.line.x1(left).y1(baseY).x2(w - right).y2(baseY).stroke(lineC).strokeWidth(1.0)
        val area     = Svg.path.d(areaPath).fill(red).fillOpacity(0.12).stroke(Svg.Paint.None)
        // fill=freeze holds the fully-drawn state when the tween ends; without it the SMIL default
        // (fill=remove) reverts stroke-dashoffset to its base (= total), erasing the line.
        val line = Svg.path.d(linePath).fill(Svg.Paint.None).stroke(red).strokeWidth(2.5)
            .strokeLinecap(Svg.StrokeLinecap.Round)
            .strokeDasharray(Seq(total, total)).strokeDashoffset(Svg.SvgLength.px(total))(
                Svg.animate.attributeName("stroke-dashoffset").from(total).to(0.0).dur("0.7s").begin("0s")
                    .repeatCount("1").fill(Svg.AnimFill.Freeze)
            )
        // The start is the brand accent (a single step still mostly works); the line climbs into red.
        val startDot = Svg.circle.cx(pts.head._1).cy(pts.head._2).r(4.0).fill(accent)
        val endDot   = Svg.circle.cx(pts.last._1).cy(pts.last._2).r(5.0).fill(red)
        val endLabel = Svg.text.x(pts.last._1).y(pts.last._2 - 10.0).textAnchor(Svg.TextAnchor.End)
            .fill(red).fontSize(Svg.SvgLength.px(15.0))("4 in 5")
        val caption = Svg.text.x(w / 2).y(h - 8).textAnchor(Svg.TextAnchor.Middle)
            .fill(dim).fontSize(Svg.SvgLength.px(11.0))("chance of failure, 1 to 10 chained steps")

        UI.div.cssClass("stat-chart")(
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
                        "Safety in four layers, ordered by when each one catches a mistake, and none of them ask you to remember anything. No conventions to follow, no annotations to add, no cleanup to wire. The safe behavior is the default, for code written by a developer or generated by an AI agent alike."
                    )
                ),
                UI.div.cssClass("rungs")(
                    rung(
                        "As you write",
                        "The APIs refuse invalid states.",
                        Seq(
                            UI.p("Broad categories of mistakes cannot be typed in. The UI layer rejects wrong HTML at the call site:"),
                            uiCode,
                            UI.p(
                                "The same discipline runs through the toolkit: CSS setters reject units that make no sense for the property, a single-consumer queue cannot be shared, resources are scope-managed unless you opt out, and the unsafe tier exists but demands a visible opt-in witness."
                            )
                        ),
                        Seq(mod("kyo-ui") -> "kyo-ui · UI", mod("kyo-core") -> "kyo-core · Queue", mod("kyo-core") -> "kyo-core · Scope")
                    ),
                    rung(
                        "As it compiles",
                        "The contracts catch what slipped through.",
                        Seq(
                            UI.p("Every effect a piece of code uses, and every way it can fail, is part of its type:"),
                            chargeCode,
                            UI.p(
                                "This function can suspend, and it can be declined. Nothing else. Forget to handle the failure and it does not compile. Every rejection points at the exact line, so whoever wrote it, a person or a model, corrects in a step instead of a rewrite."
                            )
                        ),
                        Seq(mod("kyo-prelude") -> "kyo-prelude · Abort")
                    ),
                    rung(
                        "As it runs",
                        "The runtime acts on its own.",
                        Seq(
                            UI.p(
                                "Work started together is tracked and torn down together, so running a model call, a tool, and a request in parallel does not leak fibers or race teardown. Anything acquired in a scope, a connection, a file, a handle, is released when the scope ends, including when something fails partway through."
                            ),
                            UI.p(
                                "The scheduler runs every task on a bounded time slice so no single task starves the others, detects blocking by sampling CPU time so blocking calls need no annotation, and watches its own latency to shed load before a traffic spike becomes an outage. Flaky calls retry on a policy you choose and time out cleanly instead of hanging."
                            )
                        ),
                        Seq(
                            mod("kyo-core")      -> "kyo-core · Async",
                            mod("kyo-core")      -> "kyo-core · Scope",
                            mod("kyo-scheduler") -> "kyo-scheduler",
                            mod("kyo-core")      -> "kyo-core · Retry"
                        )
                    ),
                    rung(
                        "When it fails anyway",
                        "Progress survives.",
                        Seq(
                            UI.p(
                                "Long-running work checkpoints its steps. The process goes down, comes back, and continues from the last completed step instead of starting over."
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
    private def rung(beat: String, lead: String, bodyBlocks: Seq[UI], tags: Seq[(String, String)])(using Frame): UI =
        val tagChips =
            UI.div.cssClass("tags")(html(tags.map((href, label) => UI.a.cssClass("tag").href(Href.Path(href))(label)))*)
        val blocks = UI.h3.cssClass("rung-lead")(lead) +: bodyBlocks :+ tagChips
        UI.div.cssClass("rung")(
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
                    tType("ul"),
                    "(",
                    tType("li"),
                    "(",
                    tStr("\"first\""),
                    "), ",
                    tType("li"),
                    "(",
                    tStr("\"second\""),
                    "))  ",
                    tCom("// compiles"),
                    UI.br,
                    tType("ul"),
                    "(",
                    tType("div"),
                    "(",
                    tStr("\"oops\""),
                    "))  ",
                    tCom("// does not: ul only accepts list items")
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
    private def tNum(s: String)(using Frame): UI  = UI.span.cssClass("tok-number")(s)

    // ---- 4. One foundation ----

    private def oneFoundation(mod: String => String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("build").id("build").data("section", "one-foundation")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("One foundation"),
                    UI.h2("Everything you need, on the same ground."),
                    UI.p(
                        "Not a dozen libraries that each break in their own way. One foundation, one set of rules, from the data layer to the browser, so the thing you build holds together, not just the parts."
                    )
                ),
                UI.div.cssClass("feat-grid")(
                    fcat(webIcon, "Web", mod("kyo-http"), Seq("HTTP services and clients", "Real-time and streaming", "Web frontends")),
                    fcat(
                        concurrencyIcon,
                        "Concurrency",
                        mod("kyo-actor"),
                        Seq("Thousands of tasks at once", "Channels and queues", "Parallel work, kept orderly")
                    ),
                    fcat(
                        reliabilityIcon,
                        "Reliability",
                        mod("kyo-flow"),
                        Seq("Typed errors and retries", "Durable workflows", "A self-tuning scheduler")
                    ),
                    fcat(
                        dataIcon,
                        "Data",
                        mod("kyo-schema"),
                        Seq("Typed data and schemas", "JSON, Protobuf, validation", "Transactional state")
                    ),
                    fcat(
                        operationsIcon,
                        "Operations",
                        mod("kyo-config"),
                        Seq("Config and feature flags", "Metrics and tracing", "Structured logging")
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

    // Category glyphs for the feature cards, drawn on the kyo-ui `Svg` DSL as a single-weight line set
    // (24x24, `currentColor` so the `.fcat-ic` rule themes them). No fills except the slider knobs.
    private def catIcon(parts: Svg.SvgChild*)(using Frame): UI =
        UI.span.cssClass("fcat-ic")(
            Svg.svg.viewBox(Svg.ViewBox(0, 0, 24, 24)).width(24).height(24)(
                Svg.g.fill(Svg.Paint.None).stroke(Svg.Paint.CurrentColor).strokeWidth(1.7)
                    .strokeLinecap(Svg.StrokeLinecap.Round).strokeLinejoin(Svg.StrokeLinejoin.Round)(parts*)
            )
        )

    // Web: a globe (outline + equator + meridian).
    private def webIcon(using Frame): UI = catIcon(
        Svg.circle.cx(12).cy(12).r(8.5),
        Svg.line.x1(3.5).y1(12).x2(20.5).y2(12),
        Svg.ellipse.cx(12).cy(12).rx(4.0).ry(8.5)
    )

    // Concurrency: two rightward arrows running in parallel.
    private def concurrencyIcon(using Frame): UI = catIcon(
        Svg.line.x1(4).y1(8.5).x2(16).y2(8.5),
        Svg.polyline.points(Svg.Points((13.0, 5.5), (16.5, 8.5), (13.0, 11.5))),
        Svg.line.x1(8).y1(15.5).x2(20).y2(15.5),
        Svg.polyline.points(Svg.Points((16.5, 12.5), (20.0, 15.5), (16.5, 18.5)))
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

    // ---- 5. Platforms and the floor ----

    private def platforms(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").id("platforms").data("section", "platforms")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("Platforms and the floor"),
                    UI.h2("One codebase. Four platforms. One process that carries the load.")
                ),
                UI.div.cssClass("pf-cards")(
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Servers and services"),
                        UI.div.cssClass("pf-n")("JVM"),
                        UI.p("Backends, APIs, and services, on the platform most production systems already run on.")
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Web and edge"),
                        UI.div.cssClass("pf-n")("JavaScript"),
                        UI.p("Web frontends in the browser, plus serverless and edge functions on Node, from the same code as the backend.")
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Command line"),
                        UI.div.cssClass("pf-n")("Native"),
                        UI.p("Command-line tools and binaries that start in milliseconds, with no runtime to install.")
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Browser, near-native"),
                        UI.div.cssClass("pf-n")("WebAssembly"),
                        UI.p("The Scala.js WasmGC backend on Node 24, from the same source as the rest.")
                    )
                ),
                UI.p.cssClass("floor")(
                    "And the floor under all of it: compiled code, an effect runtime built around allocation discipline, and an adaptive work-stealing scheduler that keeps every core fed. One process holds thousands of concurrent computations, where the default stack of the AI ecosystem scales by adding worker fleets. Latency and throughput per process are the serving bill, and the serving bill is what decides whether you can afford to run what you built."
                )
            )
        )
    end platforms

    // ---- 6. Social proof ----

    private def socialProof(using Frame): UI =
        UI.section.cssClass("band").cssClass("proof").data("section", "social-proof")(
            UI.div.cssClass("wrap")(
                UI.p.cssClass("proof-line")(
                    "Presented at Scalar, Lambda Days, LambdaConf, Functional Scala, ScalaIO, and Func Prog Sweden."
                )
            )
        )
    end socialProof

    // ---- 7. Why this exists ----

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
                        UI.a("Get started").href(Href.Path(home)),
                        UI.a("Documentation").href(Href.Path(home)),
                        UI.a("Modules").href(Href.Path(home)),
                        UI.a("API reference")
                            .href(Href.External("https", "//javadoc.io/doc/io.getkyo/kyo-core_3"))
                            .target(Target.Blank)
                    ),
                    UI.div(
                        UI.h5("Project"),
                        UI.a("GitHub")
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
                        UI.a("Discord")
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
