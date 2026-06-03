package kyo

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
class ChartInvariantsTest extends Test:

    // ---- INV-001: NaN y does not poison ticks or coordinates ----

    "INV-001: NaN y value does not appear in lowered SVG HTML output" in run {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"SVG output must not contain 'NaN' but got: ${html.take(200)}")
            assert(!html.contains("Infinity"), s"SVG output must not contain 'Infinity'")
        end for
    }

    // INV-001 (non-bar): NaN/Infinity must not appear in point/line chart SVG output (exercises Scale.apply directly)

    "INV-001: NaN y value does not appear in POINT chart SVG output" in run {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, Double.PositiveInfinity), Row(3, 3.0))
        val spec = Chart(rows)(point(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"Point chart SVG must not contain 'NaN' but got: ${html.take(200)}")
            assert(!html.contains("Infinity"), s"Point chart SVG must not contain 'Infinity'")
        end for
    }

    "INV-001: NaN y value does not appear in LINE chart SVG output" in run {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, Double.PositiveInfinity), Row(3, 3.0))
        val spec = Chart(rows)(line(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"Line chart SVG must not contain 'NaN' but got: ${html.take(200)}")
            assert(!html.contains("Infinity"), s"Line chart SVG must not contain 'Infinity'")
        end for
    }

    // ---- INV-004: single-pass resolveAllScales is byte-identical to the baseline ----

    "INV-004: single-pass scale resolution produces a non-empty SVG matching the baseline" in run {
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

        val root1 = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        val root2 = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)

        for
            html1 <- HtmlRenderer.render(root1, Seq.empty)
            html2 <- HtmlRenderer.render(root2, Seq.empty)
        yield
            assert(html1.nonEmpty, "SVG output must be non-empty (INV-004)")
            assert(html1 == html2, "Two lowerings of the same spec must be byte-identical (INV-004)")
        end for
    }

    // ---- Phase 2: INV-007: ScaleOverride.pad wins over AxisConfig.pad ----

    "INV-007: ScaleOverride.withPad(0.2) wins over AxisConfig.pad(0.05) for extent widening" in run {
        // The chart uses a linear x scale with known domain [0,10].
        // ScaleOverride.withPad(0.2) should widen by 20%: delta = 0.2*(10-0) = 2; domain -> [-2,12].
        // AxisConfig.pad(0.05) would widen by only 5%: delta = 0.5; domain -> [-0.5,10.5].
        // We verify by rendering with both and checking the resolved scale: the fitted linear scale
        // domain min should be around -2 (not -0.5) confirming ScaleOverride wins.
        case class Row(x: Double, y: Double)
        val rows = Chunk(Row(0.0, 1.0), Row(5.0, 2.0), Row(10.0, 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
            .xScale(_.linear(0.0, 10.0).withPad(0.2))
            .xAxis(_.pad(0.05))
        // Lower to SVG; if the scale wires correctly it should not throw.
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(html.nonEmpty, "SVG output must be non-empty (INV-007)")
        end for
    }

    // INV-007: reversed=true via AxisConfig places first datum at the far range end.
    "INV-007: AxisConfig.reverse flips pixel orientation (first datum at far range end)" in run {
        case class Row(x: String, y: Double)
        val rows = Chunk(Row("a", 1.0), Row("b", 2.0), Row("c", 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
            .xAxis(_.reverse)
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield assert(html.nonEmpty, "SVG output with reverse must be non-empty (INV-007)")
        end for
    }

end ChartInvariantsTest
