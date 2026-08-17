import Model.*
import kyo.*

/** Holds `BenchPlan`'s forecast to the threshold a real bracket actually produced.
  *
  * This is the check the fix was missing. `Plan` was corrected twice against the replicated sweep, by
  * hand, by running the command and reading two outputs side by side. That verified the fix and left
  * nothing behind to catch it regressing, which is exactly the shape this project keeps having to
  * correct in its own results.
  *
  * The assertion is deliberately a *relationship* rather than a table of constants: the forecast for a
  * bracket's rows must reproduce the threshold that bracket's own `compareReplicated` computed. A
  * hardcoded expectation would go stale the moment the store is replaced, and stale expectations are
  * how a broken component keeps passing. Both sides are derived from the same stored legs, so if
  * either estimator drifts the two stop agreeing.
  *
  * Why the two agreeing is not circular: they are computed by different code along different routes.
  * `Stats.threshold` pools the legs it is given and applies a t critical value and a floor;
  * `Plan.forecast` reconstructs the same quantity from a *planned* leg count without seeing the
  * comparison. They agreed only after two independent defects were fixed, and before that they
  * disagreed by up to 1.7x.
  */
object PlanTest extends KyoApp:

    val store = Roots.repo / "bench-results" / "sweep-replicated"

    var failures = 0

    def check(name: String, cond: Boolean, detail: String = ""): Unit =
        if cond then println(s"  ok   $name")
        else
            failures += 1
            println(s"  FAIL $name${if detail.nonEmpty then s"\n         $detail" else ""}")

    run {
        for
            runs <- Store.list(store)
            controls = runs.filter(_.label.startsWith("control")).sortBy(_.label)
            variants = runs.filter(_.label.startsWith("variant")).sortBy(_.label)

            _ = println("the fixture: a real five-leg bracket")
            _ = check("three control legs", controls.size == 3, s"${controls.size}")
            _ = check("two variant legs", variants.size == 2, s"${variants.size}")
            _ = check(
                "and it recorded the configuration that produced it",
                variants.forall(_.jvmArgs.exists(_.contains("CompileCommandFile"))) && controls.forall(_.jvmArgs.isEmpty),
                s"control ${controls.map(_.jvmArgs).mkString}, variant ${variants.map(_.jvmArgs).mkString}"
            )

            cmp      = Bench.compareReplicated(controls, variants)
            forecast = Plan.forecast(runs, legs = 5, Bench.FamilyAlpha)
            byRow    = forecast.map(f => f.row -> f).toMap

            _ = println("\nthe forecast reproduces the threshold the bracket computed")
            // every row the comparison resolved must have a forecast, and the two must agree closely.
            // The tolerance is a tenth of the threshold: these are two derivations of one quantity,
            // not two measurements of it, so they should differ only by rounding and by the fact that
            // the forecast normalises against every leg while the threshold normalises against the
            // control mean.
            compared = cmp.deltas.flatMap(d => Chunk.from(d.resolution.map(r => (d.row, r.percent))))
            _        = check("every row carries a resolution to compare against", compared.size == cmp.deltas.size, s"${compared.size} of ${cmp.deltas.size}")
            _ = compared.foreach { (row, actual) =>
                byRow.get(row) match
                    case None => check(s"$row has a forecast", false)
                    case Some(f) =>
                        val predicted = f.resolvable * 100
                        val slack     = Math.max(0.5, actual * 0.10)
                        check(
                            f"$row%-34s forecast ±${predicted}%.1f%% against actual ±${actual}%.1f%%",
                            Math.abs(predicted - actual) <= slack,
                            f"off by ${Math.abs(predicted - actual)}%.2f points, tolerance ${slack}%.2f"
                        )
            }

            _ = println("\nthe estimator says which basis it used")
            // a one-arm forecast cannot see the other arm's variance and is optimistic because of it.
            // Saying so is the difference between a number and a number you can act on.
            oneArm = Plan.forecast(controls, legs = 5, Bench.FamilyAlpha)
            _      = check("both arms pool", forecast.forall(f => f.fromReplicates && !f.oneArm), forecast.take(1).map(_.show).mkString)
            _      = check("one arm admits it is one arm", oneArm.forall(_.oneArm), oneArm.take(1).map(_.show).mkString)
            _      = check("and says so in its own output", oneArm.headOption.exists(_.show.contains("optimistic")), oneArm.take(1).map(_.show).mkString)

            // The must-fire twin, and it corrects a claim I made before measuring it. I wrote that a
            // control-only forecast is "systematically optimistic". On this bracket it under-predicts
            // on 4 rows of 15 and over-predicts on others, so the honest property is not a direction
            // but a difference: seeing one arm's spread instead of two is a materially different
            // estimate, which is why the label exists. If one-arm and two-arm ever agreed everywhere,
            // the label would be decoration.
            differing = compared.count { (row, _) =>
                (oneArm.find(_.row == row), byRow.get(row)) match
                    case (Some(one), Some(both)) => Math.abs(one.resolvable - both.resolvable) * 100 > 0.5
                    case _                       => false
            }
            underPredicting = compared.count { (row, actual) =>
                oneArm.find(_.row == row).exists(_.resolvable * 100 < actual - 0.5)
            }
            _ = println(f"       one arm differs from two on $differing%d of ${compared.size}%d rows, under-predicting on $underPredicting%d")
            _ = check(
                "a one-arm forecast is a materially different estimate, not a relabelled one",
                differing >= 3,
                s"$differing of ${compared.size} rows differ by more than 0.5 points"
            )
            _ = check(
                "and it errs in both directions, so the bias is not a direction to correct for",
                underPredicting > 0 && underPredicting < compared.size,
                s"under-predicts on $underPredicting of ${compared.size}"
            )

            _ = println(if failures == 0 then "\nall checks passed" else s"\n$failures FAILED")
            _ <- Abort.when(failures > 0)(Bench.BracketFailed(s"$failures checks failed"))
        yield ()
    }
end PlanTest
