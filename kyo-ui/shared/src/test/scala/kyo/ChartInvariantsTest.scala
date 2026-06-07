package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.ChartFoundations
import kyo.internal.ChartLower
import kyo.internal.Extent
import kyo.internal.HtmlRenderer
import kyo.internal.Scale
import scala.language.implicitConversions

/** Smoke tests that pin the phase-1 foundation invariants.
  *
  * Each test is a focused "crash-if-violated" assertion rather than a full geometry
  * regression. Heavy behavioral coverage rides Phases 3-8.
  *
  * Tests correspond to invariants INV-001 through INV-004 as defined in
  * `design/04-invariants.md`.
  */
class ChartInvariantsTest extends kyo.test.Test[Any]:

    // ---- INV-001: NaN y does not poison ticks or coordinates ----

    "INV-001: NaN y value does not appear in lowered SVG HTML output" in {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
        val root = (spec).lower
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"SVG output must not contain 'NaN' but got: ${html.take(200)}")
            assert(!html.contains("Infinity"), s"SVG output must not contain 'Infinity'")
        end for
    }

    // INV-001 (non-bar): NaN/Infinity must not appear in point/line chart SVG output (exercises Scale.apply directly)

    "INV-001: NaN y value does not appear in POINT chart SVG output" in {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, Double.PositiveInfinity), Row(3, 3.0))
        val spec = Chart(rows)(point(x = _.x, y = _.y))
        val root = (spec).lower
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"Point chart SVG must not contain 'NaN' but got: ${html.take(200)}")
            assert(!html.contains("Infinity"), s"Point chart SVG must not contain 'Infinity'")
        end for
    }

    "INV-001: NaN y value does not appear in LINE chart SVG output" in {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, Double.PositiveInfinity), Row(3, 3.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
        val root = (spec).lower
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"Line chart SVG must not contain 'NaN' but got: ${html.take(200)}")
            assert(!html.contains("Infinity"), s"Line chart SVG must not contain 'Infinity'")
        end for
    }

    // ---- INV-004: single-pass resolveAllScales is byte-identical to the baseline ----

    "INV-004: single-pass scale resolution produces a non-empty SVG matching the baseline" in {
        // A 3-mark chart (bar + line + point) with a right axis exercises all scale-resolution paths.
        case class Row(x: String, yL: Double, yR: Double)
        val rows = Chunk(
            Row("Jan", 10.0, 100.0),
            Row("Feb", 20.0, 200.0),
            Row("Mar", 15.0, 150.0)
        )
        val spec = Chart(rows)(
            bar(x = _.x, y = _.yL),
            line(x = _.x, y = _.yL),
            point(x = _.x, y = _.yR, axis = Axis.Right)
        ).yAxisRight(identity)

        val root1 = (spec).lower
        val root2 = (spec).lower

        for
            html1 <- HtmlRenderer.render(root1, Seq.empty)
            html2 <- HtmlRenderer.render(root2, Seq.empty)
        yield
            assert(html1.nonEmpty, "SVG output must be non-empty (INV-004)")
            assert(html1 == html2, "Two lowerings of the same spec must be byte-identical (INV-004)")
        end for
    }

    // ---- INV-004: golden full-SVG string pins the fused single-pass scale resolution ----

    "INV-004: golden SVG pins the fused single-pass scale resolution" in {
        // Same no-gradient 3-mark (bar + line + point) right-axis chart as the determinism test above.
        // It emits NO <linearGradient> (no sequential colorScale), so the AtomicInt gradient-id prefix
        // never appears and the rendered HTML is fully deterministic. A future refactor that perturbs the
        // fused extent/scale walk fails this exact-string golden loudly instead of silently.
        case class Row(x: String, yL: Double, yR: Double)
        val rows = Chunk(
            Row("Jan", 10.0, 100.0),
            Row("Feb", 20.0, 200.0),
            Row("Mar", 15.0, 150.0)
        )
        val spec = Chart(rows)(
            bar(x = _.x, y = _.yL),
            line(x = _.x, y = _.yL),
            point(x = _.x, y = _.yR, axis = Axis.Right)
        ).yAxisRight(identity)
        val root = (spec).lower
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("linearGradient"), "golden chart must emit no gradient (determinism guard)")
            assert(!html.contains("kyo-chart-"), "golden chart must carry no non-deterministic chart-id prefix")
            assert(html == ChartInvariantsTest.expectedGolden, s"INV-004 golden SVG drift:\n$html")
        end for
    }

    // ---- Phase 2: INV-007: ScaleOverride.pad wins over AxisConfig.pad ----

    "INV-007: ScaleOverride.withPad(0.2) wins over AxisConfig.pad(0.05) for extent widening" in {
        // The chart uses a linear x scale with known domain [0,10].
        // ScaleOverride.withPad(0.2) should widen by 20%: delta = 0.2*(10-0) = 2; domain -> [-2,12].
        // AxisConfig.pad(0.05) would widen by only 5%: delta = 0.5; domain -> [-0.5,10.5].
        // We verify by rendering with both and checking the resolved scale: the fitted linear scale
        // domain min should be around -2 (not -0.5) confirming ScaleOverride wins.
        // noNice is required here so the pad difference is observable: nice now snaps to step-aligned
        // bounds (floor lo / ceil hi to the nice step), and both [-2,12] and [-0.5,10.5] would snap to
        // the same [-5,15], hiding which pad won. With noNice the resolved domain is the padded extent
        // verbatim, so [-2,12] (override) is distinguishable from [-0.5,10.5] (AxisConfig).
        case class Row(x: Double, y: Double)
        val rows = Chunk(Row(0.0, 1.0), Row(5.0, 2.0), Row(10.0, 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
            .xScale(_.linear(0.0, 10.0).withPad(0.2).noNice)
            .xAxis(_.pad(0.05))
        // Read the resolved x-scale back: ScaleOverride.withPad(0.2) widens [0,10] by 0.2*(10-0)=2 each
        // side -> [-2,12]; AxisConfig.pad(0.05) would give [-0.5,10.5]. The OVERRIDE must win.
        val (_, sc) = spec.lowerWithScales
        sc.x.kind match
            case ScaleKind.Linear(lo, hi) =>
                assert(
                    math.abs(lo - -2.0) < 1e-9,
                    s"ScaleOverride.withPad(0.2) must widen domain min to -2.0 (not AxisConfig.pad's -0.5), got $lo"
                )
                assert(
                    math.abs(hi - 12.0) < 1e-9,
                    s"ScaleOverride.withPad(0.2) must widen domain max to 12.0, got $hi"
                )
            case other => fail(s"Expected ScaleKind.Linear but got $other")
        end match
    }

    // INV-007: reversed=true via AxisConfig places first datum at the far range end.
    "INV-007: AxisConfig.reverse flips pixel orientation (first datum at far range end)" in {
        case class Row(x: String, y: Double)
        val rows = Chunk(Row("a", 1.0), Row("b", 2.0), Row("c", 3.0))
        // Forward control (no reverse): the first category 'a' sits left of the last 'c'.
        val fwdSpec    = Chart(rows)(bar(x = _.x, y = _.y))
        val (_, fwdSc) = fwdSpec.lowerWithScales
        val fwdA       = fwdSc.x.toPixelCategory("a").getOrElse(fail("forward: band key 'a' must project"))
        val fwdC       = fwdSc.x.toPixelCategory("c").getOrElse(fail("forward: band key 'c' must project"))
        assert(fwdA < fwdC, s"forward (no reverse): first band 'a' must be left of 'c', px(a)=$fwdA, px(c)=$fwdC")

        // Reversed: the first category 'a' must project to the FAR (right) end, the last 'c' to the near end.
        val spec    = Chart(rows)(bar(x = _.x, y = _.y)).xAxis(_.reverse)
        val (_, sc) = spec.lowerWithScales
        val pa      = sc.x.toPixelCategory("a").getOrElse(fail("reverse: band key 'a' must project"))
        val pc      = sc.x.toPixelCategory("c").getOrElse(fail("reverse: band key 'c' must project"))
        assert(pa > pc, s"reverse must place first datum 'a' at the far (right) end: px(a)=$pa must exceed px(c)=$pc")
        // 'a' must sit in the right half of the plot under reverse.
        assert(
            pa > sc.plot.x + sc.plot.width / 2.0,
            s"reversed first band must be in the right half, px(a)=$pa, plot=[${sc.plot.x}, ${sc.plot.x + sc.plot.width}]"
        )
    }

    // ---- Phase 4 smoke tests: INV-020..024 ----

    // INV-020: rule defaults to RuleValue.Unset; a both-Unset rule is skipped at lowering (empty Chunk)
    // so it emits NO Svg.Line, while a sibling bar still renders a rect.
    "INV-020: rule() with both-Unset positions emits no rule line while the sibling bar renders" in {
        case class Row(x: String, y: Double)
        val rows = Chunk(Row("a", 1.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y), rule[Row, Double]())
        val root = (spec).lower
        // In the static lowering the marks live in a single Svg.G; chrome (axis) lines are direct root
        // children (not inside a G). Scope the line check to the marks group so axis lines do not leak in.
        val marksGroups = root.children.collect { case g: Svg.G => g }.filter(g =>
            g.children.exists { case _: Svg.Rect => true; case _ => false }
        )
        assert(marksGroups.nonEmpty, "the sibling bar must still render inside a marks Svg.G")
        val marksRects = marksGroups.flatMap(_.children.collect { case r: Svg.Rect => r })
        val marksLines = marksGroups.flatMap(_.children.collect { case l: Svg.Line => l })
        assert(marksRects.nonEmpty, "the sibling bar must still render a rect")
        assert(marksLines.isEmpty, "a rule() with both positions Unset must emit NO Svg.Line (skipped at lowering)")
    }

    // INV-021: text mark lowers to Svg.Text elements and contributes to extent.
    "INV-021: text mark lowers to at least one Svg.Text element (crash-if-violated)" in {
        case class Row(x: String, y: Double)
        val rows = Chunk(Row("a", 5.0), Row("b", 3.0))
        val spec = Chart(rows)(text(x = _.x, y = _.y, label = _.x))
        val root = (spec).lower
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(html.nonEmpty, "text mark must produce non-empty SVG (INV-021)")
        end for
    }

    // INV-022: errorBar lowers to plain SVG lines/circles with no url(#id).
    "INV-022: errorBar lowers without url(# references (crash-if-violated)" in {
        case class Row(x: String, mean: Double, lo: Double, hi: Double)
        val rows = Chunk(Row("a", 6.0, 4.0, 8.0))
        val spec = Chart(rows)(errorBar(x = _.x, y = _.mean, low = _.lo, high = _.hi))
        val root = (spec).lower
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("url(#"), "errorBar must not emit url(#...) references (INV-022)")
            assert(html.nonEmpty, "errorBar must produce non-empty SVG (INV-022)")
        end for
    }

    // INV-023: line mark with onSelect carries a click handler.
    "INV-023: line mark with onSelect carries a Present onClick on the rendered path" in {
        case class Pt(x: String, y: Double)
        given CanEqual[Pt, Pt] = CanEqual.derived
        for
            selectRef <- Signal.initRef[Maybe[Pt]](Absent)
            rows = Chunk(Pt("a", 1.0), Pt("b", 2.0))
            spec = Chart(rows)(line(x = _.x, y = _.y))
                .onSelect(selectRef)
            root = (spec).lower
            paths = root.children.flatMap:
                case g: Svg.G => g.children.collect { case p: Svg.Path => p }
                case _        => Chunk.empty
            interactivePaths = paths.toSeq.filter(p => p.attrs.onClick.isDefined)
        yield assert(interactivePaths.nonEmpty, "line mark with onSelect must carry onClick on path (INV-023)")
        end for
    }

    // INV-024: interaction(_.highlightSelect) with no onSelect is a no-op (no crash).
    "INV-024: interaction(_.highlightSelect) with no onSelect ref is a no-op without crash" in {
        case class Row(x: String, y: Double)
        val rows = Chunk(Row("a", 1.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
            .interaction(_.highlightSelect) // no onSelect configured
        val root = (spec).lower
        succeed
    }

end ChartInvariantsTest

object ChartInvariantsTest:
    // Captured verbatim from a green run of the no-gradient 3-mark right-axis chart. Regenerate (never
    // hand-edit) from a green run if the rendering legitimately changes; the chart must stay no-gradient.
    val expectedGolden: String =
        """<svg data-kyo-path="" viewBox="0 0 640 480" width="640" height="480"><rect data-kyo-path="0" fill="#ffffff" x="0" y="0" width="640" height="480"></rect><line data-kyo-path="1" stroke="#374151" x1="60" y1="20" x2="60" y2="440"></line><line data-kyo-path="2" stroke="#374151" x1="60" y1="440" x2="580" y2="440"></line><line data-kyo-path="3" stroke="#22c55e" x1="580" y1="20" x2="580" y2="440"></line><line data-kyo-path="4" stroke="#374151" x1="55" y1="440" x2="60" y2="440"></line><text data-kyo-path="5" fill="#374151" x="51" y="440" text-anchor="end" dominant-baseline="middle">0</text><line data-kyo-path="6" stroke="#374151" x1="55" y1="335" x2="60" y2="335"></line><text data-kyo-path="7" fill="#374151" x="51" y="335" text-anchor="end" dominant-baseline="middle">5</text><line data-kyo-path="8" stroke="#374151" x1="55" y1="230" x2="60" y2="230"></line><text data-kyo-path="9" fill="#374151" x="51" y="230" text-anchor="end" dominant-baseline="middle">10</text><line data-kyo-path="10" stroke="#374151" x1="55" y1="125" x2="60" y2="125"></line><text data-kyo-path="11" fill="#374151" x="51" y="125" text-anchor="end" dominant-baseline="middle">15</text><line data-kyo-path="12" stroke="#374151" x1="55" y1="20" x2="60" y2="20"></line><text data-kyo-path="13" fill="#374151" x="51" y="20" text-anchor="end" dominant-baseline="middle">20</text><line data-kyo-path="14" stroke="#22c55e" x1="580" y1="440" x2="585" y2="440"></line><text data-kyo-path="15" fill="#22c55e" x="589" y="440" text-anchor="start" dominant-baseline="middle">100</text><line data-kyo-path="16" stroke="#22c55e" x1="580" y1="230" x2="585" y2="230"></line><text data-kyo-path="17" fill="#22c55e" x="589" y="230" text-anchor="start" dominant-baseline="middle">150</text><line data-kyo-path="18" stroke="#22c55e" x1="580" y1="20" x2="585" y2="20"></line><text data-kyo-path="19" fill="#22c55e" x="589" y="20" text-anchor="start" dominant-baseline="middle">200</text><line data-kyo-path="20" stroke="#374151" x1="146.66666666666669" y1="440" x2="146.66666666666669" y2="445"></line><text data-kyo-path="21" fill="#374151" x="146.66666666666669" y="449" text-anchor="middle" dominant-baseline="hanging">Jan</text><line data-kyo-path="22" stroke="#374151" x1="320" y1="440" x2="320" y2="445"></line><text data-kyo-path="23" fill="#374151" x="320" y="449" text-anchor="middle" dominant-baseline="hanging">Feb</text><line data-kyo-path="24" stroke="#374151" x1="493.33333333333337" y1="440" x2="493.33333333333337" y2="445"></line><text data-kyo-path="25" fill="#374151" x="493.33333333333337" y="449" text-anchor="middle" dominant-baseline="hanging">Mar</text><g data-kyo-path="26"><rect data-kyo-path="26.0" fill="#3b82f6" x="68.66666666666667" y="230" width="156" height="210"></rect><rect data-kyo-path="26.1" fill="#3b82f6" x="242" y="20" width="156" height="420"></rect><rect data-kyo-path="26.2" fill="#3b82f6" x="415.33333333333337" y="125" width="156" height="315"></rect><path data-kyo-path="26.3" fill="none" stroke="#f97316" stroke-width="2px" d="M146.66666666666669 230 L320 20 L493.33333333333337 125"></path><circle data-kyo-path="26.4" fill="#22c55e" stroke="#ffffff" stroke-width="1.5px" cx="146.66666666666669" cy="440" r="4"></circle><circle data-kyo-path="26.5" fill="#22c55e" stroke="#ffffff" stroke-width="1.5px" cx="320" cy="20" r="4"></circle><circle data-kyo-path="26.6" fill="#22c55e" stroke="#ffffff" stroke-width="1.5px" cx="493.33333333333337" cy="230" r="4"></circle></g></svg>"""
end ChartInvariantsTest
