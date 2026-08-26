package kyo

import kyo.Chart.*
import kyo.internal.ChartAxes
import kyo.internal.ChartScales
import kyo.internal.Scale
import scala.language.implicitConversions

/** Tests for the time axis an `Instant` encoding selects, and for the tick labels it produces.
  *
  * The README says an `Instant` x-encoding selects a time scale. It did not: an `Instant` folds into the same
  * `Extent.Continuous` a `Double` does, and the scale kind was inferred from the extent alone, so a chart of
  * the last minute was labelled `1785879980000 ... 1785880060000` under ticks that a time scale had never
  * chosen. The last of those labels then ran off the right edge and was truncated mid-number, which reads as
  * a different, wrong value rather than as a clipped one.
  */
class ChartTimeAxisTest extends kyo.test.Test[Any]:

    private val PlotX = 60.0
    private val PlotW = 560.0
    private val SvgW  = 640.0

    case class Hit(at: Instant, count: Double)
    given CanEqual[Hit, Hit] = CanEqual.derived

    /** A minute of samples, one every twenty seconds, starting at a round epoch second. */
    private val start: Instant = Instant.Epoch + 1785879980L.seconds

    private val rows: Chunk[Hit] =
        Chunk(
            Hit(start, 10.0),
            Hit(start + 20.seconds, 20.0),
            Hit(start + 40.seconds, 30.0),
            Hit(start + 60.seconds, 40.0)
        )

    private def frameTextsIn(root: Svg.Root): Chunk[Svg.Text] =
        root.children.flatMap:
            case t: Svg.Text => Chunk(t)
            case _           => Chunk.empty

    private def xTickLabels(root: Svg.Root): Chunk[String] =
        frameTextsIn(root)
            .filter(t => t.svgAttrs.dominantBaseline.contains(Svg.DominantBaseline.Hanging))
            .map(_.children.collect { case UI.Ast.Text(v) => v }.mkString)

    "an Instant x-encoding selects a time scale" in {
        val spec = Chart(rows)(line(x = _.at, y = _.count))
        val kind = ChartScales.declaredXKind(spec.marks)
        assert(kind.contains(Scale.Kind.Time))
        spec.lower.map { _ =>
            // The inference path is what regressed: an Instant produces a Continuous extent, so a
            // scale kind read off the extent alone can only ever say Linear.
            val inferred = ChartScales.inferKind(
                kyo.internal.Extent.Continuous(0.0, 1.0),
                spec.marks,
                isX = true
            )
            assert(inferred == Scale.Kind.Time)
        }
    }

    "a Double x-encoding still selects a linear scale" in {
        case class Point(x: Double, y: Double)
        val spec = Chart(Chunk(Point(0.0, 1.0), Point(1.0, 2.0)))(line(x = _.x, y = _.y))
        val inferred = ChartScales.inferKind(
            kyo.internal.Extent.Continuous(0.0, 1.0),
            spec.marks,
            isX = true
        )
        assert(inferred == Scale.Kind.Linear)
    }

    "the default tick labels of a time axis are times, not epoch milliseconds" in {
        Chart(rows)(line(x = _.at, y = _.count)).lower.map { root =>
            val labels = xTickLabels(root)
            assert(labels.nonEmpty)
            // The exact defect: a 13-digit integer under every tick.
            assert(labels.forall(l => !l.matches("\\d{13}")), s"got raw epoch millis: $labels")
            // A sub-day tick step formats as HH:mm.
            assert(labels.forall(_.matches("\\d{2}:\\d{2}")), s"expected HH:mm labels, got: $labels")
        }
    }

    "formatTime hands the callback the Instant the axis was typed with" in {
        val seen = collection.mutable.ArrayBuffer.empty[Instant]
        val spec = Chart(rows)(line(x = _.at, y = _.count))
            .xAxis(_.formatTime { i =>
                seen += i
                "T"
            })
        spec.lower.map { root =>
            assert(xTickLabels(root).forall(_ == "T"))
            assert(seen.nonEmpty)
            // Every instant handed over is inside the sampled window, so the callback saw the real
            // domain value rather than a pixel or a re-derived number.
            assert(seen.forall(_.between(start - 1.minute, start + 2.minutes)))
        }
    }

    "formatTime is ignored on a non-time axis, where format remains the formatter" in {
        case class Point(x: Double, y: Double)
        val spec = Chart(Chunk(Point(0.0, 1.0), Point(1.0, 2.0)))(line(x = _.x, y = _.y))
            .xAxis(_.formatTime(_ => "T").format(v => s"n$v"))
        spec.lower.map { root =>
            val labels = xTickLabels(root)
            assert(labels.nonEmpty)
            assert(labels.forall(_.startsWith("n")), s"expected the numeric formatter to win, got: $labels")
        }
    }

    "a numeric formatter still applies on a time axis when no time formatter is set" in {
        val spec = Chart(rows)(line(x = _.at, y = _.count)).xAxis(_.format(v => s"ms$v"))
        spec.lower.map { root =>
            assert(xTickLabels(root).forall(_.startsWith("ms")))
        }
    }

    "an x tick label at the right edge is anchored inward instead of running off the SVG" in {
        val cfg  = AxisConfig.default
        val wide = "1785880060000"
        // A centred label at the last tick extends half its width past the plot edge, which is past the
        // SVG edge: the renderer then cuts it mid-value.
        assert(ChartAxes.xTickAnchor(cfg, wide, PlotX + PlotW, SvgW, 12.0) == Svg.TextAnchor.End)
        // The mirror case at the left edge. The default 60px left margin keeps even a 13-character label
        // inside the SVG at plotX, so the adjustment only fires for a plot that starts nearer the edge.
        assert(ChartAxes.xTickAnchor(cfg, wide, PlotX, SvgW, 12.0) == Svg.TextAnchor.Middle)
        assert(ChartAxes.xTickAnchor(cfg, wide, 20.0, SvgW, 12.0) == Svg.TextAnchor.Start)
        assert(ChartAxes.xTickAnchor(cfg, "12:00", SvgW / 2.0, SvgW, 12.0) == Svg.TextAnchor.Middle)
        // A time label at the last tick fits, which is the point of defaulting to one.
        assert(ChartAxes.xTickAnchor(cfg, "12:00", PlotX + PlotW, SvgW, 12.0) == Svg.TextAnchor.Middle)
    }

    "an explicitly chosen anchor is never overridden by the edge adjustment" in {
        val cfg = AxisConfig.default.anchor(TextAnchor.Start)
        assert(ChartAxes.xTickAnchor(cfg, "1785880060000", PlotX + PlotW, SvgW, 12.0) == Svg.TextAnchor.Start)
    }

end ChartTimeAxisTest
