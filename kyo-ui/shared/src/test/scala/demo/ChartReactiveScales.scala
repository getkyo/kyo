package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.mark.*
import scala.language.implicitConversions

/** Shows the REACTIVE and SCALE features this campaign added, captured across two frames so the morph is visible.
  *
  * The page holds three charts:
  *
  *   1. Reactive morph: a line chart bound to a `Signal[Chunk[Wave]]` with `.animate(_.ease(...))`. A background
  *      fiber replaces the data with a same-structure update once between the two captured frames, so the path
  *      tweens rather than snapping.
  *   2. Clamp: a bar chart with a FIXED y-domain `.yScale(_.linear(lo, hi).withClamp(true))`. One bar's value
  *      sits above `hi`; with clamp on it pins to the top of the plot instead of overflowing the frame.
  *   3. Scale readback overlay: a static bar chart lowered with `toSvgWithScales`, then composed inside an outer
  *      `Svg.svg` together with a horizontal target `Svg.line` drawn at `scales.y.toPixel(target)`, so the rule
  *      lands on the exact data pixel for the target value.
  *
  * `ChartReactiveScales.app` builds the page (allocating the signal and starting the fiber);
  * `ChartReactiveScalesShot` captures `reactive-scales-1.png` (before the tick) and `reactive-scales-2.png` (after).
  */
object ChartReactiveScales extends KyoApp:

    // ---- domain ----

    /** One sample of a series; the morph swaps the whole chunk for a same-length, same-x chunk. */
    case class Wave(x: Double, y: Double) derives CanEqual

    /** Initial series captured in frame 1. */
    val waveStart: Chunk[Wave] = Chunk(
        Wave(0, 40),
        Wave(1, 70),
        Wave(2, 55),
        Wave(3, 90),
        Wave(4, 65),
        Wave(5, 110),
        Wave(6, 80)
    )

    /** Same structure (same length and x values), different y values: captured in frame 2 after the tick. */
    val waveEnd: Chunk[Wave] = Chunk(
        Wave(0, 95),
        Wave(1, 50),
        Wave(2, 120),
        Wave(3, 60),
        Wave(4, 135),
        Wave(5, 75),
        Wave(6, 150)
    )

    // ---- clamp demo data ----

    case class Bar(label: String, value: Double)

    private val clampLo = 0.0
    private val clampHi = 100.0

    /** The "Spike" bar at 160 exceeds the fixed domain max of 100; clamp pins it to the top. */
    val clampData: Chunk[Bar] = Chunk(
        Bar("A", 30),
        Bar("B", 65),
        Bar("C", 90),
        Bar("Spike", 160),
        Bar("D", 45)
    )

    // ---- overlay demo data ----

    case class Sample(name: String, score: Double)

    val overlayData: Chunk[Sample] = Chunk(
        Sample("Q1", 42),
        Sample("Q2", 78),
        Sample("Q3", 61),
        Sample("Q4", 95)
    )

    /** The data value we draw a target rule at; the overlay uses `scales.y.toPixel` to place it exactly. */
    private val targetScore = 70.0
    private val overlayW    = 360
    private val overlayH    = 240

    // ---- clamp chart (static) ----

    val clampChart: Svg.Root =
        UI.chart(clampData)(
            bar(x = _.label, y = _.value, color = _.label)
        )
            .yScale(_.linear(clampLo, clampHi).withClamp(true))
            .yAxis(_.grid.ticks(5))
            .legend(_.hidden)
            .size(360, 240)
            .toSvg

    /** A bar chart plus a target rule placed at the exact pixel for `targetScore` via the read-back y-scale. */
    val overlayChart: Svg.Root =
        val (chartSvg, scales) =
            UI.chart(overlayData)(
                bar(x = _.name, y = _.score)
            )
                .yScale(_.linear(0.0, 100.0))
                .yAxis(_.grid.ticks(5))
                .size(overlayW, overlayH)
                .toSvgWithScales
        val targetPx = scales.y.toPixel(targetScore)
        val target =
            Svg.line
                .x1(scales.plot.x)
                .x2(scales.plot.x + scales.plot.width)
                .y1(targetPx)
                .y2(targetPx)
                .stroke(Style.Color.red)
                .strokeWidth(2.0)
                .strokeDasharray(Seq(6.0, 4.0))
        Svg.svg
            .width(overlayW)
            .height(overlayH)(
                chartSvg,
                target
            )
    end overlayChart

    // ---- styles ----

    private val pageStyle =
        Style.column
            .padding(24.px)
            .gap(24.px)
            .bg(Style.Color.white)
            .fontFamily(_.SansSerif)

    private val gridStyle =
        Style.row
            .flexWrap(_.wrap)
            .gap(20.px)

    private val cellStyle =
        Style.column
            .gap(6.px)
            .align(_.start)

    private val titleStyle =
        Style.fontSize(13.px)
            .fontWeight(_.bold)
            .color(Style.Color.rgb(60, 60, 80))

    private def chartCell(title: String, svg: Svg.Root): UI.Ast.Div =
        UI.div.style(cellStyle)(
            UI.h3(title).style(titleStyle),
            svg
        )

    /** Builds the page: allocates the live signal, starts the one-shot morph fiber, assembles the cells. */
    def app: UI < Async =
        for
            wave <- Signal.initRef(waveStart)

            // One-shot morph: after the first frame is captured, swap to the same-structure end data so the
            // animated path tweens between the two screenshots. No var, no blocking; just a delayed set.
            _ <- Fiber.initUnscoped {
                Async.sleep(2500.millis).andThen(wave.set(waveEnd))
            }

            morphChart =
                UI.chart(wave)(
                    line(x = _.x, y = _.y, curve = Curve.monotone),
                    point(x = _.x, y = _.y)
                )
                    .yScale(_.linear(0.0, 200.0))
                    .yAxis(_.grid.ticks(5))
                    .animate(_.ease(800.millis))
                    .size(360, 240)
                    .toSvg
        yield UI.div.style(pageStyle)(
            UI.h2("kyo-ui Chart Reactive + Scales").style(Style.fontSize(18.px).fontWeight(_.bold)),
            UI.div.style(gridStyle)(
                chartCell("1. Reactive morph (eased, tweens between frames)", morphChart),
                chartCell("2. Clamp (Spike=160 pinned to domain max 100)", clampChart),
                chartCell("3. Scale readback overlay (target rule at y=70px)", overlayChart)
            )
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"ChartReactiveScales running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end ChartReactiveScales

/** Serves `ChartReactiveScales.app` and captures TWO frames so the reactive morph is visible:
  * `reactive-scales-1.png` ~1.5s after load (before the one-shot tick) and `reactive-scales-2.png`
  * after a further 3s (once the data has morphed).
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.ChartReactiveScalesShot [outDir]'` (default outDir `/tmp`).
  */
object ChartReactiveScalesShot extends KyoApp:

    private val W = 1200
    private val H = 700

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(ChartReactiveScales.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"ChartReactiveScalesShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1200,700", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _ <- Browser.goto(url)
                    _ <- Async.sleep(1500.millis) // before the one-shot morph (which fires at ~2.5s)
                    a <- Browser.screenshot(W, H)
                    _ <- a.writeFileBinary(s"$outDir/reactive-scales-1.png")
                    _ <- Console.printLine(s"wrote $outDir/reactive-scales-1.png")

                    _ <- Async.sleep(3.seconds) // after the morph + ease has settled
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/reactive-scales-2.png")
                    _ <- Console.printLine(s"wrote $outDir/reactive-scales-2.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end ChartReactiveScalesShot
