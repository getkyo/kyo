import kyo.*

/** Exercises the classification and rendering the report depends on, with synthetic JMH json so it runs without a benchmark. */
object BenchTest:

    def jmh(name: String, score: Double, error: Double): String =
        s"""{"benchmark":"kyo.kernel.bench.ProtoKernelBench.$name","mode":"avgt",
           |"primaryMetric":{"score":$score,"scoreError":$error,"scoreUnit":"us/op",
           |"rawData":[[1.0,2.0,3.0,4.0,5.0]]}}""".stripMargin

    def leg(label: String, sha: String, entries: (String, Double, Double)*): Bench.Leg =
        val raw  = entries.map(jmh.tupled).mkString("[", ",", "]")
        val rows = Bench.parseJmhJson(raw)
        Bench.Leg(label, sha, rows.map(r => r.name -> r).toMap, Map("SuspendWith" -> 4))

    def check(name: String, cond: Boolean): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name")
        if !cond then throw new AssertionError(name)

    def main(args: Array[String]): Unit =
        println("classification")
        val control = leg("control", "aaaaaaa", ("fast", 100.0, 1.0), ("flat", 50.0, 1.0), ("slow", 20.0, 0.5), ("tiny", 0.005, 0.004))
        val variant = leg("variant", "bbbbbbb", ("fast", 60.0, 1.0), ("flat", 51.0, 1.0), ("slow", 22.0, 0.5), ("tiny", 0.006, 0.004))
        val deltas  = Bench.compare(control, variant)
        val byRow   = deltas.map(d => d.row -> d).toMap

        check("a 40% improvement reads as faster", byRow("fast").verdict == Bench.Verdict.Faster)
        check("a 2% move stays inside the drift band", byRow("flat").verdict == Bench.Verdict.Flat)
        check("a 10% loss reads as a regression", byRow("slow").verdict == Bench.Verdict.Regressed)
        check("a score dominated by its own error is below resolution", byRow("tiny").verdict == Bench.Verdict.BelowResolution)
        val real = deltas.filter(_.verdict != Bench.Verdict.BelowResolution)
        check("the worst real movement sorts last among real rows", real.last.row == "slow")
        check("an artifact never sorts as the headline regression", deltas.last.row == "tiny")
        check("counts come from rawData", byRow("fast").variant.count == 5)

        println("rendering")
        val out = Bench.render(control, variant, deltas, forks = 3)
        check("a regressed row is called out as unfinished", out.contains("the work is unfinished"))
        check("markers are printed for both legs", out.contains("SuspendWith -> 4"))
        check("the drift band is stated", out.contains("Drift band"))
        check("every row appears", Seq("fast", "flat", "slow", "tiny").forall(r => out.contains(s"`$r`")))

        println("clean board")
        val quiet = Bench.compare(control, leg("v2", "ccccccc", ("fast", 100.0, 1.0), ("flat", 50.5, 1.0), ("slow", 20.1, 0.5), ("tiny", 0.005, 0.004)))
        check("no regression line when nothing regressed", Bench.render(control, control, quiet, 1).contains("No row regressed"))

        println("\nall checks passed")
        println(out)
    end main
end BenchTest
