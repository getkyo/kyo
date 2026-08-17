import Model.*
import kyo.*

/** Proves the guards fire, using synthetic runs so it needs no benchmark. Each check corresponds to a real failure from the sessions that
  * produced this tool.
  */
object BenchTest:

    def jmh(name: String, score: Double, error: Double, alloc: Double, compileMs: Double = 0.0): String =
        s"""{"benchmark":"kyo.kernel.bench.ProtoKernelBench.$name","mode":"avgt","forks":3,
           |"primaryMetric":{"score":$score,"scoreError":$error,"scoreUnit":"us/op","rawData":[[1,2,3,4,5]]},
           |"secondaryMetrics":{"gc.alloc.rate.norm":{"score":$alloc},"gc.count":{"score":9},
           |"compiler.time.profiled":{"score":$compileMs},"compiler.time.total":{"score":150.0}}}""".stripMargin

    def rows(entries: (String, Double, Double, Double)*)(using Frame): Chunk[Row] =
        Abort.run(Bench.parseJmh(entries.map(e => jmh(e._1, e._2, e._3, e._4)).mkString("[", ",", "]"))).eval.getOrThrow

    def rowsCompiling(name: String, compileMs: Double)(using Frame): Chunk[Row] =
        Abort.run(Bench.parseJmh("[" + jmh(name, 10.0, 0.1, 64.0, compileMs) + "]")).eval.getOrThrow

    val session  = Session("s-1", "host", "25", 4.0)
    val session2 = Session("s-2", "host", "25", 4.0)

    def leg(
        label: String,
        entries: Seq[(String, Double, Double, Double)],
        jit: Chunk[InlineSites] = Chunk.empty,
        whole: Boolean = true,
        evidence: Evidence = Evidence.Full,
        sess: Session = session,
        forks: Int = 3,
        cpu: Chunk[CpuSite] = Chunk.empty
    )(using Frame): Run =
        Run(
            id = s"$label-x", session = sess, treeHash = "abc", label = label, sha = "0123456789abcdef", forks = forks, evidence = evidence,
            wholeClass = whole, declaredRows = 15, markers = Chunk(Marker("SuspendWith", 4)),
            warmup = 10, jit_metrics = Maybe.empty,
            rows = rows(entries*), jit = jit, coverage = Chunk.empty, alloc = Chunk.empty, cpu = cpu,
            deopts = Chunk.empty, morphism = Chunk.empty, recordedAt = "now"
        )

    def check(name: String, cond: Boolean, detail: String = ""): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name${if detail.nonEmpty then s"  <- $detail" else ""}")
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
        // both stored e2e runs printed a mechanism beside a +0.8% and a +0.3% delta
        check("a flat row carries no mechanism at all", by("flat").mechanism.isEmpty, by("flat").mechanism.mkString(","))

        println("jit diff")
        val method = "kyo.kernel.proto.Arrow$SuspendWith::apply"
        val withJit = Bench.compare(
            leg("control", base, Chunk(InlineSites(method, 6, inlined = 8, refused = 0, Chunk.empty))),
            leg("variant", base, Chunk(InlineSites(method, 87, inlined = 0, refused = 8, Chunk("callee is too large"))))
        )
        check("a unanimous verdict flip is surfaced", withJit.jitChanges.exists(s => s.contains("6B inlined") && s.contains("87B refused")))

        // the defect this whole rework exists for: a method inlined at 5 of 6 sites in one leg and
        // 4 of 6 in the other has not decided anything, and calling it a mechanism is what let two
        // runs of one comparison name disjoint causes
        val marginal = Bench.compare(
            leg("control", base, Chunk(InlineSites("kyo.kernel.proto.Safepoint::enter", 50, inlined = 5, refused = 1, Chunk("callee is too large")))),
            leg("variant", base, Chunk(InlineSites("kyo.kernel.proto.Safepoint::enter", 50, inlined = 4, refused = 2, Chunk("callee is too large"))))
        )
        check("a fraction that moved is not a mechanism", marginal.jitChanges.isEmpty, marginal.jitChanges.mkString(","))
        check("but it is still visible as unstable", Bench.jitUnstable(
            leg("control", base, Chunk(InlineSites("kyo.kernel.proto.Safepoint::enter", 50, inlined = 5, refused = 1, Chunk.empty))),
            leg("variant", base, Chunk(InlineSites("kyo.kernel.proto.Safepoint::enter", 50, inlined = 4, refused = 2, Chunk.empty)))
        ).nonEmpty)
        check("a verdict carries its denominator", InlineSites("m", 50, 5, 1, Chunk("too large")).show.contains("1/6"))

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

        println("steady state")
        val settled = rows(("a", 10.0, 0.1, 64.0))
        check("compiler time parsed", settled.head.compilerMsProfiled == Maybe(0.0), s"${settled.head.compilerMsProfiled}")
        // named rather than positional: a nineteen-field record built by position silently
        // misaligns the moment a field is added, which is how this test last broke
        val hot = Run(
            id = "h", session = session, label = "h", sha = "s", treeHash = "t", forks = 3, evidence = Evidence.Full,
            wholeClass = true, declaredRows = 15, markers = Chunk.empty, warmup = 10, jit_metrics = Maybe.empty,
            rows = rowsCompiling("a", 900.0), jit = Chunk.empty, coverage = Chunk.empty, alloc = Chunk.empty,
            cpu = Chunk.empty, deopts = Chunk.empty, morphism = Chunk.empty, recordedAt = "now"
        )
        check("a window with heavy compilation is flagged", Bench.stillCompiling(hot).nonEmpty, s"${Bench.stillCompiling(hot)}")
        check("a settled window is not flagged", Bench.stillCompiling(ctl).isEmpty)
        check("the report refuses the deltas", Report.render(Bench.compare(ctl, hot)).contains("NOT STEADY STATE"))
        check("and points at the JIT metrics", Report.render(Bench.compare(ctl, hot)).contains("JIT metrics below"))

        println("replicated comparison")
        def legScores(label: String, scores: Seq[(String, Double)]) =
            leg(label, scores.map((n, s) => (n, s, 0.05, 640.0)))
        val ctlLegs = Chunk(
            legScores("c1", Seq(("a", 100.0), ("b", 50.0))),
            legScores("c2", Seq(("a", 100.4), ("b", 50.2))),
            legScores("c3", Seq(("a", 99.7), ("b", 49.8)))
        )
        val vntFlat = Chunk(legScores("v1", Seq(("a", 100.2), ("b", 50.1))), legScores("v2", Seq(("a", 99.9), ("b", 49.9))))
        val vntSlow = Chunk(legScores("v1", Seq(("a", 118.0), ("b", 50.1))), legScores("v2", Seq(("a", 117.4), ("b", 49.9))))

        val repFlat = Bench.compareReplicated(ctlLegs, vntFlat)
        val repSlow = Bench.compareReplicated(ctlLegs, vntSlow)
        check("replicated noise stays flat", repFlat.deltas.forall(_.verdict == Verdict.Flat), repFlat.deltas.map(d => s"${d.row}=${d.verdict}").mkString(","))
        // the must-fire twin: without it, never classifying passes
        check("a real regression is classified", repSlow.deltas.find(_.row == "a").exists(_.verdict == Verdict.Regressed))
        check("and the untouched row is not", repSlow.deltas.find(_.row == "b").exists(_.verdict == Verdict.Flat))
        check("every flat row carries a resolution", repFlat.deltas.forall(_.resolution.isDefined))
        check("so no flat row is unbounded", repFlat.deltas.forall(!_.flatButUnbounded))
        val out = Report.render(repFlat)
        check("the report states the resolution", out.contains("flat to within its own resolution"), out.linesIterator.find(_.contains("resolution")).getOrElse(""))
        // a single-pair comparison cannot bound anything, and must say so rather than implying it did
        check("an unreplicated comparison admits it is unbounded", Report.render(cmp).contains("cannot say how small an effect"))

        println("the A/A null")
        val quietNull = Bench.nullComparison(Chunk(ctlLegs(0), ctlLegs(1), ctlLegs(2), legScores("c4", Seq(("a", 100.1), ("b", 50.05)))))
        check("the null runs when there are enough control legs", quietNull.isDefined)
        check("and names nothing on quiet controls", quietNull.forall(_.deltas.forall(_.verdict == Verdict.Flat)),
            quietNull.map(_.deltas.map(d => s"${d.row}=${d.verdict}").mkString(",")).getOrElse(""))
        check("and no mechanism", quietNull.forall(_.deltas.forall(_.mechanism.isEmpty)))
        // must-fire: a null that cannot detect a difference is not a null, it is a rubber stamp
        // alternating contamination: every other leg is slow, so the two arms genuinely differ.
        // A mid-session step change is deliberately NOT this case, since alternation puts it in
        // both arms, where it correctly shows up as poor resolution rather than a false verdict.
        val dirtyNull = Bench.nullComparison(Chunk(
            legScores("c1", Seq(("a", 100.0))), legScores("c2", Seq(("a", 130.0))),
            legScores("c3", Seq(("a", 100.4))), legScores("c4", Seq(("a", 130.4)))
        ))
        check("a genuinely dirty null is caught", dirtyNull.exists(_.deltas.exists(_.verdict != Verdict.Flat)),
            dirtyNull.map(_.deltas.map(d => s"${d.row}=${d.verdict}").mkString(",")).getOrElse("none"))
        check("too few legs means no null at all", Bench.nullComparison(Chunk(ctlLegs(0))).isEmpty)
        // the documented session has three control legs; requiring four made the null unreachable
        check("the documented three-control session can run a null", Bench.nullComparison(ctlLegs).isDefined)
        // alternating, not contiguous: contiguous halves group adjacent-in-time legs and put any
        // warm-up trend entirely in the numerator
        val trending = Chunk(
            legScores("c1", Seq(("a", 100.0))), legScores("c2", Seq(("a", 101.0))), legScores("c3", Seq(("a", 102.0))),
            legScores("c4", Seq(("a", 103.0))), legScores("c5", Seq(("a", 104.0))), legScores("c6", Seq(("a", 105.0)))
        )
        check("a monotone trend does not read as a regression in the null",
            Bench.nullComparison(trending).forall(_.deltas.forall(_.verdict == Verdict.Flat)),
            Bench.nullComparison(trending).map(_.deltas.map(d => s"${d.row}=${d.verdict} ${d.percent}").mkString(",")).getOrElse(""))

        println("nothing resolvable")
        // every row below its own error is an unreadable run, not a clean one
        val unreadable = Bench.compare(
            leg("c", Seq(("a", 10.0, 9.0, 64.0), ("b", 20.0, 19.0, 64.0))),
            leg("v", Seq(("a", 30.0, 9.0, 64.0), ("b", 60.0, 19.0, 64.0)))
        )
        val unreadableOut = Report.render(unreadable)
        check("all rows below resolution", unreadable.deltas.forall(_.verdict == Verdict.BelowResolution))
        check("and the report refuses to call that clean", !unreadableOut.contains("No row regressed beyond the drift band"), unreadableOut.linesIterator.filter(_.contains("regress")).mkString)
        check("saying plainly that it is unreadable", unreadableOut.contains("Nothing was resolvable"))

        println("win and loss")
        check("a change that both wins and loses demands two diagnoses", Report.render(cmp).contains("two diagnoses"))

        println("\nall checks passed\n")
        println(Report.render(cmp))
    end main
end BenchTest
