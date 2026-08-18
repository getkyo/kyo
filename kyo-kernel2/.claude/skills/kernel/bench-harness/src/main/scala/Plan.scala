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
    case class Forecast(
        row: String,
        priorError: Double,
        legs: Int,
        resolvable: Double,
        fromReplicates: Boolean,
        /** True when the spread came from one arm only, which biases the forecast optimistic. */
        oneArm: Boolean = false,
        /** True when the leg's own reported error, not the between-leg spread, set the resolution: more legs cannot move it, only more
          * iterations per fork. A row this holds for stays blind to a target below its floor however many legs are planned. */
        floorBound: Boolean = false
    ) derives Schema:
        def canSee(target: Double): Boolean = target >= resolvable

        def lever: String =
            if floorBound then "more iterations per fork (bounded by each leg's own error, which more legs cannot lower)"
            else "more legs, or a quieter machine (bounded by the between-leg spread)"

        def show: String =
            val basis =
                if !fromReplicates then "within-leg, an approximation"
                else if oneArm then "one arm only, optimistic"
                else "both arms, pooled"
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
                // Split by arm and pool WITHIN each, which is what `Stats.pooledSd` does and therefore
                // what the threshold being forecast actually rests on. Lumping every prior into one
                // spread is wrong twice over: given one arm it sees only that arm's variance, and
                // given both it folds the real difference between them into the "spread" and inflates
                // the forecast instead.
                //
                // Measured against the replicated sweep, the one-arm version under-predicted:
                // `continuationBodiesFuse` forecast +-1.9% against an actual +-3.6%, and
                // `handleLoopAnswersInPlace` +-10.0% against +-14.4%, because the variant legs carry
                // more spread than the controls and a control-only forecast cannot see it.
                def armScores(arm: String) =
                    Chunk.from(priors.filter(_.label.startsWith(arm)).flatMap(_.row(name)).map(_.score))
                val ctl  = armScores("control")
                val vnt  = armScores("variant")
                val arms = Chunk(ctl, vnt).filter(_.size >= 2)
                val all = Chunk.from(priors.flatMap(_.row(name)).map(_.score))
                // normalised against the CONTROL mean when there is one, because that is what
                // `Stats.threshold` divides by. Using the mean of every leg understates the forecast
                // whenever the arms genuinely differ: on `trailingMapsStayLinear`, whose variant runs
                // a third slower than its control, that alone put the forecast 8.8 points below the
                // threshold it was predicting.
                val mean =
                    if ctl.nonEmpty then ctl.sum / ctl.size
                    else if all.isEmpty then 0.0
                    else all.sum / all.size

                def pooled(groups: Chunk[Chunk[Double]]): Maybe[Double] =
                    val df = groups.map(_.size - 1).sum
                    if df <= 0 then Maybe.empty
                    else
                        val ss = groups.map { g =>
                            val m = g.sum / g.size
                            g.map(x => (x - m) * (x - m)).sum
                        }.sum
                        Maybe(Math.sqrt(ss / df))

                val (rel, replicated) =
                    pooled(arms) match
                        case Maybe.Present(sd) if mean > 0.0 && arms.size >= 2 => (sd / mean, true)
                        case Maybe.Present(sd) if mean > 0.0                   => (sd / mean, true)
                        case _ =>
                            if all.size >= 2 && mean > 0.0 then
                                val sd = Math.sqrt(all.map(x => (x - mean) * (x - mean)).sum / (all.size - 1))
                                (sd / mean, true)
                            else
                                (priors.headMaybe.flatMap(_.row(name)).map(_.relativeError).getOrElse(0.0), false)
                val oneArm = arms.size < 2
                val se     = rel * Math.sqrt(1.0 / nC + 1.0 / nV)
                // the same floor `Stats.threshold` applies: a threshold below the legs' own reported
                // uncertainty would classify that uncertainty as a result. Forecasting without it
                // predicts a resolution the real comparison will never award.
                val ownError = priors.flatMap(_.row(name)).map(_.relativeError).maxOption.getOrElse(0.0)
                Forecast(name, rel, legs, Math.max(t * se, ownError), replicated, oneArm, floorBound = ownError >= t * se)
            }.sortBy(-_.resolvable)
        )

    /** Rows that cannot support a verdict about an effect of the given size. */
    def blind(fs: Chunk[Forecast], target: Double): Chunk[Forecast] = Chunk.from(fs.filterNot(_.canSee(target)))

end Plan
