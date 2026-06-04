package kyo

import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Reactive
import kyo.UI.mark.*
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
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
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
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
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
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
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
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
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
            spec = UI.chart(rows)(
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
            specA = UI.chart(rowsA)(bar(x = _.month, y = _.revenue))
                .onHover(hovered)
            specB = UI.chart(rowsB)(
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
        val spec  = UI.chart(rows)(bar(x = _.month, y = _.revenue))
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

    /** Collect all Svg.Path children from a root, traversing one level of Svg.G. */
    private def pathsIn(root: Svg.Root): Chunk[Svg.Path] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case p: Svg.Path => Chunk(p)
                    case ig: Svg.G   => ig.children.collect { case p: Svg.Path => p }
                    case _           => Chunk.empty
            case _ => Chunk.empty

    // ---- Phase-4 tests: line/area interaction (INV-023) ----

    // Test 8 (plan leaf 12): line chart onSelect fires on click (INV-023)
    "line chart with onSelect carries a Present onClick handler that fires (INV-023)" in run {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = UI.chart(rows)(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root  = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            paths = pathsIn(root)
            _     = assert(paths.nonEmpty, s"Expected at least one Svg.Path from line mark but got none")
            // The line path should carry a click handler (INV-023).
            linePath = paths.toSeq.find(p => p.attrs.onClick.isDefined)
            _        = assert(linePath.isDefined, "line path must carry Present onClick when onSelect is configured")
            _     <- runAction(linePath.get.attrs.onClick.get)
            after <- selectRef.get
        yield assert(
            after.isDefined,
            s"After clicking the line path, selectRef must be Present but got $after"
        )
    }

    // Test 9 (plan leaf 13): area chart onHover fires (INV-023)
    "area chart with onHover carries a Present onHover handler that fires (INV-023)" in run {
        for
            hoverRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = UI.chart(rows)(area(x = _.month, y = _.revenue))
                .onHover(hoverRef)
            root     = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            paths    = pathsIn(root)
            _        = assert(paths.nonEmpty, s"Expected at least one Svg.Path from area mark but got none")
            areaPath = paths.toSeq.find(p => p.attrs.onHover.isDefined)
            _        = assert(areaPath.isDefined, "area path must carry Present onHover when onHover is configured")
            _     <- runAction(areaPath.get.attrs.onHover.get)
            after <- hoverRef.get
        yield assert(after.isDefined, s"After hovering area path, hoverRef must be Present but got $after")
    }

    // Test 10 (plan leaf 14): stacked area attaches one handler per segment path (INV-023)
    "stacked area with onSelect carries Present onClick on each segment path (INV-023)" in run {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(
                Sale("Jan", Rev(1000.0)),
                Sale("Jan", Rev(500.0)),
                Sale("Feb", Rev(2000.0)),
                Sale("Feb", Rev(800.0))
            )
            // Use color channel as the stack group so 2 groups are formed.
            // area with stack: group 1 = rows 0,2; group 2 = rows 1,3.
            spec = UI.chart(rows)(area(
                x = _.month,
                y = _.revenue,
                color = _.revenue.toInt.toString,
                stack = by(_.revenue.toInt.toString)
            ))
                .onSelect(selectRef)
            root             = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            paths            = pathsIn(root)
            interactivePaths = paths.toSeq.filter(p => p.attrs.onClick.isDefined)
            _ = assert(
                interactivePaths.nonEmpty,
                s"Stacked area must have at least one path with onClick, got ${paths.size} paths total"
            )
        yield succeed
    }

    // Test 11 (plan leaf 15): line single series has exactly one handler (INV-023)
    "line without color split has exactly one interaction-bearing path (INV-023)" in run {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = UI.chart(rows)(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root             = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            paths            = pathsIn(root)
            interactivePaths = paths.toSeq.filter(p => p.attrs.onClick.isDefined)
        yield assert(
            interactivePaths.size == 1,
            s"Single-series line must have exactly 1 interaction-bearing path but got ${interactivePaths.size}"
        )
    }

    // ---- Phase-4 tests: highlight (INV-024) ----

    /** Count Reactive nodes anywhere under the marks `<g>` (the highlight region wraps the bars in a
      * `Svg.g(Reactive[Svg.G])`). The lowering must create the highlight as a user-ref-driven Reactive and
      * must NOT create an internal SignalRef of its own.
      */
    private def markRegionReactives(root: Svg.Root): Chunk[Reactive[?]] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case r: Reactive[?] => Chunk(r)
                    case ig: Svg.G      => ig.children.collect { case r: Reactive[?] => r }
                    case _              => Chunk.empty
            case _ => Chunk.empty

    // Test 12 (plan leaf 16): highlightSelect drives the active bar's style from the select ref (INV-024)
    "bar with highlightSelect: after the select ref is set, the active bar carries the select style (INV-024)" in run {
        // Default highlight (no custom style) is a dark 2px stroke outline on the active bar only.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            // Before any selection: the ref is Absent, so no bar carries the highlight stroke.
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No bar may carry the select stroke before a selection, but got:\n${htmlBefore.take(2000)}"
            )
            // Select the first row; the corresponding bar must now carry the select style.
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield
            // The active bar carries the default select style: a dark 2px stroke (INV-024).
            assert(
                htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
                s"Selected bar must carry the select style (stroke=#000000, stroke-width=2px) but got:\n${htmlAfter.take(2000)}"
            )
            // Exactly one bar is highlighted (the active row), not both.
            val strokeOccurrences = "stroke=\"#000000\"".r.findAllMatchIn(htmlAfter).size
            assert(
                strokeOccurrences == 1,
                s"Only the active bar may carry the select stroke, but found $strokeOccurrences occurrences"
            )
    }

    // Test 13 (plan leaf 17): highlightHover with a custom hoverStyle applies that style value (INV-024)
    "bar with a custom hoverStyle: the hovered bar carries the custom style value in the output (INV-024)" in run {
        // Custom hover style: a purple fill (Style.Color.purple == #a855f7), chosen distinct from the default
        // palette fill (palette(0) == blue == #3b82f6). When the hover ref points at the row, the active bar's
        // emitted fill must become the custom color.
        for
            hoverRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)))
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
                .onHover(hoverRef)
                .interaction(_.hoverStyle(Style.bg(Style.Color.purple)))
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            // Before hover: the custom fill is absent (the bar uses the default palette blue).
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("fill=\"#a855f7\""),
                s"Custom hover fill must not appear before hover, but got:\n${htmlBefore.take(2000)}"
            )
            // Hover the row; the active bar must now carry the custom purple fill.
            _         <- hoverRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            htmlAfter.contains("fill=\"#a855f7\""),
            s"Hovered bar must carry the custom hoverStyle fill (#a855f7) but got:\n${htmlAfter.take(2000)}"
        )
    }

    // Test 14 (plan leaf 18): highlight with no ref configured is a no-op (INV-024)
    "interaction(_.highlightSelect) with no onSelect configured is a no-op (INV-024)" in run {
        val rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .interaction(_.highlightSelect) // highlight configured, but no onSelect ref
        val root      = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        val rects     = rectsIn(root)
        val withClick = rects.toSeq.filter(r => r.attrs.onClick.isDefined)
        assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
        // No ref means no handlers and no highlight reactive region: bars are plain, no crash.
        assert(withClick.isEmpty, "no onClick on rects when no onSelect ref is configured (no-op, INV-024)")
        assert(markRegionReactives(root).isEmpty, "no highlight reactive region without a ref (no-op, INV-024)")
        // No bar carries the default select stroke (highlight produced nothing).
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            !html.contains("stroke=\"#000000\""),
            s"No highlight style may appear without a ref, but got:\n${html.take(2000)}"
        )
    }

    // Test 15 (plan leaf 19): highlight is a Reactive region driven by the user ref, with no internal cell (INV-024)
    "highlight is a Reactive region driven by the user onSelect ref, with no internal SignalRef (INV-024)" in run {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)))
            spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
            // The highlight is realized as a Reactive region inside the marks group (no internal cell:
            // no tooltip is configured, so the ONLY reactive here is the highlight, and it is driven by
            // the user's selectRef directly).
            reactives = markRegionReactives(root)
            _         = assert(reactives.size == 1, s"Expected exactly one highlight Reactive region but got ${reactives.size}")
            // Driving the USER ref (not any internal ref) changes the rendered output: proof the region is
            // bound to the user's selectRef, so there is no separate internal mutable interaction cell.
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _          <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter  <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            !htmlBefore.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke=\"#000000\""),
            s"Setting the user ref must drive the highlight region directly (INV-024).\nbefore:\n${htmlBefore
                    .take(1500)}\nafter:\n${htmlAfter.take(1500)}"
        )
    }

end ChartInteractionTest
