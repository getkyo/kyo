import Model.*
import kyo.*

/** The statistics a verdict rests on.
  *
  * The previous design compared the variant against `mean(C1, C2)` and thresholded on `|C2 - C1|`, a single absolute difference. Simulated
  * under its own model, that classifies 45.5% of rows under pure noise, so its acceptance criterion ("no row classified in an A/A") failed
  * 99.99% of the time on a correct implementation. The predictable response is to widen the band until it passes, which is reward-hacking
  * the harness's own gate. Under drift of 3 sigma per leg the same band inflates to 6 sigma and a real 4 sigma regression is detected 14% of
  * the time. It was wrong in both directions at once.
  *
  * What replaces it is ordinary and boring, which is the point: replicate the legs, estimate the spread from the replicates, and threshold
  * with a t statistic at a stated alpha, corrected for testing fifteen rows at once.
  */
object Stats:

    /** Log of the gamma function, Lanczos approximation. Accurate to about 15 significant digits over the range used here. */
    private def logGamma(x: Double): Double =
        val c = Array(
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        )
        val tmp0 = x + 5.5
        val tmp  = tmp0 - (x + 0.5) * Math.log(tmp0)
        var ser  = 1.000000000190015
        var j    = 0
        while j < 6 do
            ser += c(j) / (x + j + 1)
            j += 1
        -tmp + Math.log(2.5066282746310005 * ser / x)
    end logGamma

    /** Continued-fraction expansion for the incomplete beta function, evaluated by the modified Lentz method. */
    private def betaContinuedFraction(a: Double, b: Double, x: Double): Double =
        val tiny = 1e-30
        val qab  = a + b
        val qap  = a + 1.0
        val qam  = a - 1.0
        var c    = 1.0
        var d    = 1.0 - qab * x / qap
        if Math.abs(d) < tiny then d = tiny
        d = 1.0 / d
        var h = d
        var m = 1
        while m <= 300 do
            val m2  = 2 * m
            val aa1 = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa1 * d
            if Math.abs(d) < tiny then d = tiny
            c = 1.0 + aa1 / c
            if Math.abs(c) < tiny then c = tiny
            d = 1.0 / d
            h *= d * c
            val aa2 = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa2 * d
            if Math.abs(d) < tiny then d = tiny
            c = 1.0 + aa2 / c
            if Math.abs(c) < tiny then c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if Math.abs(del - 1.0) < 3e-16 then m = 301 else m += 1
        h
    end betaContinuedFraction

    /** Regularized incomplete beta function I_x(a, b). */
    private def incompleteBeta(a: Double, b: Double, x: Double): Double =
        if x <= 0.0 then 0.0
        else if x >= 1.0 then 1.0
        else
            val front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log1p(-x))
            if x < (a + 1.0) / (a + b + 2.0) then front * betaContinuedFraction(a, b, x) / a
            else 1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b

    /** Two-sided tail probability of Student's t: P(|T| > t) for the given degrees of freedom. */
    def tTailTwoSided(df: Int, t: Double): Double =
        if df <= 0 then 1.0
        else incompleteBeta(df / 2.0, 0.5, df / (df + t * t))

    /** Two-sided Student t critical value, computed rather than tabulated.
      *
      * A table was tried first and was wrong in two ways that a table is always liable to be: it returned infinity for any degrees of freedom
      * it happened to omit (7 and 9, both reachable from ordinary leg counts), which silently classified a 100% regression as *flat* and
      * printed "detectable at +-Infinity%" under a green all-clear; and its fallback for an untabulated alpha was *looser* than the truth
      * while its comment claimed the opposite, so tightening the Bonferroni correction from 15 rows to 16 would have loosened the test.
      *
      * Bisection on the exact tail probability has neither failure mode and is about as much code as the table was.
      */
    def tCritical(df: Int, alpha: Double): Double =
        if df <= 0 then Double.PositiveInfinity
        else if alpha <= 0.0 then Double.PositiveInfinity
        else if alpha >= 1.0 then 0.0
        else
            var lo = 0.0
            var hi = 1.0
            // expand until the tail at `hi` is below alpha, so the root is bracketed
            while tTailTwoSided(df, hi) > alpha && hi < 1e12 do hi *= 2.0
            var i = 0
            while i < 200 do
                val mid = (lo + hi) / 2.0
                if tTailTwoSided(df, mid) > alpha then lo = mid else hi = mid
                i += 1
            (lo + hi) / 2.0

    /** One row measured across the replicate legs of one session. */
    case class Replicated(
        row: String,
        control: Chunk[Double],
        variant: Chunk[Double],
        legError: Chunk[Double] = Chunk.empty,
        /** Which way is down for this row (`Row.lowerIsBetter`): time per operation shrinks when the code gets faster, throughput
          * grows. The classifier used to read `diff < 0` as faster unconditionally, which is backwards for a `thrpt` row.
          */
        lowerIsBetter: Boolean = true
    ) derives Schema:
        /** The largest relative error any single leg reported for itself, as a fraction.
          *
          * A threshold derived only from between-leg spread can come out tighter than the uncertainty
          * of the legs it is built from, and then it classifies that uncertainty as a result. A real
          * A/A bracket did exactly this: five legs of identical sources, each leg reporting +-5.2% of
          * its own, produced a 2.60% threshold and called a 3.8% difference a regression. The retired
          * drift estimator had this floor (`max(spread, ownError)`) and the replicate statistic lost
          * it.
          */
        def ownError: Double = if legError.isEmpty then 0.0 else legError.max

        def nC: Int             = control.size
        def nV: Int             = variant.size
        def controlMean: Double = if nC == 0 then 0.0 else control.sum / nC
        def variantMean: Double = if nV == 0 then 0.0 else variant.sum / nV

        /** Pooled within-group standard deviation, the only honest estimate of run-to-run spread available.
          *
          * A single absolute difference between two control legs is a draw from a distribution, not a threshold; using it as one is what made
          * the previous design classify noise half the time.
          */
        def pooledSd: Maybe[Double] =
            val df = degreesOfFreedom
            if df <= 0 then Maybe.empty
            else
                val ssC = control.map(x => (x - controlMean) * (x - controlMean)).sum
                val ssV = variant.map(x => (x - variantMean) * (x - variantMean)).sum
                Maybe(Math.sqrt((ssC + ssV) / df))

        def degreesOfFreedom: Int = Math.max(0, (nC - 1) + (nV - 1))

        /** Standard error of the difference of means. */
        def standardError: Maybe[Double] =
            if nC == 0 || nV == 0 then Maybe.empty
            else pooledSd.map(sd => sd * Math.sqrt(1.0 / nC + 1.0 / nV))

        def deltaPercent: Double = if controlMean == 0.0 then 0.0 else (variantMean - controlMean) / controlMean * 100
    end Replicated

    /** Per-row alpha after correcting for testing every row of the class at once.
      *
      * Fifteen independent tests at 5% each produce at least one false positive 54% of the time. Bonferroni is the conservative choice and
      * the one that can be stated in a sentence, which matters more here than the power a sharper correction would buy.
      */
    def perRowAlpha(familyAlpha: Double, rows: Int): Double =
        if rows <= 0 then familyAlpha else familyAlpha / rows

    def threshold(r: Replicated, familyAlpha: Double, rows: Int): Maybe[Resolution] =
        val alpha = perRowAlpha(familyAlpha, rows)
        r.standardError.map { se =>
            val t = tCritical(r.degreesOfFreedom, alpha)
            // never tighter than the legs' own reported uncertainty: a threshold below that
            // classifies the measurement's own error as a result
            val stat  = t * se
            val floor = r.ownError * r.controlMean
            val abs   = Math.max(stat, floor)
            // which term won decides which lever tightens the threshold, so the report can stop
            // telling a floor-bound row to add legs, which cannot move it
            Resolution(if r.controlMean == 0.0 then 0.0 else abs / r.controlMean * 100, abs, r.degreesOfFreedom, alpha, floorBound = floor >= stat)
        }

    /** Classifies one row against its own threshold. A row whose spread cannot be estimated is unresolved, never flat. */
    def classify(r: Replicated, familyAlpha: Double, rows: Int): (Verdict, Maybe[Resolution]) =
        val th = threshold(r, familyAlpha, rows)
        th match
            case Maybe.Present(t) =>
                val diff = r.variantMean - r.controlMean
                // the sign of the difference says which way the score moved; which way is better is
                // the row's mode, not the classifier's assumption
                val improved = if r.lowerIsBetter then diff < 0 else diff > 0
                if Math.abs(diff) <= t.absolute then (Verdict.Flat, th)
                else if improved then (Verdict.Faster, th)
                else (Verdict.Regressed, th)
            case _ => (Verdict.BelowResolution, th)
    end classify

    /** Common-mode drift across the session, estimated as the median per-row control trend.
      *
      * Reported rather than spent: if every row moves the same way by the same amount, that is the machine warming, and the replicate design
      * already cancels it from the estimator. Charging it to the band as well would inflate every threshold by a quantity that has already
      * been removed, which is the contradiction the previous design carried.
      */
    def commonMode(rs: Chunk[Replicated]): Maybe[Double] =
        val trends =
            rs.flatMap { r =>
                if r.control.size < 2 then Chunk.empty
                else Chunk((r.control.last - r.control.head) / (if r.controlMean == 0.0 then 1.0 else r.controlMean) * 100)
            }
        median(trends)

    /** Per-row residual after removing the session's common mode: the part that is this row's own noise. */
    def residual(r: Replicated, common: Double): Maybe[Double] =
        if r.control.size < 2 then Maybe.empty
        else
            val trend = (r.control.last - r.control.head) / (if r.controlMean == 0.0 then 1.0 else r.controlMean) * 100
            Maybe(Math.abs(trend - common))

    def median(xs: Chunk[Double]): Maybe[Double] =
        if xs.isEmpty then Maybe.empty
        else
            val s = xs.sorted
            val n = s.size
            Maybe(if n % 2 == 1 then s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2.0)

end Stats
