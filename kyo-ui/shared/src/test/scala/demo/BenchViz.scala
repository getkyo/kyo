package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.Ast.HtmlContent

/** Benchmark-runs viewer, served as a server-push HTML-over-SSE app with `UI.runHandlers`.
  *
  * A clean reimplementation of `docs/dev/bench/index.html` (which was a one-off hack). The original is gist-driven, but the benchmark
  * workflow now publishes to GitHub Releases, so this points at real, still-live sources: the `bench-cafc6d6` release (JMH JSON +
  * flamegraph HTMLs) and surviving `fwbrasil` result gists. Only the feature set is reproduced, not the original's implementation.
  *
  * Run via `sbt 'kyo-ui/runMain demo.BenchViz'` (optional port as the first argument).
  *
  * Features (mirrors the original):
  *   - Two-pane layout: a scrollable run list on the left, a details iframe on the right.
  *   - A run table with Commit / Message / Profiling / Gist / Run columns and external links.
  *   - Multi-select rows: clicking a row toggles it (highlighted); the selected runs drive the right pane, which loads the
  *     `jmh.morethan.io` visualizer with each selected run's JMH result JSON as a source.
  *   - "Show URL" reveals the current iframe source so it can be copied (the server-push equivalent of the original's clipboard copy;
  *     a pure clipboard write needs client JS, which this transport intentionally avoids).
  *   - A flamegraph modal opened from "View": lists the run's benchmarks, toggles CPU / Alloc, and "Open" loads the chosen flamegraph
  *     HTML into the iframe.
  */
object BenchViz extends KyoApp:

    /** A profiled benchmark with its CPU and allocation flamegraph URLs. */
    case class Bench(label: String, cpuUrl: String, allocUrl: String) derives CanEqual

    /** One benchmark run: a commit, links, the JMH result JSON used as a visualizer source, and any flamegraphs. */
    case class Run(
        sha: String,
        message: String,
        commitUrl: String,
        gistUrl: String,
        runUrl: String,
        jsonUrl: String,
        benches: Chunk[Bench]
    ) derives CanEqual

    private def gistRaw(user: String, gist: String, file: String): String =
        s"https://gist.githubusercontent.com/$user/$gist/raw/$file"

    private val gist5b21637 = "b7def453643bf07ab12cb43d9fb076b7"
    private val gist3a94bff = "93372315f57a503b3e97c4062aa7abce"

    /** Real, verified-live sources: surviving result gists carrying both the JMH JSON (the visualizer source) and the flamegraph HTMLs.
      * Gist raw is fetchable by `htmlpreview`, so the flamegraphs render (the `bench-cafc6d6` release assets are not, being CORS-blocked
      * and served as attachments, which is why an earlier release-based version showed blank profiling).
      */
    private val runs: Chunk[Run] = Chunk(
        Run(
            "5b21637",
            "BroadFlatMap forkKyo (cpu + alloc flamegraphs)",
            "https://github.com/getkyo/kyo/commit/5b21637",
            s"https://gist.github.com/fwbrasil/$gist5b21637",
            "https://github.com/getkyo/kyo/actions",
            gistRaw("fwbrasil", gist5b21637, "5b21637-jmh-result.json"),
            Chunk(
                Bench(
                    "BroadFlatMapBench.forkKyo",
                    gistRaw("fwbrasil", gist5b21637, "BroadFlatMapBench-forkKyo-cpu.html"),
                    gistRaw("fwbrasil", gist5b21637, "BroadFlatMapBench-forkKyo-alloc.html")
                )
            )
        ),
        Run(
            "3a94bff",
            "earlier run (forkCats alloc flamegraph)",
            "https://github.com/getkyo/kyo/commit/3a94bff",
            s"https://gist.github.com/fwbrasil/$gist3a94bff",
            "https://github.com/getkyo/kyo/actions",
            gistRaw("fwbrasil", gist3a94bff, "3a94bff-jmh-result.json"),
            Chunk(
                // Only an alloc flamegraph was published for this run; CPU falls back to it.
                Bench(
                    "BroadFlatMapBench.forkCats",
                    gistRaw("fwbrasil", gist3a94bff, "BroadFlatMapBench-forkCats-alloc.html"),
                    gistRaw("fwbrasil", gist3a94bff, "BroadFlatMapBench-forkCats-alloc.html")
                )
            )
        )
    )

    private def visualizerUrl(selected: Set[String]): String =
        val sources = runs.filter(r => selected.contains(r.sha)).map(_.jsonUrl)
        if sources.isEmpty then "about:blank"
        else s"https://jmh.morethan.io/?sources=${sources.mkString(",")}"
    end visualizerUrl

    // ---- styles ----
    private val gray   = Color.rgb(245, 245, 245)
    private val rule   = Color.rgb(221, 221, 221)
    private val accent = Color.rgb(0, 123, 255)

    private val pageStyle    = Style.row.flexGrow(1)
    private val leftStyle    = Style.column.flexGrow(1).flexBasis(0.px).overflow(_.auto).padding(16.px).bg(gray).gap(8.px)
    private val rightStyle   = Style.column.flexGrow(3).flexBasis(0.px).padding(16.px)
    private val iframeStyle  = Style.width(100.pct).height(100.pct).border(1.px, rule)
    private val tableStyle   = Style.fontSize(13.px)
    private val thStyle      = Style.border(1.px, rule).padding(5.px).fontWeight(_.bold)
    private val tdStyle      = Style.border(1.px, rule).padding(5.px)
    private val rowStyle     = Style.cursor(_.pointer)
    private val rowSelStyle  = Style.cursor(_.pointer).bg(rule)
    private val linkStyle    = Style.color(accent)
    private val btnStyle     = Style.padding(6.px, 12.px).bg(accent).color(_.white).border(0.px, accent).cursor(_.pointer)
    private val btnOffStyle  = Style.padding(6.px, 12.px).bg(gray).color(_.black).border(1.px, rule).cursor(_.pointer)
    private val overlayStyle = Style.position(Position.overlay).bg(Color.rgba(0, 0, 0, 0.4)).align(_.center).justify(_.center)
    private val cardStyle    = Style.column.bg(_.white).padding(20.px).width(440.px).gap(12.px).border(1.px, rule)
    private val headerStyle  = Style.row.justify(_.spaceBetween).align(_.center).gap(12.px)
    private val urlBoxStyle  = Style.padding(6.px).bg(_.white).border(1.px, rule).fontSize(12.px)

    private def extLink(text: String, url: String): HtmlContent =
        UI.a.href(Href.Path(url)).style(linkStyle)(text)

    private[demo] def app: UI < Async =
        for
            selected  <- Signal.initRef(Set("5b21637"))
            iframeUrl <- Signal.initRef(visualizerUrl(Set("5b21637")))
            dialog    <- Signal.initRef(Maybe.empty[String])
            ftype     <- Signal.initRef("cpu")
            showUrl   <- Signal.initRef(false)

            toggle = (sha: String) =>
                for
                    cur <- selected.get
                    next = if cur.contains(sha) then cur - sha else cur + sha
                    _ <- selected.set(next)
                    _ <- iframeUrl.set(visualizerUrl(next))
                yield ()

            openFlame = (b: Bench) =>
                for
                    t <- ftype.get
                    raw = if t == "cpu" then b.cpuUrl else b.allocUrl
                    // Release-hosted HTML is served as an attachment; render it through htmlpreview like the original page did.
                    _ <- iframeUrl.set(s"https://htmlpreview.github.io/?${java.net.URLEncoder.encode(raw, "UTF-8")}")
                    _ <- dialog.set(Absent)
                yield ()

            // Whole run table as one reactive region: rebuilt when the selection changes so the highlight tracks it.
            tableRegion = selected.map { sel =>
                val header = UI.tr(
                    UI.th("Commit").style(thStyle),
                    UI.th("Message").style(thStyle),
                    UI.th("Profiling").style(thStyle),
                    UI.th("Gist").style(thStyle),
                    UI.th("Run").style(thStyle)
                )
                val rows = runs.map { r =>
                    UI.tr.id(s"row-${r.sha}").style(if sel.contains(r.sha) then rowSelStyle else rowStyle).onClick(toggle(r.sha))(
                        UI.td(extLink(r.sha, r.commitUrl)).style(tdStyle),
                        UI.td(r.message).id(s"msg-${r.sha}").style(tdStyle),
                        UI.td(
                            UI.a.id(s"view-${r.sha}").href(Href.Fragment("")).style(linkStyle).onClick(dialog.set(Present(r.sha)))("View")
                        ).style(tdStyle),
                        UI.td(extLink(r.gistUrl.split("/").last.take(7), r.gistUrl)).style(tdStyle),
                        UI.td(extLink(r.runUrl.split("/").last, r.runUrl)).style(tdStyle)
                    )
                }
                UI.table.style(tableStyle)((header +: rows).map(UI.Ast.HtmlChildVal.lift(_))*)
            }

            // Flamegraph modal: present only when a run is chosen via "View".
            dialogUI = dialog.map {
                case Present(sha) =>
                    val run = runs.find(_.sha == sha).getOrElse(runs.head)
                    val typeToggle = ftype.map { t =>
                        UI.div.style(Style.row.gap(8.px))(
                            UI.button("CPU").style(if t == "cpu" then btnStyle else btnOffStyle).onClick(ftype.set("cpu")),
                            UI.button("Alloc").style(if t == "alloc" then btnStyle else btnOffStyle).onClick(ftype.set("alloc"))
                        )
                    }
                    val benchRows =
                        if run.benches.isEmpty then
                            Chunk(UI.tr(UI.td("No flamegraphs published for this run.").style(tdStyle)))
                        else
                            run.benches.map { b =>
                                UI.tr(
                                    UI.td(b.label).style(tdStyle),
                                    UI.td(
                                        UI.button("Open").id(s"open-${b.label.replace('.', '-')}").style(btnStyle).onClick(openFlame(b))
                                    ).style(tdStyle)
                                )
                            }
                    UI.div.id("dialog").style(overlayStyle)(
                        UI.div.style(cardStyle)(
                            UI.div.style(headerStyle)(
                                UI.button("Close").style(btnStyle).onClick(dialog.set(Absent)),
                                typeToggle
                            ),
                            UI.h3(s"Flamegraphs — ${run.sha}"),
                            UI.table.style(tableStyle)(benchRows.map(UI.Ast.HtmlChildVal.lift(_))*)
                        )
                    )
                case Absent => UI.span("").hidden(true)
            }
        yield UI.div.style(Style.position(Position.overlay).column)(
            dialogUI,
            UI.div.style(pageStyle)(
                UI.div.style(leftStyle)(
                    UI.div.style(Style.row.gap(8.px).align(_.center))(
                        UI.button("Show URL").id("showurl").style(btnStyle).onClick(showUrl.getAndUpdate(!_).unit),
                        UI.span("select rows to compare runs").style(Style.fontSize(12.px).color(_.gray))
                    ),
                    when(showUrl)(UI.div.style(urlBoxStyle)(iframeUrl)),
                    tableRegion
                ),
                UI.div.style(rightStyle)(
                    UI.iframe("about:blank").id("frame").style(iframeStyle).title("benchmark results").src(iframeUrl)
                )
            )
        )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"BenchViz running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end BenchViz
