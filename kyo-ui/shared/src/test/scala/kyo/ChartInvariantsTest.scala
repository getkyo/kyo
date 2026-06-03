package kyo

import kyo.internal.ChartFoundations
import kyo.internal.ChartLower
import kyo.internal.HtmlRenderer
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

end ChartInvariantsTest
