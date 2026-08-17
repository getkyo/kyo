import Model.*
import kyo.*

/** Answers, before a session is spent, what effect size it could detect.
  *
  * A five-leg session at one fork resolved to ±5.77% and therefore could not see the +4.5% regression
  * this campaign is about. That was discovered by running the session and reading the footer, which
  * is the expensive order. Worse, `trailingMapsStayLinear` resolves to ±22.64%, so three separate
  * runs were spent reporting a 25% timing regression on a row that cannot support a verdict of that
  * size, and each was believed at the time.
  *
  * Everything needed to say so first is already stored: any prior run on the same rows carries each
  * leg's own error, and a bracket's resolution follows from that plus the leg count.
  *
  * Calibration says forks do not help. Tripling them cut each leg's own error six-fold and moved the
  * resolution by 0.05 points, because the variance is between legs rather than within them. So the
  * answer this gives is in legs, and it says that rather than leaving the reader to discover it.
  */
object Plan:

    /** What one row could resolve at a given leg count.
      *
      * `fromReplicates` records which quantity the estimate rests on, because they are not the same
      * and confusing them is how this file was wrong on its first run. A single leg reports the
      * spread of its own iterations; a bracket's threshold rests on the spread *between* legs. For
      * `continuationBodiesFuse` those are ±1.9% and ±0.9%, so a forecast from one leg called the row
      * unresolvable at ±14.6% while a real bracket resolved a -6.8% win on it.
      */
    case class Forecast(row: String, priorError: Double, legs: Int, resolvable: Double, fromReplicates: Boolean) derives Schema:
        def canSee(target: Double): Boolean = target >= resolvable

        def show: String =
            val basis = if fromReplicates then "between-leg" else "within-leg, an approximation"
            f"$row%-34s $basis%-28s ±${priorError * 100}%.1f%%  ->  resolves ±${resolvable * 100}%.1f%% at $legs legs"

    /** Estimate the detectable effect for each row of a stored run.
      *
      * The estimate is the leg's own relative error scaled by the standard error of a difference of
      * means over the planned split, times the t critical value the real comparison would use. It is
      * an estimate and is labelled one: the true spread is between legs and a prior single run can
      * only approximate it.
      */
    def forecast(priors: Chunk[Run], legs: Int, familyAlpha: Double): Chunk[Forecast] =
        val nC   = (legs + 1) / 2
        val nV   = legs / 2
        val df   = Math.max(0, (nC - 1) + (nV - 1))
        val rows = priors.headMaybe.map(_.rows.map(_.name)).getOrElse(Chunk.empty)
        val t    = Stats.tCritical(df, Stats.perRowAlpha(familyAlpha, rows.size))
        Chunk.from(
            rows.map { name =>
                val scores = Chunk.from(priors.flatMap(_.row(name)).map(_.score))
                val mean   = if scores.isEmpty then 0.0 else scores.sum / scores.size
                // between-leg spread when there are legs to measure it from; otherwise the leg's own
                // error, which is a different quantity and is labelled as an approximation
                val (rel, replicated) =
                    if scores.size >= 2 && mean > 0.0 then
                        val sd = Math.sqrt(scores.map(x => (x - mean) * (x - mean)).sum / (scores.size - 1))
                        (sd / mean, true)
                    else
                        (priors.headMaybe.flatMap(_.row(name)).map(_.relativeError).getOrElse(0.0), false)
                val se = rel * Math.sqrt(1.0 / nC + 1.0 / nV)
                Forecast(name, rel, legs, t * se, replicated)
            }.sortBy(-_.resolvable)
        )

    /** Rows that cannot support a verdict about an effect of the given size. */
    def blind(fs: Chunk[Forecast], target: Double): Chunk[Forecast] = Chunk.from(fs.filterNot(_.canSee(target)))

end Plan
