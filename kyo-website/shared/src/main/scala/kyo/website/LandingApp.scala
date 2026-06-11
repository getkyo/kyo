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
      *   The local docs home (`/<prefix>/<firstSlug>/`) that every in-body "Start building" call to
      *   action targets. Its first path segment is the active prefix (`latest` or a version tag), used
      *   to build the module and manifesto links.
      */
    def body(docsHome: String)(using Frame): UI < Sync =
        Sync.defer {
            val prefix = docsHome.split('/').iterator.filter(_.nonEmpty).nextOption().getOrElse("latest")
            val mod    = (slug: String) => s"/$prefix/$slug/"
            UI.div.cssClass("wrap").data("section", "page")(
                hero(docsHome),
                gap,
                ladder(mod),
                oneFoundation(mod),
                platforms,
                socialProof,
                whyExists(mod("manifesto")),
                finalCta(docsHome),
                pageFooter(docsHome, mod("manifesto"))
            )
        }

    // Lift a dynamically-built `Seq[UI]` into the splat type an HTML container accepts (the implicit
    // UI -> HtmlChildVal conversion does not apply element-wise through a splat).
    private def html(cs: Seq[UI]): Seq[UI.Ast.HtmlChildVal] = cs.map(n => UI.Ast.HtmlChildVal.lift(n))

    // ---- 1. Hero ----

    private def hero(docsHome: String)(using Frame): UI =
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
                            "Software is easier to write than it has ever been. Making it hold up under real use is the part that has not changed. Kyo is a foundation with layered safety, from the first line of code to crash recovery, so what you build survives errors, restarts, and real traffic."
                        ),
                        UI.div.cssClass("hero-cta")(
                            UI.a.cssClass("btn").cssClass("btn-primary").href(Href.Path(docsHome))("Start building"),
                            UI.a.cssClass("btn").href(Href.Fragment("ladder"))("How it works")
                        ),
                        UI.div.cssClass("trust")(
                            UI.span("Open source"),
                            UI.span("·"),
                            UI.span("Apache-2.0"),
                            UI.span("·"),
                            UI.span("Scala 3"),
                            UI.span("·"),
                            UI.span("Server, browser, and native")
                        )
                    ),
                    heroCode
                )
            )
        )
    end hero

    // A real, type-checked Kyo program in the hero: the pending type lists every effect, handlers
    // discharge them in any order, and `.eval` yields a Result. Same `tok-*` palette as the docs.
    private def heroCode(using Frame): UI =
        UI.div.cssClass("code").cssClass("hero-code")(
            UI.pre(
                UI.code(
                    tCom("// effects and failures are part of the type"),
                    UI.br,
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
                    "]) =",
                    UI.br,
                    "  ",
                    tType("Env"),
                    ".get[",
                    tType("Int"),
                    "].map(n =>",
                    UI.br,
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
                    "))",
                    UI.br,
                    UI.br,
                    tCom("// handlers discharge them, in any order"),
                    UI.br,
                    "program.handle(",
                    tType("Abort"),
                    ".run, ",
                    tType("Env"),
                    ".run(",
                    tNum("10"),
                    ")).eval",
                    UI.br,
                    tCom("// Result[String, Int]")
                )
            )
        )

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
                            "The demo works. Then an error slips through unnoticed, a restart wipes the night's work, or a little real traffic shows up and the whole thing buckles. The distance between software that works once and software you can depend on is the gap most projects fall into, and the less of the code you wrote yourself, the more the ground underneath has to hold."
                        )
                    ),
                    UI.div.cssClass("stat")(
                        UI.div.cssClass("big")("1 in 5"),
                        UI.div.cssClass("stat-txt")(
                            "Chain ten steps that each work 85% of the time, a model call, a tool, a flaky external API, and the whole run finishes correctly about one time in five. Small unreliabilities compound fast, which is why software that demos perfectly comes apart in real use."
                        )
                    )
                )
            )
        )
    end gap

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
                    fcat("Web", mod("kyo-http"), Seq("HTTP services and clients", "Real-time and streaming", "Web frontends")),
                    fcat(
                        "Concurrency",
                        mod("kyo-actor"),
                        Seq("Thousands of tasks at once", "Channels and queues", "Parallel work, kept orderly")
                    ),
                    fcat("Reliability", mod("kyo-flow"), Seq("Typed errors and retries", "Durable workflows", "A self-tuning scheduler")),
                    fcat("Data", mod("kyo-schema"), Seq("Typed data and schemas", "JSON, Protobuf, validation", "Transactional state")),
                    fcat("Operations", mod("kyo-config"), Seq("Config and feature flags", "Metrics and tracing", "Structured logging"))
                )
            )
        )
    end oneFoundation

    private def fcat(title: String, href: String, items: Seq[String])(using Frame): UI =
        UI.a.cssClass("fcat").href(Href.Path(href))(
            UI.h4(title),
            UI.ul(html(items.map(s => UI.li(s)))*)
        )

    // ---- 5. Platforms and the floor ----

    private def platforms(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").id("platforms").data("section", "platforms")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("Platforms and the floor"),
                    UI.h2("One codebase. Three platforms. One process that carries the load.")
                ),
                UI.div.cssClass("pf-cards")(
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Server"),
                        UI.div.cssClass("pf-n")("JVM"),
                        UI.p("Backends, APIs, and services, on the platform most production systems already run on.")
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("JavaScript"),
                        UI.div.cssClass("pf-n")("Browser and Node"),
                        UI.p("Web frontends in the browser, plus serverless and edge functions on Node, from the same code as the backend.")
                    ),
                    UI.div.cssClass("pf")(
                        UI.div.cssClass("pf-k")("Native"),
                        UI.div.cssClass("pf-n")("Native binary"),
                        UI.p("Command-line tools and binaries that start in milliseconds, with no runtime to install.")
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

    private def finalCta(docsHome: String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").cssClass("cta-band").data("section", "final-cta")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("cta-final").cssClass("on-dark")(
                    UI.h2("Build something that holds."),
                    UI.div.cssClass("hero-cta")(
                        UI.a.cssClass("btn").cssClass("btn-primary").href(Href.Path(docsHome))("Start building"),
                        UI.a
                            .cssClass("btn")
                            .href(Href.External("https", "//discord.gg/KxxkBbW8bq"))
                            .target(Target.Blank)("Join the community")
                    )
                )
            )
        )
    end finalCta

    private def pageFooter(docsHome: String, manifestoHref: String)(using Frame): UI =
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
                        UI.a("Get started").href(Href.Path(docsHome)),
                        UI.a("Documentation").href(Href.Path(docsHome)),
                        UI.a("Modules").href(Href.Path(docsHome)),
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
