package kyo

import kyo.Svg.PathCommand
import kyo.Svg.PathData
import kyo.internal.CurvePath

/** Unit tests for CurvePath interpolation (INV-016, catalog #13/D3).
  *
  * All tests are pure: no fibers, no signals, no timing (AF1 from prep.md).
  * PathData inspection uses `Svg.PathData.commands` (private[kyo], accessible here
  * because this file is in `package kyo`; AF2/AF3 from prep.md).
  */
class CurvePathTest extends Test:

    private def hasCubic(pd: PathData): Boolean =
        Svg.PathData.commands(pd).toSeq.exists:
            case PathCommand.CubicTo(_, _, _, _, _, _) => true
            case _                                     => false

    private def hasHLine(pd: PathData): Boolean =
        Svg.PathData.commands(pd).toSeq.exists:
            case PathCommand.HLineTo(_) => true
            case _                      => false

    private def hasVLine(pd: PathData): Boolean =
        Svg.PathData.commands(pd).toSeq.exists:
            case PathCommand.VLineTo(_) => true
            case _                      => false

    private def hasLine(pd: PathData): Boolean =
        Svg.PathData.commands(pd).toSeq.exists:
            case PathCommand.LineTo(_, _) => true
            case _                        => false

    "linear over 3 points emits LineTo commands only" in {
        val pts = Chunk((1.0, 2.0), (3.0, 4.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.linear)
        assert(hasLine(pd))
        assert(!hasCubic(pd))
        assert(!hasHLine(pd))
    }

    "stepAfter over 3 points emits H then V staircases (test T2 from prep.md, INV-016)" in {
        val pts = Chunk((1.0, 2.0), (2.0, 1.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.stepAfter)
        assert(hasHLine(pd), "Expected HLineTo commands in stepAfter path")
        assert(hasVLine(pd), "Expected VLineTo commands in stepAfter path")
        assert(!hasCubic(pd), "stepAfter must not emit cubic commands")
        // Verify H-before-V order: the first non-MoveTo command should be HLineTo.
        val cmds = Svg.PathData.commands(pd).toSeq.filterNot:
            case PathCommand.MoveTo(_, _) => true
            case _                        => false
        assert(cmds.nonEmpty, "Expected non-empty path commands after moveTo")
        assert(cmds.head.isInstanceOf[PathCommand.HLineTo], s"First command after moveTo should be HLineTo, got ${cmds.head}")
    }

    "stepBefore over 3 points emits V then H staircases (INV-016)" in {
        val pts = Chunk((1.0, 2.0), (2.0, 1.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.stepBefore)
        assert(hasVLine(pd), "Expected VLineTo commands in stepBefore path")
        assert(hasHLine(pd), "Expected HLineTo commands in stepBefore path")
        // Verify V-before-H order.
        val cmds = Svg.PathData.commands(pd).toSeq.filterNot:
            case PathCommand.MoveTo(_, _) => true
            case _                        => false
        assert(cmds.head.isInstanceOf[PathCommand.VLineTo], s"First command after moveTo should be VLineTo, got ${cmds.head}")
    }

    "monotone over 3 points emits cubic commands and non-linear geometry (test T1 from prep.md, INV-016)" in {
        // Points: (0,0) -> (1,2) -> (2,1); moveTo is at (0,0) so append receives the tail.
        val pts = Chunk((1.0, 2.0), (2.0, 1.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.monotone)
        assert(hasCubic(pd), "Expected CubicTo commands in monotone path")
        // Verify non-linear geometry (AF6 from prep.md): the mid-point must differ from
        // the straight chord. The chord from (0,0) to (2,1) at x=1 has y=0.5.
        // A monotone cubic through (0,0),(1,2),(2,1) will have a different y at x=1.
        // We assert that the cubic is emitted (already checked) and that it has non-trivial
        // control points by inspecting the first CubicTo command.
        val cubic = Svg.PathData.commands(pd).toSeq.collectFirst:
            case c: PathCommand.CubicTo => c
        assert(cubic.isDefined, "Expected at least one CubicTo in monotone path")
        val c = cubic.get
        // The control points must not both be on the chord y=0.5*x (from (0,0) to (2,1)).
        val isLinearC1 = math.abs(c.c1y - 0.5 * c.c1x) < 1e-9
        val isLinearC2 = math.abs(c.c2y - 0.5 * c.c2x) < 1e-9
        assert(!(isLinearC1 && isLinearC2), "Control points should not all lie on the straight chord")
    }

    "basis over 4 points emits cubic commands (test T3 from prep.md, INV-016)" in {
        val pts = Chunk((1.0, 2.0), (2.0, 0.0), (3.0, 2.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.basis)
        assert(hasCubic(pd), "Expected CubicTo commands in basis path")
    }

    "catmullRom over 4 points emits cubic commands (INV-016)" in {
        val pts = Chunk((1.0, 2.0), (2.0, 0.0), (3.0, 2.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.catmullRom)
        assert(hasCubic(pd), "Expected CubicTo commands in catmullRom path")
    }

    "1-point degrade to single lineTo, no crash (test T4 from prep.md, INV-016)" in {
        // With 1 remaining point, append degrades to a single lineTo.
        val pts    = Chunk((1.0, 2.0))
        val curves = Seq(Curve.basis, Curve.catmullRom, Curve.monotone, Curve.stepAfter, Curve.stepBefore)
        val results = curves.map: curve =>
            val pd      = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, curve)
            val noCubic = !hasCubic(pd)
            val cmds = Svg.PathData.commands(pd).toSeq.filterNot:
                case PathCommand.MoveTo(_, _) => true
                case _                        => false
            (curve, noCubic, cmds.nonEmpty)
        assert(results.forall(_._2), s"All curves with 1 point should not emit cubics: $results")
        assert(results.forall(_._3), s"All curves with 1 point should emit at least one command: $results")
    }

    "0-point degrade to no-op (empty pts, INV-016)" in {
        val pts: Chunk[(Double, Double)] = Chunk.empty
        val pd0                          = Svg.PathData.from(0.0, 0.0)
        val pd                           = CurvePath.append(pd0, pts, Curve.basis)
        // With 0 remaining pts the fold emits nothing new; path equals the moveTo.
        assert(Svg.PathData.commands(pd).size == Svg.PathData.commands(pd0).size)
    }

    "monotone on 2 remaining points emits exactly 1 cubicTo (INV-016)" in {
        // append receives pts (the remaining points after moveTo anchor).
        // With 2 remaining points, n=2, n-1=1 segment => 1 cubicTo.
        val pts = Chunk((1.0, 1.0), (2.0, 3.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.monotone)
        val cubics = Svg.PathData.commands(pd).toSeq.count:
            case _: PathCommand.CubicTo => true
            case _                      => false
        assert(cubics == 1, s"Expected exactly 1 cubicTo for 2-remaining-point monotone (1 segment), got $cubics")
    }

    "basis loop anchors at first and last point (n-1 segments for n remaining points, INV-016)" in {
        // With 3 remaining points: n=3, loop runs i=1 to i<3 => 2 cubicTo segments.
        val pts = Chunk((1.0, 0.0), (2.0, 0.0), (3.0, 0.0))
        val pd  = CurvePath.append(Svg.PathData.from(0.0, 0.0), pts, Curve.basis)
        val cubics = Svg.PathData.commands(pd).toSeq.count:
            case _: PathCommand.CubicTo => true
            case _                      => false
        assert(cubics == 2, s"Expected 2 cubicTo for 3-remaining-point basis (n-1=2 segments), got $cubics")
    }

end CurvePathTest
