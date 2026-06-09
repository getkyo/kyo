package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.Ast.Reactive
import kyo.internal.HtmlRenderer

/** Tests for hover/select handlers, tooltip overlay, reactive rule, and linked views.
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
class ChartInteractionTest extends kyo.test.Test[Any]:

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

    "each mark shape carries Present onHover and onClick handlers when onHover/onSelect are configured" in {
        for
            hoverRef  <- Signal.initRef[Maybe[Sale]](Absent)
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onHover(hoverRef)
                .onSelect(selectRef)
            root <- (spec).lower
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

    "hover handler sets onHover SignalRef to Present(row); unhover sets Absent" in {
        for
            hoverRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onHover(hoverRef)
            root <- (spec).lower
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

    "onClick handler sets onSelect SignalRef to the clicked row" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Mar", Rev(3000.0)), Sale("Apr", Rev(4000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root <- (spec).lower
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

    "tooltip(f) renders f(row) in the overlay Reactive after simulated hover" in {
        for
            rows = Chunk(Sale("May", Rev(500.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .tooltip(s => s"${s.month}: ${s.revenue.toInt}")
            root <- (spec).lower
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

    "rule(y = signal) lowers inside a Reactive; a new threshold moves the rule's scaled y" in {
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
            root  <- (spec).lower
            html0 <- HtmlRenderer.render(root, Seq.empty)
            // Update threshold and re-render.
            _     <- threshold.set(Rev(3000.0))
            html1 <- HtmlRenderer.render(root, Seq.empty)
        yield
            // Both renders should contain SVG content for the reactive rule.
            // The rule is wrapped in a Reactive[Svg.Line] inside a Svg.G.
            // After threshold=1000, the rule y1/y2 is at pixel 335.
            // After threshold=3000, the rule y1/y2 is at pixel 125.
            // A vertical reactive rule is a single Svg.Line whose y1 and y2 BOTH equal the scaled
            // pixel (125 after threshold=3000). Require BOTH endpoints with `&&`: an `||` check would
            // green-light a rule with only one endpoint moved to the new pixel (a misplaced line).
            assert(
                html1.contains("y1=\"125") && html1.contains("y2=\"125"),
                s"Reactive rule must place BOTH y1 and y2 at 125 (threshold=3000), got:\n${html1.take(2000)}"
            )
    }

    // ---- Test 6: linked views: rule(x = hovered.map(...)) tracks published hover ----

    "rule(x = hovered.map(...)) follows the hover signal from another chart" in {
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
            rootA <- (specA).lower
            rootB <- (specB).lower
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
        val rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
        (spec).lower.map { root =>
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

    // ---- line/area interaction ----

    // line chart onSelect fires on click
    "line chart with onSelect carries a Present onClick handler that fires" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root <- (spec).lower
            paths = pathsIn(root)
            _     = assert(paths.nonEmpty, s"Expected at least one Svg.Path from line mark but got none")
            // The line path should carry a click handler.
            linePath = paths.toSeq.find(p => p.attrs.onClick.isDefined)
            _        = assert(linePath.isDefined, "line path must carry Present onClick when onSelect is configured")
            _     <- runAction(linePath.get.attrs.onClick.get)
            after <- selectRef.get
        yield assert(
            after == Present(Sale("Jan", Rev(1000.0))),
            s"After clicking the line path, selectRef must be Present(Sale(Jan, 1000)) but got $after"
        )
    }

    // area chart onHover fires
    "area chart with onHover carries a Present onHover handler that fires" in {
        for
            hoverRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(area(x = _.month, y = _.revenue))
                .onHover(hoverRef)
            root <- (spec).lower
            paths    = pathsIn(root)
            _        = assert(paths.nonEmpty, s"Expected at least one Svg.Path from area mark but got none")
            areaPath = paths.toSeq.find(p => p.attrs.onHover.isDefined)
            _        = assert(areaPath.isDefined, "area path must carry Present onHover when onHover is configured")
            _     <- runAction(areaPath.get.attrs.onHover.get)
            after <- hoverRef.get
        yield assert(
            after == Present(Sale("Jan", Rev(1000.0))),
            s"After hovering area path, hoverRef must be Present(Sale(Jan, 1000)) but got $after"
        )
    }

    // stacked area attaches one handler per segment path
    "stacked area with onSelect carries Present onClick on each segment path" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(
                Sale("Jan", Rev(1000.0)),
                Sale("Jan", Rev(500.0)),
                Sale("Feb", Rev(2000.0)),
                Sale("Feb", Rev(800.0))
            )
            // Use color encoding as the stack group so 2 groups are formed.
            // area with stack: group 1 = rows 0,2; group 2 = rows 1,3.
            spec = Chart(rows)(area(
                x = _.month,
                y = _.revenue,
                color = _.revenue.toInt.toString,
                stack = by(_.revenue.toInt.toString)
            ))
                .onSelect(selectRef)
            root <- (spec).lower
            paths            = pathsIn(root)
            interactivePaths = paths.toSeq.filter(p => p.attrs.onClick.isDefined)
            // 4 distinct revenue values -> 4 stack groups -> one interactive path per group segment.
            _ = assert(
                interactivePaths.size == 4,
                s"Stacked area with 4 stack groups must have exactly 4 paths with onClick, got ${interactivePaths.size} (${paths.size} total paths)"
            )
        yield ()
    }

    // line single series has exactly one handler
    "line without color split has exactly one interaction-bearing path" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root <- (spec).lower
            paths            = pathsIn(root)
            interactivePaths = paths.toSeq.filter(p => p.attrs.onClick.isDefined)
        yield assert(
            interactivePaths.size == 1,
            s"Single-series line must have exactly 1 interaction-bearing path but got ${interactivePaths.size}"
        )
    }

    // ---- highlight ----

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

    // highlightSelect drives the active bar's style from the select ref
    "bar with highlightSelect: after the select ref is set, the active bar carries the select style" in {
        // Default highlight (no custom style) is a dark 2px stroke outline on the active bar only.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root <- (spec).lower
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
            // The active bar carries the default select style: a dark 2px stroke.
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

    // highlightHover with a custom hoverStyle applies that style value
    "bar with a custom hoverStyle: the hovered bar carries the custom style value in the output" in {
        // Custom hover style: a purple fill (Style.Color.purple == #a855f7), chosen distinct from the default
        // palette fill (palette(0) == blue == #3b82f6). When the hover ref points at the row, the active bar's
        // emitted fill must become the custom color.
        for
            hoverRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onHover(hoverRef)
                .interaction(_.hoverStyle(Style.bg(Style.Color.purple)))
            root <- (spec).lower
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

    // highlight with no ref configured is a no-op
    "interaction(_.highlightSelect) with no onSelect configured is a no-op" in {
        val rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        val spec = Chart(rows)(bar(x = _.month, y = _.revenue))
            .interaction(_.highlightSelect) // highlight configured, but no onSelect ref
        (spec).lower.map { root =>
            val rects     = rectsIn(root)
            val withClick = rects.toSeq.filter(r => r.attrs.onClick.isDefined)
            assert(rects.size == 2, s"Expected 2 rects but got ${rects.size}")
            // No ref means no handlers and no highlight reactive region: bars are plain, no crash.
            assert(withClick.isEmpty, "no onClick on rects when no onSelect ref is configured (no-op)")
            assert(markRegionReactives(root).isEmpty, "no highlight reactive region without a ref (no-op)")
            // No bar carries the default select stroke (highlight produced nothing).
            for html <- HtmlRenderer.render(root, Seq.empty)
            yield assert(
                !html.contains("stroke=\"#000000\""),
                s"No highlight style may appear without a ref, but got:\n${html.take(2000)}"
            )
        }
    }

    // highlight is a Reactive region driven by the user ref, with no internal cell
    "highlight is a Reactive region driven by the user onSelect ref, with no internal SignalRef" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)))
            spec = Chart(rows)(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root <- (spec).lower
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
            s"Setting the user ref must drive the highlight region directly.\nbefore:\n${htmlBefore
                    .take(1500)}\nafter:\n${htmlAfter.take(1500)}"
        )
    }

    // ---- interactive-legend tests ----

    case class CatRow(x: String, y: Double, cat: String) derives CanEqual

    given CanEqual[Set[Int], Set[Int]] = CanEqual.derived

    /** Coord -> Double helper. */
    private def coordNum(c: Maybe[Svg.Coord]): Maybe[Double] = c match
        case Present(Svg.Coord.Num(v)) => Present(v)
        case _                         => Absent

    /** Collect the legend swatch rects (the 12x12 frame-level rects, direct children of root). */
    private def legendSwatches(root: Svg.Root): Chunk[Svg.Rect] =
        root.children.collect:
            case r: Svg.Rect if coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0) => r

    // ---- clicking a swatch toggles its index in the hiddenSeries ref ----

    "clicking a legend swatch toggles its index in the user hiddenSeries ref" in {
        val rows = Chunk(CatRow("p", 1.0, "catA"), CatRow("q", 2.0, "catB"))
        for
            hidden <- Signal.initRef(Set.empty[Int])
            spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
                .legend(_.interactive(hidden))
            root <- (spec).lower
            // The first swatch corresponds to catA (encounter order, index 0). Its onClick toggles index 0.
            swatches = legendSwatches(root)
            _        = assert(swatches.size == 2, s"Expected 2 legend swatches but got ${swatches.size}")
            click    = swatches(0).attrs.onClick
            _        = assert(click.isDefined, "Expected Present onClick on the interactive swatch")
            _      <- runAction(click.get)
            after1 <- hidden.get
            _      <- runAction(click.get)
            after2 <- hidden.get
        yield
            assert(after1 == Set(0), s"First click should add index 0 (catA) but ref was $after1")
            assert(after2 == Set.empty[Int], s"Second click should remove index 0 (catA) again but ref was $after2")
        end for
    }

    // ---- the hidden filter drops the specified series from the marks ----

    "with hiddenSeries={0}, the catA bar (index 0) is dropped from the marks while catB remains" in {
        // catA at x-band "p" (index 0), catB at x-band "q" (index 1). Hiding index 0 drops catA's bar.
        val rowsFull = Chunk(CatRow("p", 1.0, "catA"), CatRow("q", 2.0, "catB"))
        def markBars(root: Svg.Root): Chunk[Svg.Rect] =
            rectsIn(root).filter(r => !(coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0)))
        for
            none   <- Signal.initRef(Set.empty[Int])
            hidden <- Signal.initRef(Set(0))
            specFull = Chart(rowsFull)(bar(x = _.x, y = _.y, color = _.cat)).legend(_.interactive(none))
            specHid  = Chart(rowsFull)(bar(x = _.x, y = _.y, color = _.cat)).legend(_.interactive(hidden))
            rootFull <- (specFull).lower
            rootHid  <- (specHid).lower
            html     <- HtmlRenderer.render(rootHid, Seq.empty)
        yield
            val fullBars = markBars(rootFull)
            val hidBars  = markBars(rootHid)
            assert(fullBars.size == 2, s"Expected 2 bars with nothing hidden but got ${fullBars.size}")
            assert(hidBars.size == 1, s"Expected exactly 1 bar after hiding catA but got ${hidBars.size}")
            // The surviving bar is catB's: its x-band differs from catA's band-"p" position.
            val catAbandX = fullBars.map(b => coordNum(b.svgAttrs.x).getOrElse(-1.0)).min
            val survivorX = coordNum(hidBars.head.svgAttrs.x).getOrElse(-1.0)
            assert(survivorX != catAbandX, s"The surviving bar must be catB's (different band) but was at catA's band x=$catAbandX")
            // The legend still shows BOTH category labels (so the user can toggle catA back on).
            assert(html.contains(">catA<"), s"Legend must still show the hidden category label catA:\n$html")
            assert(html.contains(">catB<"), s"Legend must show catB:\n$html")
        end for
    }

    // ---- the hidden filter applies before color-splitting ----

    "with 3 series and catB hidden (index 1), mark colors index over the visible set {catA, catC} only" in {
        val rows = Chunk(
            CatRow("p", 1.0, "catA"),
            CatRow("q", 2.0, "catB"),
            CatRow("r", 3.0, "catC")
        )
        for
            hidden <- Signal.initRef(Set(1)) // catB is encounter index 1
            spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
                .legend(_.interactive(hidden))
            root <- (spec).lower
        yield
            // Visible marks are catA and catC. With catB filtered BEFORE color-splitting, the visible set is
            // {catA, catC}: catA -> palette(0)=blue, catC -> palette(1)=orange. (Not catC -> palette(2)=green,
            // which is what would happen if the filter ran AFTER color-splitting over all three.)
            val markFills = rectsIn(root).filter(r =>
                !(coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0))
            ).map(r => r.svgAttrs.fill).toSeq
            assert(
                markFills.contains(Present(Svg.Paint.Color(Style.Color.blue))),
                s"catA mark should be palette(0) blue but mark fills were: $markFills"
            )
            assert(
                markFills.contains(Present(Svg.Paint.Color(Style.Color.orange))),
                s"catC mark should index to palette(1) orange (visible set), not palette(2); fills were: $markFills"
            )
            assert(
                !markFills.contains(Present(Svg.Paint.Color(Style.Color.green))),
                s"No mark should use palette(2) green: the hidden filter ran before color-splitting; fills were: $markFills"
            )
        end for
    }

    // ---- hiding all series leaves the legend visible but the marks empty ----

    "hiding all series leaves the legend swatches visible but the marks region empty" in {
        val rows = Chunk(CatRow("p", 1.0, "catA"), CatRow("q", 2.0, "catB"))
        for
            hidden <- Signal.initRef(Set(0, 1)) // catA=index 0, catB=index 1
            spec = Chart(rows)(bar(x = _.x, y = _.y, color = _.cat))
                .legend(_.interactive(hidden))
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            // No mark rects (all series hidden): only the 12x12 legend swatches remain among rects.
            val markFills = rectsIn(root).filter(r =>
                !(coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0))
            )
            assert(markFills.isEmpty, s"All series hidden: expected no mark rects but found ${markFills.size}")
            // The legend swatches and labels are still present (the user can toggle a series back on).
            assert(legendSwatches(root).size == 2, s"Expected 2 legend swatches to remain visible but got ${legendSwatches(root).size}")
            assert(
                html.contains(">catA<") && html.contains(">catB<"),
                s"Legend labels must remain visible when all series are hidden:\n$html"
            )
        end for
    }

    // ---- highlight coverage for line/area/text/errorBar ----

    // Domain type for errorBar tests (needs low/high accessors).
    case class EB(x: String, y: Double, lo: Double, hi: Double) derives CanEqual

    // line with highlightSelect: the active series path carries stroke="#000000"
    "line with highlightSelect: after the select ref is set, the active series path carries the select style" in {
        // 2-row chart: Jan and Feb. Select Jan; the single-series line path must carry the dark stroke.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No line may carry the select stroke before selection, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
                s"Selected line series must carry stroke=#000000 and stroke-width=2px but got:\n${htmlAfter.take(2000)}"
            )
            // Single series: exactly 1 occurrence of the highlight stroke.
            val strokeOccurrences = "stroke=\"#000000\"".r.findAllMatchIn(htmlAfter).size
            assert(
                strokeOccurrences == 1,
                s"Only the active series path may carry the select stroke, but found $strokeOccurrences occurrences"
            )
    }

    // area with highlightSelect: the active series path carries stroke="#000000"
    "area with highlightSelect: after the select ref is set, the active series path carries the select style" in {
        // 2-row chart: Jan and Feb. Select Jan; the single-series area path must carry the dark stroke.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(area(x = _.month, y = _.revenue))
                .yScale(_.linear(0, 2000))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No area may carry the select stroke before selection, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
                s"Selected area series must carry stroke=#000000 and stroke-width=2px but got:\n${htmlAfter.take(2000)}"
            )
            // Single series: exactly 1 occurrence.
            val strokeOccurrences = "stroke=\"#000000\"".r.findAllMatchIn(htmlAfter).size
            assert(
                strokeOccurrences == 1,
                s"Only the active area series path may carry the select stroke, but found $strokeOccurrences occurrences"
            )
    }

    // text with highlightSelect: the active glyph carries stroke="#000000"
    "text with highlightSelect: after the select ref is set, the active glyph carries the select style" in {
        // 2-row chart: Jan and Feb. Select Jan; only the Jan glyph must carry the dark stroke.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            spec = Chart(rows)(text(x = _.month, y = _.revenue, label = _.month))
                .yScale(_.linear(0, 2000))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No text glyph may carry the select stroke before selection, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
                s"Selected text glyph must carry stroke=#000000 and stroke-width=2px but got:\n${htmlAfter.take(2000)}"
            )
            // 2 rows, 1 active: exactly 1 occurrence.
            val strokeOccurrences = "stroke=\"#000000\"".r.findAllMatchIn(htmlAfter).size
            assert(
                strokeOccurrences == 1,
                s"Only the active text glyph may carry the select stroke, but found $strokeOccurrences occurrences"
            )
    }

    // errorBar with highlightSelect: the active row GROUP carries stroke="#000000" once
    "errorBar with highlightSelect: after the select ref is set, the active row group carries the select style once" in {
        // 2-row chart: Jan and Feb. Select Jan; the Jan error-bar GROUP must carry the dark stroke exactly once.
        // The group wraps the 4 sub-shapes (vLine, capLow, capHigh, marker) so highlight fires once, not 4 times.
        for
            selectRef <- Signal.initRef[Maybe[EB]](Absent)
            rows = Chunk(EB("Jan", 1000.0, 800.0, 1200.0), EB("Feb", 2000.0, 1700.0, 2300.0))
            spec = Chart(rows)(
                errorBar(x = _.x, y = _.y, low = _.lo, high = _.hi)
            )
                .yScale(_.linear(0, 3000))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No errorBar element may carry the select stroke before selection, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(EB("Jan", 1000.0, 800.0, 1200.0)))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(
                htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
                s"Selected errorBar group must carry stroke=#000000 and stroke-width=2px but got:\n${htmlAfter.take(2000)}"
            )
            // The group's stroke="#000000" must appear exactly once (on the group, not on the 4 sub-shapes
            // individually). This validates the Svg.g grouping approach.
            val strokeOccurrences = "stroke=\"#000000\"".r.findAllMatchIn(htmlAfter).size
            assert(
                strokeOccurrences == 1,
                s"The highlight stroke must appear exactly once (on the group element), but found $strokeOccurrences occurrences"
            )
    }

    // ---- Live-path tests: interaction on animated bar/line/area ----
    // These tests verify that the animated (live-chart) arms of marksRegionWithTransitions
    // correctly attach onClick/onHover handlers and withHighlight,
    // so a Chart(signal) with onSelect/onHover/highlightSelect propagates interaction configuration.

    // Test 20: live bar carries onClick handler from onSelect
    "LIVE bar with onSelect: rendered rect carries data-kyo-ev=click" in {
        // AnimateConfig.default.enabled=true, so Chart(signal)(...) routes through
        // marksRegionWithTransitions -> lowerBarSimpleWithTransitions. That arm must call
        // buildInteractionAttrs so the data-kyo-ev attribute is emitted.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            signal = Signal.initConst[Seq[Sale]](rows)
            spec = Chart(signal: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            html.contains("data-kyo-ev") && html.contains("click"),
            s"LIVE bar with onSelect must carry data-kyo-ev=click on rendered rects, but got:\n${html.take(3000)}"
        )
    }

    // Test 21: live bar with highlightSelect: after setting selectRef the active bar carries the select stroke
    "LIVE bar with highlightSelect: after selectRef is set, the active bar carries stroke=#000000" in {
        // withHighlight must be called in lowerBarSimpleWithTransitions;
        // a Reactive highlight region must be created so the select stroke appears.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            signal = Signal.initConst[Seq[Sale]](rows)
            spec = Chart(signal: Signal[Seq[Sale]])(bar(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No bar may carry the select stroke before selection on a live chart, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
            s"LIVE bar with highlightSelect must carry stroke=#000000 after selection, but got:\n${htmlAfter.take(2000)}"
        )
    }

    // Test 22: live line carries onClick handler from onSelect
    "LIVE line with onSelect: rendered path carries data-kyo-ev=click" in {
        // lowerLineWithTransitions must call lowerLineSeries with spec/internalHoverRef,
        // so interaction attrs are attached to the line path.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            signal = Signal.initConst[Seq[Sale]](rows)
            spec = Chart(signal: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            html.contains("data-kyo-ev") && html.contains("click"),
            s"LIVE line with onSelect must carry data-kyo-ev=click on rendered path, but got:\n${html.take(3000)}"
        )
    }

    // Test 23: live line with highlightSelect fires after selection
    "LIVE line with highlightSelect: after selectRef is set, the active series path carries stroke=#000000" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            signal = Signal.initConst[Seq[Sale]](rows)
            spec = Chart(signal: Signal[Seq[Sale]])(line(x = _.month, y = _.revenue))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No live line may carry the select stroke before selection, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
            s"LIVE line with highlightSelect must carry stroke=#000000 after selection, but got:\n${htmlAfter.take(2000)}"
        )
    }

    // Test 24: live area carries onClick handler from onSelect
    "LIVE area with onSelect: rendered path carries data-kyo-ev=click" in {
        // lowerAreaWithTransitions must call lowerArea with internalHoverRef for the
        // non-stacked path, so interaction attrs are on the area path element.
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            signal = Signal.initConst[Seq[Sale]](rows)
            spec = Chart(signal: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue))
                .onSelect(selectRef)
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            html.contains("data-kyo-ev") && html.contains("click"),
            s"LIVE area with onSelect must carry data-kyo-ev=click on rendered path, but got:\n${html.take(3000)}"
        )
    }

    // Test 25: live area with highlightSelect fires after selection
    "LIVE area with highlightSelect: after selectRef is set, the active series path carries stroke=#000000" in {
        for
            selectRef <- Signal.initRef[Maybe[Sale]](Absent)
            rows   = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
            signal = Signal.initConst[Seq[Sale]](rows)
            spec = Chart(signal: Signal[Seq[Sale]])(area(x = _.month, y = _.revenue))
                .yScale(_.linear(0, 2000))
                .onSelect(selectRef)
                .interaction(_.highlightSelect)
            root       <- (spec).lower
            htmlBefore <- HtmlRenderer.render(root, Seq.empty)
            _ = assert(
                !htmlBefore.contains("stroke=\"#000000\""),
                s"No live area may carry the select stroke before selection, but got:\n${htmlBefore.take(2000)}"
            )
            _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
            htmlAfter <- HtmlRenderer.render(root, Seq.empty)
        yield assert(
            htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""),
            s"LIVE area with highlightSelect must carry stroke=#000000 after selection, but got:\n${htmlAfter.take(2000)}"
        )
    }

    // ---- interactive legend hide-set keyed by series index ----

    "colliding-toString categories toggle independently via index-based hiddenSeries Set[Int]" in {
        // Two color categories whose toString both return "x". A Set[String] hide-set would map both
        // categories to the key "x", so toggling one would hide BOTH. With index-based keying,
        // index 0 toggles only category A and index 1 toggles only category B.
        enum Col derives CanEqual, Plottable:
            case A, B
            override def toString: String = "x"

        case class ColRow(pos: String, v: Double, col: Col) derives CanEqual

        val rows = Chunk(
            ColRow("p", 1.0, Col.A),
            ColRow("q", 2.0, Col.B)
        )

        for
            hidden <- Signal.initRef(Set.empty[Int])
            spec = Chart(rows)(bar(x = _.pos, y = _.v, color = _.col))
                .legend(_.interactive(hidden))
            root <- (spec).lower
            // The legend should have 2 swatches, one per color category.
            swatches = legendSwatches(root)
            _        = assert(swatches.size == 2, s"Expected 2 legend swatches but got ${swatches.size}")
            // Click the first swatch: should toggle index 0 (Col.A only).
            click0 = swatches(0).attrs.onClick
            _      = assert(click0.isDefined, "First swatch must have an onClick")
            _      <- runAction(click0.get)
            after0 <- hidden.get
            _ = assert(after0 == Set(0), s"After clicking swatch 0, hiddenSeries must be Set(0) but was $after0")
            // Render the chart: only Col.A rows should be filtered; Col.B must still produce a bar.
            rootHidden <- (spec).lower
        yield
            // Build a fresh root from the same spec to reflect the current hidden state.
            // After hiding index 0 (Col.A), Col.B's row must still be rendered.
            // We compute it by lowering again (hiddenSeries ref is read synchronously at lower time).
            val markBars = rectsIn(rootHidden).filter(r =>
                !(coordNum(r.svgAttrs.width).contains(12.0) && coordNum(r.svgAttrs.height).contains(12.0))
            )
            // Col.A is hidden, Col.B is visible: exactly 1 bar remains.
            assert(
                markBars.size == 1,
                s"Hiding index 0 (Col.A) must leave exactly 1 bar (Col.B), but found ${markBars.size}"
            )
        end for
    }

end ChartInteractionTest
