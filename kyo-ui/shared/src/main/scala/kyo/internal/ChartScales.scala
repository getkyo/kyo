package kyo.internal

import kyo.*
import kyo.Chart.*
import kyo.Chart.Encoding
import kyo.UI.*
import kyo.UI.Ast.*

/** Domain-extent folding and scale-kind resolution for chart data.
  *
  * Provides the family of extent-collection helpers (`foldExtent`, `xExtent`, `yLeftExtent`, etc.)
  * and the combined `resolveAllScales` entry point that produces all three resolved scales (x, left-y,
  * right-y) in one pass. `ResolvedScales` carries the result so callers can destructure it cleanly.
  * `inferKind` and `domainKey` are shared utilities used by both the extent helpers and the mark lowerers.
  */
private[kyo] object ChartScales:

    import ChartLayout.Layout

    /** Holds all three resolved scales from a single combined pass. */
    private[kyo] case class ResolvedScales(xs: Scale, ysL: Scale, ysR: Maybe[Scale])

    /** Fold domain values from a sequence of rows through an encoding into an `Extent`.
      *
      * `Absent` returns from `toDomain` (gap values) are skipped and contribute nothing to the extent.
      */
    private[kyo] def foldExtent[A](rows: Chunk[A], domainFn: A => Maybe[Domain]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= rows.size then acc
            else
                domainFn(rows(i)) match
                    case Absent => loop(i + 1, acc)
                    case Present(d) =>
                        val newAcc = d match
                            case Domain.Continuous(v) =>
                                // drop NaN/Inf to avoid poisoning min/max
                                if !ChartFoundations.isFiniteDouble(v) then acc
                                else
                                    acc match
                                        case Absent => Present(Extent.Continuous(v, v))
                                        case Present(Extent.Continuous(lo, hi)) =>
                                            Present(Extent.Continuous(math.min(lo, v), math.max(hi, v)))
                                        case Present(Extent.Categories(_)) => Present(Extent.Continuous(v, v))
                            case Domain.Category(key) =>
                                acc match
                                    case Absent => Present(Extent.Categories(Chunk(key)))
                                    case Present(Extent.Categories(keys)) =>
                                        if keys.exists(_ == key) then Present(Extent.Categories(keys))
                                        else Present(Extent.Categories(keys.append(key)))
                                    case Present(Extent.Continuous(_, _)) => Present(Extent.Categories(Chunk(key)))
                            case Domain.Temporal(ms) =>
                                // drop non-finite timestamps (e.g. Long overflow to Infinity)
                                if !ChartFoundations.isFiniteDouble(ms.toDouble) then acc
                                else
                                    acc match
                                        case Absent => Present(Extent.Continuous(ms.toDouble, ms.toDouble))
                                        case Present(Extent.Continuous(lo, hi)) =>
                                            Present(Extent.Continuous(math.min(lo, ms.toDouble), math.max(hi, ms.toDouble)))
                                        case Present(Extent.Categories(_)) => Present(Extent.Continuous(ms.toDouble, ms.toDouble))
                        loop(i + 1, newAcc)
        loop(0, Absent)
    end foldExtent

    // Group/color encodings carry an `Any` element type because nothing ever reads it back: the value is
    // keyed by its label string only. So the group/color encoding builders pair `Plottable.any` (which plots
    // any value by its `toString` label) with `summon[ConcreteTag[Any]]` as the encoding's tag. Both are the
    // correct evidence at `Any`: the categorical label is total on any value, and the tag serves only as a
    // structural `CatKey` identity component, never to reconstruct or constrain an element type.

    /** Collect x-extent across all marks' x encodings for the given rows. */
    private[kyo] def xExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent = marks(i) match
                    case m: Mark.Bar[A, ?, ?]      => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r)))
                    case m: Mark.Line[A, ?, ?]     => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r)))
                    case m: Mark.Area[A, ?, ?]     => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r)))
                    case m: Mark.Point[A, ?, ?]    => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r)))
                    case _: Mark.Rule[A]           => Absent
                    case m: Mark.Text[A, ?, ?]     => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r)))
                    case m: Mark.ErrorBar[A, ?, ?] => foldExtent(rows, r => m.x.plottable.toDomain(m.x.accessor(r)))
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end xExtent

    /** Collect y-extent across all marks on `Axis.Left` for the given rows.
      *
      * When the mark has a `stack` grouping, the extent uses the STACKED maxima: for each x value the
      * contributions of all stack groups are summed, and the maximum sum is used. For `normalize = true` the
      * extent is fixed to `[0, 1]`.
      */
    private[kyo] def yLeftExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Left =>
                        val base = m.stack.group match
                            case Absent =>
                                foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r)))
                            case Present(_) =>
                                if m.stack.normalize then Present(Extent.Continuous(0.0, 1.0))
                                else stackedYExtent(rows, m)
                        ensureZero(base)
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Area[A, ?, ?] if m.axis == Axis.Left =>
                        val base = m.y match
                            case Present(ch) =>
                                m.stack.group match
                                    case Present(_) =>
                                        if m.stack.normalize then Present(Extent.Continuous(0.0, 1.0))
                                        else stackedAreaYExtent(rows, m, ch)
                                    case Absent =>
                                        foldExtent(rows, r => ch.accessor(r).flatMap(v => ch.plottable.toDomain(v)))
                            case Absent =>
                                val e0 = m.y0 match
                                    case Present(ch) => foldExtent(rows, r => ch.plottable.toDomain(ch.accessor(r)))
                                    case Absent      => Absent
                                val e1 = m.y1 match
                                    case Present(ch) => foldExtent(rows, r => ch.plottable.toDomain(ch.accessor(r)))
                                    case Absent      => Absent
                                mergeExtents(e0, e1)
                        ensureZero(base)
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(rows, r => m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Rule[A] if m.axis == Axis.Left =>
                        m.y match
                            case Present(c: RuleValue.Const[?]) => constDomain(c) match
                                    case Present(d) => Present(extentFromDomain(d))
                                    case Absent     => Absent
                            case _ => Absent
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Left =>
                        // Text y encoding contributes to y-extent; gap rows (Absent) are skipped.
                        foldExtent(rows, r => m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Left =>
                        // All three y encodings (low, high, y) fold into the y-extent.
                        val eY    = foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r)))
                        val eLow  = foldExtent(rows, r => m.low.plottable.toDomain(m.low.accessor(r)))
                        val eHigh = foldExtent(rows, r => m.high.plottable.toDomain(m.high.accessor(r)))
                        ensureZero(mergeExtents(mergeExtents(eY, eLow), eHigh))
                    case (_: Mark.Bar[?, ?, ?] | _: Mark.Line[?, ?, ?] | _: Mark.Area[?, ?, ?] | _: Mark.Point[?, ?, ?] |
                        _: Mark.Rule[?] | _: Mark.Text[?, ?, ?] | _: Mark.ErrorBar[?, ?, ?]) =>
                        Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yLeftExtent

    /** Collect y-extent across all marks on `Axis.Left` WITHOUT applying `ensureZero`.
      *
      * Used for logarithmic scale resolution, where the domain must not include zero (log(0) is undefined).
      */
    private[kyo] def yLeftExtentNoZero[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Left =>
                        // Filter non-positive values so the log scale domain starts at the smallest positive value.
                        foldExtent(
                            rows,
                            r =>
                                m.y.plottable.toDomain(m.y.accessor(r)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Left =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Left =>
                        // Text y contributes to the log-scale no-zero extent; positive values only.
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Left =>
                        // Low/high/y for log scale; drop non-positive.
                        def posExtent(ch: Encoding[A, ?]) = foldExtent(
                            rows,
                            r =>
                                ch.plottable.toDomain(ch.accessor(r)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                        mergeExtents(
                            mergeExtents(posExtent(m.y), posExtent(m.low)),
                            posExtent(m.high)
                        )
                    case (_: Mark.Bar[?, ?, ?] | _: Mark.Line[?, ?, ?] | _: Mark.Area[?, ?, ?] | _: Mark.Point[?, ?, ?] |
                        _: Mark.Rule[?] | _: Mark.Text[?, ?, ?] | _: Mark.ErrorBar[?, ?, ?]) =>
                        Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yLeftExtentNoZero

    /** Collect y-extent for marks on `Axis.Right`. */
    private def yRightExtent[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Right =>
                        ensureZero(foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r))))
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(rows, r => m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Area[A, ?, ?] if m.axis == Axis.Right =>
                        val base = m.y match
                            case Present(ch) => foldExtent(rows, r => ch.accessor(r).flatMap(v => ch.plottable.toDomain(v)))
                            case Absent      => Absent
                        ensureZero(base)
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(rows, r => m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Right =>
                        // Text y contributes to right-axis extent when on the right axis.
                        foldExtent(rows, r => m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)))
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Right =>
                        // Low/high/y contribute to right-axis extent.
                        val eY    = foldExtent(rows, r => m.y.plottable.toDomain(m.y.accessor(r)))
                        val eLow  = foldExtent(rows, r => m.low.plottable.toDomain(m.low.accessor(r)))
                        val eHigh = foldExtent(rows, r => m.high.plottable.toDomain(m.high.accessor(r)))
                        ensureZero(mergeExtents(mergeExtents(eY, eLow), eHigh))
                    case (_: Mark.Bar[?, ?, ?] | _: Mark.Line[?, ?, ?] | _: Mark.Area[?, ?, ?] | _: Mark.Point[?, ?, ?] |
                        _: Mark.Rule[?] | _: Mark.Text[?, ?, ?] | _: Mark.ErrorBar[?, ?, ?]) =>
                        Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yRightExtent

    /** Collect the no-zero y-extent for marks on `Axis.Right` (mirrors `yLeftExtentNoZero`).
      *
      * Used by the Log scale arm in `resolveYScale` for the right axis. Filters out non-positive
      * values so the log domain starts at the smallest positive value.
      */
    private def yRightExtentNoZero[A](rows: Chunk[A], marks: Chunk[Mark[A]]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Maybe[Extent]): Maybe[Extent] =
            if i >= marks.size then acc
            else
                val markExtent: Maybe[Extent] = marks(i) match
                    case m: Mark.Bar[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.plottable.toDomain(m.y.accessor(r)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Line[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Point[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.Text[A, ?, ?] if m.axis == Axis.Right =>
                        foldExtent(
                            rows,
                            r =>
                                m.y.accessor(r).flatMap(v => m.y.plottable.toDomain(v)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                    case m: Mark.ErrorBar[A, ?, ?] if m.axis == Axis.Right =>
                        def posExtent(ch: Encoding[A, ?]) = foldExtent(
                            rows,
                            r =>
                                ch.plottable.toDomain(ch.accessor(r)).flatMap:
                                    case Domain.Continuous(v) if v > 0 => Present(Domain.Continuous(v))
                                    case _                             => Absent
                        )
                        mergeExtents(
                            mergeExtents(posExtent(m.y), posExtent(m.low)),
                            posExtent(m.high)
                        )
                    case (_: Mark.Bar[?, ?, ?] | _: Mark.Line[?, ?, ?] | _: Mark.Area[?, ?, ?] | _: Mark.Point[?, ?, ?] |
                        _: Mark.Rule[?] | _: Mark.Text[?, ?, ?] | _: Mark.ErrorBar[?, ?, ?]) =>
                        Absent
                val merged = mergeExtents(acc, markExtent)
                loop(i + 1, merged)
        loop(0, Absent)
    end yRightExtentNoZero

    /** Compute the stacked y-extent for a Bar mark with a stack grouping.
      *
      * For each distinct x value, separately tracks the sum of positive contributions
      * (posSum) and the sum of negative contributions (negSum). Returns
      * `Extent.Continuous(minNegSum, maxPosSum)` so that negative stacks extend the
      * axis below zero. `ensureZero` in `yLeftExtent` ensures zero is always in-domain.
      */
    private def stackedYExtent[A, X, Y](rows: Chunk[A], mark: Mark.Bar[A, X, Y]): Maybe[Extent] =
        // Build xKey -> (posSum, negSum) across all groups at that x slot.
        @scala.annotation.tailrec
        def loop(i: Int, sums: Map[String, (Double, Double)]): Map[String, (Double, Double)] =
            if i >= rows.size then sums
            else
                val row = rows(i)
                val xKey = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => domainKey(d)
                    case Absent     => ""
                val yVal = mark.y.plottable.toDomain(mark.y.accessor(row)) match
                    case Present(Domain.Continuous(v)) => v
                    case _                             => 0.0
                val (pos, neg) = sums.getOrElse(xKey, (0.0, 0.0))
                val newPair    = if yVal >= 0.0 then (pos + yVal, neg) else (pos, neg + yVal)
                loop(i + 1, sums.updated(xKey, newPair))
        val sums = loop(0, Map.empty)
        if sums.isEmpty then Absent
        else
            val maxPosSum = sums.values.map(_._1).fold(0.0)(math.max)
            val minNegSum = sums.values.map(_._2).fold(0.0)(math.min)
            Present(Extent.Continuous(minNegSum, maxPosSum))
        end if
    end stackedYExtent

    /** Compute the stacked y-extent for an Area mark with a stack grouping.
      *
      * For each distinct x value, sums the y contributions of all stack groups and returns the maximum sum as
      * the extent upper bound (lower bound is zero after `ensureZero`). Mirrors `stackedYExtent` for bars.
      */
    private def stackedAreaYExtent[A, X, Y](rows: Chunk[A], mark: Mark.Area[A, X, Y], yEnc: EncodingMaybe[A, Y]): Maybe[Extent] =
        @scala.annotation.tailrec
        def loop(i: Int, totals: Map[String, Double]): Map[String, Double] =
            if i >= rows.size then totals
            else
                val row = rows(i)
                val xKey = mark.x.plottable.toDomain(mark.x.accessor(row)) match
                    case Present(d) => domainKey(d)
                    case Absent     => ""
                val yVal = yEnc.accessor(row) match
                    case Present(yv) => yEnc.plottable.toDomain(yv) match
                            case Present(Domain.Continuous(v)) => v
                            case _                             => 0.0
                    case Absent => 0.0
                loop(i + 1, totals.updated(xKey, totals.getOrElse(xKey, 0.0) + yVal))
        val totals = loop(0, Map.empty)
        if totals.isEmpty then Absent
        else
            val maxTotal = totals.values.fold(0.0)(math.max)
            Present(Extent.Continuous(0.0, maxTotal))
        end if
    end stackedAreaYExtent

    private[kyo] def domainKey(d: Domain): String = d match
        case Domain.Continuous(v) => NumberFormat.double(v)
        case Domain.Category(key) => key
        case Domain.Temporal(ms)  => ms.toString

    private def extentFromDomain(d: Domain): Extent = d match
        case Domain.Continuous(v) => Extent.Continuous(v, v)
        case Domain.Category(key) => Extent.Categories(Chunk(key))
        case Domain.Temporal(ms)  => Extent.Continuous(ms.toDouble, ms.toDouble)

    private def ensureZero(e: Maybe[Extent]): Maybe[Extent] = e match
        case Present(Extent.Continuous(lo, hi)) => Present(Extent.Continuous(math.min(lo, 0.0), hi))
        case other                              => other

    private def mergeExtents(a: Maybe[Extent], b: Maybe[Extent]): Maybe[Extent] = (a, b) match
        case (Absent, x) => x
        case (x, Absent) => x
        case (Present(ea), Present(eb)) =>
            (ea, eb) match
                case (Extent.Continuous(lo1, hi1), Extent.Continuous(lo2, hi2)) =>
                    Present(Extent.Continuous(math.min(lo1, lo2), math.max(hi1, hi2)))
                case (Extent.Categories(k1), Extent.Categories(k2)) =>
                    // Append each key from k2 not already in k1, preserving k1's order then k2's new keys.
                    val merged = k2.foldLeft(k1)((acc, k) => if acc.exists(_ == k) then acc else acc.append(k))
                    Present(Extent.Categories(merged))
                case (Extent.Continuous(lo, hi), Extent.Categories(_)) => Present(Extent.Continuous(lo, hi))
                case (Extent.Categories(_), Extent.Continuous(lo, hi)) => Present(Extent.Continuous(lo, hi))
        case _ => Absent

    // Project a `RuleValue.Const`'s value into its domain. Taking the case directly opens the existential
    // once into a single `C`, so `plottable.toDomain(value)` type-checks (both are `C`) with no cast.
    private def constDomain[C](c: RuleValue.Const[C]): Maybe[Domain] =
        c.plottable.toDomain(c.value)

    /** Resolves all three scales in one combined pass.
      *
      * Preserves every per-mark/per-axis branch exactly (stacked vs simple, ensureZero,
      * log-no-zero path, right-axis vs left-axis).
      *
      * `computeRight`: when `false` the right scale is `Absent` without computing `yRightExtent`.
      */
    private[kyo] def resolveAllScales[A](
        rows: Chunk[A],
        marks: Chunk[Mark[A]],
        layout: Layout,
        xOverride: Maybe[ScaleOverride],
        yOverride: Maybe[ScaleOverride],
        computeRight: Boolean,
        xAxisCfg: AxisConfig = AxisConfig.default,
        yAxisCfg: AxisConfig = AxisConfig.default,
        yRightOverride: Maybe[ScaleOverride] = Absent,
        yAxisRightCfg: AxisConfig = AxisConfig.default
    ): ResolvedScales =

        // Compute effective pad: ScaleOverride wins over AxisConfig.
        def effectivePad(ov: Maybe[ScaleOverride], axisCfg: AxisConfig): Double =
            ov.flatMap(o => if o.pad != 0.0 then Present(o.pad) else Absent).getOrElse(axisCfg.padding)

        // Apply symmetric fractional padding to a continuous extent.
        def padExtent(ext: Extent, pad: Double): Extent = ext match
            case Extent.Continuous(lo, hi) if pad != 0.0 =>
                val delta = pad * (hi - lo)
                Extent.Continuous(lo - delta, hi + delta)
            case other => other

        // Resolve a y-axis scale from its data extent, no-zero extent (for log), scale override, axis config,
        // and pixel range bounds. The rangeLo/rangeHi are caller-supplied to capture the left-vs-right
        // range difference (left uses plotY+topHeadroom as hi; right uses plotY directly).
        // When override=Absent and axisCfg=default this reproduces Scale.fit(Linear, ext, rangeLo, rangeHi, nice=true)
        // byte-identically (default Linear getOrElse, nice=true getOrElse, no reverse, no clamp, pad=0).
        def resolveYScale(
            ext: Extent,
            extNoZero: Extent,
            ov: Maybe[ScaleOverride],
            axisCfg: AxisConfig,
            rangeLo: Double,
            rangeHi: Double
        ): Scale =
            val pad     = effectivePad(ov, axisCfg)
            val nice    = ov.map(_.nice).getOrElse(true)
            val reverse = axisCfg.reversed
            val kindOpt: Maybe[Scale.Kind] = ov.flatMap(_.kind) match
                case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
                case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
                case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
                case Present(ScaleKind.Time)         => Present(Scale.Kind.Time)
                case Present(ScaleKind.Point)        => Present(Scale.Kind.Point)
                case Present(ScaleKind.Symlog)       => Present(Scale.Kind.Symlog)
                case _                               => Absent
            val kind = kindOpt.getOrElse(Scale.Kind.Linear)
            // Swap range bounds when reverse=true.
            val (rLoBase, rHiBase) = if reverse then (rangeHi, rangeLo) else (rangeLo, rangeHi)
            val (extFinal, rLo, rHi, useNice) = ov.flatMap(_.kind) match
                // Pad applies to an explicit linear domain too; withPad must win.
                case Present(ScaleKind.Linear(domLo, domHi)) =>
                    (padExtent(Extent.Continuous(domLo, domHi), pad), rLoBase, rHiBase, false)
                // Log scale uses the no-zero extent computation.
                // Apply pad to the log extent too (every other arm pads).
                case Present(ScaleKind.Log) =>
                    (padExtent(extNoZero, pad), rLoBase, rHiBase, false)
                case _ => (padExtent(ext, pad), rLoBase, rHiBase, nice)
            val clamp = ov.map(_.clamp).getOrElse(false)
            Scale.fit(kind, extFinal, rLo, rHi, nice = useNice, clamp = clamp)
        end resolveYScale

        // X scale
        val xExt     = xExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val xPad     = effectivePad(xOverride, xAxisCfg)
        val xNice    = xOverride.map(_.nice).getOrElse(true)
        val xReverse = xAxisCfg.reversed

        val xKindOpt: Maybe[Scale.Kind] = xOverride.flatMap(_.kind) match
            case Present(ScaleKind.Band)         => Present(Scale.Kind.Band)
            case Present(ScaleKind.Log)          => Present(Scale.Kind.Log)
            case Present(ScaleKind.Linear(_, _)) => Present(Scale.Kind.Linear)
            case Present(ScaleKind.Time)         => Present(Scale.Kind.Time)
            case Present(ScaleKind.Point)        => Present(Scale.Kind.Point)
            case Present(ScaleKind.Symlog)       => Present(Scale.Kind.Symlog)
            case _                               => Absent
        val xKind = xKindOpt.getOrElse(inferKind(xExt, marks, isX = true))
        val (xExtFinal, xLoRaw, xHiRaw, useXNice) = xOverride.flatMap(_.kind) match
            // Pad applies to an explicit linear domain too (every other arm pads); withPad must win.
            // An explicit linear x-domain is honored exactly (nice=false), mirroring the y path below.
            case Present(ScaleKind.Linear(domLo, domHi)) =>
                (padExtent(Extent.Continuous(domLo, domHi), xPad), layout.plotX, layout.plotX + layout.plotW, false)
            case _ => (padExtent(xExt, xPad), layout.plotX, layout.plotX + layout.plotW, xNice)
        // Swap range bounds when reverse=true so the first datum appears at the far end.
        val (xLo, xHi) = if xReverse then (xHiRaw, xLoRaw) else (xLoRaw, xHiRaw)
        val xClamp     = xOverride.map(_.clamp).getOrElse(false)
        val xs         = Scale.fit(xKind, xExtFinal, xLo, xHi, nice = useXNice, clamp = xClamp)

        // Y left scale: push the top of the y range down by topHeadroom so the max-value point's
        // centre sits topHeadroom px below plotY, keeping its top edge (cy - r) at or below plotY.
        val yExt     = yLeftExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
        val yNoZero  = yLeftExtentNoZero(rows, marks).getOrElse(Extent.Continuous(1.0, 10.0))
        val baseline = layout.plotBaseline
        val top      = layout.plotY + layout.topHeadroom
        val ysL      = resolveYScale(yExt, yNoZero, yOverride, yAxisCfg, baseline, top)

        // Y right scale (optional): uses plotY directly, with no topHeadroom offset.
        val ysR: Maybe[Scale] =
            if computeRight then
                val rExt    = yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))
                val rNoZero = yRightExtentNoZero(rows, marks).getOrElse(Extent.Continuous(1.0, 10.0))
                Present(resolveYScale(rExt, rNoZero, yRightOverride, yAxisRightCfg, layout.plotBaseline, layout.plotY))
            else Absent

        ResolvedScales(xs, ysL, ysR)
    end resolveAllScales

    private[kyo] def inferKind[A](ext: Extent, marks: Chunk[Mark[A]], isX: Boolean): Scale.Kind =
        ext match
            case _: Extent.Categories => Scale.Kind.Band
            case _: Extent.Continuous => Scale.Kind.Linear

end ChartScales
