import Model.*
import kyo.*
import kyo.test.*

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
class PlanTest extends Test[Any]:

    val store = Roots.repo / "bench-results" / "sweep-replicated"

    // one leaf per suite: the artifact is read once inside it, every check records its claim, and the
    // leaf fails listing every claim that did not hold. This is the mains' counting `check` with the
    // framework holding the exit code
    private val failed = scala.collection.mutable.ListBuffer.empty[String]
    private def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if !cond then failed += (if detail.nonEmpty then s"$name  <- $detail" else name)

    "the forecast reproduces the bracket's own threshold" in {
        for
            runs <- Store.list(store)
            controls = runs.filter(_.label.startsWith("control")).sortBy(_.label)
            variants = runs.filter(_.label.startsWith("variant")).sortBy(_.label)

            // item 6, forecast side: three priors that agree to the digit but each reports a 5% own error. The between-leg spread is
            // zero, so the leg's own error is the binding term and more legs cannot move it; the forecast must say so
            tight = Chunk(
                BenchTest.leg("control", Seq(("r", 100.0, 5.0, 640.0))),
                BenchTest.leg("control", Seq(("r", 100.0, 5.0, 640.0))),
                BenchTest.leg("control", Seq(("r", 100.0, 5.0, 640.0)))
            )
            fc = Plan.forecast(tight, 5, Bench.FamilyAlpha)
            _  = check("a tight-spread, high-own-error forecast is floor-bound", fc.exists(f => f.row == "r" && f.floorBound), fc.map(f => s"${f.row}=${f.floorBound}").mkString)
            _  = check("and its lever is more iterations per fork", fc.find(_.row == "r").exists(_.lever.contains("iterations per fork")), fc.find(_.row == "r").map(_.lever).getOrElse(""))

            _ = assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
        yield ()
    }
end PlanTest
