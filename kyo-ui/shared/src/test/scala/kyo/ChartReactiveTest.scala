package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Reactive
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Tests for reactivity, static/reactive split, fixed vs inferred domain.
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
  *   1. Structural split: Chart(signal)(bar(...)) root has a Reactive child (marks) and static frame elements.
  *   2. Inferred domain: a new, larger max drives the signal; the domain contains the new max (not clipped).
  *   3. Fixed domain: yScale(_.linear(0,5000)) yields a static y-axis outside the Reactive region.
  *   4. Signal-driven height changes: SignalRef.set produces updated rect heights in the reactive region.
  *   5. Reactive BAND x-axis: category labels from the data appear; linear-fallback numeric labels do not.
  */
class ChartReactiveTest extends kyo.test.Test[Any]:

    // ---- shared domain types ----

    opaque type Rev <: Double = Double
    object Rev:
        def apply(d: Double): Rev                = d
        given Plottable[Rev]                     = Plottable.numeric
        given CanEqual[Rev, Rev]                 = CanEqual.derived
        implicit def doubleToRev(d: Double): Rev = d
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

    private def assertClose(actual: Double, expected: Double, msg: String = "")(using Frame, kyo.test.AssertScope): Unit =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    // ---- Test 1: structural split ----

    "Chart(signal)(bar(...)) root contains a Reactive child for marks and static frame elements" in {
        // A constant signal is sufficient for the structural test: no need to drive new values.
        val rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val signal = Signal.initConst[Seq[Sale]](rows)
        val spec   = Chart(signal)(bar(x = _.month, y = _.revenue))
        (spec).lower.map { root =>
            // The root children must include at least one Reactive node (the marks region).
            val hasReactive = root.children.exists:
                case _: Reactive[?] => true
                case _              => false
            assert(
                hasReactive,
                s"Expected a Reactive child in root but found ${root.children.size} children, none of which matched Reactive[?]"
            )

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
    }

    // ---- Test 2: inferred domain updates tick labels ----

    "inferred domain: new max via signal updates marks and y-axis tick labels in the reactive region" in {
        // Initial data: max revenue = 200.
        // niceTicks(0, 200, 5): step=50, tks=[0,50,100,150,200]; math.max(200,200)=200 -> domain=[0,200].
        //   Scale.Linear(0, 200, 440, 20).ticks(5) = [0,50,100,150,200]. Labels include "200"; "4000" absent.
        // Updated data: max revenue = 5000.
        // niceTicks(0, 5000, 5): step=2000, tks=[0,2000,4000]; math.max(5000,4000)=5000 -> domain=[0,5000].
        //   Scale.Linear(0, 5000, 440, 20).ticks(5): niceTicks(0,5000,5)=[0,2000,4000].
        //   Labels include "4000"; "200" absent (ticks step by 2000, not 50).
        // Domain [0,5000] contains the data max (5000); the scale is fitted to the full signal extent.
        // Bar for Jan (rev=2500) maps to height 440-(440+(2500/5000)*(20-440))=210 in the fixed domain.
        val initialRows = Chunk(Sale("Jan", Rev(100.0)), Sale("Feb", Rev(200.0)))
        val updatedRows = Chunk(Sale("Jan", Rev(2500.0)), Sale("Feb", Rev(5000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initialRows)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
            root <- (spec).lower
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
            // The domain spans [0,5000]: "200" is not a tick at step 2000.
            assert(!html1.contains(">200<"), s"Expected '200' absent after scale update:\n$html1")
        end for
    }

    // ---- Test 3: fixed domain keeps y-axis static ----

    "fixed domain yScale(_.linear(0,5000)): y-axis is static (outside Reactive), only marks are reactive" in {
        val rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val signal = Signal.initConst[Seq[Sale]](rows)
        // niceTicks for fixed domain [0,5000] (nice=false, so Scale.Linear(0,5000)):
        //   ticks(5): niceTicks(0, 5000, 5) = step=2000, ticks=[0, 2000, 4000]. Max label = "4000".
        val spec = Chart(signal)(bar(x = _.month, y = _.revenue))
            .yScale(_.linear(0.0, 5000.0))
        (spec).lower.map { root =>
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
            // `exists(_.contains("0"))` proved nothing: "0", "2000", "4000" all contain a "0".
            // Assert EXACT membership of each y-tick label so a wrong tick set fails.
            assert(
                staticLabels.toSeq.contains("0"),
                s"Expected the exact static y-axis tick '0' but got static texts: $staticLabels"
            )
            assert(
                staticLabels.toSeq.contains("2000"),
                s"Expected the exact static y-axis tick '2000' but got static texts: $staticLabels"
            )
            assert(
                staticLabels.toSeq.contains("4000"),
                s"Expected the exact static y-axis tick '4000' but got static texts: $staticLabels"
            )
            // The full y-tick numeric label set must be exactly {0, 2000, 4000} (no extra/missing tick).
            val yTickLabels = staticLabels.toSeq.filter(s => Set("0", "2000", "4000").contains(s)).toSet
            assert(
                yTickLabels == Set("0", "2000", "4000"),
                s"Expected y-tick labels exactly {0,2000,4000} but got: $yTickLabels (all static: $staticLabels)"
            )
        }
    }

    // ---- Test 4: SignalRef.set changes rect heights ----

    "driving the signal with SignalRef changes the marks region rect heights to new scaled values" in {
        // Fixed domain yScale(_.linear(0, 4000)) so the scale does not change between emissions.
        // This isolates the height-change assertion to the marks values, not scale changes.
        // Scale.Linear(0, 4000, 440, 20), nice=false (linear override):
        //   rev=1000: apply(1000) = 440 + (1000/4000)*(-420) = 440-105 = 335. barH = 440-335 = 105.
        //   rev=4000: apply(4000) = 440 + (4000/4000)*(-420) = 440-420 = 20.  barH = 440-20  = 420.
        val initialRows = Chunk(Sale("Jan", Rev(1000.0)))
        val updatedRows = Chunk(Sale("Jan", Rev(4000.0)))
        for
            ref <- Signal.initRef[Seq[Sale]](initialRows)
            spec = Chart(ref: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .yScale(_.linear(0.0, 4000.0))
            root <- (spec).lower
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

    "reactive bar chart x-axis emits band category labels (Jan, Feb) not linear-fallback numerics (0.25)" in {
        // x-axis for a bar chart with categories "Jan" and "Feb" must use a Band scale sourced from
        // the live data. If the x-axis were built from Chunk.empty, the extent
        // is Continuous(0, -1) (empty data default), which resolves to a Linear [0, 1] fallback and
        // emits tick labels "0", "0.25", "0.5", "0.75", "1" -- none of which are category names.
        // The x-axis is built inside the reactive region using the live xs, which is a
        // Band scale over the actual categories, so tick labels "Jan" and "Feb" appear and "0.25" does not.
        val rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val signal = Signal.initConst[Seq[Sale]](rows)
        val spec   = Chart(signal)(bar(x = _.month, y = _.revenue))
        for
            root <- (spec).lower
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

    // ---- Test 6: live legend with explicit colorScale emits swatches+labels in the rendered emission ----

    "live chart .legend(_.top.colorScale{...}) emits a legend (swatches + labels) in the rendered HTML" in {
        // The live legend is built inside the reactive region, so its swatches and labels appear in
        // the rendered HTML of each emission. The category set (2xx/4xx/5xx) comes from the live stack groups.
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
            ref <- Signal.initRef[Seq[StatusRow]](rows)
            spec = Chart(ref: Signal[Seq[StatusRow]])(bar(x = _.name, y = _.count, stack = by(_.code)))
                .legend(
                    _.top.colorScale {
                        case "2xx" => scGreen
                        case "4xx" => scAmber
                        case _     => scRed
                    }
                )
                .theme(_.dark)
                .size(520, 240)
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The three legend labels must be present (one per stack category).
            assert(html.contains(">2xx<"), s"Expected legend label '2xx' but it was absent:\n$html")
            assert(html.contains(">4xx<"), s"Expected legend label '4xx' but it was absent:\n$html")
            assert(html.contains(">5xx<"), s"Expected legend label '5xx' but it was absent:\n$html")

            // The legend swatches carry the colorScale colors (green/amber/red) as fills in the HTML.
            assert(html.contains("rgb(34, 197, 94)"), s"Expected a green (2xx) swatch fill in HTML:\n$html")
            assert(html.contains("rgb(245, 158, 11)"), s"Expected an amber (4xx) swatch fill in HTML:\n$html")
            assert(html.contains("rgb(239, 68, 68)"), s"Expected a red (5xx) swatch fill in HTML:\n$html")

            // Three 12x12 swatch rects are emitted (one per category).
            val swatchCount = """width="12"""".r.findAllMatchIn(html).size
            assert(swatchCount >= 3, s"Expected at least 3 legend swatches (12-wide rects) but found $swatchCount:\n$html")
        end for
    }

    // ---- a late color category gets a swatch on the emission that introduces it ----

    "a live chart legend gains a swatch for a category that appears after the first emission" in {
        given CanEqual[Chunk[StatusRow], Chunk[StatusRow]] = CanEqual.derived
        for
            ref <- Signal.initRef[Seq[StatusRow]](Chunk(StatusRow("/x", "a", 5.0)))
            spec = Chart(ref: Signal[Seq[StatusRow]])(bar(x = _.name, y = _.count, color = _.code))
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _          <- ref.set(Chunk(StatusRow("/x", "a", 5.0), StatusRow("/y", "b", 7.0)))
            htmlAfter  <- HtmlRenderer.render(root, Seq.empty)
        yield
            // First emission: only category "a" has a swatch+label. Category "b" is absent.
            assert(htmlBefore.contains(">a<"), s"Expected category label 'a' in the first emission:\n$htmlBefore")
            assert(!htmlBefore.contains(">b<"), s"Category 'b' must NOT appear before it is introduced:\n$htmlBefore")
            // Second emission: the new category "b" gains its own swatch+label.
            assert(htmlAfter.contains(">a<"), s"Category 'a' must still appear after the second emission:\n$htmlAfter")
            assert(htmlAfter.contains(">b<"), s"New category 'b' swatch+label must appear on the emission that introduces it:\n$htmlAfter")
        end for
    }

    // ---- a fixed-category live legend is unchanged across emissions ----

    "a live chart legend with a fixed category set is unchanged across emissions" in {
        given CanEqual[Chunk[StatusRow], Chunk[StatusRow]] = CanEqual.derived
        for
            ref <- Signal.initRef[Seq[StatusRow]](Chunk(StatusRow("/x", "a", 5.0), StatusRow("/y", "b", 7.0)))
            spec = Chart(ref: Signal[Seq[StatusRow]])(bar(x = _.name, y = _.count, color = _.code))
            root  <- (spec).lower
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // New values, same two categories {a, b}.
            _     <- ref.set(Chunk(StatusRow("/x", "a", 50.0), StatusRow("/y", "b", 70.0)))
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both categories present in both emissions; the count of swatch labels is stable.
            def labelCount(h: String, label: String): Int = s">$label<".r.findAllMatchIn(h).size
            assert(labelCount(html0, "a") == labelCount(html1, "a"), "Category 'a' label count must be stable across emissions")
            assert(labelCount(html0, "b") == labelCount(html1, "b"), "Category 'b' label count must be stable across emissions")
            assert(html0.contains(">a<") && html0.contains(">b<"), "Both categories must appear in the first emission")
            assert(html1.contains(">a<") && html1.contains(">b<"), "Both categories must appear in the second emission")
        end for
    }

    // ---- the legend is reactive, built from emitted rows, not a one-shot sample ----

    "live chart legend is built from reactive rows: an empty first emission yields no swatch, a later one does" in {
        // If the lowering still sampled signal.current once (the removed one-shot), the FIRST render would show
        // swatches sampled from the ref's current value. Because the legend is built per emission from the
        // emitted rows, an empty first emission yields NO swatch, and a later non-empty emission yields one.
        given CanEqual[Chunk[StatusRow], Chunk[StatusRow]] = CanEqual.derived
        for
            ref <- Signal.initRef[Seq[StatusRow]](Chunk.empty[StatusRow])
            spec = Chart(ref: Signal[Seq[StatusRow]])(bar(x = _.name, y = _.count, color = _.code))
            root      <- (spec).lower
            htmlEmpty <- HtmlRenderer.render(root, Seq.empty)
            _         <- ref.set(Chunk(StatusRow("/x", "a", 5.0)))
            htmlFull  <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Empty first emission: no category 'a' label.
            assert(!htmlEmpty.contains(">a<"), s"Empty first emission must produce no legend swatch:\n$htmlEmpty")
            // After the non-empty emission: the legend has the 'a' swatch+label.
            assert(htmlFull.contains(">a<"), s"After a non-empty emission the legend must show the 'a' swatch:\n$htmlFull")
        end for
    }

end ChartReactiveTest
