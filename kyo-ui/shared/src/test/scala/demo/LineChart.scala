package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import scala.language.implicitConversions

/** Animated line chart built entirely on the kyo-ui SVG layer and served as a server-push app via `UI.runHandlers`.
  *
  * This is a reference for drawing a line/area chart with the SVG API. Scales, ticks, and axes are pure helpers
  * (`linearScale`, `niceTicks`, `renderXAxis`, `renderYAxis`); every visual node is a typed `Svg.*` factory (`Svg.path`,
  * `Svg.circle`, `Svg.line`, `Svg.text`, `Svg.animate`) with typed value DSLs (`ViewBox`, `Paint`, `PathData`,
  * `SvgLength`). There is no raw markup and no string escape hatch.
  *
  * The animation is a draw-in: the line is one `Svg.path` whose `stroke-dasharray` is set to its own length and whose
  * `stroke-dashoffset` starts at that same length (so nothing is visible), then a SMIL `Svg.animate` tweens the offset
  * to zero, revealing the stroke from start to end. The browser drives the SMIL tween, so no server round-trips are
  * needed. An area fill under the line and `Svg.circle` point markers complete the chart.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.LineChart'` (optional port as the first argument).
  */
object LineChart extends KyoApp:

    // ---- data ----

    /** Latency over iterations (ms per iteration). An illustrative series shaped to make the draw-in legible. */
    val latencySeries: Chunk[(String, Double)] = Chunk(
        ("1", 8.2),
        ("2", 7.5),
        ("3", 9.1),
        ("4", 6.8),
        ("5", 7.2),
        ("6", 8.9),
        ("7", 7.0)
    )

    val labels: Chunk[String] = latencySeries.map(_._1)
    val values: Chunk[Double] = latencySeries.map(_._2)

    // ---- layout constants (SVG user units) ----

    val W: Double         = 600.0
    val H: Double         = 320.0
    val marginTop: Double = 20.0
    val marginBot: Double = 50.0 // room for x-axis labels
    val marginL: Double   = 60.0 // room for y-axis labels
    val marginR: Double   = 20.0
    val chartW: Double    = W - marginL - marginR
    val chartH: Double    = H - marginTop - marginBot

    private def viewBox: Svg.ViewBox = Svg.ViewBox(0, 0, W, H)

    // ---- colors ----

    private val lineColor: Style.Color  = Style.Color.rgb(99, 102, 241) // indigo
    private val axisColor: Style.Color  = Style.Color.rgb(120, 120, 120)
    private val gridColor: Style.Color  = Style.Color.rgb(225, 225, 225)
    private val labelColor: Style.Color = Style.Color.rgb(40, 40, 40)
    private val white: Style.Color      = Style.Color.rgb(255, 255, 255)

    // ---- pure chart helpers ----

    /** Map a value in `[domainMin, domainMax]` to `[rangeMin, rangeMax]` linearly, clamped to the range.
      *
      * Returns `rangeMin` when the domain is degenerate (`domainMax == domainMin`).
      */
    def linearScale(domainMin: Double, domainMax: Double, rangeMin: Double, rangeMax: Double)(v: Double): Double =
        if domainMax == domainMin then rangeMin
        else
            val t   = (v - domainMin) / (domainMax - domainMin)
            val out = rangeMin + t * (rangeMax - rangeMin)
            val lo  = math.min(rangeMin, rangeMax)
            val hi  = math.max(rangeMin, rangeMax)
            if out < lo then lo else if out > hi then hi else out
    end linearScale

    /** Return at most `maxTicks` evenly-spaced tick values covering `[min, max]`, snapped to a nice step.
      *
      * Degenerate inputs (`min == max` or `maxTicks <= 1`) return `Chunk(min)`. Every returned tick lies in `[min, max]`.
      */
    def niceTicks(min: Double, max: Double, maxTicks: Int = 5): Chunk[Double] =
        if maxTicks <= 1 || min == max then Chunk(min)
        else
            val rawStep   = (max - min) / (maxTicks - 1).toDouble
            val magnitude = math.pow(10.0, math.floor(math.log10(rawStep)))
            val residual  = rawStep / magnitude
            val niceUnit =
                if residual <= 1.0 then 1.0
                else if residual <= 2.0 then 2.0
                else if residual <= 5.0 then 5.0
                else 10.0
            val step = niceUnit * magnitude
            @scala.annotation.tailrec
            def loop(i: Int, t: Double, acc: Chunk[Double]): Chunk[Double] =
                if i >= maxTicks || t > max + step * 1.0e-9 then acc
                else loop(i + 1, t + step, acc.append(t))
            loop(0, min, Chunk.empty)
        end if
    end niceTicks

    /** Sum of Euclidean distances between consecutive points: the exact polyline length, used to size the draw-in. */
    def pathLen(pts: Chunk[(Double, Double)]): Double =
        @scala.annotation.tailrec
        def go(i: Int, acc: Double): Double =
            if i >= pts.length then acc
            else
                val dx = pts(i)._1 - pts(i - 1)._1
                val dy = pts(i)._2 - pts(i - 1)._2
                go(i + 1, acc + math.sqrt(dx * dx + dy * dy))
        go(1, 0.0)
    end pathLen

    private def formatValue(v: Double): String =
        if v == v.toLong.toDouble then v.toLong.toString else f"$v%.1f"

    private def maxOf(values: Chunk[Double]): Double = values.foldLeft(1.0)(math.max)

    // ---- axes ----

    /** Render a horizontal x-axis: a baseline line plus one centered category label per point. */
    def renderXAxis(labels: Chunk[String], xs: Chunk[Double], y: Double, width: Double)(using Frame): Svg.G =
        val baseline = Svg.line
            .x1(marginL).y1(y).x2(marginL + width).y2(y)
            .stroke(Svg.Paint.Color(axisColor)).strokeWidth(1.0)
        val texts = labels.zip(xs).map { (label, cx) =>
            Svg.text
                .x(cx).y(y + 16)
                .textAnchor(Svg.TextAnchor.Middle)
                .fill(Svg.Paint.Color(labelColor))
                .fontSize(Svg.SvgLength.px(11.0))(label)
        }
        Svg.g(baseline +: texts*)
    end renderXAxis

    /** Render a vertical y-axis: the axis line plus a tick mark, gridline, and value label per tick. */
    def renderYAxis(ticks: Chunk[Double], scale: Double => Double, chartWidth: Double, labelX: Double)(using
        Frame
    ): Svg.G =
        val axisLine = Svg.line
            .x1(marginL).y1(marginTop).x2(marginL).y2(marginTop + chartH)
            .stroke(Svg.Paint.Color(axisColor)).strokeWidth(1.0)
        val parts = ticks.flatMap { t =>
            val ty = scale(t)
            Chunk(
                Svg.line // gridline across the plot
                    .x1(marginL).y1(ty).x2(marginL + chartWidth).y2(ty)
                    .stroke(Svg.Paint.Color(gridColor)).strokeWidth(1.0),
                Svg.line // tick mark
                    .x1(marginL - 4).y1(ty).x2(marginL).y2(ty)
                    .stroke(Svg.Paint.Color(axisColor)).strokeWidth(1.0),
                Svg.text
                    .x(labelX).y(ty + 4)
                    .textAnchor(Svg.TextAnchor.End)
                    .fill(Svg.Paint.Color(labelColor))
                    .fontSize(Svg.SvgLength.px(10.0))(formatValue(t))
            )
        }
        Svg.g(axisLine +: parts*)
    end renderYAxis

    // ---- line chart ----

    /** Render the line chart for the given values and labels as a complete typed `Svg.Root`.
      *
      * The line is one `Svg.path` built from a `PathData` (`moveTo` the first point, then `lineTo` through the rest),
      * drawn in via a SMIL `Svg.animate` on `stroke-dashoffset` (from the full path length to zero). A translucent area
      * `Svg.path` under the line and `Svg.circle` markers at each point complete the chart, with x/y axes and ticks.
      *
      * Values and labels are zipped into aligned pairs, so a mismatched-length input can never index past either one.
      */
    def renderLineChart(values: Chunk[Double], labels: Chunk[String])(using Frame): Svg.Root =
        val pairs = values.zip(labels)
        if pairs.isEmpty then
            // No data: return an empty canvas rather than indexing pts.head/pts.last on an empty path.
            Svg.svg.width(W.toInt).height(H.toInt).viewBox(viewBox)()
        else renderLineChartNonEmpty(pairs)
        end if
    end renderLineChart

    private def renderLineChartNonEmpty(pairs: Chunk[(Double, String)])(using Frame): Svg.Root =
        val pLabels  = pairs.map(_._2)
        val pValues  = pairs.map(_._1)
        val maxVal   = maxOf(pValues)
        val baseline = chartH + marginTop
        val yScale   = linearScale(0.0, maxVal, baseline, marginTop)
        val ticks    = niceTicks(0.0, maxVal, 5)
        val n        = pairs.length
        val xStep    = chartW / math.max(n - 1, 1).toDouble

        val pts: Chunk[(Double, Double)] = pValues.zipWithIndex.map { (v, i) =>
            (marginL + i.toDouble * xStep, yScale(v))
        }
        val total = pathLen(pts)

        val linePath: Svg.PathData =
            val start = Svg.PathData.from(pts.head._1, pts.head._2)
            pts.drop(1).foldLeft(start)((acc, p) => acc.lineTo(p._1, p._2))

        val areaPath: Svg.PathData =
            linePath.lineTo(pts.last._1, baseline).lineTo(pts.head._1, baseline).close

        val area = Svg.path
            .d(areaPath)
            .fill(Svg.Paint.Color(lineColor)).fillOpacity(0.15)
            .stroke(Svg.Paint.None)

        val line = Svg.path
            .d(linePath)
            .stroke(Svg.Paint.Color(lineColor)).fill(Svg.Paint.None)
            .strokeWidth(2.0)
            .strokeDasharray(Seq(total, total))
            .strokeDashoffset(Svg.SvgLength.px(total))(
                Svg.animate.attributeName("stroke-dashoffset").from(total).to(0.0).dur("1.2s").begin("0s").repeatCount("1")
            )

        val markers = pts.map { (px, py) =>
            Svg.circle.cx(px).cy(py).r(4)
                .fill(Svg.Paint.Color(lineColor))
                .stroke(Svg.Paint.Color(white)).strokeWidth(2.0)
        }

        val xAxis = renderXAxis(pLabels, Chunk.from(pts.map(_._1)), baseline, chartW)
        val yAxis = renderYAxis(ticks, yScale, chartW, marginL - 8)

        Svg.svg.width(W.toInt).height(H.toInt).viewBox(viewBox)(
            (yAxis +: xAxis +: area +: line +: markers)*
        )
    end renderLineChartNonEmpty

    // ---- styles ----

    private val rule = Color.rgb(221, 221, 221)

    private val pageStyle  = Style.column.padding(16.px).gap(10.px).fontFamily(_.SansSerif)
    private val barStyle   = Style.row.gap(8.px).align(_.center)
    private val hintStyle  = Style.fontSize(12.px).color(_.gray)
    private val titleStyle = Style.fontSize(16.px)
    private val svgWrap    = Style.border(1.px, rule).maxWidth(100.pct)

    // ---- app ----

    private[demo] def app: UI < Async =
        UI.div.style(pageStyle)(
            UI.div.style(barStyle)(
                UI.span("kyo-ui SVG line chart").style(titleStyle),
                UI.span("latency over iterations (ms), illustrative series.").style(hintStyle)
            ),
            UI.div.style(svgWrap)(renderLineChart(values, labels))
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"LineChart running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end LineChart
