package demo

import kyo.*
import kyo.Chart.*
import kyo.Style.*
import kyo.UI.*

/** A light-theme gallery of the STATIC chart features this campaign added.
  *
  * Each cell isolates one feature over a small, readable dataset so the visual point is obvious:
  *
  *   1. Sequential color scale: a numeric color channel mapped to a low/high gradient via
  *      `.legend(_.colorScaleSequential(low, high))`.
  *   2. Error bars: `Chart.errorBar(x, y, low, high, capWidth)` over a series with variance.
  *   3. Text annotations: a bar chart plus `Chart.text(x, y, label, anchor)` labeling each value.
  *   4. Stacked filled area: `Chart.area` with `stack = Chart.by(_.region)` and a categorical color,
  *      so the per-group fills are distinct bands.
  *   5. Theme + named palette: `.theme(_.dark.palette(Chart.Palette.Okabe))`.
  *   6. Accessibility: `.title(...)` (implies `role="img"`) and `.desc(...)` on a chart.
  *   7. Grouped (dodged) bar with categorical colorScale: distinct per-region colors + legend, no stack.
  *   8. Colored errorBar via colorScale: per-category whisker colors.
  *   9. Colored text annotations via colorScale: per-region colored value labels.
  *  10. X tick rotation + theme font: long category labels rotated -40 degrees + Georgia font.
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

    // ---- dataset for grouped bar + colorScale demos ----

    /** Multi-month, multi-region sales data for grouped-bar and colored errorBar/text demos. */
    case class SaleRow(month: String, units: Double, lo: Double, hi: Double, region: Region)

    val saleData: Chunk[SaleRow] = Chunk(
        SaleRow("Jan", 120, 108, 132, Region.NA),
        SaleRow("Jan", 80, 72, 88, Region.EU),
        SaleRow("Jan", 60, 52, 68, Region.APAC),
        SaleRow("Feb", 140, 125, 155, Region.NA),
        SaleRow("Feb", 95, 86, 104, Region.EU),
        SaleRow("Feb", 72, 64, 80, Region.APAC)
    )

    /** One value per region for the colored-text-annotation demo. Region is the x category (one bar per
      * band), so a text mark's per-band label centers over its bar. A text mark positions by its x channel
      * and does NOT dodge to follow a grouped bar, so labeling dodged sub-bars (region inside a shared
      * month band) would land the labels at the band centre, not over each sub-bar.
      */
    case class RegionVal(name: String, value: Double, region: Region)

    val regionVals: Chunk[RegionVal] = Chunk(
        RegionVal("NA", 120, Region.NA),
        RegionVal("EU", 80, Region.EU),
        RegionVal("APAC", 60, Region.APAC)
    )

    // Long category labels for the tick-rotation demo
    case class CatRow(category: String, value: Double)

    val rotateData: Chunk[CatRow] = Chunk(
        CatRow("London", 310),
        CatRow("Berlin", 245),
        CatRow("Madrid", 198),
        CatRow("Lisbon", 134),
        CatRow("Vienna", 92)
    )

    // ---- feature charts ----

    /** 1. Numeric color channel + sequential gradient (cool to warm).
      *
      * Uses a `point` mark: its per-row fills are the concrete interpolated colors of the sequential
      * scale (low at the data minimum, high at the maximum). One gradient legend, with min/mid/max value
      * labels, keeps the single feature (sequential color) clear and uncluttered.
      */
    val sequentialColor: Svg.Root =
        Chart(readings)(
            point(x = _.month, y = _.value, color = _.heat)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.colorScaleSequential(Style.Color.blue, Style.Color.red))
            .margins(_.top(30))
            .size(360, 260)
            .toSvg

    /** 2. Error bars: low-to-high whisker with caps and a center marker. */
    val errorBars: Svg.Root =
        Chart(readings)(
            point(x = _.month, y = _.value),
            errorBar(x = _.month, y = _.value, low = _.lo, high = _.hi, capWidth = 10.0)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .size(360, 240)
            .toSvg

    /** 3. Text annotations stamping each bar's value. */
    val textAnnotations: Svg.Root =
        Chart(readings)(
            bar(x = _.month, y = _.value),
            text(x = _.month, y = _.value, label = r => r.value.toInt.toString, anchor = Chart.TextAnchor.Middle)
        )
            .yScale(_.withNice(true))
            .yAxis(_.ticks(4))
            .size(360, 240)
            .toSvg

    /** 4. Stacked filled area, one band per region (categorical color). */
    val stackedArea: Svg.Root =
        Chart(stackData)(
            area(x = _.month, y = _.units, color = _.region, stack = by(_.region))
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top)
            .size(360, 240)
            .toSvg

    /** 5. Dark theme with the Okabe-Ito accessible palette. */
    val themedPalette: Svg.Root =
        Chart(stackData)(
            bar(x = _.month, y = _.units, color = _.region, stack = by(_.region))
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top)
            .theme(_.dark.palette(Chart.Palette.Okabe))
            .size(360, 240)
            .toSvg

    /** 6. Accessibility: title (implies role="img") and desc. */
    val accessible: Svg.Root =
        Chart(readings)(
            line(x = _.month, y = _.value)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .title("Monthly readings, North America")
            .desc("Line chart of the NA monthly reading value from January through May.")
            .ariaLabel("Monthly NA readings line chart")
            .size(360, 240)
            .toSvg

    /** 7. Grouped (dodged) bar with categorical colorScale: distinct per-region colors + legend. No stack.
      *
      * A grouped bar is `bar(x, y, color = _.region)` with NO `stack` argument. The `.legend`
      * call attaches a `colorScale[Region](...)` so each region gets its own explicit color and a
      * legend swatch. Contrast with cell 4 (stacked area) where the same data is stacked instead.
      */
    val groupedColorScale: Svg.Root =
        Chart(saleData)(
            bar(x = _.month, y = _.units, color = _.region)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top.colorScale[Region](
                Region.NA   -> Style.Color.rgb(59, 130, 246),
                Region.EU   -> Style.Color.rgb(16, 185, 129),
                Region.APAC -> Style.Color.rgb(245, 158, 11)
            ))
            .size(360, 240)
            .toSvg

    /** 8. Colored errorBar via colorScale: per-category whisker colors.
      *
      * Each region's error whiskers are colored to match the region's categorical color, making it
      * easy to distinguish overlapping confidence intervals when multiple groups share an x position.
      */
    val coloredErrorBar: Svg.Root =
        Chart(saleData)(
            point(x = _.month, y = _.units, color = _.region),
            errorBar(x = _.month, y = _.units, low = _.lo, high = _.hi, color = _.region, capWidth = 8.0)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top.colorScale[Region](
                Region.NA   -> Style.Color.rgb(59, 130, 246),
                Region.EU   -> Style.Color.rgb(16, 185, 129),
                Region.APAC -> Style.Color.rgb(245, 158, 11)
            ))
            .size(360, 240)
            .toSvg

    /** 9. Colored text annotations via colorScale: each bar's value label uses the region's color.
      *
      * Region is the x category (one bar per band), so the `text` mark's per-band label centers over its
      * bar. The `text` carries `color = _.region`, so each value label is drawn in that region's colorScale
      * color, matching the bar and the legend. Pure value annotation without the uniform grey of an
      * uncolored `text` mark.
      */
    val coloredText: Svg.Root =
        Chart(regionVals)(
            bar(x = _.name, y = _.value, color = _.region),
            text(x = _.name, y = _.value, label = r => r.value.toInt.toString, color = _.region, anchor = Chart.TextAnchor.Middle)
        )
            .yScale(_.withNice(true))
            .yAxis(_.ticks(4))
            .legend(_.top.colorScale[Region](
                Region.NA   -> Style.Color.rgb(59, 130, 246),
                Region.EU   -> Style.Color.rgb(16, 185, 129),
                Region.APAC -> Style.Color.rgb(245, 158, 11)
            ))
            .size(360, 240)
            .toSvg

    /** 10. X tick rotation + theme font: category names rotated -40 degrees in a Georgia serif font.
      *
      * `.xAxis(_.rotateTicks(-40))` tilts the x-axis tick labels so they read clearly without crowding
      * the axis. `.theme(_.font("Georgia"))` applies a serif font to the axis labels. The combination is
      * the canonical solution for charts whose x domain is a set of named categories (regions, SKUs, etc.).
      */
    val rotatedTicksAndFont: Svg.Root =
        Chart(rotateData)(
            bar(x = _.category, y = _.value)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .xAxis(_.rotateTicks(-40))
            .theme(_.font("Georgia"))
            .margins(_.bottom(74).left(56))
            .size(480, 300)
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
                chartCell("6. Accessibility (title + desc)", accessible),
                chartCell("7. Grouped bar + categorical colorScale", groupedColorScale),
                chartCell("8. Colored errorBar via colorScale", coloredErrorBar),
                chartCell("9. Colored text annotations via colorScale", coloredText),
                chartCell("10. X tick rotation + theme font (Georgia)", rotatedTicksAndFont)
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
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1400,1380", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _   <- Browser.goto(url)
                    _   <- Async.sleep(1500.millis)
                    img <- Browser.screenshot(1400, 1380)
                    _   <- img.writeFileBinary(s"$outDir/chart-features.png")
                    _   <- Console.printLine(s"wrote $outDir/chart-features.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end ChartFeatureGalleryShot
