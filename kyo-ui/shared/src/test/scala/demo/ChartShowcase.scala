package demo

import kyo.*
import kyo.Chart.*
import kyo.Style.*
import kyo.UI.*
import scala.language.implicitConversions

/** Animated + interactive showcase of chart features.
  *
  * Four panels demonstrate the API surface:
  *
  *   1. Animated dual-axis combo (`yScaleRight`): colored bars on the left axis and a growth-rate line on
  *      the independent right axis, both bound to a `Signal` and tweening via `.animate` when the one-shot
  *      morph fires. Highlights the dual-axis independent-scale feature.
  *   2. Interactive highlight on a multi-series line chart: three categorical colored lines via
  *      `.legend(_.colorScale[Series](...))` + `.onSelect(ref)` + `.interaction(_.highlightSelect)`.
  *      A background fiber sets the ref to a chosen series after the first captured frame, so frame 2 shows
  *      the highlighted series with the dark select stroke.
  *   3. Animated multi-series colored AREA: three non-stacked area series that morph between two
  *      same-structure datasets via `.animate`. Showcases the animated-area-colorScale fix (colors persist
  *      through the morph).
  *   4. Animated bars with per-bar opacity + text labels: `.animate` on bars with `opacity` and `label`
  *      encodings that update live (value annotations float above the bars).
  *
  * All four panels are driven by one "Animate & Highlight" button: clicking it swaps every chart's Signal
  * to its end dataset (the `.animate` charts tween) and selects a real Beta row (the line chart highlights
  * it). `ChartShowcase.app` builds the page; `ChartShowcaseShot` captures `chart-showcase-1.png` (initial)
  * then clicks the button and captures `chart-showcase-2.png` (animated + highlighted) at 1400x900.
  */
object ChartShowcase extends KyoApp:

    // ---- domain: panel 1 - dual-axis animated combo ----

    case class Rev(month: String, revenue: Double, growth: Double) derives CanEqual

    val revStart: Seq[Rev] = Chunk(
        Rev("Jan", 45_000, 0.0),
        Rev("Feb", 52_000, 15.6),
        Rev("Mar", 48_000, -7.7),
        Rev("Apr", 61_000, 27.1),
        Rev("May", 70_000, 14.8),
        Rev("Jun", 83_000, 18.6)
    )

    val revEnd: Seq[Rev] = Chunk(
        Rev("Jan", 55_000, 0.0),
        Rev("Feb", 68_000, 23.6),
        Rev("Mar", 74_000, 8.8),
        Rev("Apr", 91_000, 22.9),
        Rev("May", 105_000, 15.4),
        Rev("Jun", 118_000, 12.4)
    )

    // ---- domain: panel 2 - interactive multi-series line ----

    enum Series derives CanEqual:
        case Alpha, Beta, Gamma

    case class LinePt(x: Int, y: Double, series: Series) derives CanEqual

    val lineData: Chunk[LinePt] =
        val xs = Chunk.from(0 to 6)
        xs.flatMap { i =>
            Chunk(
                LinePt(i, 30 + i * 8.0 + math.sin(i) * 5, Series.Alpha),
                LinePt(i, 60 + i * 3.5 - math.cos(i) * 8, Series.Beta),
                LinePt(i, 45 + i * 6.0 + math.sin(i * 0.8) * 10, Series.Gamma)
            )
        }
    end lineData

    // ---- domain: panel 3 - animated multi-series area ----

    case class AreaPt(x: Int, y: Double, grp: String) derives CanEqual

    val areaStart: Seq[AreaPt] =
        Chunk.from((0 to 5).flatMap { i =>
            Chunk(
                AreaPt(i, 20 + i * 6.0, "North"),
                AreaPt(i, 35 + i * 4.0, "South"),
                AreaPt(i, 15 + i * 3.0, "West")
            )
        })

    val areaEnd: Seq[AreaPt] =
        Chunk.from((0 to 5).flatMap { i =>
            Chunk(
                AreaPt(i, 40 + i * 5.0, "North"),
                AreaPt(i, 25 + i * 7.0, "South"),
                AreaPt(i, 30 + i * 4.5, "West")
            )
        })

    // ---- domain: panel 4 - animated bars with opacity + labels ----

    case class BarItem(label: String, value: Double, opacity: Double) derives CanEqual

    val barsStart: Seq[BarItem] = Chunk(
        BarItem("Q1", 42.0, 0.5),
        BarItem("Q2", 78.0, 0.7),
        BarItem("Q3", 61.0, 0.6),
        BarItem("Q4", 95.0, 0.9),
        BarItem("Q5", 53.0, 0.55)
    )

    val barsEnd: Seq[BarItem] = Chunk(
        BarItem("Q1", 68.0, 0.9),
        BarItem("Q2", 55.0, 0.6),
        BarItem("Q3", 89.0, 0.85),
        BarItem("Q4", 72.0, 0.7),
        BarItem("Q5", 110.0, 1.0)
    )

    // ---- color palette ----

    private val alphaColor = Style.Color.rgb(99, 102, 241)
    private val betaColor  = Style.Color.rgb(34, 197, 94)
    private val gammaColor = Style.Color.rgb(245, 158, 11)

    private val northColor = Style.Color.rgb(56, 189, 248)
    private val southColor = Style.Color.rgb(232, 121, 249)
    private val westColor  = Style.Color.rgb(52, 211, 153)

    // ---- styles ----

    private val pageStyle =
        Style.column
            .padding(24.px)
            .gap(24.px)
            .bg(Style.Color.rgb(248, 250, 252))
            .fontFamily(_.SansSerif)

    private val gridStyle =
        Style.row
            .flexWrap(_.wrap)
            .gap(20.px)

    private val cellStyle =
        Style.column
            .gap(8.px)
            .align(_.start)
            .padding(16.px)
            .bg(Style.Color.white)
            .rounded(10.px)

    private val titleStyle =
        Style.fontSize(13.px)
            .fontWeight(_.bold)
            .color(Style.Color.rgb(30, 41, 59))

    private val subtitleStyle =
        Style.fontSize(11.px)
            .color(Style.Color.rgb(100, 116, 139))

    private val buttonStyle =
        Style.fontSize(13.px)
            .fontWeight(_.bold)
            .color(Style.Color.white)
            .bg(Style.Color.rgb(37, 99, 235))
            .padding(10.px)
            .rounded(8.px)

    private def chartCell(title: String, subtitle: String, svg: Svg.Root): UI.Ast.Div =
        UI.div.style(cellStyle)(
            UI.div.style(Style.column.gap(2.px))(
                UI.h3(title).style(titleStyle),
                UI.div(subtitle).style(subtitleStyle)
            ),
            svg
        )

    // ---- app ----

    /** Builds the showcase page: allocates signals, starts one-shot morph fibers, assembles panels. */
    def app: UI < Async =
        for
            revSignal  <- Signal.initRef(revStart)
            areaSignal <- Signal.initRef(areaStart)
            barsSignal <- Signal.initRef(barsStart)
            selected   <- Signal.initRef(Maybe.empty[LinePt])

            // One "Animate & Highlight" button drives every panel at once: it swaps each chart's Signal to
            // its end dataset (so the .animate charts tween) and selects the Beta series (so the line chart
            // highlights it with the dark select stroke). highlightSelect matches the selected value by
            // equality against each series path's representative row, which lowerLine tags as the series'
            // FIRST row, so we select Beta's first row (x = 0). Driving from one explicit click makes the
            // before/after deterministic instead of racing a timer against page load.
            betaRow = Maybe.fromOption(lineData.find(p => p.series == Series.Beta && p.x == 0))
            animateAction =
                revSignal.set(revEnd)
                    .andThen(areaSignal.set(areaEnd))
                    .andThen(barsSignal.set(barsEnd))
                    .andThen(selected.set(betaRow))

            // Panel 1: dual-axis animated combo
            dualAxisChart =
                Chart(revSignal)(
                    bar(x = _.month, y = _.revenue, color = _.month),
                    line(x = _.month, y = _.growth, axis = Axis.Right, curve = Curve.monotone)
                )
                    .yScale(_.linear(0.0, 130_000.0))
                    .yAxis(_.grid.ticks(5).label("Revenue ($)"))
                    .yScaleRight(_.linear(-20.0, 40.0))
                    .yAxisRight(_.label("Growth (%)").grid)
                    .legend(_.hidden)
                    .animate(_.ease(800.millis))
                    .size(620, 280)
                    .lower

            // Panel 2: interactive multi-series line with categorical colorScale
            lineChart =
                Chart(lineData)(
                    line(x = _.x, y = _.y, color = _.series, curve = Curve.monotone)
                )
                    .yScale(_.withNice(true))
                    .yAxis(_.grid.ticks(4))
                    .legend(_.top.colorScale[Series](
                        Series.Alpha -> alphaColor,
                        Series.Beta  -> betaColor,
                        Series.Gamma -> gammaColor
                    ))
                    .onSelect(selected)
                    .interaction(_.highlightSelect)
                    .size(620, 280)
                    .lower

            // Panel 3: animated multi-series non-stacked area with colorScale
            areaChart =
                Chart(areaSignal)(
                    area(x = _.x, y = _.y, color = _.grp)
                )
                    .yScale(_.linear(0.0, 70.0))
                    .yAxis(_.grid.ticks(4))
                    .legend(_.top.colorScale {
                        case "North" => northColor
                        case "South" => southColor
                        case _       => westColor
                    })
                    .animate(_.ease(800.millis))
                    .size(620, 280)
                    .lower

            // Panel 4: animated bars with per-bar opacity + text labels
            barsChart =
                Chart(barsSignal)(
                    bar(x = _.label, y = _.value, opacity = _.opacity),
                    text(x = _.label, y = _.value, label = r => r.value.toInt.toString, anchor = Chart.TextAnchor.Middle)
                )
                    .yScale(_.linear(0.0, 120.0))
                    .yAxis(_.grid.ticks(5))
                    .animate(_.ease(800.millis))
                    .size(620, 280)
                    .lower
        yield UI.div.style(pageStyle)(
            UI.div.style(Style.row.gap(16.px).align(_.center))(
                UI.h2("kyo-ui Chart Showcase").style(Style.fontSize(20.px).fontWeight(_.bold).color(Style.Color.rgb(15, 23, 42))),
                UI.button("Animate & Highlight").id("play").style(buttonStyle).onClick(animateAction)
            ),
            UI.div.style(gridStyle)(
                chartCell(
                    "1. Dual-Axis Animated Combo",
                    "bars left + growth line right, independent yScaleRight, tweens on morph",
                    dualAxisChart
                ),
                chartCell(
                    "2. Interactive Multi-Series Line",
                    "3 colored lines, onSelect + highlightSelect; Animate selects Beta (dark stroke)",
                    lineChart
                )
            ),
            UI.div.style(gridStyle)(
                chartCell(
                    "3. Animated Multi-Series Area",
                    "non-stacked colored areas with categorical colorScale, morphs between datasets",
                    areaChart
                ),
                chartCell(
                    "4. Animated Bars: opacity + text labels",
                    "per-bar opacity encoding + floating text annotations, live update",
                    barsChart
                )
            )
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"ChartShowcase running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end ChartShowcase

/** Serves `ChartShowcase.app` and captures TWO frames so animation and interaction are visible.
  *
  * `chart-showcase-1.png` is captured right after load with every panel in its initial state. The Shot then
  * clicks the "Animate & Highlight" button and, once the ease has settled, captures `chart-showcase-2.png`
  * showing the dual-axis bars and area morphed to their new shapes and the Beta series highlighted in
  * panel 2 with the dark select stroke. Driving the change by an explicit click keeps the two frames
  * deterministically distinct.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.ChartShowcaseShot [outDir]'` (default outDir `/tmp`).
  */
object ChartShowcaseShot extends KyoApp:

    private val W = 1400
    private val H = 900

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(ChartShowcase.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"ChartShowcaseShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1400,900", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _ <- Browser.goto(url)
                    _ <- Browser.waitForVisible(Browser.Selector.css("#play"))
                    _ <- Async.sleep(700.millis) // let the initial render settle
                    a <- Browser.screenshot(W, H)
                    _ <- a.writeFileBinary(s"$outDir/chart-showcase-1.png")
                    _ <- Console.printLine(s"wrote $outDir/chart-showcase-1.png (initial)")

                    // Click the button: every Signal swaps to its end dataset (the .animate charts tween over
                    // the ease) and the Beta line is selected (highlightSelect applies the dark stroke).
                    _ <- Browser.click(Browser.Selector.css("#play"))
                    _ <- Async.sleep(1300.millis) // ease (800ms) settles + highlight applied
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/chart-showcase-2.png")
                    _ <- Console.printLine(s"wrote $outDir/chart-showcase-2.png (animated + highlighted)")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end ChartShowcaseShot
