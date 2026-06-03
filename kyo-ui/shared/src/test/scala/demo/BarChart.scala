package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import scala.language.implicitConversions

/** Animated bar chart built entirely on the kyo-ui SVG layer and served as a server-push app via `UI.runHandlers`.
  *
  * This is a reference for charting with the SVG API. Scales, ticks, and axes are pure helpers (`linearScale`,
  * `bandScale`, `niceTicks`, `renderXAxis`, `renderYAxis`); every visual node is a typed `Svg.*` factory (`Svg.rect`,
  * `Svg.line`, `Svg.text`, `Svg.animate`) with typed value DSLs (`ViewBox`, `Paint`, `SvgLength`). There is no raw
  * markup and no string escape hatch.
  *
  * Two animation mechanisms are shown. (1) On load, each bar grows in from the baseline via a SMIL `Svg.animate` on the
  * rect's `height` and `y`: the renderer emits the `<animate>` children and the browser drives the tween, so no
  * server round-trips are needed. (2) The "Refresh data" control tweens the displayed values to a new same-length
  * dataset through a `SignalRef`, stepped by `Async.sleep` over an eased interpolation (never blocks a thread),
  * re-rendering the chart through the reactive boundary on each step.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.BarChart'` (optional port as the first argument).
  */
object BarChart extends KyoApp:

    // ---- data ----

    /** HTTP throughput by runtime (ops/s). Representative figures from a short `HttpServerBench` run, not a definitive
      * benchmark; they exist to give the chart a meaningful shape.
      */
    val httpThroughput: Chunk[(String, Double)] = Chunk(
        ("kyo", 61_200.0),
        ("cats", 49_800.0),
        ("zio", 52_100.0)
    )

    /** A second same-length dataset the "Refresh data" control tweens to (and back), showing the value tween. */
    val httpThroughputAlt: Chunk[(String, Double)] = Chunk(
        ("kyo", 58_400.0),
        ("cats", 53_300.0),
        ("zio", 47_900.0)
    )

    val labels: Chunk[String] = httpThroughput.map(_._1)

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

    private val palette: Chunk[Style.Color] = Chunk(
        Style.Color.rgb(59, 130, 246), // blue
        Style.Color.rgb(34, 197, 94),  // green
        Style.Color.rgb(249, 115, 22)  // orange
    )

    /** A stable palette color for the bar at index `i`. */
    def barColor(i: Int): Style.Color = palette(i % palette.length)

    private val axisColor: Style.Color  = Style.Color.rgb(120, 120, 120)
    private val gridColor: Style.Color  = Style.Color.rgb(225, 225, 225)
    private val labelColor: Style.Color = Style.Color.rgb(40, 40, 40)

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

    /** Return `(xOffset, bandWidth)` for the `i`-th of `n` bands across `[0, totalWidth]` with the given padding ratio. */
    def bandScale(i: Int, n: Int, totalWidth: Double, padding: Double = 0.1): (Double, Double) =
        if n <= 0 then (0.0, totalWidth)
        else
            val slot      = totalWidth / n.toDouble
            val bandWidth = totalWidth * (1.0 - padding) / n.toDouble
            val xOffset   = i.toDouble * slot + (slot - bandWidth) / 2.0
            (xOffset, bandWidth)
    end bandScale

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

    private def formatValue(v: Double): String =
        if v == v.toLong.toDouble then v.toLong.toString else f"$v%.1f"

    private def maxOf(values: Chunk[Double]): Double = values.foldLeft(1.0)(math.max)

    /** A stable per-bar id stem for the enclosing `<g>`. */
    def barCellId(i: Int): String = "bar-cell-" + i.toString

    /** Id of the bar `<rect>` at index `i`. */
    def barRectId(i: Int): String = "bar-rect-" + i.toString

    // ---- axes ----

    /** Render a horizontal x-axis: a baseline line plus one centered category label per band. */
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

    // ---- bar chart ----

    /** Render the bar chart for the given values and labels as a complete typed `Svg.Root`.
      *
      * Each bar is one `Svg.rect` placed via `bandScale` (x/width) and `linearScale` (height/y, baseline at the bottom),
      * carrying a native `Svg.title` tooltip and two SMIL `Svg.animate` children that grow it from the baseline: one
      * tweens `height` from 0, the other slides `y` from the baseline up to the top, so the bar grows upward on load.
      *
      * Values and labels are zipped into aligned pairs, so iterating pairs (rather than indexing labels by a value
      * index) cannot read past either collection even if a caller passed mismatched lengths.
      */
    def renderBarChart(values: Chunk[Double], labels: Chunk[String])(using Frame): Svg.Root =
        val pairs    = values.zip(labels)
        val n        = pairs.length
        val maxVal   = maxOf(pairs.map(_._1))
        val baseline = chartH + marginTop
        val yScale   = linearScale(0.0, maxVal, baseline, marginTop)
        val ticks    = niceTicks(0.0, maxVal, 5)

        val bars = pairs.zipWithIndex.map { case ((v, label), i) =>
            val (bx, bw) = bandScale(i, n, chartW)
            val xPx      = bx + marginL
            val barTop   = yScale(v)
            val barH     = baseline - barTop
            val rect = Svg.rect
                .id(barRectId(i))
                .x(xPx).y(barTop).width(bw).height(barH)
                .fill(Svg.Paint.Color(barColor(i)))(
                    Svg.title(s"$label: ${formatValue(v)} ops/s"),
                    Svg.animate.attributeName("height").from(0.0).to(barH).dur("0.6s").begin("0s").repeatCount("1"),
                    Svg.animate.attributeName("y").from(baseline).to(barTop).dur("0.6s").begin("0s").repeatCount("1")
                )
            val valueLabel = Svg.text
                .x(xPx + bw / 2.0).y(barTop - 4)
                .textAnchor(Svg.TextAnchor.Middle)
                .fill(Svg.Paint.Color(labelColor))
                .fontSize(Svg.SvgLength.px(11.0))(formatValue(v))
            // The value <text> sits on top of the rect and SVG hit-tests the topmost element, so the cell wraps both
            // in a <g>; an id on the <g> gives the whole cell a stable target.
            Svg.g.id(barCellId(i))(rect, valueLabel)
        }

        val barLabels = pairs.map(_._2)
        val xs        = (0 until n).map(i => bandScale(i, n, chartW)._1 + marginL + bandScale(i, n, chartW)._2 / 2.0)
        val xAxis     = renderXAxis(barLabels, Chunk.from(xs), baseline, chartW)
        val yAxis     = renderYAxis(ticks, yScale, chartW, marginL - 8)

        Svg.svg.width(W.toInt).height(H.toInt).viewBox(viewBox)(
            (yAxis +: xAxis +: bars)*
        )
    end renderBarChart

    // ---- value tween ----

    private val tweenSteps  = 24
    private val tweenStepMs = 16

    private def easeInOutCubic(t: Double): Double =
        if t < 0.5 then 4.0 * t * t * t
        else 1.0 - math.pow(-2.0 * t + 2.0, 3.0) / 2.0

    /** Tween `ref` element-wise from its current values to `to` over an eased interpolation, stepping via `Async.sleep`
      * (never blocks a thread), then pin the exact target. Requires `to` to be the same length as the current values;
      * the bar chart always tweens between same-length datasets.
      */
    def tweenTo(ref: SignalRef[Chunk[Double]], to: Chunk[Double])(using Frame): Unit < Async =
        for
            from <- ref.get
            n = math.min(from.length, to.length)
            _ <- Loop(1) { step =>
                val t      = step.toDouble / tweenSteps.toDouble
                val e      = easeInOutCubic(t)
                val interp = Chunk.from((0 until n).map(i => from(i) + (to(i) - from(i)) * e))
                ref.set(interp).andThen {
                    if step >= tweenSteps then Loop.done
                    else Async.sleep(tweenStepMs.millis).andThen(Loop.continue(step + 1))
                }
            }
            _ <- ref.set(to)
        yield ()

    // ---- styles ----

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
            // `values` holds the currently displayed bar heights; `useAlt` flips between the two datasets.
            values <- Signal.initRef(httpThroughput.map(_._2))
            useAlt <- Signal.initRef(false)

            refresh =
                for
                    alt <- useAlt.get
                    to = if alt then httpThroughput.map(_._2) else httpThroughputAlt.map(_._2)
                    // background fiber: tween the values, then record which dataset is now shown.
                    _ <- Fiber.initUnscoped(tweenTo(values, to).andThen(useAlt.set(!alt)))
                yield ()

            region = values.render(vs => renderBarChart(vs, labels))
        yield UI.div.style(pageStyle)(
            UI.div.style(barStyle)(
                UI.span("kyo-ui SVG bar chart").style(titleStyle),
                UI.button("Refresh data").id("refresh-btn").style(btnStyle).onClick(refresh),
                UI.span("HTTP throughput by runtime (ops/s), representative figures.").style(hintStyle)
            ),
            UI.div.style(svgWrap)(region)
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"BarChart running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end BarChart
