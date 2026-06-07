package kyo.internal

import kyo.Absent
import kyo.Chunk
import kyo.Maybe
import kyo.Present
import kyo.internal.NumberFormat

/** A resolved mapping from a data domain to a pixel range, with its inverse and tick generator.
  *
  * Constructed by `Scale.fit` from a `Scale.Kind`, the data domain `Extent`, and the pixel range bounds. All
  * implementations are pure; no effects.
  *
  * `apply` maps a domain value to a pixel coordinate; `invert` maps a pixel coordinate back to a domain value;
  * `ticks` generates labeled tick positions; `bandwidth` returns the category band width (zero for continuous
  * scales).
  */
sealed private[kyo] trait Scale:
    def apply(d: Domain): Double
    def invert(px: Double): Domain
    def ticks(maxTicks: Int): Chunk[Scale.Tick]
    def bandwidth: Double
end Scale

/** The data domain before a scale is fitted: either a continuous numeric range or an ordered set of category keys.
  *
  * `Continuous(min, max)` is produced by folding numeric domain values. `Categories(keys)` is produced by folding
  * categorical domain values in encounter order (deduplication preserving order).
  *
  * Defined above `object Scale` because it appears in `Scale.fit` and other companion signatures.
  */
private[kyo] enum Extent derives CanEqual:
    case Continuous(min: Double, max: Double)
    case Categories(keys: Chunk[String])
end Extent

private[kyo] object Extent:
    /** Construct a continuous extent over `[lo, hi]`. */
    def continuous(lo: Double, hi: Double): Extent = Extent.Continuous(lo, hi)

    /** Construct a categorical extent from an ordered key list. */
    def categories(keys: Chunk[String]): Extent = Extent.Categories(keys)
end Extent

/** The scale domain coordinate produced by `Chart.Plottable.toDomain`.
  *
  * Three variants cover all scale families: `Continuous` for linear/log/time numeric axes, `Category` for band and
  * ordinal axes, and `Temporal` for time axes (epoch milliseconds). Scales consume this union; the kind selects
  * which variant is valid.
  *
  * Defined above `object Scale` because `Scale.apply`/`Scale.invert` reference it.
  */
private[kyo] enum Domain derives CanEqual:
    case Continuous(value: Double)
    case Category(key: String)
    case Temporal(epochMillis: Long)
end Domain

/** Companion object providing `Scale.Kind`, `Scale.Tick`, `Extent`, and the `fit` factory.
  *
  * `Scale.fit` is the single entry point: supply a `Kind`, the domain `Extent`, and the pixel range, and get back a
  * fully resolved `Scale`. All seven concrete implementations (`Linear`, `Log`, `Band`, `Time`, `Ordinal`, `Point`,
  * `Symlog`) are produced exclusively through `fit`.
  *
  * `niceTicks` is exposed for direct use in tests and for axis tick generation at chart-build time.
  */
private[kyo] object Scale:

    /** Selects the scale family for an encoding. */
    enum Kind derives CanEqual:
        case Linear, Log, Band, Time, Ordinal, Point, Symlog
    end Kind

    /** A single tick: the domain value, a formatted label, and the pixel position on the range axis.
      *
      * `value` is the raw domain coordinate (continuous ticks carry the actual numeric value; time ticks carry
      * epoch millis as Double; categorical ticks carry the zero-based category index). This field is what the
      * `tickFormat` function in `AxisConfig` receives, so formatters always see the domain quantity, not the
      * screen pixel.
      */
    final case class Tick(value: Double, label: String, pixel: Double) derives CanEqual

    /** Construct a scale of `kind` over `extent`, mapping into `[rangeLo, rangeHi]` pixels.
      *
      * For continuous kinds (`Linear`, `Log`, `Time`), the extent supplies a numeric `(min, max)` pair. For
      * categorical kinds (`Band`, `Ordinal`), the extent supplies an ordered `Chunk[String]` of keys. The `nice`
      * flag snaps continuous bounds to round values (the demos' niceTicks logic); it has no effect for categorical
      * extents.
      */
    def fit(
        kind: Kind,
        extent: Extent,
        rangeLo: Double,
        rangeHi: Double,
        nice: Boolean = true,
        clamp: Boolean = false
    ): Scale =
        kind match
            case Kind.Linear  => fitLinear(extent, rangeLo, rangeHi, nice, clamp)
            case Kind.Log     => fitLog(extent, rangeLo, rangeHi, clamp)
            case Kind.Band    => fitBand(extent, rangeLo, rangeHi)
            case Kind.Time    => fitTime(extent, rangeLo, rangeHi, nice)
            case Kind.Ordinal => fitOrdinal(extent, rangeLo, rangeHi)
            case Kind.Point   => fitPoint(extent, rangeLo, rangeHi)
            case Kind.Symlog  => fitSymlog(extent, rangeLo, rangeHi, clamp)
    end fit

    /** Produce at most `maxTicks` evenly-spaced tick values covering `[min, max]`, snapped to a nice step.
      *
      * Degenerate inputs (`min == max` or `maxTicks <= 1`) return `Chunk(min)`. Every returned tick lies in
      * `[min, max]`. The step is chosen from `{1, 2, 5} * 10^k` to minimise the number of steps while covering
      * the range, following D3's nice-tick algorithm and the BarChart demo's `niceTicks` helper.
      */
    def niceTicks(min: Double, max: Double, maxTicks: Int = 5): Chunk[Double] =
        if maxTicks <= 1 || min == max then Chunk(min)
        else
            val rawStep   = (max - min) / (maxTicks - 1).toDouble
            val magnitude = math.pow(10.0, math.floor(math.log10(rawStep)))
            val residual  = rawStep / magnitude
            val niceUnit =
                if residual <= 1.0 then 1.0
                else if residual <= 2.0 then 2.0
                else if residual <= 5.0 then 5.0
                else 10.0
            val step = niceUnit * magnitude
            @scala.annotation.tailrec
            def loop(i: Int, t: Double, acc: Chunk[Double]): Chunk[Double] =
                if i >= maxTicks || t > max + step * 1.0e-9 then acc
                else loop(i + 1, t + step, acc.append(t))
            loop(0, min, Chunk.empty)
        end if
    end niceTicks

    // ---- concrete scale implementations ----

    /** A linear scale over a continuous `[domainMin, domainMax]` mapped to `[rangeLo, rangeHi]`. */
    final case class Linear(
        domainMin: Double,
        domainMax: Double,
        rangeLo: Double,
        rangeHi: Double,
        clamp: Boolean = false,
        niceStep: Maybe[Double] = Absent
    ) extends Scale:

        def apply(d: Domain): Double = d match
            case Domain.Continuous(v) =>
                if !v.isFinite then rangeLo
                else if domainMax == domainMin then rangeLo
                else
                    val t   = (v - domainMin) / (domainMax - domainMin)
                    val out = rangeLo + t * (rangeHi - rangeLo)
                    if clamp then
                        val lo = math.min(rangeLo, rangeHi)
                        val hi = math.max(rangeLo, rangeHi)
                        if out < lo then lo else if out > hi then hi else out
                    else out
                    end if
            case Domain.Category(_)  => rangeLo
            case Domain.Temporal(ms) => apply(Domain.Continuous(ms.toDouble))

        def invert(px: Double): Domain =
            if rangeHi == rangeLo then Domain.Continuous(domainMin)
            else
                val t = (px - rangeLo) / (rangeHi - rangeLo)
                Domain.Continuous(domainMin + t * (domainMax - domainMin))

        def ticks(maxTicks: Int): Chunk[Tick] =
            // Emit ticks at exactly `step` from domainMin by multiply-by-index (not accumulation,
            // to avoid float drift), so the TOP tick is exactly domainMax. The step is chosen to
            // honor the caller's maxTicks while guaranteeing it divides the domain evenly (top tick
            // lands on domainMax with no overshoot, no recomputed-step disagreement, no crash).
            def emit(step: Double): Chunk[Tick] =
                val count = math.max(0, math.round((domainMax - domainMin) / step).toInt)
                Chunk.tabulate(count + 1): i =>
                    val v = domainMin + i.toDouble * step
                    Tick(v, NumberFormat.double(v), apply(Domain.Continuous(v)))
            end emit
            niceStep match
                case Present(fitStep) if fitStep > 0 =>
                    // niceTicks honors maxTicks but may pick a step that does NOT land on
                    // domainMax over the widened snapped range (e.g. [0,250] -> step 100 -> top
                    // tick 200 != 250). fitStep divides the snapped range exactly by construction
                    // (snappedHi = fitStep*ceil(hi/fitStep)). When the maxTicks-honoring step
                    // already lands on domainMax, use it directly (byte-identical to the old
                    // niceTicks output for every non-overshooting domain). Otherwise fall back to
                    // the fitStep multiple closest to that step, which is guaranteed to land on
                    // domainMax.
                    val req     = niceTicks(domainMin, domainMax, maxTicks)
                    val reqStep = if req.size >= 2 then req(1) - req(0) else fitStep
                    val span    = domainMax - domainMin
                    val eps     = math.abs(fitStep) * 1.0e-9
                    val reqLandsOnMax =
                        reqStep > 0 && math.abs(math.round(span / reqStep) * reqStep - span) <= eps
                    if reqLandsOnMax then emit(reqStep)
                    else
                        // k = number of fitStep intervals in the snapped span (integer). Choose the
                        // multiplier m (>= 1) of fitStep whose interval count k/m is nearest the
                        // requested count, restricted to divisors of k so the top tick stays on
                        // domainMax. Ties prefer the smaller step (more ticks).
                        val k       = math.max(1, math.round(span / fitStep).toInt)
                        val wantInt = math.max(1, maxTicks - 1)
                        @scala.annotation.tailrec
                        def bestDivisor(m: Int, best: Int): Int =
                            if m > k then best
                            else if k % m == 0 then
                                val curDist  = math.abs(k / m - wantInt)
                                val bestDist = math.abs(k / best - wantInt)
                                bestDivisor(m + 1, if curDist < bestDist then m else best)
                            else bestDivisor(m + 1, best)
                        val m = bestDivisor(1, 1)
                        emit(fitStep * m.toDouble)
                    end if
                case _ =>
                    niceTicks(domainMin, domainMax, maxTicks).map: v =>
                        Tick(v, NumberFormat.double(v), apply(Domain.Continuous(v)))
            end match
        end ticks

        def bandwidth: Double = 0.0
    end Linear

    /** A logarithmic (base-10) scale over `[domainMin, domainMax]` mapped to `[rangeLo, rangeHi]`. */
    final case class Log(
        domainMin: Double,
        domainMax: Double,
        rangeLo: Double,
        rangeHi: Double,
        clamp: Boolean = false
    ) extends Scale:

        private val logMin: Double = if domainMin > 0 then math.log10(domainMin) else 0.0
        private val logMax: Double = if domainMax > 0 then math.log10(domainMax) else 0.0

        def apply(d: Domain): Double = d match
            case Domain.Continuous(v) =>
                if !v.isFinite then rangeLo
                else
                    val vc = if clamp then math.max(domainMin, math.min(domainMax, v)) else v
                    if vc <= 0 then rangeLo
                    else if logMax == logMin then rangeLo
                    else
                        val t   = (math.log10(vc) - logMin) / (logMax - logMin)
                        val out = rangeLo + t * (rangeHi - rangeLo)
                        if clamp then
                            val lo = math.min(rangeLo, rangeHi)
                            val hi = math.max(rangeLo, rangeHi)
                            if out < lo then lo else if out > hi then hi else out
                        else out
                        end if
                    end if
            case Domain.Category(_)  => rangeLo
            case Domain.Temporal(ms) => apply(Domain.Continuous(ms.toDouble))

        def invert(px: Double): Domain =
            if rangeHi == rangeLo then Domain.Continuous(domainMin)
            else
                val t = (px - rangeLo) / (rangeHi - rangeLo)
                Domain.Continuous(math.pow(10.0, logMin + t * (logMax - logMin)))

        def ticks(maxTicks: Int): Chunk[Tick] =
            val lo = math.ceil(logMin).toInt
            val hi = math.floor(logMax).toInt
            @scala.annotation.tailrec
            def loop(exp: Int, acc: Chunk[Tick]): Chunk[Tick] =
                if exp > hi || acc.size >= maxTicks then acc
                else
                    val v = math.pow(10.0, exp.toDouble)
                    loop(exp + 1, acc.append(Tick(v, NumberFormat.double(v), apply(Domain.Continuous(v)))))
            loop(lo, Chunk.empty)
        end ticks

        def bandwidth: Double = 0.0
    end Log

    /** A band scale mapping an ordered set of category keys to equal-width bands across `[rangeLo, rangeHi]`.
      *
      * Each band center is at `rangeLo + (i + 0.5) * slot` where `slot = (rangeHi - rangeLo) / n`. Inner
      * padding of 0.1 of the slot is applied (matching the BarChart demo's `bandScale`).
      */
    final case class Band(
        keys: Chunk[String],
        rangeLo: Double,
        rangeHi: Double,
        padding: Double = 0.1
    ) extends Scale:

        val n: Int         = keys.size
        val totalW: Double = rangeHi - rangeLo
        val slot: Double   = if n <= 0 then totalW else totalW / n.toDouble
        val bandW: Double  = if n <= 0 then totalW else totalW * (1.0 - padding) / n.toDouble
        private val keyIndex: Map[String, Int] = keys.zipWithIndex.foldLeft(Map.empty[String, Int]):
            case (m, (k, i)) => m.updated(k, i)

        def apply(d: Domain): Double = d match
            case Domain.Category(key) =>
                Maybe.fromOption(keyIndex.get(key)) match
                    case Absent => rangeLo
                    case Present(i) =>
                        val xOffset = i.toDouble * slot + (slot - bandW) / 2.0
                        rangeLo + xOffset
            case Domain.Continuous(v) =>
                // First try the numeric value as a category key (handles overridden Band scales
                // where the data is a numeric type but the scale was forced to Band via xScale(_.band)).
                // This converts e.g. Continuous(2020.0) to "2020" before trying integer index lookup.
                val keyStr = NumberFormat.double(v)
                Maybe.fromOption(keyIndex.get(keyStr)) match
                    case Present(i) =>
                        val xOffset = i.toDouble * slot + (slot - bandW) / 2.0
                        rangeLo + xOffset
                    case Absent =>
                        // Fall back to treating v as a 0-based index (original behavior)
                        val i = v.toInt
                        if i >= 0 && i < n then
                            val xOffset = i.toDouble * slot + (slot - bandW) / 2.0
                            rangeLo + xOffset
                        else rangeLo
                        end if
                end match
            case Domain.Temporal(_) => rangeLo

        def invert(px: Double): Domain =
            if n <= 0 || totalW <= 0 then Domain.Category(if keys.isEmpty then "" else keys(0))
            else
                val i = math.min(n - 1, math.max(0, ((px - rangeLo) / slot).toInt))
                Domain.Category(keys(i))

        def ticks(maxTicks: Int): Chunk[Tick] =
            val n = keys.size
            // Use original key indices so pixel positions reflect actual band positions.
            val visible: Chunk[(String, Int)] =
                if maxTicks >= n then keys.zipWithIndex
                else
                    val stride = math.max(1, math.ceil(n.toDouble / maxTicks).toInt)
                    keys.zipWithIndex.collect { case (k, i) if i % stride == 0 => (k, i) }
            visible.map: (k, i) =>
                val xOffset = i.toDouble * slot + (slot - bandW) / 2.0 + bandW / 2.0
                Tick(i.toDouble, k, rangeLo + xOffset)
        end ticks

        def bandwidth: Double = bandW
    end Band

    /** A time scale: a linear scale over epoch-millisecond values. */
    final case class Time(
        domainMin: Long,
        domainMax: Long,
        rangeLo: Double,
        rangeHi: Double
    ) extends Scale:

        private val inner: Linear = Linear(domainMin.toDouble, domainMax.toDouble, rangeLo, rangeHi)

        def apply(d: Domain): Double = d match
            case Domain.Temporal(ms)  => inner.apply(Domain.Continuous(ms.toDouble))
            case Domain.Continuous(v) => inner.apply(Domain.Continuous(v))
            case Domain.Category(_)   => rangeLo

        def invert(px: Double): Domain = inner.invert(px) match
            case Domain.Continuous(v) => Domain.Temporal(v.toLong)
            case other                => other

        def ticks(maxTicks: Int): Chunk[Tick] =
            val ts   = niceTicks(domainMin.toDouble, domainMax.toDouble, maxTicks)
            val step = if ts.size >= 2 then (ts(1) - ts(0)).toLong else (domainMax - domainMin)
            ts.map: v =>
                Tick(v, TimeFormat.epochMillisLabel(v.toLong, step), apply(Domain.Temporal(v.toLong)))
        end ticks

        def bandwidth: Double = 0.0
    end Time

    /** An ordinal scale: maps category keys to palette indices in `[0, n)`.
      *
      * `apply` returns the zero-based index of a known key. For an UNKNOWN key it returns `-1.0`, a detectable
      * sentinel that the legend and color layers can treat as "unmapped" without silently colliding with the
      * first valid index (0). Callers that receive `-1.0` should render a fallback color or omit the mark.
      */
    final case class Ordinal(
        keys: Chunk[String],
        rangeLo: Double,
        rangeHi: Double
    ) extends Scale:

        val n: Int = keys.size
        private val keyIndex: Map[String, Int] = keys.zipWithIndex.foldLeft(Map.empty[String, Int]):
            case (m, (k, i)) => m.updated(k, i)

        def apply(d: Domain): Double = d match
            case Domain.Category(key) =>
                // Maybe.fromOption at the stdlib Map.get boundary; getOrElse on Kyo Maybe.
                Maybe.fromOption(keyIndex.get(key)).map(_.toDouble).getOrElse(-1.0)
            case Domain.Continuous(v) => v
            case Domain.Temporal(ms)  => ms.toDouble

        def invert(px: Double): Domain =
            val i = px.toInt
            if i >= 0 && i < n then Domain.Category(keys(i))
            else Domain.Category(if keys.isEmpty then "" else keys(0))
        end invert

        def ticks(maxTicks: Int): Chunk[Tick] =
            val n = keys.size
            val visible: Chunk[(String, Int)] =
                if maxTicks >= n then keys.zipWithIndex
                else
                    val stride = math.max(1, math.ceil(n.toDouble / maxTicks).toInt)
                    keys.zipWithIndex.collect { case (k, i) if i % stride == 0 => (k, i) }
            visible.map: (k, i) =>
                Tick(i.toDouble, k, i.toDouble)
        end ticks

        def bandwidth: Double = 0.0
    end Ordinal

    // ---- private fit helpers ----

    private def fitLinear(extent: Extent, rangeLo: Double, rangeHi: Double, nice: Boolean, clamp: Boolean): Scale =
        val (lo, hi) = extent match
            case Extent.Continuous(mn, mx) => (mn, mx)
            case Extent.Categories(keys)   => (0.0, keys.size.toDouble - 1.0)
        if nice then
            val tks  = niceTicks(lo, hi, 5)
            val step = if tks.size >= 2 then tks(1) - tks(0) else 0.0
            // Snap the domain to step-aligned bounds (d3 scale.nice() semantics): floor lo and
            // ceil hi to the nearest multiple of the nice step so the endpoints ARE ticks. The
            // chosen step is STORED on the scale (niceStep) and reused by Linear.ticks to emit
            // ticks at exactly that step from domainMin to domainMax. This makes the top tick
            // equal domainMax for ANY snapped domain. Sharing the step (rather than re-deriving
            // it from niceTicks over the widened range) guarantees the top tick lands on
            // domainMax without overshoot (e.g. [10,210] snaps to [0,250]; a re-derived
            // niceTicks(0,250) would step by 100 and overshoot).
            // An already-aligned domain stays unchanged (floor/ceil are no-ops).
            if step > 0 then
                val snappedLo = step * math.floor(lo / step)
                val snappedHi = step * math.ceil(hi / step)
                Linear(snappedLo, snappedHi, rangeLo, rangeHi, clamp, niceStep = Present(step))
            else Linear(lo, hi, rangeLo, rangeHi, clamp) // degenerate (lo == hi): niceStep Absent
            end if
        else Linear(lo, hi, rangeLo, rangeHi, clamp)
        end if
    end fitLinear

    private def fitLog(extent: Extent, rangeLo: Double, rangeHi: Double, clamp: Boolean): Scale =
        val (lo, hi) = extent match
            case Extent.Continuous(mn, mx) => (mn, mx)
            case Extent.Categories(keys)   => (1.0, keys.size.toDouble)
        // Non-positive values are filtered upstream in yLeftExtentNoZero; lo is positive by construction.
        // math.max(hi, lo) guards a degenerate empty domain after filtering.
        Log(lo, math.max(hi, lo), rangeLo, rangeHi, clamp)
    end fitLog

    private def fitBand(extent: Extent, rangeLo: Double, rangeHi: Double): Scale =
        val keys = extent match
            case Extent.Categories(ks) => ks
            case Extent.Continuous(mn, mx) =>
                val lo = mn.toInt
                val hi = mx.toInt
                Chunk.from(lo.to(hi).map(_.toString))
        Band(keys, rangeLo, rangeHi)
    end fitBand

    private def fitTime(extent: Extent, rangeLo: Double, rangeHi: Double, nice: Boolean): Scale =
        val (lo, hi) = extent match
            case Extent.Continuous(mn, mx) => (mn.toLong, mx.toLong)
            case Extent.Categories(_)      => (0L, 1L)
        Time(lo, hi, rangeLo, rangeHi)
    end fitTime

    private def fitOrdinal(extent: Extent, rangeLo: Double, rangeHi: Double): Scale =
        val keys = extent match
            case Extent.Categories(ks) => ks
            case Extent.Continuous(mn, mx) =>
                val lo = mn.toInt
                val hi = mx.toInt
                Chunk.from(lo.to(hi).map(_.toString))
        Ordinal(keys, rangeLo, rangeHi)
    end fitOrdinal

    // Point scale: like Band but each band has zero inner width (marks placed at band centers).
    // Reuses Band internally with padding = 0.5 so each slot's band collapses to zero width.
    private def fitPoint(extent: Extent, rangeLo: Double, rangeHi: Double): Scale =
        val keys = extent match
            case Extent.Categories(ks) => ks
            case Extent.Continuous(mn, mx) =>
                val lo = mn.toInt
                val hi = mx.toInt
                Chunk.from(lo.to(hi).map(_.toString))
        Band(keys, rangeLo, rangeHi, padding = 0.5)
    end fitPoint

    private def fitSymlog(extent: Extent, rangeLo: Double, rangeHi: Double, clamp: Boolean): Scale =
        val (rawLo, rawHi) = extent match
            case Extent.Continuous(mn, mx) => (mn, mx)
            case Extent.Categories(_)      => (-1.0, 1.0)
        Symlog(rawLo, rawHi, rangeLo, rangeHi, clamp)
    end fitSymlog

    /** Symmetric-log scale for data spanning negative-to-positive values.
      *
      * Forward transform: f(v) = sign(v) * log10(1 + |v| / C), C=1 fixed. Exact algebraic inverse: g(u) =
      * sign(u) * C * (10^|u| - 1). Finite at zero (f(0)=0), monotone, symmetric about zero. Ticks are
      * generated in the transformed domain via niceTicks then mapped back through g. No public C knob; C=1
      * matches D3's default, log10 base gives readable power-of-10 labels.
      */
    final case class Symlog(
        domainMin: Double,
        domainMax: Double,
        rangeLo: Double,
        rangeHi: Double,
        clamp: Boolean
    ) extends Scale:
        private val C                    = 1.0
        private def f(v: Double): Double = math.signum(v) * math.log10(1.0 + math.abs(v) / C)
        private def g(u: Double): Double = math.signum(u) * C * (math.pow(10.0, math.abs(u)) - 1.0)
        private val fMin                 = f(domainMin)
        private val fMax                 = f(domainMax)

        private def applyRaw(raw: Double): Double =
            if fMax == fMin then rangeLo
            else rangeLo + (f(raw) - fMin) / (fMax - fMin) * (rangeHi - rangeLo)

        def apply(d: Domain): Double = d match
            case Domain.Continuous(v) =>
                if !v.isFinite then rangeLo
                else if clamp then applyRaw(math.max(domainMin, math.min(domainMax, v)))
                else applyRaw(v)
            case Domain.Temporal(ms) =>
                val v = ms.toDouble
                if clamp then applyRaw(math.max(domainMin, math.min(domainMax, v)))
                else applyRaw(v)
            case Domain.Category(_) => applyRaw(domainMin)
        end apply

        def invert(px: Double): Domain =
            if rangeHi == rangeLo then Domain.Continuous(domainMin)
            else
                val t = (px - rangeLo) / (rangeHi - rangeLo)
                Domain.Continuous(g(fMin + t * (fMax - fMin)))

        def ticks(maxTicks: Int): Chunk[Tick] =
            niceTicks(fMin, fMax, maxTicks).map: u =>
                val v = g(u)
                Tick(v, NumberFormat.double(v), apply(Domain.Continuous(v)))

        def bandwidth: Double = 0.0
    end Symlog

end Scale
