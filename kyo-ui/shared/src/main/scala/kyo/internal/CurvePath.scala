package kyo.internal

import kyo.*
import kyo.Chart.Curve

/** Builds an SVG path through a point sequence under a `Curve` interpolation.
  * Emits `lineTo`/`hLineTo`/`vLineTo`/`cubicTo` only (no new
  * SVG primitives), so `renderPathDataStr` and the SMIL morph keep working.
  * Interpolation applies within one defined-gap segment; fewer than 2 points
  * degrade to linear.
  *
  * The six cases cover: linear (straight line segments), stepBefore and
  * stepAfter (staircase interpolation), monotone (Fritsch-Carlson cubic Hermite
  * that preserves monotonicity), basis (uniform cubic B-spline anchored to
  * endpoints), and catmullRom (Catmull-Rom converted to cubic Bezier).
  *
  * This object is `private[kyo]` (engine-internal, not public surface). All
  * methods are private; only `append` is accessible within the package.
  */
private[kyo] object CurvePath:

    /** Appends `pts` to `data` under `curve`, starting from a prior moveTo.
      *
      * `pts` contains the remaining points AFTER the moveTo anchor (i.e. the
      * caller has already issued `Svg.PathData.from(p0x, p0y)`). When fewer
      * than 2 points remain, degrades to linear (single `lineTo` or no-op).
      */
    def append(data: Svg.PathData, pts: Chunk[(Double, Double)], curve: Curve): Svg.PathData =
        if pts.size < 2 then pts.foldLeft(data)((d, p) => d.lineTo(p._1, p._2))
        else
            curve match
                case Curve.linear     => linear(data, pts)
                case Curve.stepBefore => stepBefore(data, pts)
                case Curve.stepAfter  => stepAfter(data, pts)
                case Curve.monotone   => monotone(data, pts)
                case Curve.basis      => basis(data, pts)
                case Curve.catmullRom => catmullRom(data, pts)

    private def linear(d: Svg.PathData, pts: Chunk[(Double, Double)]): Svg.PathData =
        pts.foldLeft(d)((acc, p) => acc.lineTo(p._1, p._2))

    // Staircase: horizontal segment first, then vertical (step at the RIGHT of each interval).
    // Chunk has no `sliding`; fold over the index range via a @tailrec inner def.
    private def stepAfter(d: Svg.PathData, pts: Chunk[(Double, Double)]): Svg.PathData =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Svg.PathData): Svg.PathData =
            if i >= pts.size then acc
            else loop(i + 1, acc.hLineTo(pts(i)._1).vLineTo(pts(i)._2))
        loop(0, d)
    end stepAfter

    // Staircase: vertical segment first, then horizontal (step at the LEFT of each interval).
    private def stepBefore(d: Svg.PathData, pts: Chunk[(Double, Double)]): Svg.PathData =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Svg.PathData): Svg.PathData =
            if i >= pts.size then acc
            else loop(i + 1, acc.vLineTo(pts(i)._2).hLineTo(pts(i)._1))
        loop(0, d)
    end stepBefore

    // Fritsch-Carlson monotone cubic Hermite interpolation.
    //
    // Two-pass algorithm:
    //   Pass 1: initialize per-knot tangents from adjacent secant slopes; set
    //           flat tangents at local extrema (sign change).
    //   Pass 2: Fritsch-Carlson clamp: project (alpha,beta) into the monotonicity
    //           circle of radius 3 by scaling both by tau=3/sqrt(alpha^2+beta^2).
    //
    // Hermite-to-Bezier: control points sit at one-third of the x-interval from
    // each knot. Uses a single mutable Array[Double] tangent scratch buffer (the
    // tightest-scoped local accumulator) because pass 2 clamps tangents in place;
    // it is filled and updated via @tailrec, never `var` or an index loop.
    // Array (not the immutable Span the codebase defaults to) is required here precisely
    // because the Fritsch-Carlson pass mutates tangents in place; the buffer is method-local
    // and never escapes, so the in-place writes stay isolated behind this pure interface.
    private def monotone(d: Svg.PathData, pts: Chunk[(Double, Double)]): Svg.PathData =
        val n  = pts.size
        val xs = pts.map(_._1)
        val ys = pts.map(_._2)
        // Secant slopes delta_i = (y_{i+1}-y_i)/(x_{i+1}-x_i) for i in 0..n-2.
        // When two consecutive x values are equal the segment is vertical; treat slope as 0.
        val delta = Array.tabulate(n - 1): i =>
            val dx = xs(i + 1) - xs(i)
            if dx == 0.0 then 0.0 else (ys(i + 1) - ys(i)) / dx
        // Pass 1: initialize per-knot tangents. Interior knots average adjacent secants,
        // or flatten at local extrema (sign change). Filled via Array.tabulate.
        val m = Array.tabulate(n): i =>
            if i == 0 then delta(0)
            else if i == n - 1 then delta(n - 2)
            else if delta(i - 1) * delta(i) <= 0.0 then 0.0
            else (delta(i - 1) + delta(i)) / 2.0
        // Pass 2: Fritsch-Carlson clamp, updating m in place via @tailrec.
        @scala.annotation.tailrec
        def clamp(i: Int): Unit =
            if i < n - 1 then
                if delta(i) == 0.0 then
                    m(i) = 0.0
                    m(i + 1) = 0.0
                else
                    val alpha = m(i) / delta(i)
                    val beta  = m(i + 1) / delta(i)
                    val s     = alpha * alpha + beta * beta
                    if s > 9.0 then
                        val tau = 3.0 / math.sqrt(s)
                        m(i) = tau * alpha * delta(i)
                        m(i + 1) = tau * beta * delta(i)
                    end if
                end if
                clamp(i + 1)
        clamp(0)
        // Emit one cubicTo per segment using Hermite-to-Bezier control-point formula.
        @scala.annotation.tailrec
        def emit(i: Int, acc: Svg.PathData): Svg.PathData =
            if i >= n - 1 then acc
            else
                val dx  = xs(i + 1) - xs(i)
                val c1x = xs(i) + dx / 3.0
                val c1y = ys(i) + m(i) * dx / 3.0
                val c2x = xs(i + 1) - dx / 3.0
                val c2y = ys(i + 1) - m(i + 1) * dx / 3.0
                emit(i + 1, acc.cubicTo(c1x, c1y, c2x, c2y, xs(i + 1), ys(i + 1)))
        emit(0, d)
    end monotone

    // Uniform cubic B-spline, emitted as cubicTo per segment.
    //
    // Endpoint duplication: p(-1)==p(0) and p(n)==p(n-1) so the spline anchors
    // at the first and last data points. The clamped index function handles
    // out-of-range access. Loop runs i=1 to i<n (n-1 segments) so the last
    // point is reached.
    private def basis(d: Svg.PathData, pts: Chunk[(Double, Double)]): Svg.PathData =
        val n = pts.size
        val p: Int => (Double, Double) = idx =>
            val c = math.max(0, math.min(n - 1, idx))
            pts(c)
        @scala.annotation.tailrec
        def loop(i: Int, acc: Svg.PathData): Svg.PathData =
            if i >= n then acc
            else
                val (xa, ya) = p(i - 1)
                val (xb, yb) = p(i)
                val (xc, yc) = p(i + 1)
                val c1x      = xa / 3.0 + xb * 2.0 / 3.0
                val c1y      = ya / 3.0 + yb * 2.0 / 3.0
                val c2x      = xb * 2.0 / 3.0 + xc / 3.0
                val c2y      = yb * 2.0 / 3.0 + yc / 3.0
                val ex       = (xb + xc) / 2.0
                val ey       = (yb + yc) / 2.0
                loop(i + 1, acc.cubicTo(c1x, c1y, c2x, c2y, ex, ey))
        loop(1, d)
    end basis

    // Catmull-Rom to cubic Bezier conversion with endpoint duplication.
    //
    // For segment (p(i) -> p(i+1)):
    //   c1 = p(i)   + (p(i+1) - p(i-1)) / 6
    //   c2 = p(i+1) - (p(i+2) - p(i))   / 6
    //
    // Endpoint duplication: p(idx)=pts(clamp(0, n-1, idx)). Loop runs i=0 to i<n-1.
    // The ghost point before the first segment is pts(0).
    private def catmullRom(d: Svg.PathData, pts: Chunk[(Double, Double)]): Svg.PathData =
        val n = pts.size
        val p: Int => (Double, Double) = idx =>
            val c = math.max(0, math.min(n - 1, idx))
            pts(c)
        @scala.annotation.tailrec
        def loop(i: Int, acc: Svg.PathData): Svg.PathData =
            if i >= n - 1 then acc
            else
                val (x0, y0) = p(i - 1)
                val (x1, y1) = p(i)
                val (x2, y2) = p(i + 1)
                val (x3, y3) = p(i + 2)
                val c1x      = x1 + (x2 - x0) / 6.0
                val c1y      = y1 + (y2 - y0) / 6.0
                val c2x      = x2 - (x3 - x1) / 6.0
                val c2y      = y2 - (y3 - y1) / 6.0
                loop(i + 1, acc.cubicTo(c1x, c1y, c2x, c2y, x2, y2))
        loop(0, d)
    end catmullRom

end CurvePath
