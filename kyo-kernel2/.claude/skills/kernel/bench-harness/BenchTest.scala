import Model.*
import kyo.*

/** Proves the guards fire, using synthetic runs so it needs no benchmark. Each check corresponds to a real failure from the sessions that
  * produced this tool.
  */
object BenchTest:

    def jmh(name: String, score: Double, error: Double, alloc: Double): String =
        s"""{"benchmark":"kyo.kernel.bench.ProtoKernelBench.$name","mode":"avgt","forks":3,
           |"primaryMetric":{"score":$score,"scoreError":$error,"scoreUnit":"us/op","rawData":[[1,2,3,4,5]]},
           |"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$alloc},"gc.count":{"score":9}}}""".stripMargin

    def rows(entries: (String, Double, Double, Double)*)(using Frame): Chunk[Row] =
        Abort.run(Bench.parseJmh(entries.map(jmh.tupled).mkString("[", ",", "]"))).eval.getOrThrow

    val session  = Session("s-1", "host", "25", 4.0)
    val session2 = Session("s-2", "host", "25", 4.0)

    def leg(
        label: String,
        entries: Seq[(String, Double, Double, Double)],
        jit: Chunk[JitEntry] = Chunk.empty,
        whole: Boolean = true,
        evidence: Evidence = Evidence.Full,
        sess: Session = session,
        forks: Int = 3,
        cpu: Chunk[CpuSite] = Chunk.empty
    )(using Frame): Run =
        Run(
            id = s"$label-x", session = sess, treeHash = "abc", label = label, sha = "0123456789abcdef", forks = forks, evidence = evidence,
            wholeClass = whole, declaredRows = 15, markers = Chunk(Marker("SuspendWith", 4)),
            rows = rows(entries*), jit = jit, alloc = Chunk.empty, cpu = cpu,
            deopts = Chunk.empty, morphism = Chunk.empty, recordedAt = "now"
        )

    def check(name: String, cond: Boolean): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name")
        if !cond then throw new AssertionError(name)

    def main(args: Array[String]): Unit =
        import kyo.Frame.internal

        println("jmh parsing")
        val parsed = rows(("a", 12.5, 0.4, 640.0))
        check("score, error and unit survive", parsed.head.score == 12.5 && parsed.head.error == 0.4 && parsed.head.unit == "us/op")
        check("gc.alloc.rate.norm is picked out of secondary metrics", parsed.head.allocPerOp == Maybe(640.0))
        check("iteration count comes from rawData", parsed.head.count == 5)

        println("classification")
        val base = Seq(("fast", 100.0, 1.0, 640.0), ("flat", 50.0, 1.0, 640.0), ("slow", 20.0, 0.5, 640.0), ("tiny", 0.005, 0.004, 8.0))
        val ctl  = leg("control", base)
        val vnt  = leg("variant", Seq(("fast", 60.0, 1.0, 320.0), ("flat", 51.0, 1.0, 640.0), ("slow", 22.0, 0.5, 640.0), ("tiny", 0.006, 0.004, 8.0)))
        val cmp  = Bench.compare(ctl, vnt)
        val by   = cmp.deltas.map(d => d.row -> d).toMap

        check("a large improvement reads as faster", by("fast").verdict == Verdict.Faster)
        check("a small move stays inside the drift band", by("flat").verdict == Verdict.Flat)
        check("a loss beyond the band reads as a regression", by("slow").verdict == Verdict.Regressed)
        check("a score dominated by its own error is below resolution", by("tiny").verdict == Verdict.BelowResolution)
        check("an artifact never sorts as the headline", cmp.deltas.last.row == "tiny")

        println("mechanism")
        check("an allocation change is reported as the mechanism", by("fast").mechanism.exists(_.contains("allocation")))
        check("a movement with no supporting evidence is flagged unexplained", by("slow").unexplained)
        check("a flat row is never called unexplained", !by("flat").unexplained)

        println("jit diff")
        val withJit = Bench.compare(
            leg("control", base, Chunk(JitEntry("A::apply", 6, true, "inline (hot)"))),
            leg("variant", base, Chunk(JitEntry("A::apply", 87, false, "failed to inline: callee is too large")))
        )
        check("a size and verdict change is surfaced", withJit.jitChanges.exists(s => s.contains("6B inlined") && s.contains("87B refused")))

        println("subset guard")
        val subset = Bench.compare(leg("control", base.take(2), whole = false), leg("variant", base.take(2), whole = false))
        val subsetOut = Report.render(subset)
        check("a subset run cannot claim the suite is clean", !subsetOut.contains("across the whole class"))
        check("a subset run says so explicitly", subsetOut.contains("not a statement about the suite"))
        check("a whole-class clean run does make the claim", Report.render(Bench.compare(ctl, ctl)).contains("across the whole class"))

        println("evidence guard")
        val timingOnly = Report.render(Bench.compare(leg("c", base, evidence = Evidence.Timing), leg("v", base, evidence = Evidence.Timing)))
        check("a timing-only run is not presented as attributed", timingOnly.contains("no movement here is attributed"))

        println("session guard")
        check("comparing across sessions is refused", Report.render(Bench.compare(ctl, leg("variant", base, sess = session2))).contains("not comparable"))
        check("same-session comparison carries no warning", !Report.render(cmp).contains("not comparable"))

        println("measured drift")
        val wide  = Session("s-3", "h", "25", 12.0)
        val loose = Bench.compare(leg("c", base, sess = wide), leg("v", Seq(("slow", 22.0, 0.5, 640.0)), sess = wide))
        check("a wide measured band absorbs a delta a narrow one would flag", loose.deltas.head.verdict == Verdict.Flat)
        check("the band says whether it was measured", Report.render(cmp).contains("measured this session"))

        println("forks and noise stamps")
        check("a single-fork run is stamped diagnostic", Report.render(Bench.compare(leg("c", base, forks = 1), leg("v", base, forks = 1))).contains("diagnostic and not a claim"))
        val noisy = leg("v", base, cpu = Chunk(CpuSite("scala.runtime.BoxesRunTime.boxToInteger", 600), CpuSite("kyo.kernel.proto.Eval$.loop", 400)))
        check("a boxing-dominated run says so", Report.render(Bench.compare(ctl, noisy)).contains("no kernel change can move"))

        println("win and loss")
        check("a change that both wins and loses demands two diagnoses", Report.render(cmp).contains("two diagnoses"))

        println("\nall checks passed\n")
        println(Report.render(cmp))
    end main
end BenchTest
