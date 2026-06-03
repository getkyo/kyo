package kyo

import kyo.Chart.*
import kyo.Svg.Coord
import kyo.internal.ChartLower
import scala.language.implicitConversions

/** Phase 08 demo-parity test.
  *
  * Verifies that `Chart(data)(bar(...)).size(600, 310)` lowers to the SAME bar `Svg.Rect` geometry
  * (x, y, width, height per bar) as `BarChart.renderBarChart` for the same dataset.
  *
  * Size 600x310 is chosen so the chart layer's layout constants match `BarChart.scala` exactly:
  *   - MarginLeft=60, MarginRight=20, MarginTop=20, MarginBottom=40
  *   - plotX=60, plotY=20, plotW=600-60-20=520, plotH=310-20-40=250, plotBaseline=270
  *
  * `BarChart.scala` layout: W=600, H=320, marginL=60, marginR=20, marginTop=20, marginBot=50
  *   - chartW=520, chartH=250, baseline=270
  *
  * Both produce the same bandScale (n=3, totalWidth=520, padding=0.1) and the same linearScale
  * (domain=[0, 61200], rangeMin=270, rangeMax=20), so x/y/width/height are identical per bar.
  *
  * Expected geometry per bar (computed from BarChart.scala's scale math):
  *   slot = 520/3, bandWidth = 520*0.9/3 = 156.0
  *   For bar i: xPx = 60 + i*slot + (slot-156)/2
  *   yScale(v) = 270 + (v/61200) * (20 - 270) = 270 - v*(250/61200)
  *   barTop = yScale(v), barH = 270 - barTop = v*(250/61200)
  *
  * Bars (in data order): kyo=61200, cats=49800, zio=52100.
  */
class ChartDemoParityTest extends Test:

    // ---- domain type matching BarChartViaLayer.scala ----

    case class Runtime(name: String, opsPerSec: Double)
    given CanEqual[Runtime, Runtime] = CanEqual.derived

    val runtimeData: Chunk[Runtime] = Chunk(
        Runtime("kyo", 61_200.0),
        Runtime("cats", 49_800.0),
        Runtime("zio", 52_100.0)
    )

    // ---- layout constants (must match BarChart.scala's hand layout) ----

    // BarChart.scala: W=600, H=320, marginBot=50, marginTop=20, marginL=60, marginR=20
    // chartW = 600-60-20 = 520, chartH = 320-20-50 = 250, baseline = 250+20 = 270
    private val ChartW   = 520.0
    private val Baseline = 270.0
    private val MarginL  = 60.0
    private val N        = 3

    // ---- scale helpers (copied from BarChart.scala) ----

    /** Linear scale matching BarChart.scala's linearScale. */
    private def linearScale(v: Double, domainMax: Double): Double =
        val t = v / domainMax
        Baseline + t * (20.0 - Baseline)

    /** Band scale matching BarChart.scala's bandScale. */
    private def bandScale(i: Int): (Double, Double) =
        val slot = ChartW / N.toDouble
        val bw   = ChartW * (1.0 - 0.1) / N.toDouble
        val off  = i.toDouble * slot + (slot - bw) / 2.0
        (off + MarginL, bw)
    end bandScale

    // ---- expected values from hand-demo math ----

    private val maxVal = 61_200.0

    // kyo bar (i=0)
    private val (kyoX, kyoW) = bandScale(0)                  // (68.666..., 156.0)
    private val kyoTop       = linearScale(61_200.0, maxVal) // 20.0
    private val kyoH         = Baseline - kyoTop             // 250.0

    // cats bar (i=1)
    private val (catsX, catsW) = bandScale(1) // (242.0, 156.0)
    private val catsTop        = linearScale(49_800.0, maxVal)
    private val catsH          = Baseline - catsTop

    // zio bar (i=2)
    private val (zioX, zioW) = bandScale(2) // (415.333..., 156.0)
    private val zioTop       = linearScale(52_100.0, maxVal)
    private val zioH         = Baseline - zioTop

    // ---- tolerance for floating-point comparison ----

    private val Tol = 1.0e-6

    private def assertClose(actual: Double, expected: Double, msg: String): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected=$expected actual=$actual diff=${math.abs(actual - expected)}")

    // ---- helpers to extract rect geometry ----

    private def numOf(c: Maybe[Coord]): Double = c match
        case Present(Coord.Num(v)) => v
        case other                 => fail(s"Expected Coord.Num but got $other")

    /** Extract Svg.Rect nodes from the marks G (last G child of root). */
    private def marksRects(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.last match
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case _           => Chunk.empty
            case _ => Chunk.empty

    // ---- Test 1: verify expected values match the BarChart.scala math ----

    "expected geometry computed from BarChart.scala band+linear math is self-consistent" in {
        // Slot width
        val slot = ChartW / N.toDouble
        assertClose(slot, 173.3333333333333, "slot")
        assertClose(kyoW, 156.0, "bandWidth")
        assertClose(catsW, 156.0, "bandWidth")
        assertClose(zioW, 156.0, "bandWidth")
        // x positions
        assertClose(kyoX, MarginL + (slot - 156.0) / 2.0, "kyoX")
        assertClose(catsX, MarginL + slot + (slot - 156.0) / 2.0, "catsX")
        assertClose(zioX, MarginL + 2 * slot + (slot - 156.0) / 2.0, "zioX")
        // y values
        assertClose(kyoTop, 20.0, "kyoTop (kyo bar fills full plot height)")
        assertClose(kyoH, 250.0, "kyoH")
    }

    // ---- Test 2: Chart layer parity (the core parity assertion) ----

    "Chart(data)(bar(...)).size(600,310) lowers to the same rect geometry as BarChart.renderBarChart" in {
        // Lower via the chart layer
        val spec = Chart(runtimeData)(bar(x = _.name, y = _.opsPerSec))
            .size(600, 310)
        val root  = spec.toSvg
        val rects = marksRects(root)

        // Three bars expected
        assert(rects.size == 3, s"Expected 3 bar rects but got ${rects.size}")

        // Bar 0: kyo
        val r0 = rects(0)
        assertClose(numOf(r0.svgAttrs.x), kyoX, "kyo barX")
        assertClose(numOf(r0.svgAttrs.y), kyoTop, "kyo barY (barTop)")
        assertClose(numOf(r0.svgAttrs.width), kyoW, "kyo barWidth")
        assertClose(numOf(r0.svgAttrs.height), kyoH, "kyo barHeight")

        // Bar 1: cats
        val r1 = rects(1)
        assertClose(numOf(r1.svgAttrs.x), catsX, "cats barX")
        assertClose(numOf(r1.svgAttrs.y), catsTop, "cats barY (barTop)")
        assertClose(numOf(r1.svgAttrs.width), catsW, "cats barWidth")
        assertClose(numOf(r1.svgAttrs.height), catsH, "cats barHeight")

        // Bar 2: zio
        val r2 = rects(2)
        assertClose(numOf(r2.svgAttrs.x), zioX, "zio barX")
        assertClose(numOf(r2.svgAttrs.y), zioTop, "zio barY (barTop)")
        assertClose(numOf(r2.svgAttrs.width), zioW, "zio barWidth")
        assertClose(numOf(r2.svgAttrs.height), zioH, "zio barHeight")
    }

    // ---- Test 3: parity against BarChart.renderBarChart directly ----

    "Chart layer rect geometry matches BarChart.renderBarChart output for the same data" in {
        // Build hand-demo chart using BarChart.renderBarChart
        val values   = Chunk(61_200.0, 49_800.0, 52_100.0)
        val labs     = Chunk("kyo", "cats", "zio")
        val handRoot = demo.BarChart.renderBarChart(values, labs)(using Frame.internal)

        // Extract bar rects from the hand demo (last group of rects in the children, nested inside Gs)
        val handRects: Chunk[Svg.Rect] = handRoot.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case inner: Svg.G =>
                        inner.children.flatMap:
                            case r: Svg.Rect => Chunk(r)
                            case _           => Chunk.empty
                    case _ => Chunk.empty
            case _ => Chunk.empty
        // BarChart wraps each bar in a Svg.g(rect, text), so we have a Svg.G child per bar containing a Svg.Rect.
        // Filter by height > 0 to get only bar rects, not background or other rects.
        val handBarRects = handRects.filter(r => numOf(r.svgAttrs.height) > 0)
        assert(handBarRects.size == 3, s"Expected 3 bar rects from hand demo but got ${handBarRects.size}")

        // Chart layer rects
        val spec       = Chart(runtimeData)(bar(x = _.name, y = _.opsPerSec)).size(600, 310)
        val layerRoot  = spec.toSvg
        val layerRects = marksRects(layerRoot)
        assert(layerRects.size == 3, s"Expected 3 layer rects but got ${layerRects.size}")

        // Compare per-bar (both are in kyo, cats, zio order)
        val h0 = handBarRects(0)
        val l0 = layerRects(0)
        assertClose(numOf(l0.svgAttrs.x), numOf(h0.svgAttrs.x), "bar[0] x")
        assertClose(numOf(l0.svgAttrs.y), numOf(h0.svgAttrs.y), "bar[0] y")
        assertClose(numOf(l0.svgAttrs.width), numOf(h0.svgAttrs.width), "bar[0] width")
        assertClose(numOf(l0.svgAttrs.height), numOf(h0.svgAttrs.height), "bar[0] height")
        val h1 = handBarRects(1)
        val l1 = layerRects(1)
        assertClose(numOf(l1.svgAttrs.x), numOf(h1.svgAttrs.x), "bar[1] x")
        assertClose(numOf(l1.svgAttrs.y), numOf(h1.svgAttrs.y), "bar[1] y")
        assertClose(numOf(l1.svgAttrs.width), numOf(h1.svgAttrs.width), "bar[1] width")
        assertClose(numOf(l1.svgAttrs.height), numOf(h1.svgAttrs.height), "bar[1] height")
        val h2 = handBarRects(2)
        val l2 = layerRects(2)
        assertClose(numOf(l2.svgAttrs.x), numOf(h2.svgAttrs.x), "bar[2] x")
        assertClose(numOf(l2.svgAttrs.y), numOf(h2.svgAttrs.y), "bar[2] y")
        assertClose(numOf(l2.svgAttrs.width), numOf(h2.svgAttrs.width), "bar[2] width")
        assertClose(numOf(l2.svgAttrs.height), numOf(h2.svgAttrs.height), "bar[2] height")
    }

end ChartDemoParityTest
