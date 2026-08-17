import Model.*
import kyo.*

/** Holds the verdict statistics to both directions.
  *
  * Every criterion here comes in a pair. A must-not-fire case alone is satisfied by a pipeline that never classifies anything, and an audit
  * of the previous plan found eight of twelve criteria were exactly that: a harness tuned only against nulls converges on abstaining, and
  * abstaining always passes. So each check below that asserts silence is followed by one that asserts speech.
  */
object StatsTest:

    var failures = 0

    def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if cond then println(s"  ok   $name")
        else
            failures += 1
            println(s"  FAIL $name${if detail.nonEmpty then s"\n         $detail" else ""}")

    def rep(name: String, control: Seq[Double], variant: Seq[Double]) =
        Stats.Replicated(name, Chunk.from(control), Chunk.from(variant))

    def main(args: Array[String]): Unit =
        val alpha = 0.05
        val rows  = 15

        println("\nmultiplicity")
        check("testing 15 rows tightens the per-row alpha", Stats.perRowAlpha(0.05, 15) < 0.05)
        check("and states it exactly", Math.abs(Stats.perRowAlpha(0.05, 15) - 0.00333) < 0.0001, s"${Stats.perRowAlpha(0.05, 15)}")
        // 15 independent tests at 5% produce at least one false positive 54% of the time
        check("uncorrected, the family error would be over half", 1 - Math.pow(0.95, 15) > 0.5)

        println("\nthe pair that matters: silence and speech")
        // three control legs and two variant legs, the session shape
        val quiet = rep("quiet", Seq(100.0, 100.4, 99.7), Seq(100.2, 99.9))
        val loud  = rep("loud", Seq(100.0, 100.4, 99.7), Seq(118.0, 117.4))
        val (qv, qt) = Stats.classify(quiet, alpha, rows)
        val (lv, lt) = Stats.classify(loud, alpha, rows)
        check("noise does not classify", qv == Verdict.Flat, s"$qv, threshold ${qt.map(_.show).getOrElse("none")}")
        // the must-fire twin: without this, never classifying passes
        check("a large real regression does classify", lv == Verdict.Regressed, s"$lv, threshold ${lt.map(_.show).getOrElse("none")}")
        check("and in the right direction", Stats.classify(rep("w", Seq(100.0, 100.4, 99.7), Seq(82.0, 82.6)), alpha, rows)._1 == Verdict.Faster)

        println("\nthe threshold is answerable")
        check("a flat row still reports what it could have seen", qt.isDefined, "flat with no minimum detectable effect is not a result")
        qt.foreach(t => println(s"       quiet row is flat to within ${f"${t.percent}%.2f"}%, at df=${t.df}"))
        check("degrees of freedom come from the replicates", quiet.degreesOfFreedom == 3, s"${quiet.degreesOfFreedom}")
        check("a single leg each way cannot support a verdict", Stats.classify(rep("thin", Seq(100.0), Seq(105.0)), alpha, rows)._1 == Verdict.BelowResolution)
        check("and says so rather than calling it flat", Stats.threshold(rep("thin", Seq(100.0), Seq(105.0)), alpha, rows).isEmpty)

        println("\nthe old estimator, for the record")
        // |C2 - C1| as a threshold: a single draw, not a bound. Kept as a check so the mistake cannot
        // return quietly.
        // the retired rule is exercised through the code under test rather than restated as
        // arithmetic on three local literals, which passed with Stats.scala deleted
        val c1 = 100.0; val c2 = 100.6; val v = 101.4
        val retired = Math.abs(v - (c1 + c2) / 2) > Math.abs(c2 - c1)
        val current = Stats.classify(rep("same", Seq(c1, c2, 100.2), Seq(v, 100.9)), alpha, rows)._1
        check("the retired rule classified this noise and the current one does not",
            retired && current == Verdict.Flat, s"retired=$retired current=$current")

        println("\ndrift")
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

        println("\nt critical values")
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

        println(if failures == 0 then "\nall stats checks passed\n" else s"\n$failures stats check(s) failed\n")
        if failures > 0 then sys.exit(1)
    end main
end StatsTest
