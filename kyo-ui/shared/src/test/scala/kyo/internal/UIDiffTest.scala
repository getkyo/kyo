package kyo.internal

import kyo.*
import kyo.Chart.*
import scala.language.implicitConversions

/** Tests for the reactive-region diff.
  *
  * Server-driven mode re-rendered and re-transmitted the whole region on every change, because the diff
  * granularity was the reactive boundary. `Chart(signal)(...)` is one boundary over the entire lowered SVG, so
  * a 1 Hz sample shipped the background, the axes, every gridline and tick label, and the legend along with
  * the marks that actually moved: 90 KB a frame, per connected viewer, for five doubles of new information.
  *
  * `plan` decides which nodes CAN be addressed, never which ones changed. What is actually sent is decided by
  * the transport, on rendered bytes, because a node's rendering is not a function of its AST: an element bound
  * to a `SignalRef` re-renders from the ref read at render time and its AST is the same object on every edit.
  */
class UIDiffTest extends kyo.test.Test[Any]:

    private val region = Seq("0", "1")

    private def svg(children: Svg.SvgChild*): Svg.Root = Svg.svg(children*)

    private def rect(x: Double): Svg.Rect = Svg.rect.x(x).y(0.0).width(10.0).height(10.0)

    /** Rebuilds `previous` by applying every replacement the plan names, which is what the client does.
      *
      * This is the completeness check the byte counting cannot make: sending less is only correct if what the
      * viewer ends up with is still exactly what the server rendered.
      */
    private def applyPlan(previous: UI, plan: Chunk[(Seq[String], UI)])(using Frame, kyo.test.AssertScope): UI =
        plan.foldLeft(previous)((tree, entry) => replaceAt(tree, entry._1.drop(region.size), entry._2))

    private def replaceAt(tree: UI, suffix: Seq[String], replacement: UI)(using Frame, kyo.test.AssertScope): UI =
        if suffix.isEmpty then replacement
        else
            val i = suffix.head.toInt
            tree match
                case r: Svg.Root =>
                    r.copy(children = r.children.updated(i, replaceAt(r.children(i), suffix.tail, replacement)))(using r.frame)
                case g: Svg.G =>
                    g.copy(children = g.children.updated(i, replaceAt(g.children(i), suffix.tail, replacement)))(using g.frame)
                case other =>
                    fail(s"the plan addressed a child of $other, which this test cannot rebuild")
            end match

    "plan" - {

        "a first render is the whole region" in {
            val ui   = svg(rect(1.0))
            val plan = UIDiff.plan(region, Absent, ui)
            assert(plan.size == 1)
            assert(plan.head._1 == region)
            assert(plan.head._2 == ui)
        }

        "an identical re-render still yields candidates, never an empty plan" in {
            // The invariant an AST comparison breaks: a SignalRef-bound element's AST is the same object on
            // every edit (ReactiveUI.normalize maps the bound leaf to the constant `ui`) and its rendering
            // reads the ref afresh, so deciding "nothing changed" here would drop real updates.
            val ui   = svg(rect(1.0), rect(2.0))
            val plan = UIDiff.plan(region, Present(svg(rect(1.0), rect(2.0))), ui)
            assert(plan.map(_._1) == Chunk(region :+ "0", region :+ "1"))
        }

        "a decomposable node yields one candidate per child, at each child's own path" in {
            val before = svg(rect(1.0), rect(2.0), rect(3.0))
            val after  = svg(rect(1.0), rect(9.0), rect(3.0))
            val plan   = UIDiff.plan(region, Present(before), after)
            assert(plan.map(_._1) == Chunk(region :+ "0", region :+ "1", region :+ "2"))
            assert(plan.map(_._2) == Chunk(rect(1.0), rect(9.0), rect(3.0)))
        }

        "a change on the node itself replaces the whole region" in {
            val before = svg(rect(1.0))
            val after  = Svg.svg.width(999.0)(rect(1.0))
            val plan   = UIDiff.plan(region, Present(before), after)
            assert(plan.size == 1)
            assert(plan.head._1 == region)
        }

        "a different number of children replaces the whole region" in {
            val plan = UIDiff.plan(region, Present(svg(rect(1.0))), svg(rect(1.0), rect(2.0)))
            assert(plan.size == 1)
            assert(plan.head._1 == region)
        }

        "the walk descends more than one level" in {
            def group(x: Double) = Svg.g(Svg.g(rect(x)))
            val before           = svg(group(1.0), group(2.0))
            val after            = svg(group(1.0), group(5.0))
            val plan             = UIDiff.plan(region, Present(before), after)
            assert(plan.map(_._1) == Chunk(region ++ Seq("0", "0", "0"), region ++ Seq("1", "0", "0")))
        }

        "a foreignObject is never descended into: it bridges back to HTML, where a bound field can live" in {
            val before = svg(Svg.foreignObject(UI.div(UI.span("a"))))
            val after  = svg(Svg.foreignObject(UI.div(UI.span("b"))))
            val plan   = UIDiff.plan(region, Present(before), after)
            assert(plan.map(_._1) == Chunk(region :+ "0"))
        }

        "a changed child with no node of its own replaces the enclosing region" in {
            // A text node renders no tag, so it carries no data-kyo-path for the client to resolve against.
            val before = Svg.text.x(1.0)("a")
            val after  = Svg.text.x(1.0)("b")
            val plan   = UIDiff.plan(region, Present(before), after)
            assert(plan.size == 1)
            assert(plan.head._1 == region)
        }

        "a title's text is not mistaken for an unchanged node" in {
            // title carries a text field beside its children, so it is compared whole rather than descended.
            val before = svg(Svg.title("before"))
            val after  = svg(Svg.title("after"))
            val plan   = UIDiff.plan(region, Present(before), after)
            assert(plan.size == 1)
            assert(plan.head._1 == region :+ "0")
        }
    }

    /** What the transport actually sends for a tick: the plan's candidates whose rendered bytes differ from
      * the ones last sent. The transport compares rendered HTML, never the AST, because a node's rendering is
      * not a function of its AST (a SignalRef-bound element re-renders from the ref, with the same AST).
      */
    private def wireOf(before: UI, after: UI)(using Frame): Chunk[(Seq[String], UI, String)] < Sync =
        def render(previous: UI, current: UI): Chunk[(Seq[String], UI, String)] < Sync =
            Kyo.foreach(UIDiff.plan(region, Present(previous), current))((path, ui) =>
                HtmlRenderer.render(ui, path).map((path, ui, _))
            )
        for
            lastSent   <- render(before, before)
            candidates <- render(before, after)
        yield
            val previously = lastSent.map((path, _, html) => (path, html)).toMap
            candidates.filterNot((path, _, html) => previously.get(path).contains(html))
        end for
    end wireOf

    /** A chart on fixed scales, so the frame is genuinely the same nodes from tick to tick. */
    private def chartAt(offset: Double): Chart[UIDiffTest.Point] =
        val rows = Chunk.from((0 until 60).map(i => UIDiffTest.Point(i.toDouble, 20.0 + offset + (i % 7))))
        Chart(rows)(line(x = _.x, y = _.y))
            .xScale(_.linear(0.0, 60.0))
            .yScale(_.linear(0.0, 100.0))
            .xAxis(_.grid.ticks(6))
            .yAxis(_.grid.ticks(5))
    end chartAt

    /** A live dashboard's chart: a sliding window over wall-clock time, so the x axis ADVANCES from tick to
      * tick and the frame is genuinely different, not merely re-rendered.
      *
      * Every other chart fixture here pins both scales, which is the easy case: the frame is the same nodes
      * and the diff elides all of it. This is the case a real dashboard actually runs, and the question it
      * answers is whether the diff still addresses below the region when part of the frame legitimately
      * changed, or collapses back to sending the whole region.
      */
    private def liveChartAt(offsetSeconds: Int): Chart[UIDiffTest.Sample] =
        val start = Instant.Epoch + offsetSeconds.seconds
        // The values move with the window, not just the timestamps. A fixture whose y values are a function
        // of the index alone renders a byte-identical marks path at every offset, which would leave these
        // leaves testing a moved axis over unchanged marks: the easier case, not the dashboard one.
        val rows = Chunk.from((0 until 30).map(i => UIDiffTest.Sample(start + i.seconds, 20.0 + ((i + offsetSeconds) % 7))))
        Chart(rows)(line(x = _.at, y = _.v))
            .xScale(_.time)
            .yScale(_.linear(0.0, 100.0))
            .xAxis(_.grid.ticks(6))
            .yAxis(_.grid.ticks(5))
    end liveChartAt

    "a chart whose frame itself moves" - {

        "still addresses below the region: a moving x axis does not collapse the update to the whole region" in {
            for
                before <- liveChartAt(0).lower
                after  <- liveChartAt(600).lower
            yield
                val plan = UIDiff.plan(region, Present(before), after)
                assert(plan.nonEmpty)
                assert(
                    plan.forall(_._1.size > region.size),
                    s"a moving axis collapsed the update back to the whole region: ${plan.map(_._1)}"
                )
            end for
        }

        "sends the axis that moved and the marks, and still withholds the panel background" in {
            for
                before <- liveChartAt(0).lower
                after  <- liveChartAt(600).lower
                whole  <- HtmlRenderer.render(after, region)
                sent   <- wireOf(before, after)
            yield
                val payload = sent.map(_._3).mkString
                assert(payload.nonEmpty, "the window moved, so something must go on the wire")
                assert(whole.contains("<rect"), "the whole render carries the panel background")
                // The panel background is a fixed-geometry node on fixed scales; it is the frame element that
                // stays put even when the axis does not, so it is the one that must never be re-sent.
                assert(!payload.contains("<rect"), s"the panel background was re-sent:\n$payload")
                // Both the axis labels and the marks moved here, so both are on the wire and the saving is
                // the rest of the frame: the panel background, the gridlines, the y axis and the legend.
                assert(payload.contains("<path") || payload.contains("<polyline"), "the marks moved, so they must be sent")
                // Measured on this fixture: 1193 bytes sent against 3177 for the whole region, a 2.66x cut,
                // against 3.1x on the fixed-scale fixture above. Lower is the correct direction: an advancing
                // axis puts its labels on the wire too. Neither number approaches the 1010x that a data
                // channel pushing values would give, and the reason is structural rather than a shortfall in
                // the walk: a `line` mark is ONE path element, so moving any point rewrites the whole `d`.
                assert(payload.length < whole.length, s"sent ${payload.length} of ${whole.length}")
            end for
        }

        "applying only what went on the wire still reproduces the whole render" in {
            // The correctness half, on the harder input: when part of the frame moved, the nodes withheld
            // must still be exactly the nodes the viewer already had.
            for
                before <- liveChartAt(0).lower
                after  <- liveChartAt(600).lower
                sent   <- wireOf(before, after)
                rebuilt = applyPlan(before, sent.map((path, ui, _) => (path, ui)))
                rebuiltHtml <- HtmlRenderer.render(rebuilt, region)
                wholeHtml   <- HtmlRenderer.render(after, region)
            yield assert(rebuiltHtml == wholeHtml)
        }

        "an idle tick on a time axis sends nothing" in {
            for
                before <- liveChartAt(0).lower
                after  <- liveChartAt(0).lower
                sent   <- wireOf(before, after)
            yield assert(sent.isEmpty, s"an unchanged tick would have sent ${sent.map(_._1)}")
        }
    }

    "a chart tick sends its marks, not its frame" - {

        "the candidates are below the region, never the region itself" in {
            for
                before <- chartAt(0.0).lower
                after  <- chartAt(5.0).lower
            yield
                val plan = UIDiff.plan(region, Present(before), after)
                assert(plan.nonEmpty, "the data moved, so something must be addressable")
                assert(plan.forall(_._1.size > region.size), s"expected candidates below the region, got ${plan.map(_._1)}")
            end for
        }

        "the payload carries the marks and none of the frame" in {
            for
                before <- chartAt(0.0).lower
                after  <- chartAt(5.0).lower
                whole  <- HtmlRenderer.render(after, region)
                sent   <- wireOf(before, after)
            yield
                val payload = sent.map(_._3).mkString
                // The frame's own elements: the panel background rect and the gridlines are unchanged nodes
                // and must not appear in what is sent.
                assert(whole.contains("<rect"), "the whole render carries the panel background")
                assert(!payload.contains("<rect"), s"the panel background was re-sent:\n$payload")
                assert(payload.contains("<path") || payload.contains("<polyline"), "the marks must be sent")
                // Measured on this fixture: 1291 bytes sent against 3976 for the whole region, a 3.1x cut,
                // and the frame's share is exactly what disappears. The marks themselves are still sent in
                // full every tick: a sliding window moves every point, so nothing about them is unchanged.
                assert(
                    payload.length * 2 < whole.length,
                    s"the diffed payload (${payload.length}) must be less than half the whole region (${whole.length})"
                )
            end for
        }

        "applying only what went on the wire renders exactly what the server would have sent whole" in {
            // The correctness half of the byte saving: the nodes that were NOT sent have to be the nodes the
            // viewer already had. Rendered HTML is the comparison, because that is what the viewer ends up
            // with, and two different values can render to the same bytes.
            for
                before <- chartAt(0.0).lower
                after  <- chartAt(5.0).lower
                sent   <- wireOf(before, after)
                rebuilt = applyPlan(before, sent.map((path, ui, _) => (path, ui)))
                rebuiltHtml <- HtmlRenderer.render(rebuilt, region)
                wholeHtml   <- HtmlRenderer.render(after, region)
            yield
                assert(sent.nonEmpty, "the data moved, so something must go on the wire")
                assert(rebuiltHtml == wholeHtml)
            end for
        }

        "an idle tick renders byte-identical candidates, so nothing goes on the wire" in {
            for
                before <- chartAt(0.0).lower
                after  <- chartAt(0.0).lower
                sent   <- wireOf(before, after)
            yield assert(sent.isEmpty, s"an unchanged tick would have sent ${sent.map(_._1)}")
        }
    }

end UIDiffTest

object UIDiffTest:
    case class Point(x: Double, y: Double)
    case class Sample(at: Instant, v: Double)
    given CanEqual[Point, Point] = CanEqual.derived
end UIDiffTest
