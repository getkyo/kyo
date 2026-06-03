package kyo

import kyo.UI.Ast.Reactive
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

/** Phase 07 tests: hover/select handlers, tooltip overlay, reactive rule, linked views.
  *
  * Layout defaults: plotX=60, plotY=20, plotW=560, plotH=420, baseline=440
  * (chart size 640x480, MarginL=60, MarginR=20, MarginT=20, MarginB=40).
  *
  * Scale used in geometry assertions: yScale(_.linear(0, 4000)), nice=false.
  *   pixel(v) = 440 + (v / 4000) * (20 - 440) = 440 - v * 0.105
  *   barH(v) = 440 - pixel(v) = v * 0.105
  *
  * The six tests cover:
  *   1. Mark shapes carry Present onHover and onClick attrs when the chart has onHover/onSelect configured.
  *   2. Driving the hover handler sets the user SignalRef to Present(row); unhover sets Absent.
  *   3. onSelect: running the click handler sets the select ref to the clicked row.
  *   4. tooltip(f): after simulated hover, the overlay Reactive g renders f(row) text.
  *   5. Reactive rule: rule(y = signal) lowers inside a Reactive Svg.G; a new signal value moves the
  *      rule's scaled y.
  *   6. Linked views: rule(x = hovered.map(...)) follows the published hover signal.
  */
class ChartInteractionTest extends Test:

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

    // ---- layout constants (must match ChartLower) ----
    private val PlotX    = 60.0
    private val PlotY    = 20.0
    private val PlotW    = 560.0
    private val PlotH    = 420.0
    private val Baseline = PlotY + PlotH // 440.0

    // ---- helpers ----

    /** Collect all Svg.Rect children from a root, traversing one level of Svg.G. */
    private def rectsIn(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Svg.Rect => Chunk(r)
                    case ig: Svg.G =>
                        ig.children.collect { case r: Svg.Rect => r }
                    case _ => Chunk.empty
            case _ => Chunk.empty

    /** Collect all Svg.Circle children. */
    private def circlesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case ig: Svg.G     => ig.children.collect { case c: Svg.Circle => c }
                    case _             => Chunk.empty
            case _ => Chunk.empty

    /** Collect all Reactive children from a root at the top level. */
    private def reactivesIn(root: Svg.Root): Chunk[Reactive[?]] =
        root.children.collect:
            case r: Reactive[?] => r

    /** Run an `Any < Async` action as a test effect and return Unit. */
    private def runAction(action: Any < Async)(using Frame): Unit < Async =
        action.map(_ => ())

    // ---- Test 1: each mark shape carries Present onHover and onClick handlers ----

    "each mark shape carries Present onHover and onClick handlers when onHover/onSelect are configured" in run {
        for
            hoverRef  <- Signal.initRef[Maybe[Sale]](Absent)
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onHover(hoverRef)
                .onSelect(selectRef)
            root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            rects = rectsIn(root)
        yield
            // Both bars should be present.
            assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
            // Each rect carries onHover and onClick; assert them individually (foreach returns Unit, not Assertion).
            val r0 = rects(0)
            val r1 = rects(1)
            assert(r0.attrs.onHover.isDefined, "rect[0]: Expected Present onHover")
            assert(r0.attrs.onClick.isDefined, "rect[0]: Expected Present onClick")
            assert(r1.attrs.onHover.isDefined, "rect[1]: Expected Present onHover")
            assert(r1.attrs.onClick.isDefined, "rect[1]: Expected Present onClick")
    }

    // ---- Test 2: hover/unhover handlers set and clear the user SignalRef ----

    "hover handler sets onHover SignalRef to Present(row); unhover sets Absent" in run {
        for
            hoverRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onHover(hoverRef)
            root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            rects = rectsIn(root)
            _     = assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
            rect  = rects(0)
            // onHover is Present; run the action.
            hoverAction = rect.attrs.onHover
            _           = assert(hoverAction.isDefined, "Expected Present onHover on rect")
            _          <- runAction(hoverAction.get)
            afterHover <- hoverRef.get
            // onUnhover is Present; run it.
            unhoverAction = rect.attrs.onUnhover
            _             = assert(unhoverAction.isDefined, "Expected Present onUnhover on rect")
            _            <- runAction(unhoverAction.get)
            afterUnhover <- hoverRef.get
        yield
            // After hover, ref should be Present(Sale("Jan", Rev(1000.0))).
            assert(
                afterHover == Present(Sale("Jan", Rev(1000.0))),
                s"Expected Present(Sale(Jan, 1000)) after hover but got $afterHover"
            )
            // After unhover, ref should be Absent.
            assert(
                afterUnhover == Absent,
                s"Expected Absent after unhover but got $afterUnhover"
            )
    }

    // ---- Test 3: onClick sets onSelect SignalRef ----

    "onClick handler sets onSelect SignalRef to the clicked row" in run {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Mar", Rev(3000.0)), Sale("Apr", Rev(4000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            rects = rectsIn(root)
            _     = assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
            // Click the second rect (Apr, Rev(4000)).
            clickAction = rects(1).attrs.onClick
            _           = assert(clickAction.isDefined, "Expected Present onClick on second rect")
            _          <- runAction(clickAction.get)
            afterClick <- selectRef.get
        yield assert(
            afterClick == Present(Sale("Apr", Rev(4000.0))),
            s"Expected Present(Sale(Apr, 4000)) after click but got $afterClick"
        )
    }

    // ---- Test 4: tooltip(f) renders overlay text after simulated hover ----

    "tooltip(f) renders f(row) in the overlay Reactive after simulated hover" in run {
        for
            rows = Chunk(Sale("May", Rev(500.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .tooltip(s => s"${s.month}: ${s.revenue.toInt}")
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            // Locate the tooltip overlay: last Reactive child of root.
            topReactives = reactivesIn(root)
            _            = assert(topReactives.nonEmpty, "Expected at least one Reactive child (tooltip overlay)")
            // Locate the bar rect and its onHover handler.
            rects = rectsIn(root)
            _     = assert(rects.size == 1, s"Expected 1 rect but got ${rects.size}")
            rect  = rects(0)
            _     = assert(rect.attrs.onHover.isDefined, "Expected Present onHover on rect for tooltip")
            // Simulate hover: set the internal hover ref.
            _ <- runAction(rect.attrs.onHover.get)
            // Render the root; the tooltip overlay should now contain the formatted text.
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The rendered HTML should contain the exact formatted tooltip text.
            // Using .toInt avoids platform-dependent Double.toString ("500.0" on JVM, "500" on JS).
            assert(
                html.contains("May: 500"),
                s"Expected tooltip text 'May: 500' in rendered HTML but got:\n${html.take(2000)}"
            )
    }

    // ---- Test 5: reactive rule lowers inside Reactive; new signal value moves scaled y ----

    "rule(y = signal) lowers inside a Reactive; a new threshold moves the rule's scaled y" in run {
        // Scale: linear(0, 4000), baseline=440.
        //   y=1000: pixel = 440 - 1000*0.105 = 335
        //   y=3000: pixel = 440 - 3000*0.105 = 125
        for
            threshold <- Signal.initRef[Rev](Rev(1000.0))
            rows = Chunk(Sale("Jun", Rev(2000.0)))
            spec = Chart(rows)(
                bar(x = _.month, y = _.revenue),
                rule(y = (threshold: Signal[Rev]))
            ).yScale(_.linear(0.0, 4000.0))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Update threshold and re-render.
            _     <- threshold.set(Rev(3000.0))
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both renders should contain SVG content for the reactive rule.
            // The rule is wrapped in a Reactive[Svg.Line] inside a Svg.G.
            // After threshold=1000, the rule y1/y2 is at pixel 335.
            // After threshold=3000, the rule y1/y2 is at pixel 125.
            // We verify the second render contains the new position (y1="125").
            assert(
                html1.contains("y1=\"125") || html1.contains("y2=\"125"),
                s"Expected rule y at 125 (threshold=3000) in updated render but got:\n${html1.take(2000)}"
            )
    }

    // ---- Test 6: linked views: rule(x = hovered.map(...)) tracks published hover ----

    "rule(x = hovered.map(...)) follows the hover signal from another chart" in run {
        // Layout: band x-scale over [Jan, Feb, Mar]. Chart B uses a rule tracking hovered month.
        // Band scale: 3 categories in plotW=560. Each slot = 560/3 = 186.666... px.
        //   padding=0.1, bandW = 560*0.9/3 = 168.0 px
        //   Jan (i=0): left edge = plotX + (slot - bandW)/2 = 60 + 9.333... = 69.333...
        //   Jan center = left edge + bandW/2 = 69.333... + 84.0 = 153.33333333333331
        // The x-rule for a category is placed at the BAND CENTER (left edge + bandwidth/2) so the
        // vertical guide line bisects the bar, not its left wall.
        // Exact rendered string via NumberFormat.double: "153.33333333333331"
        for
            hovered <- Signal.initRef[Maybe[Sale]](Absent)
            rowsA = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)), Sale("Mar", Rev(3000.0)))
            rowsB = Chunk(Sale("Jan", Rev(500.0)), Sale("Feb", Rev(1500.0)), Sale("Mar", Rev(2500.0)))
            specA = Chart(rowsA)(bar(x = _.month, y = _.revenue))
                .onHover(hovered)
            specB = Chart(rowsB)(
                bar(x = _.month, y = _.revenue),
                rule(x = hovered.map[Maybe[String]](_.map(_.month)))
            )
            rootA = summon[Conversion[ChartSpec[Sale], Svg.Root]](specA)
            rootB = summon[Conversion[ChartSpec[Sale], Svg.Root]](specB)
            // Chart A: hover the "Jan" bar (index 0 in the chunk, first rect).
            rectsA  = rectsIn(rootA)
            _       = assert(rectsA.size == 3, s"Expected 3 rects in chart A but got ${rectsA.size}")
            janRect = rectsA(0)
            _       = assert(janRect.attrs.onHover.isDefined, "Expected onHover on Jan rect in chart A")
            // Simulate hover on Jan in chart A.
            _ <- runAction(janRect.attrs.onHover.get)
            // Render chart B; the reactive rule should reflect the hovered month "Jan".
            htmlB <- HtmlRenderer.render(rootB, Seq.empty)
        yield
            // The rule is a vertical Svg.Line: x1 == x2 == band center for "Jan".
            // Both endpoints must carry the exact center value, confirming this is the vertical rule
            // and not a coincidental axis element.
            val expectedX = "153.33333333333331"
            assert(
                htmlB.contains(s"""x1="$expectedX""""),
                s"Expected rule x1=$expectedX in chart B HTML but got:\n${htmlB.take(2000)}"
            )
            assert(
                htmlB.contains(s"""x2="$expectedX""""),
                s"Expected rule x2=$expectedX in chart B HTML but got:\n${htmlB.take(2000)}"
            )
    }

    // ---- Test 7: static charts with no interaction stay clean ----

    "static chart with no onHover/onSelect/tooltip has Absent handler attrs and no tooltip Reactive" in {
        // A chart configured with only data and marks carries no interaction. The lowered shapes
        // must not have onHover or onClick attached, and the root must contain no tooltip Reactive.
        val rows  = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val spec  = Chart(rows)(bar(x = _.month, y = _.revenue))
        val root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects = rectsIn(root)
        assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
        // Each mark rect must have Absent onHover and Absent onClick: no spurious handler wiring.
        val r0 = rects(0)
        val r1 = rects(1)
        assert(r0.attrs.onHover.isEmpty, s"rect[0]: Expected Absent onHover on static chart but got Present")
        assert(r0.attrs.onClick.isEmpty, s"rect[0]: Expected Absent onClick on static chart but got Present")
        assert(r1.attrs.onHover.isEmpty, s"rect[1]: Expected Absent onHover on static chart but got Present")
        assert(r1.attrs.onClick.isEmpty, s"rect[1]: Expected Absent onClick on static chart but got Present")
        // The root must contain no top-level Reactive children: a static chart has no tooltip overlay.
        val topReactives = reactivesIn(root)
        assert(
            topReactives.isEmpty,
            s"Expected no Reactive children in static chart root but got ${topReactives.size}"
        )
    }

end ChartInteractionTest
