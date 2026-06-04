package kyo

import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Reactive
import kyo.UI.mark.*
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Phase 05 tests: reactivity, static/reactive split, fixed vs inferred domain.
  *
  * Layout defaults: plotX=60, plotY=20, plotW=560, plotH=420, baseline=440
  * (chart size 640x480, MarginL=60, MarginR=20, MarginT=20, MarginB=40).
  *
  * niceTicks math used in assertions:
  *   niceTicks(0, 200, 5): rawStep=200/4=50, magnitude=10, residual=5.0 -> niceUnit=5, step=50.
  *     Ticks = [0, 50, 100, 150, 200]. Max tick label = "200".
  *   niceTicks(0, 5000, 5): rawStep=5000/4=1250, magnitude=1000, residual=1.25 -> niceUnit=2, step=2000.
  *     fitLinear: tks=[0,2000,4000]; math.max(5000,4000)=5000, so niceDomain=[0,5000].
  *     Scale.Linear(0,5000).ticks(5): niceTicks(0,5000,5)=[0,2000,4000]. Max tick label="4000"; "200" absent.
  *   niceTicks(0, 5000, 5) for fixed-domain tick generation from Scale.Linear(0,5000):
  *     same computation gives ticks [0, 2000, 4000]. Max static tick label = "4000"; "5000" absent.
  *
  * The five tests cover:
  *   1. Structural split: UI.chart(signal)(bar(...)) root has a Reactive child (marks) and static frame elements.
  *   2. Inferred domain: a new, larger max drives the signal; the domain contains the new max (not clipped).
  *   3. Fixed domain: yScale(_.linear(0,5000)) yields a static y-axis outside the Reactive region.
  *   4. Signal-driven height changes: SignalRef.set produces updated rect heights in the reactive region.
  *   5. Reactive BAND x-axis: category labels from the data appear; linear-fallback numeric labels do not.
  */
class ChartReactiveTest extends Test:

    // ---- shared domain types ----

    opaque type Rev <: Double = Double
    object Rev:
        def apply(d: Double): Rev     = d
        given Plottable[Rev]          = Plottable.numeric
        given CanEqual[Rev, Rev]      = CanEqual.derived
        given Conversion[Double, Rev] = d => d
    end Rev

    case class Sale(month: String, revenue: Rev)
    given CanEqual[Sale, Sale] = CanEqual.derived

    /** A status-code count per endpoint; the stack grouping over `code` drives the legend categories. */
    case class StatusRow(name: String, code: String, count: Double) derives CanEqual

    // ---- layout constants (must match ChartLower) ----
    private val PlotX    = 60.0
    private val PlotY    = 20.0
    private val PlotW    = 560.0
    private val PlotH    = 420.0
    private val Baseline = PlotY + PlotH // 440.0
    private val Tol      = 1.0e-6

    private def assertClose(actual: Double, expected: Double, msg: String = ""): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    // ---- Test 1: structural split ----

    "UI.chart(signal)(bar(...)) root contains a Reactive child for marks and static frame elements" in {
        // A constant signal is sufficient for the structural test: no need to drive new values.
        val rows           = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val signal         = Signal.initConst(rows)
        val spec           = UI.chart(signal)(bar(x = _.month, y = _.revenue))
        val root: Svg.Root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // The root children must include at least one Reactive node (the marks region).
        val hasReactive = root.children.exists:
            case _: Reactive[?] => true
            case _              => false
        assert(hasReactive, s"Expected a Reactive child in root but children are: ${root.children.map(_.getClass.getSimpleName)}")

        // The root children must also include static elements NOT wrapped in a Reactive.
        val staticElements = root.children.filter:
            case _: Reactive[?] => false
            case _              => true
        assert(staticElements.nonEmpty, "Expected at least one static frame element outside the Reactive region")

        // Background rect is always static (never reactive).
        val hasBgRect = root.children.exists:
            case _: Svg.Rect => true
            case _           => false
        assert(hasBgRect, "Expected a static background Svg.Rect in root children")
    }

    // ---- Test 2: inferred domain updates tick labels ----

    "inferred domain: new max via signal updates marks and y-axis tick labels in the reactive region" in run {
        // Initial data: max revenue = 200.
        // niceTicks(0, 200, 5): step=50, tks=[0,50,100,150,200]; math.max(200,200)=200 -> domain=[0,200].
        //   Scale.Linear(0, 200, 440, 20).ticks(5) = [0,50,100,150,200]. Labels include "200"; "4000" absent.
        // Updated data: max revenue = 5000.
        // niceTicks(0, 5000, 5): step=2000, tks=[0,2000,4000]; math.max(5000,4000)=5000 -> domain=[0,5000].
        //   Scale.Linear(0, 5000, 440, 20).ticks(5): niceTicks(0,5000,5)=[0,2000,4000].
        //   Labels include "4000"; "200" absent (ticks step by 2000, not 50).
        // Domain [0,5000] contains the data max (5000) -- the fix ensures no clipping.
        // Bar for Jan (rev=2500) maps to height 440-(440+(2500/5000)*(20-440))=210 in the fixed domain.
        val initialRows = Chunk(Sale("Jan", Rev(100.0)), Sale("Feb", Rev(200.0)))
        val updatedRows = Chunk(Sale("Jan", Rev(2500.0)), Sale("Feb", Rev(5000.0)))
        for
            ref <- Signal.initRef(initialRows)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            // Render with initial rows (max=200): tick labels are 0,50,100,150,200. "4000" absent.
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Drive new data with larger max (max=5000).
            _ <- ref.set(updatedRows)
            // Render again: domain=[0,5000]; ticks=[0,2000,4000]. "4000" appears; "200" is absent.
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Initial render contains "200" as a tick label and does NOT contain ">4000<".
            assert(html0.contains(">200<"), s"Expected initial tick label '200' in html0:\n$html0")
            assert(!html0.contains(">4000<"), s"Expected '4000' absent before signal update:\n$html0")
            // After update, domain=[0,5000] (contains the data max); tick "4000" appears.
            assert(html1.contains(">4000<"), s"Expected tick label '4000' after signal update:\n$html1")
            // The domain was NOT clipped to [0,4000]: "200" is no longer a tick (step is 2000 now).
            assert(!html1.contains(">200<"), s"Expected '200' absent after scale update:\n$html1")
        end for
    }

    // ---- Test 3: fixed domain keeps y-axis static ----

    "fixed domain yScale(_.linear(0,5000)): y-axis is static (outside Reactive), only marks are reactive" in {
        val rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val signal = Signal.initConst(rows)
        // niceTicks for fixed domain [0,5000] (nice=false, so Scale.Linear(0,5000)):
        //   ticks(5): niceTicks(0, 5000, 5) = step=2000, ticks=[0, 2000, 4000]. Max label = "4000".
        val spec = UI.chart(signal)(bar(x = _.month, y = _.revenue))
            .yScale(_.linear(0.0, 5000.0))
        val root: Svg.Root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)

        // With a fixed y-domain, the Reactive node wraps ONLY the marks Svg.G.
        // The y-axis ticks live as direct children of root (static), not inside the Reactive.

        // There must be exactly one Reactive node.
        val reactiveChildren = root.children.filter:
            case _: Reactive[?] => true
            case _              => false
        assert(reactiveChildren.size == 1, s"Expected exactly 1 Reactive child but got ${reactiveChildren.size}")

        // There must be static Svg.Text elements (tick labels from the fixed-domain y-axis) among
        // root's direct non-reactive children.
        val staticTexts: Chunk[Svg.Text] = root.children.flatMap:
            case t: Svg.Text    => Chunk(t)
            case _: Reactive[?] => Chunk.empty
            case _              => Chunk.empty
        val staticLabels = staticTexts.map(_.children.headOption match
            case Some(UI.Ast.Text(s)) => s
            case _                    => "")

        // niceTicks(0, 5000, 5) -> step=2000, ticks=[0, 2000, 4000].
        // The tick for "0" must be among static frame texts (proves static y-axis).
        assert(
            staticLabels.toSeq.exists(_.contains("0")),
            s"Expected static y-axis tick '0' outside Reactive but got static texts: $staticLabels"
        )
        // The tick for "4000" (the max from niceTicks) must be among static frame texts.
        assert(
            staticLabels.toSeq.exists(_.contains("4000")),
            s"Expected static y-axis tick '4000' outside Reactive but got static texts: $staticLabels"
        )
    }

    // ---- Test 4: SignalRef.set changes rect heights ----

    "driving the signal with SignalRef changes the marks region rect heights to new scaled values" in run {
        // Fixed domain yScale(_.linear(0, 4000)) so the scale does not change between emissions.
        // This isolates the height-change assertion to the marks values, not scale changes.
        // Scale.Linear(0, 4000, 440, 20), nice=false (linear override):
        //   rev=1000: apply(1000) = 440 + (1000/4000)*(-420) = 440-105 = 335. barH = 440-335 = 105.
        //   rev=4000: apply(4000) = 440 + (4000/4000)*(-420) = 440-420 = 20.  barH = 440-20  = 420.
        val initialRows = Chunk(Sale("Jan", Rev(1000.0)))
        val updatedRows = Chunk(Sale("Jan", Rev(4000.0)))
        for
            ref <- Signal.initRef(initialRows)
            spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            // Render with initial rows: barH = 105.
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Drive updated rows with full-height revenue.
            _ <- ref.set(updatedRows)
            // Render with updated rows: barH = 420.
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // height="105" appears in the initial rendering (bar for revenue=1000).
            assert(html0.contains("height=\"105\""), s"Expected height=105 in initial render:\n$html0")
            // height="420" appears after the update (bar for revenue=4000).
            assert(html1.contains("height=\"420\""), s"Expected height=420 after update:\n$html1")
            // After update, the old height (105) is absent (reactive region fully re-renders).
            assert(!html1.contains("height=\"105\""), s"Expected height=105 absent after update:\n$html1")
        end for
    }

    // ---- Test 5: reactive BAND x-axis emits category labels, not linear-fallback numerics ----

    "reactive bar chart x-axis emits band category labels (Jan, Feb) not linear-fallback numerics (0.25)" in run {
        // x-axis for a bar chart with categories "Jan" and "Feb" must use a Band scale sourced from
        // the live data. If the x-axis is built from Chunk.empty (the old static approach), the extent
        // is Continuous(0, -1) (empty data default), which resolves to a Linear [0, 1] fallback and
        // emits tick labels "0", "0.25", "0.5", "0.75", "1" -- none of which are category names.
        // After Must-fix 3 the x-axis is built inside the reactive region using the live xs, which is a
        // Band scale over the actual categories, so tick labels "Jan" and "Feb" appear and "0.25" does not.
        val rows           = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val signal         = Signal.initConst(rows)
        val spec           = UI.chart(signal)(bar(x = _.month, y = _.revenue))
        val root: Svg.Root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        for
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Category labels must appear: the reactive x-axis ticks use the Band scale.
            assert(html.contains(">Jan<"), s"Expected '>Jan<' in HTML (band x-axis label) but was absent:\n$html")
            assert(html.contains(">Feb<"), s"Expected '>Feb<' in HTML (band x-axis label) but was absent:\n$html")
            // Linear-fallback numeric labels must NOT appear: they indicate the x-axis used an empty-data
            // fallback (Linear [0,1]) instead of the real Band scale.
            assert(!html.contains(">0.25<"), s"Expected '>0.25<' absent (linear fallback) but found it:\n$html")
        end for
    }

    // ---- Test 6: live legend with explicit colorScale emits swatches+labels in the reserved band ----

    "live chart .legend(_.top.colorScale{...}) emits a legend g (swatches + labels) at in-bounds y" in run {
        // Regression: the live (signal-backed) lowering built the static legend from an empty initial chunk,
        // which yields zero categories and suppresses the whole legend. The dashboard's status/latency charts
        // are live, so no legend rendered at all. The fix samples the signal's current value so the legend
        // derives its categories (here the stack groups 2xx/4xx/5xx) from real rows.
        val rows = Chunk(
            StatusRow("/login", "2xx", 90.0),
            StatusRow("/login", "4xx", 8.0),
            StatusRow("/login", "5xx", 2.0),
            StatusRow("/feed", "2xx", 80.0),
            StatusRow("/feed", "4xx", 12.0),
            StatusRow("/feed", "5xx", 4.0)
        )
        val scGreen = Style.Color.rgb(34, 197, 94)
        val scAmber = Style.Color.rgb(245, 158, 11)
        val scRed   = Style.Color.rgb(239, 68, 68)
        for
            ref <- Signal.initRef(rows)
            spec = UI.chart(ref: Signal[Chunk[StatusRow]])(bar(x = _.name, y = _.count, stack = by(_.code)))
                .legend(
                    _.top.colorScale {
                        case "2xx" => scGreen
                        case "4xx" => scAmber
                        case _     => scRed
                    }
                )
                .theme(_.dark)
                .size(520, 240)
            root = summon[Conversion[ChartSpec[StatusRow], Svg.Root]](spec)
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The three legend labels must be present (one per stack category).
            assert(html.contains(">2xx<"), s"Expected legend label '2xx' but it was absent:\n$html")
            assert(html.contains(">4xx<"), s"Expected legend label '4xx' but it was absent:\n$html")
            assert(html.contains(">5xx<"), s"Expected legend label '5xx' but it was absent:\n$html")

            // The legend lives in the static frame: the swatch rects and label texts are DIRECT children of
            // root (not nested in the reactive marks g). Collect them and assert exactly three of each.
            def coordNum(c: Maybe[Svg.Coord]): Maybe[Double] = c match
                case Present(Svg.Coord.Num(v)) => Present(v)
                case _                         => Absent
            val swatchFills = root.children.collect:
                case r: Svg.Rect if coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0) =>
                    r.svgAttrs.fill
            assert(swatchFills.size == 3, s"Expected 3 legend swatches (12x12 rects) but found ${swatchFills.size}")
            // Swatch fills carry the colorScale colors (green/amber/red), not the theme chrome color.
            val greenPaint: Maybe[Svg.Paint] = Present(Svg.Paint.Color(scGreen))
            val amberPaint: Maybe[Svg.Paint] = Present(Svg.Paint.Color(scAmber))
            val redPaint: Maybe[Svg.Paint]   = Present(Svg.Paint.Color(scRed))
            assert(swatchFills.contains(greenPaint), s"Expected a green (2xx) swatch fill but fills were: $swatchFills")
            assert(swatchFills.contains(amberPaint), s"Expected an amber (4xx) swatch fill but fills were: $swatchFills")
            assert(swatchFills.contains(redPaint), s"Expected a red (5xx) swatch fill but fills were: $swatchFills")

            // Legend label texts are static root children; assert there are 3 and each y is in-bounds (0..240)
            // and inside the reserved band (the top 20px strip, y <= 36 covers the swatch+label centre).
            val labelYs: Chunk[Double] = root.children.collect:
                case t: Svg.Text if coordNum(t.svgAttrs.y).exists(_ <= 36.0) => coordNum(t.svgAttrs.y).getOrElse(-1.0)
            assert(labelYs.size == 3, s"Expected 3 legend labels in the top band but found ${labelYs.size}: $labelYs")
            assert(
                labelYs.forall(y => y >= 0.0 && y <= 240.0),
                s"Every legend label y must be inside the viewBox 0..240 but were: $labelYs"
            )
        end for
    }

end ChartReactiveTest
