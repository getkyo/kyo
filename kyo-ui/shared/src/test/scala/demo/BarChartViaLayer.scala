package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import scala.language.implicitConversions

/** BarChart re-expressed using the `Chart` layer.
  *
  * Uses the same dataset and renders the same bar geometry as `BarChart.scala`. Compare the two side by side to see how
  * the chart layer maps onto the underlying SVG API: the mark factories (`bar`) replace explicit band/linear scale
  * calls, and the lowered `Svg.Root` is identical in shape to the one `renderBarChart` produces for the same data and
  * layout size.
  *
  * Size is set to 600x310 so the chart layer's layout constants (MarginLeft=60, MarginRight=20, MarginTop=20,
  * MarginBottom=40) produce the same plot dimensions as BarChart.scala (chartW=520, chartH=250, baseline=270), making
  * the lowered bar geometry directly comparable between the two approaches.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.BarChartViaLayer'` (optional port as the first argument).
  */
object BarChartViaLayer extends KyoApp:

    // ---- data (same as BarChart) ----

    val httpThroughput: Chunk[(String, Double)] = Chunk(
        ("kyo", 61_200.0),
        ("cats", 49_800.0),
        ("zio", 52_100.0)
    )

    val labels: Chunk[String] = httpThroughput.map(_._1)

    // ---- domain type ----

    case class Runtime(name: String, opsPerSec: Double)
    given CanEqual[Runtime, Runtime] = CanEqual.derived

    val runtimeData: Chunk[Runtime] = Chunk(
        Runtime("kyo", 61_200.0),
        Runtime("cats", 49_800.0),
        Runtime("zio", 52_100.0)
    )

    // ---- styles (same as BarChart) ----

    private val accent = Color.rgb(99, 102, 241)
    private val rule   = Color.rgb(221, 221, 221)

    private val pageStyle  = Style.column.padding(16.px).gap(10.px).fontFamily(_.SansSerif)
    private val barStyle   = Style.row.gap(8.px).align(_.center)
    private val btnStyle   = Style.padding(6.px, 12.px).bg(accent).color(_.white).border(0.px, accent).cursor(_.pointer)
    private val hintStyle  = Style.fontSize(12.px).color(_.gray)
    private val titleStyle = Style.fontSize(16.px)
    private val svgWrap    = Style.border(1.px, rule).maxWidth(100.pct)

    // ---- app ----

    private[demo] def app: UI < Async =
        for
            data <- Signal.initRef(runtimeData)
        yield UI.div.style(pageStyle)(
            UI.div.style(barStyle)(
                UI.span("kyo-ui Chart layer bar chart").style(titleStyle),
                UI.span("HTTP throughput by runtime (ops/s), representative figures.").style(hintStyle)
            ),
            UI.div.style(svgWrap)(
                data.render { rows =>
                    // Size 600x310 matches BarChart.scala's layout:
                    //   plotX=60, plotY=20, plotW=520, plotH=250, baseline=270
                    Chart(rows)(bar(x = _.name, y = _.opsPerSec))
                        .yAxis(_.left.grid.ticks(5))
                        .xAxis(_.bottom)
                        .size(600, 310)
                        .toSvg
                }
            )
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"BarChartViaLayer running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end BarChartViaLayer
