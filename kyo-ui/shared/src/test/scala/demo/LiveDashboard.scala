package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import scala.language.implicitConversions

/** A live service-metrics dashboard built on the `Chart` layer's reactive (`Signal`-backed) data sources.
  *
  * Four panels share state held in `SignalRef`s and driven by one background fiber that random-walks every metric on
  * a fixed cadence. The dashboard demonstrates how the chart layer keeps a stable frame (axes, legend, plot box) while
  * the marks region redraws on each emission:
  *
  *   - KPI tiles: plain reactive `UI` (not charts) via `signalRef.render`, recoloring the big number by threshold.
  *   - Throughput: a bar chart over a `Signal[Chunk[Endpoint]]` with a FIXED y-domain so the axis never jumps and the
  *     bars animate to new heights.
  *   - Latency: one line mark split by a `series` color channel (p50 / p99) over a FIXED-LENGTH rolling window
  *     (24 slots, t-23..t0); each tick shifts values left and appends the newest at t0, so the path structure is
  *     constant and the lines morph smoothly. The color channel gives the chart a two-entry legend.
  *   - Status: a stacked bar (2xx/4xx/5xx per endpoint) grouped by status code, colored by monitoring convention
  *     (2xx green, 4xx amber, 5xx red) via a `colorScale`.
  *   - Error rate: a line over a rolling window of the overall error percentage, filling the bottom-right cell.
  *
  * The four panels sit in a fits-1200px two-column grid (symmetric ~20px outer margins, ~20px inner gutter);
  * the KPI tiles span the full content width above them.
  *
  * The RNG is a pure linear-congruential seed threaded through a `Loop`; there is no shared mutable state and no thread
  * is ever blocked (the cadence is `Async.sleep`). A pause control flips a `SignalRef[Boolean]`; while paused the engine
  * idles on a short sleep and leaves every metric untouched.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.LiveDashboard'` (optional port as the first argument).
  */
object LiveDashboard extends KyoApp:

    // ---- domain types ----

    /** Requests/second for one endpoint. */
    case class Endpoint(name: String, rps: Double) derives CanEqual

    /** One slot of the rolling latency window in long form: `t` is the relative index (-23..0), `series` is
      * "p50" or "p99", and `ms` is the latency in milliseconds. Long form lets a single line mark split into
      * one line per series via a `color` channel, so the chart layer derives a two-entry legend.
      */
    case class LatPoint(t: Int, series: String, ms: Double) derives CanEqual

    /** One slot of the rolling error-rate window: `t` is the relative index (-23..0), `pct` is the error
      * percentage at that tick.
      */
    case class ErrPoint(t: Int, pct: Double) derives CanEqual

    /** A status-code count for one endpoint; `code` is one of "2xx", "4xx", "5xx" and drives the stack grouping. */
    case class StatusRow(name: String, code: String, count: Double) derives CanEqual

    // ---- fixed layout / domain constants ----

    private val endpointNames: Chunk[String] = Chunk("/login", "/feed", "/search", "/cart", "/checkout")
    private val window: Int                  = 24

    private val rpsMax: Double = 1200.0 // fixed throughput y-domain so the axis never jumps
    private val latMax: Double = 400.0  // fixed latency y-domain (ms)

    // ---- pure RNG (linear congruential; seed threaded through the loop, no var) ----

    /** Advances the LCG seed and returns `(nextSeed, uniformIn[0,1))`. Numerical recipes constants. */
    private def nextUnit(seed: Long): (Long, Double) =
        val next = (seed * 6364136223846793005L + 1442695040888963407L)
        // take the high 24 bits for a uniform in [0, 1)
        val bits = ((next >>> 40) & 0xffffffL).toDouble
        (next, bits / 0x1000000.toDouble)
    end nextUnit

    /** A symmetric delta in `[-amp, amp]` from the seed; returns the advanced seed. */
    private def delta(seed: Long, amp: Double): (Long, Double) =
        val (s, u) = nextUnit(seed)
        (s, (u * 2.0 - 1.0) * amp)

    private def clamp(v: Double, lo: Double, hi: Double): Double =
        if v < lo then lo else if v > hi then hi else v

    // ---- initial state ----

    private val initThroughput: Chunk[Endpoint] =
        Chunk(
            Endpoint("/login", 420.0),
            Endpoint("/feed", 880.0),
            Endpoint("/search", 610.0),
            Endpoint("/cart", 300.0),
            Endpoint("/checkout", 180.0)
        )

    /** Latency window in long form: each of the 24 slots contributes one p50 row and one p99 row. */
    private val initLatency: Chunk[LatPoint] =
        Chunk.from((0 until window).flatMap { i =>
            val t = i - (window - 1) // -23 .. 0
            Chunk(LatPoint(t, "p50", 60.0), LatPoint(t, "p99", 140.0))
        })

    private val initErr: Chunk[ErrPoint] =
        Chunk.from((0 until window).map { i =>
            val t = i - (window - 1) // -23 .. 0
            ErrPoint(t, 1.0)
        })

    private val initStatus: Chunk[StatusRow] =
        Chunk.from(endpointNames.flatMap { name =>
            Chunk(
                StatusRow(name, "2xx", 90.0),
                StatusRow(name, "4xx", 8.0),
                StatusRow(name, "5xx", 2.0)
            )
        })

    // ---- one engine step: random-walk every metric, threading the seed ----

    /** Random-walk the throughput chunk; returns the advanced seed and the new chunk. */
    private def stepThroughput(seed: Long, cur: Chunk[Endpoint]): (Long, Chunk[Endpoint]) =
        cur.foldLeft((seed, Chunk.empty[Endpoint])) { case ((s0, acc), e) =>
            val (s1, d) = delta(s0, 40.0)
            (s1, acc.append(e.copy(rps = clamp(e.rps + d, 20.0, rpsMax))))
        }

    /** Shift the latency window left by one slot and append a freshly-walked newest p50/p99 pair at t0.
      *
      * Operates on the long-form chunk: the newest pair is derived from the current t0 rows, then every
      * slot's two rows are re-indexed to -23..0 after dropping the oldest slot.
      */
    private def stepLatency(seed: Long, cur: Chunk[LatPoint]): (Long, Chunk[LatPoint]) =
        val lastP50   = cur.filter(_.series == "p50").lastMaybe.map(_.ms).getOrElse(60.0)
        val lastP99   = cur.filter(_.series == "p99").lastMaybe.map(_.ms).getOrElse(140.0)
        val (s1, d50) = delta(seed, 12.0)
        val (s2, d99) = delta(s1, 28.0)
        val newP50    = clamp(lastP50 + d50, 20.0, latMax * 0.6)
        val newP99    = clamp(math.max(lastP99 + d99, newP50 + 20.0), newP50 + 20.0, latMax)
        // Reconstruct the per-slot p50/p99 series, drop the oldest slot, append the newest, re-index to -23..0.
        val p50s = cur.filter(_.series == "p50").map(_.ms).drop(1).append(newP50)
        val p99s = cur.filter(_.series == "p99").map(_.ms).drop(1).append(newP99)
        val rebuilt = Chunk.from((0 until window).flatMap { i =>
            val t = i - (window - 1)
            Chunk(LatPoint(t, "p50", p50s(i)), LatPoint(t, "p99", p99s(i)))
        })
        (s2, rebuilt)
    end stepLatency

    /** Shift the error-rate window left by one slot and append the freshly-computed newest percentage at t0. */
    private def stepErr(cur: Chunk[ErrPoint], newPct: Double): Chunk[ErrPoint] =
        val shifted = cur.map(_.pct).drop(1).append(newPct)
        Chunk.from(shifted.zipWithIndex.map { case (p, i) => ErrPoint(i - (window - 1), p) })

    /** Random-walk every status-code count for every endpoint. */
    private def stepStatus(seed: Long, cur: Chunk[StatusRow]): (Long, Chunk[StatusRow]) =
        cur.foldLeft((seed, Chunk.empty[StatusRow])) { case ((s0, acc), row) =>
            val (amp, lo, hi) = row.code match
                case "2xx" => (8.0, 40.0, 140.0)
                case "4xx" => (2.0, 0.0, 30.0)
                case _     => (1.5, 0.0, 20.0)
            val (s1, d) = delta(s0, amp)
            (s1, acc.append(row.copy(count = clamp(row.count + d, lo, hi))))
        }

    // ---- derived KPI helpers (pure) ----

    private def totalRps(t: Chunk[Endpoint]): Double = t.foldLeft(0.0)(_ + _.rps)

    private def errorPct(s: Chunk[StatusRow]): Double =
        val total  = s.foldLeft(0.0)(_ + _.count)
        val errors = s.filter(r => r.code == "4xx" || r.code == "5xx").foldLeft(0.0)(_ + _.count)
        if total <= 0.0 then 0.0 else errors / total * 100.0
    end errorPct

    /** The most-recent (t0) p99 latency from the long-form window. */
    private def p99Now(l: Chunk[LatPoint]): Double =
        l.filter(_.series == "p99").lastMaybe.map(_.ms).getOrElse(0.0)

    // ---- colors / styles (dark theme) ----

    private val pageBg   = Color.rgb(15, 18, 28)
    private val panelBg  = Color.rgb(24, 28, 42)
    private val rule     = Color.rgb(44, 50, 70)
    private val textCol  = Color.rgb(226, 232, 240)
    private val mutedCol = Color.rgb(148, 163, 184)
    private val green    = Color.rgb(34, 197, 94)
    private val amber    = Color.rgb(245, 158, 11)
    private val red      = Color.rgb(239, 68, 68)
    private val accent   = Color.rgb(99, 102, 241)

    // Chart-layer Style.Color equivalents for colorScale mappings (the chart layer maps category -> Style.Color).
    private val scGreen = Style.Color.rgb(34, 197, 94)
    private val scAmber = Style.Color.rgb(245, 158, 11)
    private val scRed   = Style.Color.rgb(239, 68, 68)
    private val scCyan  = Style.Color.rgb(6, 182, 212)

    // Two-column grid sizing that fits a 1200px canvas: 20px page padding each side -> 1160 content;
    // 20px inner gutter -> each column up to (1160 - 20) / 2 = 570; panels cap at 560 so a column plus
    // gutter plus margins stays under 1200. Charts are 520 wide and fit inside a 560 panel's 12px padding.
    private val panelMaxW: Int = 560
    private val chartW: Int    = 520
    private val chartH: Int    = 240

    private val pageStyle =
        Style.column.padding(20.px).gap(20.px).bg(pageBg).color(textCol).fontFamily(_.SansSerif).minWidth(100.pct)

    private val headerRow  = Style.row.gap(12.px).align(_.center).justify(_.spaceBetween)
    private val titleStyle = Style.fontSize(22.px).fontWeight(_.bold)
    // Rows stretch across the full content width; each child grows equally and caps at panelMaxW.
    private val kpiRow    = Style.row.gap(20.px)
    private val chartGrid = Style.row.gap(20.px)

    private val tileStyle =
        Style.column.gap(6.px).padding(16.px).bg(panelBg).rounded(10.px).border(1.px, rule)
            .flexGrow(1.0).flexBasis(0.px)
    private val tileLabel = Style.fontSize(12.px).color(mutedCol)
    private val bigNumber = Style.fontSize(34.px).fontWeight(_.bold)
    private val panelStyle =
        Style.column.gap(8.px).padding(12.px).bg(panelBg).rounded(10.px).border(1.px, rule)
            .flexGrow(1.0).flexBasis(0.px).maxWidth(panelMaxW.px)
    private val panelTitle = Style.fontSize(14.px).color(mutedCol)

    private val btnStyle =
        Style.padding(8.px, 16.px).bg(accent).color(_.white).border(0.px, accent).rounded(8.px).cursor(_.pointer)

    /** Format a non-negative magnitude as an integer with thousands separators (e.g. 2438 -> "2,438").
      *
      * Pure and allocation-light: groups the digit string from the right in threes via a fold, no var/while.
      */
    private def withThousands(v: Double): String =
        val digits = v.toLong.toString
        val n      = digits.length
        digits.zipWithIndex
            .map { case (c, i) =>
                val fromRight = n - i
                if i > 0 && fromRight % 3 == 0 then s",$c" else c.toString
            }
            .mkString
    end withThousands

    private def fmt0(v: Double): String = v.toLong.toString
    private def fmt1(v: Double): String = f"$v%.1f"

    /** Label the rolling-window x-axis: the newest slot (t = 0) reads "now", older slots read their relative
      * tick index (e.g. -20, -10). Used as the x-axis tick formatter for the latency and error-rate charts.
      */
    private def timeAxisLabel(t: Double): String =
        if math.abs(t) < 0.5 then "now" else t.toLong.toString

    // ---- KPI tile (reactive UI, not a chart) ----

    private def tile(label: String, value: String, numberColor: Color)(using Frame): UI.Ast.Div =
        UI.div.style(tileStyle)(
            UI.div(label).style(tileLabel),
            UI.div(value).style(bigNumber.color(numberColor))
        )

    // ---- app ----

    private[demo] def app: UI < Async =
        for
            throughput <- Signal.initRef(initThroughput)
            latency    <- Signal.initRef(initLatency)
            status     <- Signal.initRef(initStatus)
            errRate    <- Signal.initRef(initErr)
            paused     <- Signal.initRef(false)

            // background engine: random-walk every metric on a fixed cadence, threading a pure seed (no var/while).
            _ <- Fiber.initUnscoped {
                Loop(0x9e3779b97f4a7c15L) { seed =>
                    paused.get.map { isPaused =>
                        if isPaused then Async.sleep(200.millis).andThen(Loop.continue(seed))
                        else
                            for
                                t <- throughput.get
                                l <- latency.get
                                s <- status.get
                                e <- errRate.get
                                (s1, nt) = stepThroughput(seed, t)
                                (s2, nl) = stepLatency(s1, l)
                                (s3, ns) = stepStatus(s2, s)
                                ne       = stepErr(e, errorPct(ns))
                                _ <- throughput.set(nt)
                                _ <- latency.set(nl)
                                _ <- status.set(ns)
                                _ <- errRate.set(ne)
                                _ <- Async.sleep(700.millis)
                            yield Loop.continue(s3)
                    }
                }
            }

            // KPI tiles: each reads one metric and recolors its number by threshold.
            totalTile = throughput.render(t => tile("Total req/s", withThousands(totalRps(t)), green))
            errorTile = status.render { s =>
                val pct   = errorPct(s)
                val color = if pct > 5.0 then red else if pct > 2.0 then amber else green
                tile("Error %", fmt1(pct) + "%", color)
            }
            p99Tile = latency.render { l =>
                val v     = p99Now(l)
                val color = if v > 300.0 then red else if v > 200.0 then amber else green
                tile("p99 latency", fmt0(v) + " ms", color)
            }

            // A chart whose data source is a `Signal` is reactive by construction: the layer redraws only the
            // marks region on each emission and keeps the frame (axes, legend) stable, so no `.render` wrapper
            // is needed. (Wrapping would rebuild the whole chart per tick and reset the animation state.)
            // Throughput bar chart: FIXED y-domain so the axis stays put; bars animate to new heights.
            throughputChart = Chart(throughput)(bar(x = _.name, y = _.rps, color = _.name))
                .yAxis(_.left.grid.ticks(4))
                .xAxis(_.bottom)
                .yScale(_.linear(0.0, rpsMax))
                .legend(_.hidden)
                .theme(_.dark)
                .animate(_.ease(400.millis))
                .size(chartW, chartH)
                .toSvg

            // Latency lines over the fixed 24-slot rolling window: one line per series via the color channel,
            // so the layer derives a p50/p99 legend. Cyan p50 and amber p99 stay distinct from the bars.
            latencyChart = Chart(latency)(line(x = _.t, y = _.ms, color = _.series))
                .yAxis(_.left.grid.ticks(4))
                .xAxis(_.bottom.format(timeAxisLabel))
                .yScale(_.linear(0.0, latMax))
                .legend(
                    _.top.colorScale {
                        case "p50" => scCyan
                        case _     => scAmber
                    }
                )
                .theme(_.dark)
                .animate(_.ease(400.millis))
                .size(chartW, chartH)
                .toSvg

            // Status stacked bar grouped by code, colored by monitoring convention: 2xx green, 4xx amber, 5xx red.
            statusChart = Chart(status)(bar(x = _.name, y = _.count, stack = by(_.code)))
                .yAxis(_.left.grid.ticks(4))
                .xAxis(_.bottom)
                .legend(
                    _.top.colorScale {
                        case "2xx" => scGreen
                        case "4xx" => scAmber
                        case _     => scRed
                    }
                )
                .theme(_.dark)
                .size(chartW, chartH)
                .toSvg

            // Error-rate line over the rolling window: fills the bottom-right cell so all four quadrants balance.
            errorChart = Chart(errRate)(line(x = _.t, y = _.pct))
                .yAxis(_.left.grid.ticks(4))
                .xAxis(_.bottom.format(timeAxisLabel))
                .yScale(_.linear(0.0, 10.0))
                .theme(_.dark)
                .animate(_.ease(400.millis))
                .size(chartW, chartH)
                .toSvg

            // Pause / resume control; label reacts to the paused signal.
            pauseBtn = paused.render { isPaused =>
                UI.button(if isPaused then "Resume" else "Pause").style(btnStyle).onClick(paused.updateAndGet(!_))
            }
        yield UI.div.style(pageStyle)(
            UI.div.style(headerRow)(
                UI.div("Service Metrics").style(titleStyle),
                pauseBtn
            ),
            UI.div.style(kpiRow)(totalTile, errorTile, p99Tile),
            UI.div.style(chartGrid)(
                UI.div.style(panelStyle)(UI.div("Throughput (req/s)").style(panelTitle), throughputChart),
                UI.div.style(panelStyle)(UI.div("Latency p50 / p99 (ms)").style(panelTitle), latencyChart)
            ),
            UI.div.style(chartGrid)(
                UI.div.style(panelStyle)(UI.div("Status codes by endpoint").style(panelTitle), statusChart),
                UI.div.style(panelStyle)(UI.div("Error rate (%)").style(panelTitle), errorChart)
            )
        )

    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(app)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"LiveDashboard running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end LiveDashboard
