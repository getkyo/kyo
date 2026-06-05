package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.mark.*

/** Renders a gallery of six representative static charts and writes a PNG screenshot.
  *
  * The six charts cover the main visual surface of the Chart layer: grouped bar, line, stacked bar,
  * dual-axis combo, scatter, and dark-theme bar.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.ChartGalleryShot [outDir]'` (default outDir `/tmp`).
  *
  * The first run downloads Chrome-for-Testing (~120 MB). The PNG is written to
  * `<outDir>/chart-gallery.png`.
  */
object ChartGalleryShot extends KyoApp:

    // ---- domain types ----

    enum Region derives CanEqual:
        case NA, EU, APAC

    case class SaleRow(month: String, units: Double, region: Region)
    case class TrendRow(month: String, value: Double)
    case class ComboRow(month: String, revenue: Double, growthPct: Double)
    case class ScatterRow(a: Double, b: Double, w: Double)

    // ---- datasets ----

    val saleData: Chunk[SaleRow] = Chunk(
        SaleRow("Jan", 120, Region.NA),
        SaleRow("Jan", 80, Region.EU),
        SaleRow("Jan", 60, Region.APAC),
        SaleRow("Feb", 140, Region.NA),
        SaleRow("Feb", 95, Region.EU),
        SaleRow("Feb", 72, Region.APAC),
        SaleRow("Mar", 160, Region.NA),
        SaleRow("Mar", 110, Region.EU),
        SaleRow("Mar", 85, Region.APAC)
    )

    val trendData: Chunk[TrendRow] = Chunk(
        TrendRow("Jan", 230),
        TrendRow("Feb", 280),
        TrendRow("Mar", 310),
        TrendRow("Apr", 295),
        TrendRow("May", 340),
        TrendRow("Jun", 390)
    )

    val comboData: Chunk[ComboRow] = Chunk(
        ComboRow("Jan", 45_000, 0.0),
        ComboRow("Feb", 52_000, 15.6),
        ComboRow("Mar", 48_000, -7.7),
        ComboRow("Apr", 61_000, 27.1),
        ComboRow("May", 70_000, 14.8),
        ComboRow("Jun", 83_000, 18.6)
    )

    val scatterData: Chunk[ScatterRow] = Chunk(
        ScatterRow(1.2, 3.4, 8.0),
        ScatterRow(2.5, 1.8, 5.0),
        ScatterRow(3.1, 4.7, 12.0),
        ScatterRow(4.4, 2.2, 7.0),
        ScatterRow(5.0, 5.5, 15.0),
        ScatterRow(6.3, 3.1, 9.0),
        ScatterRow(7.1, 6.2, 11.0),
        ScatterRow(8.2, 4.0, 6.0)
    )

    // ---- chart specs (all static) ----

    // 1. Grouped/colored bar with axes + legend
    val chart1: Svg.Root =
        UI.chart(saleData)(
            bar(x = _.month, y = _.units, color = _.region)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .xAxis(_.label("Month"))
            .legend(_.top)
            .size(360, 240)
            .toSvg

    // 2. Line chart with left y-axis grid
    val chart2: Svg.Root =
        UI.chart(trendData)(
            line(x = _.month, y = _.value)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid)
            .size(360, 240)
            .toSvg

    // 3. Stacked bar
    val chart3: Svg.Root =
        UI.chart(saleData)(
            bar(x = _.month, y = _.units, stack = by(_.region))
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .legend(_.top)
            .size(360, 240)
            .toSvg

    // 4. Dual-axis combo: bar (left) + line (right)
    val chart4: Svg.Root =
        UI.chart(comboData)(
            bar(x = _.month, y = _.revenue),
            line(x = _.month, y = _.growthPct, axis = Axis.Right)
        )
            .yScale(_.withNice(true))
            .yAxis(_.label("Revenue"))
            .yAxisRight(_.label("Growth %"))
            .size(360, 240)
            .toSvg

    // 5. Scatter (point) with optional size channel
    val chart5: Svg.Root =
        UI.chart(scatterData)(
            point(x = _.a, y = _.b, size = _.w)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid)
            .size(360, 240)
            .toSvg

    // 6. Dark-theme bar
    val chart6: Svg.Root =
        UI.chart(trendData)(
            bar(x = _.month, y = _.value)
        )
            .yScale(_.withNice(true))
            .yAxis(_.grid.ticks(4))
            .theme(_.dark)
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

    // ---- gallery UI ----

    private def chartCell(title: String, svg: Svg.Root): UI.Ast.Div =
        UI.div.style(cellStyle)(
            UI.h3(title).style(titleStyle),
            svg
        )

    private val app: UI =
        UI.div.style(pageStyle)(
            UI.h2("kyo-ui Chart Gallery").style(Style.fontSize(18.px).fontWeight(_.bold)),
            UI.div.style(gridStyle)(
                chartCell("1. Grouped Bar + Legend", chart1),
                chartCell("2. Line Chart", chart2),
                chartCell("3. Stacked Bar", chart3),
                chartCell("4. Dual-Axis Combo", chart4),
                chartCell("5. Scatter (sized points)", chart5),
                chartCell("6. Dark Theme Bar", chart6)
            )
        )

    // ---- run ----

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"ChartGalleryShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1200,900", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _   <- Browser.goto(url)
                    _   <- Async.sleep(1500.millis)
                    img <- Browser.screenshot(1200, 900)
                    _   <- img.writeFileBinary(s"$outDir/chart-gallery.png")
                    _   <- Console.printLine(s"wrote $outDir/chart-gallery.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end ChartGalleryShot
