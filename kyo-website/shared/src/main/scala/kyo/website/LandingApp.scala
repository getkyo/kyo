// PUBLIC landing app view
package kyo.website

import kyo.*
import kyo.UI.Href
import kyo.UI.ImgSrc
import kyo.UI.Target

/** The evergreen marketing landing content as a kyo-ui `UI` value, structured into the mock's
  * sections: hero, problem+stat, promise, outcomes grid, "built for AI", one-foundation +
  * platforms band, depth, final CTA, footer. This is the content body ONLY: the persistent header
  * is owned by `SiteApp` (D5), so `body` no longer renders its own header. Renders via `runRender`
  * (SSG) and `runMount` (interactivity) unchanged. Elements opt into the `WebsiteStyles.sheet`
  * rules via `UI.cssClass(...)` hooks (D5), with per-element one-offs via `.style(Style)`; both
  * coexist. No raw CSS. No raw HTML.
  *
  * In-body calls to action and footer documentation links target the local docs home
  * (`Href.Path(docsHome)`), client-routed by `UILocation` with no reload; genuinely external links
  * (GitHub, Discord, releases, the javadoc API, the Community `getkyo.io` identity link) stay
  * external (D2).
  */
object LandingApp:

    /** The landing content body (header excluded, owned by `SiteApp`).
      *
      * @param versions
      *   The list of available documentation versions. Retained for a uniform call shape with the
      *   SSG generator and the bundle; the header dropdown is now populated by `SiteApp`, not here.
      * @param docsHome
      *   The local docs home (`/<prefix>/<firstSlug>/`) every in-body "Start building" / "Get
      *   started" / "Documentation" / "Modules" / "Read the technical docs" call to action targets.
      * @return
      *   A `UI < Sync` value representing the landing content body.
      */
    def body(versions: Chunk[WebsiteVersion], docsHome: String)(using Frame): UI < Sync =
        Sync.defer {
            UI.div.cssClass("wrap").data("section", "page")(
                hero(docsHome),
                problem,
                promise,
                outcomes,
                builtForAI,
                oneFoundation,
                depth(docsHome),
                finalCta(docsHome),
                pageFooter(docsHome)
            )
        }

    // ---- Private section helpers ----

    private def hero(docsHome: String)(using Frame): UI =
        UI.section.cssClass("hero").id("top").data("section", "hero")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("eyebrow")("The reliability layer for AI"),
                UI.h1(
                    "Build with AI.",
                    UI.br,
                    "Ship something that ",
                    UI.span.cssClass("accent").cssClass("serif")("holds"),
                    "."
                ),
                UI.p.cssClass("lead")(
                    "AI can write the code. Making it reliable enough to depend on is the harder part. Kyo is the foundation that takes that on, so what you and your AI build holds up under real-world use."
                ),
                UI.div.cssClass("hero-cta")(
                    UI.a
                        .cssClass("btn")
                        .cssClass("btn-primary")
                        .href(Href.Path(docsHome))("Start building"),
                    UI.a
                        .cssClass("btn")
                        .href(Href.Fragment("how"))("See how it works")
                ),
                UI.div.cssClass("trust")(
                    UI.span("Open source"),
                    UI.span("One codebase: server, browser, and native"),
                    UI.span("Apache-2.0")
                )
            )
        )
    end hero

    private def problem(using Frame): UI =
        UI.section.cssClass("band").cssClass("problem").id("problem").data("section", "problem")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("The real gap"),
                    UI.h2("AI has the capability. It's missing the reliability.")
                ),
                UI.p(
                    "Give it enough tries, and AI can build almost anything. Making it hold up is another matter entirely. The demo works. Then an error slips through unnoticed, a restart wipes your work, or a little real traffic shows up and the whole thing buckles. And whoever built it often can't tell why. That distance, from something that works once to something you can depend on, is the gap Kyo closes."
                ),
                UI.div.cssClass("stat")(
                    UI.div.cssClass("big")("~20", UI.span("%")),
                    UI.div.cssClass("stat-txt")(
                        "Chain ten steps that each work 85% of the time, and the whole thing finishes correctly only about one in five. Small unreliabilities compound fast, which is why software that demos perfectly comes apart in real use."
                    )
                )
            )
        )
    end problem

    private def promise(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").cssClass("promise").data("section", "promise")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head")(
                    UI.div.cssClass("eyebrow")("The idea"),
                    UI.h2("A foundation that catches what you can't."),
                    UI.p(
                        "The less of it you wrote yourself, the more the ground underneath has to hold. Kyo is built to catch the failures that most often sink AI-built software, on your behalf. You focus on what you want to make. The foundation takes care of the parts that usually break."
                    )
                ),
                UI.div.cssClass("two")(
                    UI.div.cssClass("col")(
                        UI.div.cssClass("k")("AI writes it"),
                        UI.h3("Predictable enough for a machine to get right."),
                        UI.p(
                            "Everything in Kyo follows one shape and one vocabulary. Learn one part and the rest is predictable, which is exactly what lets an AI generate it correctly, again and again, instead of guessing."
                        )
                    ),
                    UI.div.cssClass("col")(
                        UI.div.cssClass("k")("The foundation holds it"),
                        UI.h3("Reliable enough to run for real."),
                        UI.p(
                            "AI-written code doesn't know how to survive a failure, a restart, or a crowd. The foundation underneath does. Capability from the model, reliability from Kyo: together they make something you can actually run."
                        )
                    )
                )
            )
        )
    end promise

    private def outcomes(using Frame): UI =
        UI.section.cssClass("band").data("section", "outcomes")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("What's doing the work"),
                    UI.h2("How the foundation earns that trust."),
                    UI.p("No hand-waving. Each one is a real mechanism, with the part of Kyo behind it.")
                ),
                UI.div.cssClass("grid")(
                    UI.div.cssClass("cell").data("module", "kyo-prelude")(
                        UI.h3("Failures don't stay hidden"),
                        UI.p(
                            "Errors are part of the contract, not an afterthought. The kind of failure that untyped code swallows silently has to be dealt with here, so it surfaces where you can see it instead of hiding until a user hits it."
                        ),
                        UI.div.cssClass("by")("kyo-prelude ", UI.span("·"), " Abort")
                    ),
                    UI.div.cssClass("cell").data("module", "kyo-flow")(
                        UI.h3("It can resume after a restart"),
                        UI.p(
                            "Build a long-running task on the durable workflow engine and it checkpoints its progress. If the process goes down, it comes back and continues from the last completed step instead of starting over."
                        ),
                        UI.div.cssClass("by")("kyo-flow")
                    ),
                    UI.div.cssClass("cell").data("module", "kyo-core")(
                        UI.h3("Many things at once, kept orderly"),
                        UI.p(
                            "Concurrency is structured: work started together is tracked and torn down together. Running a model call, a tool, and a request at the same time avoids the leaks and race conditions that usually come with doing things in parallel."
                        ),
                        UI.div.cssClass("by")("kyo-core ", UI.span("·"), " Async")
                    ),
                    UI.div.cssClass("cell").data("module", "kyo-core-scope")(
                        UI.h3("Resources get released, even on failure"),
                        UI.p(
                            "Anything acquired inside a scope, a connection, a file, a handle, is released when that scope ends, including when something fails partway through. That closes off the most common source of slow resource leaks."
                        ),
                        UI.div.cssClass("by")("kyo-core ", UI.span("·"), " Scope")
                    ),
                    UI.div.cssClass("cell").data("module", "kyo-scheduler")(
                        UI.h3("It sheds load before it breaks"),
                        UI.p(
                            "The runtime scheduler watches its own latency and starts turning away work when it can't keep up, so a traffic spike degrades gracefully instead of cascading into an outage."
                        ),
                        UI.div.cssClass("by")("kyo-scheduler")
                    ),
                    UI.div.cssClass("cell").data("module", "kyo-core-retry")(
                        UI.h3("Flaky calls don't take you down"),
                        UI.p(
                            "Model and tool calls fail, time out, and hit rate limits constantly. Kyo retries them on a policy you choose and times out cleanly instead of hanging, so one bad call doesn't sink the whole run."
                        ),
                        UI.div.cssClass("by")("kyo-core ", UI.span("·"), " Retry")
                    )
                ),
                UI.p.cssClass("honest")(
                    "Kyo doesn't make bugs impossible. What it does is take the failure modes that wreck most AI projects off the table, and catch many of the rest before they ship. Not flawless, but far steadier than generated code usually is."
                )
            )
        )
    end outcomes

    private def builtForAI(using Frame): UI =
        UI.section.cssClass("band").data("section", "built-for-ai")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head").cssClass("center")(
                    UI.div.cssClass("eyebrow")("Built for AI to write"),
                    UI.h2("Every capability, spelled out for the model."),
                    UI.p(
                        "An AI generates reliable code when it knows exactly what is expected. In Kyo, every capability a piece of code uses, reaching the network, failing, holding state, calling a tool, is written into its contract. The model generates against that precise specification instead of guessing, and the foundation enforces it: a capability the contract doesn't grant, or a failure left unhandled, won't compile. The mistakes models typically make are caught before the code ever runs."
                    )
                ),
                UI.div.cssClass("ctrl-row")(
                    UI.div.cssClass("ctrl")(
                        UI.h4("A precise target to write to"),
                        UI.p(
                            "The contract names exactly what each piece of code may do, so the model aims at a clear specification instead of improvising."
                        )
                    ),
                    UI.div.cssClass("ctrl")(
                        UI.h4("Enforced, not suggested"),
                        UI.p(
                            "Reach for a capability the contract doesn't grant, or skip a failure path, and it's rejected on the spot. The model can't drift off-spec unnoticed."
                        )
                    ),
                    UI.div.cssClass("ctrl")(
                        UI.h4("Fewer tries to get there"),
                        UI.p(
                            "Every rejection points at the exact line, so the model corrects in a step or two instead of flailing across rewrites."
                        )
                    )
                )
            )
        )
    end builtForAI

    private def oneFoundation(using Frame): UI =
        UI.section.cssClass("band").cssClass("build").id("build").data("section", "one-foundation")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("sec-head")(
                    UI.div.cssClass("eyebrow")("One foundation"),
                    UI.h2("Everything you need, on the same ground."),
                    UI.p(
                        "Not a dozen libraries that each break in their own way. One foundation, one set of rules, from the data layer to the browser, so the thing you build holds together, not just the parts."
                    )
                ),
                UI.div.cssClass("feat-grid")(
                    UI.div.cssClass("fcat")(
                        UI.h4("Web and APIs"),
                        UI.ul(
                            UI.li("HTTP services and clients"),
                            UI.li("Real-time and streaming"),
                            UI.li("Web frontends")
                        )
                    ),
                    UI.div.cssClass("fcat")(
                        UI.h4("AI and agents"),
                        UI.ul(
                            UI.li("Calls to language models"),
                            UI.li("Tools a model can use"),
                            UI.li("Multi-step agent workflows")
                        )
                    ),
                    UI.div.cssClass("fcat")(
                        UI.h4("Concurrency"),
                        UI.ul(
                            UI.li("Thousands of tasks at once"),
                            UI.li("Channels and queues"),
                            UI.li("Parallel work, kept orderly")
                        )
                    ),
                    UI.div.cssClass("fcat")(
                        UI.h4("Reliability"),
                        UI.ul(
                            UI.li("Typed errors and retries"),
                            UI.li("Durable workflows"),
                            UI.li("A self-tuning scheduler")
                        )
                    ),
                    UI.div.cssClass("fcat")(
                        UI.h4("Data"),
                        UI.ul(
                            UI.li("Typed data and schemas"),
                            UI.li("JSON, Protobuf, validation"),
                            UI.li("Transactional state")
                        )
                    ),
                    UI.div.cssClass("fcat")(
                        UI.h4("Config and visibility"),
                        UI.ul(
                            UI.li("Config and feature flags"),
                            UI.li("Metrics and tracing"),
                            UI.li("Structured logging")
                        )
                    )
                ),
                UI.div.cssClass("platforms")(
                    UI.div.cssClass("pf-head")(
                        UI.h3("One codebase. Three platforms."),
                        UI.p(
                            "One project, and one skill set, reaches your backend, the browser, serverless and edge, and native binaries, with no rewrite in between."
                        )
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
                            UI.p(
                                "Web frontends in the browser, plus serverless and edge functions on Node, from the same code as the backend."
                            )
                        ),
                        UI.div.cssClass("pf")(
                            UI.div.cssClass("pf-k")("Native"),
                            UI.div.cssClass("pf-n")("Native binary"),
                            UI.p("Fast-starting command-line tools and binaries, with no runtime to install.")
                        )
                    )
                )
            )
        )
    end oneFoundation

    private def depth(docsHome: String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("depth").id("how").data("section", "depth")(
            UI.div.cssClass("wrap").cssClass("inner")(
                UI.div.cssClass("sec-head")(
                    UI.div.cssClass("eyebrow")("For the curious"),
                    UI.h2("What's underneath.")
                ),
                UI.div(
                    UI.p(
                        "Kyo is a foundation built on algebraic effects: every capability a piece of code uses, and every way it can fail, is tracked precisely and handled deliberately. That tracking is why the foundation can catch what it catches, and why an AI can target it so reliably."
                    ),
                    UI.p(
                        "It is a real, production-grade runtime, not a thin wrapper. One composable model describes everything from a single tool call to a multi-step durable workflow, with typed failures, structured concurrency, and a self-protecting scheduler underneath, and it fits alongside the tools teams already use."
                    ),
                    UI.p(
                        "This is not another framework. Algebraic effects are the frontier programming-language research has been converging on for over a decade: the same idea drives research languages like Koka and Eff, landed in OCaml in 2022, and is the direction Scala's own type system is taking. Most tools for building with AI are glue code over an API. Kyo brings that frontier into production, on a runtime you can ship today, instead of leaving it in papers and experimental compilers."
                    ),
                    UI.div.cssClass("plat")(
                        UI.span.cssClass("t")("JVM"),
                        UI.span.cssClass("t")("JavaScript"),
                        UI.span.cssClass("t")("Native"),
                        UI.span.cssClass("t")("Scala 3"),
                        UI.span.cssClass("t")("interops with ZIO & Cats Effect")
                    ),
                    UI.div.cssClass("links")(
                        UI.a("Read the technical docs")
                            .href(Href.Path(docsHome)),
                        UI.a("Explore the source")
                            .href(Href.External("https", "//github.com/getkyo/kyo"))
                            .target(Target.Blank)
                    )
                )
            )
        )
    end depth

    private def finalCta(docsHome: String)(using Frame): UI =
        UI.section.cssClass("band").cssClass("dark").cssClass("promise").data("section", "final-cta")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("cta-final").cssClass("on-dark")(
                    UI.h2("Build something that holds."),
                    UI.div.cssClass("hero-cta")(
                        UI.a
                            .cssClass("btn")
                            .cssClass("btn-primary")
                            .href(Href.Path(docsHome))("Start building"),
                        UI.a
                            .cssClass("btn")
                            .href(Href.External("https", "//discord.gg/KxxkBbW8bq"))
                            .target(Target.Blank)("Join the community")
                    )
                )
            )
        )
    end finalCta

    private def pageFooter(docsHome: String)(using Frame): UI =
        UI.footer.data("section", "footer")(
            UI.div.cssClass("wrap")(
                UI.div.cssClass("foot")(
                    UI.div(
                        UI.a.cssClass("brand").href(Href.Fragment("top"))(
                            UI.img(ImgSrc.Path("/kyo.png"), "Kyo").cssClass("mark"),
                            UI.span("kyo")
                        ),
                        UI.p.cssClass("note")(
                            "The foundation that makes AI-built software hold up. Build it with AI, ship something that lasts, from the browser to the server."
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
                        UI.a("getkyo.io").href(Href.External("https", "//getkyo.io"))
                    )
                ),
                UI.div.cssClass("foot-bottom")(
                    UI.span("© 2026 Kyo contributors · Apache-2.0"),
                    UI.span("Build with AI. Ship something that holds.")
                )
            )
        )
    end pageFooter

end LandingApp
