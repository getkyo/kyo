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

    /** Two-sided Student t critical values, indexed by degrees of freedom.
      *
      * Small tables rather than an incomplete-beta implementation: a session has 5 legs, so df is 3, and the only values ever needed are the
      * first few rows. Values beyond the table fall back to the normal limit, which is correct in the direction that matters (it never makes
      * the threshold looser than the table would).
      */
    private val tTable: Map[Int, Map[Double, Double]] = Map(
        1 -> Map(0.05 -> 12.706, 0.01 -> 63.657, 0.00333 -> 191.18),
        2 -> Map(0.05 -> 4.303, 0.01 -> 9.925, 0.00333 -> 17.28),
        3 -> Map(0.05 -> 3.182, 0.01 -> 5.841, 0.00333 -> 8.58),
        4 -> Map(0.05 -> 2.776, 0.01 -> 4.604, 0.00333 -> 6.25),
        5 -> Map(0.05 -> 2.571, 0.01 -> 4.032, 0.00333 -> 5.25),
        6 -> Map(0.05 -> 2.447, 0.01 -> 3.707, 0.00333 -> 4.74),
        8 -> Map(0.05 -> 2.306, 0.01 -> 3.355, 0.00333 -> 4.15),
        10 -> Map(0.05 -> 2.228, 0.01 -> 3.169, 0.00333 -> 3.87)
    )

    private val normal: Map[Double, Double] = Map(0.05 -> 1.960, 0.01 -> 2.576, 0.00333 -> 2.935)

    def tCritical(df: Int, alpha: Double): Double =
        if df <= 0 then Double.PositiveInfinity
        else
            val row = tTable.getOrElse(df, tTable.getOrElse(if df > 10 then 10 else df, Map.empty))
            // nearest tabulated alpha at or below the requested one, so an untabulated alpha is never
            // rounded to a looser threshold than asked for
            val exact = row.get(alpha)
            exact.getOrElse {
                val candidates = row.filter(_._1 <= alpha)
                if candidates.nonEmpty then candidates.minBy(_._1)._2
                else if df > 10 then normal.getOrElse(alpha, 2.935)
                else row.values.maxOption.getOrElse(Double.PositiveInfinity)
            }

    /** One row measured across the replicate legs of one session. */
    case class Replicated(row: String, control: Chunk[Double], variant: Chunk[Double]) derives Schema:
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

    /** The threshold a row must clear, and the smallest effect that could have cleared it.
      *
      * `minimumDetectable` is reported on every flat row, so "flat" means "flat to within this much" rather than "nothing was found". A
      * harness that cannot say how small an effect it could have seen is not entitled to call a row unchanged.
      */
    case class Threshold(row: String, absolute: Double, percent: Double, df: Int, alpha: Double) derives Schema:
        def show: String = f"+-${percent}%.2f%% (t at alpha=$alpha%.5f, df=$df)"

    /** Per-row alpha after correcting for testing every row of the class at once.
      *
      * Fifteen independent tests at 5% each produce at least one false positive 54% of the time. Bonferroni is the conservative choice and
      * the one that can be stated in a sentence, which matters more here than the power a sharper correction would buy.
      */
    def perRowAlpha(familyAlpha: Double, rows: Int): Double =
        if rows <= 0 then familyAlpha else familyAlpha / rows

    def threshold(r: Replicated, familyAlpha: Double, rows: Int): Maybe[Threshold] =
        val alpha = perRowAlpha(familyAlpha, rows)
        r.standardError.map { se =>
            val t   = tCritical(r.degreesOfFreedom, alpha)
            val abs = t * se
            Threshold(r.row, abs, if r.controlMean == 0.0 then 0.0 else abs / r.controlMean * 100, r.degreesOfFreedom, alpha)
        }

    /** Classifies one row against its own threshold. A row whose spread cannot be estimated is unresolved, never flat. */
    def classify(r: Replicated, familyAlpha: Double, rows: Int): (Verdict, Maybe[Threshold]) =
        val th = threshold(r, familyAlpha, rows)
        th match
            case Maybe.Present(t) =>
                val diff = r.variantMean - r.controlMean
                if Math.abs(diff) <= t.absolute then (Verdict.Flat, th)
                else if diff < 0 then (Verdict.Faster, th)
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
