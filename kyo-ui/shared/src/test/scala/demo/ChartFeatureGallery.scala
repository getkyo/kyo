package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.mark.*

/** A light-theme gallery of the STATIC chart features this campaign added.
  *
  * Each cell isolates one feature over a small, readable dataset so the visual point is obvious:
  *
  *   1. Sequential color scale: a numeric color channel mapped to a low/high gradient via
  *      `.legend(_.colorScaleSequential(low, high))`.
  *   2. Error bars: `UI.mark.errorBar(x, y, low, high, capWidth)` over a series with variance.
  *   3. Text annotations: a bar chart plus `UI.mark.text(x, y, label, anchor)` labeling each value.
  *   4. Stacked filled area: `UI.mark.area` with `stack = UI.by(_.region)` and a categorical color,
  *      so the per-group fills are distinct bands.
  *   5. Theme + named palette: `.theme(_.dark.palette(UI.Ast.Palette.Okabe))`.
  *   6. Accessibility: `.title(...)` (implies `role="img"`) and `.desc(...)` on a chart.
  *
  * `ChartFeatureGallery.app` is the page value; `ChartFeatureGalleryShot` serves it and captures a PNG.
  */
object ChartFeatureGallery extends KyoApp:

    // ---- domain ----

    enum Region derives CanEqual:
        case NA, EU, APAC

    /** One measured value with a lo/hi confidence band and a heat magnitude for the gradient. */
    case class Reading(month: String, value: Double, lo: Double, hi: Double, heat: Double, region: Region)

    val readings: Chunk[Reading] = Chunk(
        Reading("Jan", 120, 104, 136, 10, Region.NA),
        Reading("Feb", 145, 132, 158, 30, Region.NA),
        Reading("Mar", 132, 118, 146, 55, Region.NA),
        Reading("Apr", 168, 150, 186, 80, Region.NA),
        Reading("May", 154, 140, 168, 100, Region.NA)
    )

    case class StackRow(month: String, units: Double, region: Region)

    val stackData: Chunk[StackRow] = Chunk(
        StackRow("Jan", 60, Region.NA),
        StackRow("Jan", 40, Region.EU),
        StackRow("Jan", 25, Region.APAC),
        StackRow("Feb", 72, Region.NA),
        StackRow("Feb", 48, Region.EU),
        StackRow("Feb", 30, Region.APAC),
        StackRow("Mar", 80, Region.NA),
        StackRow("Mar", 55, Region.EU),
        StackRow("Mar", 38, Region.APAC),
        StackRow("Apr", 90, Region.NA),
        StackRow("Apr", 60, Region.EU),
        StackRow("Apr", 44, Region.APAC)
    )

    // ---- feature charts ----

    /** 1. Numeric color channel + sequential gradient (cool to warm).
      *
      * Uses a `point` mark: its per-row fills are the concrete interpolated colors of the sequential
      * scale (low at the data minimum, high at the maximum). One gradient legend, with min/mid/max value
      * labels, keeps the single feature (sequential color) clear and uncluttered.
      */
    val sequentialColor: Svg.Root =
        UI.chart(readings)(
            point(x = _.month, y = _.value, color = _.heat)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.colorScaleSequential(Style.Color.blue, Style.Color.red))
            .size(360, 240)
            .toSvg

    /** 2. Error bars: low-to-high whisker with caps and a center marker. */
    val errorBars: Svg.Root =
        UI.chart(readings)(
            point(x = _.month, y = _.value),
            errorBar(x = _.month, y = _.value, low = _.lo, high = _.hi, capWidth = 10.0)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .size(360, 240)
            .toSvg

    /** 3. Text annotations stamping each bar's value. */
    val textAnnotations: Svg.Root =
        UI.chart(readings)(
            bar(x = _.month, y = _.value),
            text(x = _.month, y = _.value, label = r => r.value.toInt.toString, anchor = UI.TextAnchor.Middle)
        )
            .yScale(_.withNice(true))
            .yAxis(_.ticks(4))
            .size(360, 240)
            .toSvg

    /** 4. Stacked filled area, one band per region (categorical color). */
    val stackedArea: Svg.Root =
        UI.chart(stackData)(
            area(x = _.month, y = _.units, color = _.region, stack = by(_.region))
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top)
            .size(360, 240)
            .toSvg

    /** 5. Dark theme with the Okabe-Ito accessible palette. */
    val themedPalette: Svg.Root =
        UI.chart(stackData)(
            bar(x = _.month, y = _.units, color = _.region, stack = by(_.region))
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top)
            .theme(_.dark.palette(UI.Ast.Palette.Okabe))
            .size(360, 240)
            .toSvg

    /** 6. Accessibility: title (implies role="img") and desc. */
    val accessible: Svg.Root =
        UI.chart(readings)(
            line(x = _.month, y = _.value)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .title("Monthly readings, North America")
            .desc("Line chart of the NA monthly reading value from January through May.")
            .ariaLabel("Monthly NA readings line chart")
            .size(360, 240)
            .toSvg

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

    val app: UI =
        UI.div.style(pageStyle)(
            UI.h2("kyo-ui Chart Feature Gallery").style(Style.fontSize(18.px).fontWeight(_.bold)),
            UI.div.style(gridStyle)(
                chartCell("1. Sequential color gradient", sequentialColor),
                chartCell("2. Error bars (lo/hi whiskers)", errorBars),
                chartCell("3. Text value annotations", textAnnotations),
                chartCell("4. Stacked filled area", stackedArea),
                chartCell("5. Dark theme + Okabe palette", themedPalette),
                chartCell("6. Accessibility (title + desc)", accessible)
            )
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"ChartFeatureGallery running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end ChartFeatureGallery

/** Serves `ChartFeatureGallery.app` in-process and writes `chart-features.png`.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.ChartFeatureGalleryShot [outDir]'` (default outDir `/tmp`).
  */
object ChartFeatureGalleryShot extends KyoApp:

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(ChartFeatureGallery.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"ChartFeatureGalleryShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1200,900", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _   <- Browser.goto(url)
                    _   <- Async.sleep(1500.millis)
                    img <- Browser.screenshot(1200, 900)
                    _   <- img.writeFileBinary(s"$outDir/chart-features.png")
                    _   <- Console.printLine(s"wrote $outDir/chart-features.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end ChartFeatureGalleryShot
