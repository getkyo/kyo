import Model.*
import kyo.*
import kyo.test.*

/** Holds the verdict statistics to both directions.
  *
  * Every criterion here comes in a pair. A must-not-fire case alone is satisfied by a pipeline that never classifies anything, and an audit
  * of the previous plan found eight of twelve criteria were exactly that: a harness tuned only against nulls converges on abstaining, and
  * abstaining always passes. So each check below that asserts silence is followed by one that asserts speech.
  */
class StatsTest extends Test[Any]:

    // every `check` is one leaf, named by its section and its claim; the predicate runs inside the
    // leaf, the fixtures it reads are built once in the class body. This is the mains' `check` with
    // the framework doing what "print FAIL and count" did
    private var group = ""
    private val seen  = scala.collection.mutable.Set.empty[String]
    private def section(name: String): Unit = group = name
    private def check(name: String, cond: => Boolean, detail: => String = ""): Unit =
        val claim = if group.isEmpty then name else s"$group: $name"
        val leaf  = if seen.add(claim) then claim else s"$claim (again)"
        leaf in assert(cond, detail)

    def rep(name: String, control: Seq[Double], variant: Seq[Double]) =
        Stats.Replicated(name, Chunk.from(control), Chunk.from(variant))

    val alpha = 0.05
    val rows  = 15

    section("multiplicity")
    check("testing 15 rows tightens the per-row alpha", Stats.perRowAlpha(0.05, 15) < 0.05)
    check("and states it exactly", Math.abs(Stats.perRowAlpha(0.05, 15) - 0.00333) < 0.0001, s"${Stats.perRowAlpha(0.05, 15)}")
    // 15 independent tests at 5% produce at least one false positive 54% of the time
    check("uncorrected, the family error would be over half", 1 - Math.pow(0.95, 15) > 0.5)

    section("the pair that matters: silence and speech")
    // three control legs and two variant legs, the session shape
    val quiet = rep("quiet", Seq(100.0, 100.4, 99.7), Seq(100.2, 99.9))
    val loud  = rep("loud", Seq(100.0, 100.4, 99.7), Seq(118.0, 117.4))
    val (qv, qt) = Stats.classify(quiet, alpha, rows)
    val (lv, lt) = Stats.classify(loud, alpha, rows)
    check("noise does not classify", qv == Verdict.Flat, s"$qv, threshold ${qt.map(_.show).getOrElse("none")}")
    // the must-fire twin: without this, never classifying passes
    check("a large real regression does classify", lv == Verdict.Regressed, s"$lv, threshold ${lt.map(_.show).getOrElse("none")}")
    check("and in the right direction", Stats.classify(rep("w", Seq(100.0, 100.4, 99.7), Seq(82.0, 82.6)), alpha, rows)._1 == Verdict.Faster)

    section("the threshold never sits below the legs' own error")
    // from a real A/A bracket: five legs of identical sources, each reporting about 5.2% error of
    // its own, whose between-leg spread happened to be small. The threshold came out at 2.60% and
    // a 3.8% difference between two groups of identical sources was called a regression.
    val tightSpread = rep("real", Seq(6.10, 6.12, 6.14), Seq(6.40, 6.42))
    val withError   = tightSpread.copy(legError = Chunk(0.052, 0.052, 0.052, 0.052, 0.052))
    check("without the floor this classifies", Stats.classify(tightSpread, alpha, 2)._1 == Verdict.Regressed,
        s"${Stats.classify(tightSpread, alpha, 2)._1}")
    check("with it, the row is flat", Stats.classify(withError, alpha, 2)._1 == Verdict.Flat,
        s"${Stats.classify(withError, alpha, 2)._1}, threshold ${Stats.threshold(withError, alpha, 2).map(_.show)}")
    check("and the reported resolution is at least the leg error",
        Stats.threshold(withError, alpha, 2).exists(_.percent >= 5.19),
        f"${Stats.threshold(withError, alpha, 2).map(_.percent).getOrElse(0.0)}%.2f%%")
    // and it must not swallow a real effect: a difference well beyond the leg error still fires
    val bigWithError = rep("big", Seq(6.10, 6.12, 6.14), Seq(8.40, 8.42)).copy(legError = Chunk(0.052, 0.052, 0.052, 0.052, 0.052))
    check("a difference beyond the leg error still classifies", Stats.classify(bigWithError, alpha, 2)._1 == Verdict.Regressed,
        s"${Stats.classify(bigWithError, alpha, 2)._1}")

    section("which lever tightens the threshold (item 6)")
    // withError's own reported error (5.2%) exceeds the tight between-leg spread, so the floor sets the threshold: more legs cannot move
    // it, only more iterations per fork. tightSpread carries no leg error, so the between-leg spread sets it and more legs help
    check("a floor-bound row is marked so", Stats.threshold(withError, alpha, 2).exists(_.floorBound), s"${Stats.threshold(withError, alpha, 2)}")
    check("and its advice is more iterations per fork",
        Stats.threshold(withError, alpha, 2).exists(_.lever.contains("iterations per fork")),
        Stats.threshold(withError, alpha, 2).map(_.lever).getOrElse(""))
    check("a spread-bound row is not floor-bound", Stats.threshold(tightSpread, alpha, 2).exists(!_.floorBound))
    check("and its advice is more legs", Stats.threshold(tightSpread, alpha, 2).exists(_.lever.contains("more legs")),
        Stats.threshold(tightSpread, alpha, 2).map(_.lever).getOrElse(""))

    section("which way is down is the row's mode, not the classifier's assumption")
    // the same real series read as throughput: 6.10 -> 6.40 ops per unit of time is more work
    // done, and the classifier called it a regression for as long as `diff < 0` meant faster
    // unconditionally (defect 44). The harness's own benchmark is AverageTime, so this was
    // latent; anything ingested in thrpt was classified backwards
    val asThroughput = tightSpread.copy(lowerIsBetter = false)
    check("in avgt a higher score is a regression", Stats.classify(tightSpread, alpha, 2)._1 == Verdict.Regressed)
    check("in thrpt the same numbers are a win", Stats.classify(asThroughput, alpha, 2)._1 == Verdict.Faster,
        s"${Stats.classify(asThroughput, alpha, 2)._1}")
    check("and a lower throughput is the regression",
        Stats.classify(rep("t", Seq(6.40, 6.42, 6.44), Seq(6.10, 6.12)).copy(lowerIsBetter = false), alpha, 2)._1 == Verdict.Regressed)
    // the direction never manufactures a verdict: inside the threshold a row is flat either way
    check("direction does not move a flat row", Stats.classify(quiet.copy(lowerIsBetter = false), alpha, rows)._1 == Verdict.Flat)

    section("the threshold is answerable")
    check("a flat row still reports what it could have seen", qt.isDefined, "flat with no minimum detectable effect is not a result")
    check("degrees of freedom come from the replicates", quiet.degreesOfFreedom == 3, s"${quiet.degreesOfFreedom}")
    check("a single leg each way cannot support a verdict", Stats.classify(rep("thin", Seq(100.0), Seq(105.0)), alpha, rows)._1 == Verdict.BelowResolution)
    check("and says so rather than calling it flat", Stats.threshold(rep("thin", Seq(100.0), Seq(105.0)), alpha, rows).isEmpty)

    section("the old estimator, for the record")
    // |C2 - C1| as a threshold: a single draw, not a bound. Kept as a check so the mistake cannot
    // return quietly.
    // the retired rule is exercised through the code under test rather than restated as
    // arithmetic on three local literals, which passed with Stats.scala deleted
    val c1 = 100.0; val c2 = 100.6; val v = 101.4
    val retired = Math.abs(v - (c1 + c2) / 2) > Math.abs(c2 - c1)
    val current = Stats.classify(rep("same", Seq(c1, c2, 100.2), Seq(v, 100.9)), alpha, rows)._1
    check("the retired rule classified this noise and the current one does not",
        retired && current == Verdict.Flat, s"retired=$retired current=$current")

    section("drift")
    // every row rising together is the machine warming, not fifteen regressions
    val drifting = Chunk(
        rep("a", Seq(100.0, 103.0, 106.0), Seq(103.0, 104.5)),
        rep("b", Seq(50.0, 51.5, 53.0), Seq(51.5, 52.2)),
        rep("c", Seq(20.0, 20.6, 21.2), Seq(20.6, 20.9))
    )
    val common = Stats.commonMode(drifting)
    check("common-mode drift is measured", common.exists(_ > 3.0), s"${common}")
    check("and is roughly the same for every row, so it is the machine", drifting.forall(r => Stats.residual(r, common.getOrElse(0.0)).exists(_ < 1.0)))
    // a row that moves against the common mode is the one worth looking at
    val odd = Chunk(rep("a", Seq(100.0, 103.0, 106.0), Seq(103.0, 104.5)), rep("odd", Seq(100.0, 100.1, 100.2), Seq(100.0, 100.1)))
    check("a row not sharing the drift shows a residual", Stats.residual(odd(1), Stats.commonMode(odd).getOrElse(0.0)).exists(_ > 2.0))

    section("t critical values")
    // against published two-sided critical values, not against the previous implementation
    val known = Seq((1, 0.05, 12.706), (2, 0.05, 4.303), (3, 0.05, 3.182), (5, 0.05, 2.571),
                    (7, 0.05, 2.365), (9, 0.05, 2.262), (10, 0.05, 2.228), (20, 0.05, 2.086),
                    (3, 0.01, 5.841), (7, 0.00333, 4.355), (9, 0.00333, 3.954))
    known.foreach { (df, a, expected) =>
        check(f"t($df, $a%.5f) = $expected%.3f", Math.abs(Stats.tCritical(df, a) - expected) < 0.005,
            f"got ${Stats.tCritical(df, a)}%.4f")
    }
    // the table this replaced returned Infinity for df 7 and 9, which classified a 100%
    // regression as flat and printed 'detectable at +-Infinity%' under a green all-clear
    check("no tabulation hole returns infinity", (1 to 30).forall(df => Stats.tCritical(df, 0.00333).isFinite))
    // and it was non-monotone: tightening 15 rows to 16 loosened the threshold
    check("a tighter alpha never loosens the threshold",
        (1 to 30).forall(df => Stats.tCritical(df, 0.05 / 16) >= Stats.tCritical(df, 0.05 / 15)))
    check("df=3 at 5% is the textbook 3.182", Math.abs(Stats.tCritical(3, 0.05) - 3.182) < 0.001)
    check("a tighter alpha gives a larger critical value", Stats.tCritical(3, 0.00333) > Stats.tCritical(3, 0.05))
    check("more degrees of freedom give a smaller one", Stats.tCritical(10, 0.05) < Stats.tCritical(3, 0.05))
    check("no degrees of freedom means no threshold", Stats.tCritical(0, 0.05).isInfinite)

end StatsTest
