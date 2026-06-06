package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

/** Phase 06 tests for the `toSvgWithScales` escape hatch and its read-only [[Chart.Scales]] projection (INV-035).
  *
  * Asserts that the projected pixel coordinates match the coordinates actually emitted in the SVG, that
  * category projection works for a band axis, and that the public surface never leaks the internal `Scale` type.
  */
class ChartScalesTest extends Test:

    private val Tol = 1.0e-6

    private def assertClose(actual: Double, expected: Double, msg: String): Assertion =
        assert(math.abs(actual - expected) < Tol, s"$msg: expected $expected but got $actual")

    private def circlesIn(root: Svg.Root): Chunk[Svg.Circle] =
        root.children.flatMap:
            case g: Svg.G =>
                g.children.flatMap:
                    case c: Svg.Circle => Chunk(c)
                    case _             => Chunk.empty
            case c: Svg.Circle => Chunk(c)
            case _             => Chunk.empty

    case class PRow(x: Double, y: Double)
    case class BRow(cat: String, y: Int)

    // ---- Leaf 22: scales.x.toPixel(datumX) equals the cx of the corresponding circle ----

    "toSvgWithScales: x.toPixel(datumX) equals the emitted circle cx" in {
        // Continuous x so the linear x-scale projection is directly comparable to cx.
        val rows       = Chunk(PRow(0.0, 10.0), PRow(10.0, 20.0))
        val spec       = Chart(rows)(point(x = _.x, y = _.y)).xScale(_.linear(0.0, 10.0))
        val (root, sc) = spec.toSvgWithScales
        val circles    = circlesIn(root)
        assert(circles.size == 2, s"Expected 2 circles but got ${circles.size}")

        // Match the second datum (x=10.0) against its circle.
        val cx = circles(1).svgAttrs.cx match
            case Present(v) => v
            case Absent     => fail("Expected cx Present")
        assertClose(sc.x.toPixel(10.0), cx, "x.toPixel(10.0) vs emitted cx")

        // The plot rectangle is exposed and matches the default layout.
        assertClose(sc.plot.x, 60.0, "plot.x")
        assertClose(sc.plot.width, 560.0, "plot.width")
        // Round-trip: inverting the projected pixel returns the original continuous value.
        sc.x.invert(sc.x.toPixel(10.0)) match
            case Chart.Scales.Resolved.Continuous(v) => assertClose(v, 10.0, "x.invert round-trip")
            case other                               => fail(s"Expected Continuous but got $other")
    }

    // ---- Leaf 23: toPixelCategory returns Present for a band axis and Absent for a continuous axis ----

    "toSvgWithScales: x.toPixelCategory returns Present for a known band key, Absent for an unknown key" in {
        val rows       = Chunk(BRow("a", 1), BRow("b", 2), BRow("c", 3))
        val spec       = Chart(rows)(bar(x = _.cat, y = _.y))
        val (root, sc) = spec.toSvgWithScales

        // Band axis: a known key projects to a pixel; an unknown key is Absent.
        sc.x.toPixelCategory("b") match
            case Present(px) =>
                // Band centers lie inside the plot width.
                assert(px > 60.0 && px < 620.0, s"Expected band pixel inside plot but got $px")
            case Absent => fail("Expected Present pixel for known band key 'b'")
        end match
        assert(sc.x.toPixelCategory("zzz") == Absent, "Unknown band key must be Absent")
        assert(sc.x.kind == ScaleKind.Band, s"Expected Band kind but got ${sc.x.kind}")

        // A continuous y-axis has no category projection.
        assert(sc.y.toPixelCategory("b") == Absent, "Continuous axis must return Absent for category projection")
    }

    // ---- Leaf 24: Chart.Scales is sealed and public accessors do not leak kyo.internal.Scale types ----
    // Type ascriptions below enforce at compile time that each accessor returns a public type.
    // If any accessor returned kyo.internal.Scale, the ascription would fail to compile.

    "Chart.Scales public accessors do not leak kyo.internal.Scale types (compile-time type-ascription gate)" in {
        val rows                           = Chunk(PRow(0.0, 1.0), PRow(1.0, 2.0))
        val spec                           = Chart(rows)(point(x = _.x, y = _.y))
        val pair: (Svg.Root, Chart.Scales) = spec.toSvgWithScales
        val sc: Chart.Scales               = pair._2

        // Compile-time ascriptions: these fail to compile if any accessor returns kyo.internal.Scale.
        val _: Chart.Scales.Axis        = sc.x
        val _: Chart.Scales.Axis        = sc.y
        val _: Maybe[Chart.Scales.Axis] = sc.yRight
        val _: Chart.Scales.Rect        = sc.plot
        val _: ScaleKind                = sc.x.kind
        val _: Double                   = sc.x.toPixel(0.5)
        val _: Maybe[Double]            = sc.x.toPixelCategory("a")
        val _: Chart.Scales.Resolved    = sc.x.invert(60.0)

        // Behavioral assertions: the sealed trait is consistent with a single-axis chart.
        assert(sc.yRight == Absent, "Single-axis chart has no right axis")
        // x-axis is Linear for continuous x values; the fitted domain covers the data.
        sc.x.kind match
            case ScaleKind.Linear(lo, hi) =>
                assert(lo <= 0.0, s"x-axis domain lo must be <= data min 0.0, got $lo")
                assert(hi >= 1.0, s"x-axis domain hi must be >= data max 1.0, got $hi")
            case other => fail(s"Expected ScaleKind.Linear for continuous x but got $other")
        end match
        // Round-trip: x.invert(x.toPixel(v)) returns a Continuous wrapping approximately v.
        sc.x.invert(sc.x.toPixel(0.5)) match
            case Chart.Scales.Resolved.Continuous(v) => assert(math.abs(v - 0.5) < Tol, s"round-trip invert expected ~0.5 but got $v")
            case other                               => fail(s"Expected Continuous but got $other")
    }

    // ---- Leaf 25: ScaleKind.Linear carries the actual fitted domain, not (0.0, 0.0) ----

    "toSvgWithScales: y-axis kind is ScaleKind.Linear with actual fitted domain, not (0.0, 0.0)" in {
        // y values span 10.0..90.0; nice-ticking over that range yields nLo=10.0, nHi=90.0.
        val rows    = Chunk(PRow(0.0, 10.0), PRow(5.0, 90.0), PRow(10.0, 50.0))
        val spec    = Chart(rows)(point(x = _.x, y = _.y))
        val (_, sc) = spec.toSvgWithScales
        sc.y.kind match
            case ScaleKind.Linear(lo, hi) =>
                assert(lo != 0.0 || hi != 0.0, s"y-axis ScaleKind.Linear must not be the (0.0, 0.0) placeholder")
                assert(lo <= 10.0, s"fitted domain lo must be <= data min 10.0, got $lo")
                assert(hi >= 90.0, s"fitted domain hi must be >= data max 90.0, got $hi")
            case other => fail(s"Expected ScaleKind.Linear but got $other")
        end match
    }

end ChartScalesTest
