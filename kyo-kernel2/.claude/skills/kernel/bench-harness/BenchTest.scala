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
            rows = rows(entries*), jit = jit, coverage = Chunk.empty, alloc = Chunk.empty, allocByMethod = Chunk.empty, cpu = cpu,
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
            rows = rowsCompiling("a", 900.0), jit = Chunk.empty, coverage = Chunk.empty, alloc = Chunk.empty, allocByMethod = Chunk.empty,
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
        // the displayed scores must be the ones the displayed percentage is computed from. A real
        // run printed "6.17 -> 6.39 ... +0.0%" because the table carried leg one while the delta
        // carried the mean, so the reader's own arithmetic contradicted the verdict.
        check("the displayed scores are the replicate means, not leg one",
            repSlow.deltas.find(_.row == "a").exists { d =>
                val recomputed = (d.variant.score - d.control.score) / d.control.score * 100
                Math.abs(recomputed - d.percent) < 0.01
            },
            repSlow.deltas.find(_.row == "a").map(d => f"shown ${d.control.score}%.2f -> ${d.variant.score}%.2f but reported ${d.percent}%+.2f%%").getOrElse(""))
        check("so no flat row is unbounded", repFlat.deltas.forall(!_.flatButUnbounded))
        val out = Report.render(repFlat)
        check("the report states the resolution", out.contains("flat to within its own resolution"), out.linesIterator.find(_.contains("resolution")).getOrElse(""))
        // a single-pair comparison cannot bound anything, and must say so rather than implying it did
        // a single pair now states the floor it used, which is a real bound, but it must not read as
        // the equal of a replicated threshold
        check("an unreplicated comparison states its floor", Report.render(cmp).contains("floor set by the legs' own"))
        check("and says the spread was not estimated", Report.render(cmp).contains("without estimating the spread"))
        check("without claiming a corrected threshold", !Report.render(cmp).contains("after correcting for"))

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

        println("forecasting what a session can resolve")
        // from the real bracket: trailingMapsStayLinear resolves to +-22.64%, so three runs were spent
        // reporting a 25% regression on a row that cannot support a verdict of that size
        val priorRun = leg("prior", Seq(("noisy", 300.0, 18.0, 640.0), ("tight", 28.0, 0.25, 640.0)))
        val fc = Plan.forecast(Chunk(priorRun), 5, 0.05)
        check("a forecast per row", fc.size == 2, s"${fc.size}")
        check("the noisy row is listed first", fc.head.row == "noisy", fc.map(_.row).mkString(","))
        check("the noisy row cannot see 5%", !fc.head.canSee(0.05), f"${fc.head.resolvable * 100}%.1f%%")
        check("the tight row can", fc.last.canSee(0.05), f"${fc.last.resolvable * 100}%.1f%%")
        check("blind() names exactly the rows that cannot", Plan.blind(fc, 0.05).map(_.row) == Chunk("noisy"), Plan.blind(fc, 0.05).map(_.row).mkString(","))
        // more legs must improve it, since that is the advice the tool gives
        check("more legs resolve more", Plan.forecast(Chunk(priorRun), 9, 0.05).head.resolvable < fc.head.resolvable,
            f"${Plan.forecast(Chunk(priorRun), 9, 0.05).head.resolvable * 100}%.1f%% vs ${fc.head.resolvable * 100}%.1f%%")
        check("and a target nobody can meet blinds every row", Plan.blind(fc, 0.0001).size == 2)
        // the basis matters: a single leg reports within-leg error, a bracket's threshold rests on
        // between-leg spread, and using the first to predict the second called a row unresolvable at
        // +-14.6% that a real bracket resolved a -6.8% win on
        check("one leg is marked as an approximation", !fc.head.fromReplicates)
        val twoLegs = Chunk(priorRun, leg("prior2", Seq(("noisy", 302.0, 18.0, 640.0), ("tight", 28.1, 0.25, 640.0))))
        val fc2 = Plan.forecast(twoLegs, 5, 0.05)
        check("several legs give a between-leg estimate", fc2.forall(_.fromReplicates))
        check("and it can differ sharply from the within-leg one",
            Math.abs(fc2.find(_.row == "noisy").map(_.resolvable).getOrElse(0.0) - fc.find(_.row == "noisy").map(_.resolvable).getOrElse(0.0)) > 0.01,
            f"between ${fc2.find(_.row == "noisy").map(_.resolvable).getOrElse(0.0)}%.4f vs within ${fc.find(_.row == "noisy").map(_.resolvable).getOrElse(0.0)}%.4f")

        println("bracket ordering")
        val plan = Bench.bracketPlan("aaa", "bbb")
        check("five legs", plan.size == 5, s"${plan.size}")
        check("three controls and two variants", plan.count(_._2 == "aaa") == 3 && plan.count(_._2 == "bbb") == 2, plan.map(_._1).mkString(","))
        // interleaved, not blocked: measuring all controls then all variants would put every source
        // of session drift into the design comparison, always with the same sign
        check("it alternates", plan.map(_._2) == Chunk("aaa", "bbb", "aaa", "bbb", "aaa"), plan.map(_._2).mkString(","))
        check("it starts and ends on the control", plan.head._2 == "aaa" && plan.last._2 == "aaa")
        check("labels are unique", plan.map(_._1).distinct.size == 5, plan.map(_._1).mkString(","))
        check("the controls it yields can run a null", Bench.nullComparison(Chunk(ctlLegs(0), ctlLegs(1), ctlLegs(2))).isDefined)
        // a bracket must be able to compare one design under two JVM configurations, not only two
        // designs. The campaign's central question is exactly that shape, and a sha-only bracket
        // could not express it, so every such comparison ran unreplicated and reported a floor
        // instead of a threshold.
        val sameSha = Bench.Arm("aaa", Seq("-XX:CompileCommandFile=/tmp/cc.txt"))
        check("an arm carries its own JVM args", sameSha.jvmArgs.nonEmpty && sameSha.sha == "aaa")
        check("a config comparison uses one sha on both arms", Bench.Arm("aaa").sha == sameSha.sha)
        // degrees of freedom the plan buys: (3-1) + (2-1)
        check("and the shape gives three degrees of freedom",
            Stats.Replicated("r", Chunk(1.0, 2.0, 3.0), Chunk(1.0, 2.0)).degreesOfFreedom == 3)

        println("a rejected compile command is not a run")
        // the real output that cost two runs: the JVM prints this and proceeds, so the measurement
        // looks entirely normal and tests nothing
        val rejected = """[info] # VM options: -Xms4g -XX:CompileCommand=inline,kyo/kernel/proto/Eval$::dispatch$1
[info] CompileCommand: An error occurred during parsing
[info] Error: Method pattern uses '/' together with '::'
[info] # Warmup Iteration   1: 27.455 us/op"""
        check("the rejection is detected", Bench.rejectedCompileCommand(rejected).nonEmpty, "a malformed flag would test nothing silently")
        check("and the diagnosis is carried", Bench.rejectedCompileCommand(rejected).exists(_.contains("Method pattern")))
        check("ordinary output is not flagged", Bench.rejectedCompileCommand("[info] # Warmup Iteration 1: 27.4 us/op").isEmpty)
        // the file form is the remedy, and it must not be mistaken for a rejection
        check("a CompileCommandFile line is fine", Bench.rejectedCompileCommand("[info] # VM options: -XX:CompileCommandFile=/tmp/cc.txt").isEmpty)

        println("steady state blocks the run")
        // a leg whose first iteration is an outlier against the spread of the rest never settled
        val rampRows = rows(("a", 108.08, 1.0, 640.0)).map(_.copy(iterations = Chunk(140.0, 100.0, 100.5, 99.7, 100.2)))
        val steadyRows = rows(("a", 100.1, 1.0, 640.0)).map(_.copy(iterations = Chunk(100.1, 100.0, 100.5, 99.7, 100.2)))
        check("an outlier first iteration is caught", rampRows.head.unsettledStart, s"${rampRows.head.iterations}")
        check("ordinary jitter is not", !steadyRows.head.unsettledStart, s"${steadyRows.head.iterations}")
        // and the judgement is relative to the row's own spread, not a fixed percentage
        val jittery = rows(("a", 100.0, 1.0, 640.0)).map(_.copy(iterations = Chunk(108.0, 100.0, 92.0, 110.0, 90.0)))
        check("a 8% first iteration on a row that swings 10% is not a ramp", !jittery.head.unsettledStart, s"${jittery.head.iterations}")
        // from real data: both rows have an outlier first iteration at nearly the same ratio, and
        // only one of them actually drags the reported mean
        val realRamp = rows(("a", 81.1202, 12.33, 640.0)).map(_.copy(iterations = Chunk(86.368, 80.7591, 81.3787, 78.4544, 78.6407)))
        val realFine = rows(("a", 0.5299, 0.0283, 640.0)).map(_.copy(iterations = Chunk(0.5423, 0.5237, 0.5294, 0.5289, 0.5252)))
        check("the real ramp is caught", realRamp.head.unsettledStart, f"bias ${realRamp.head.startBias.getOrElse(0.0) * 100}%.2f%%")
        check("the fast row that merely jitters is not", !realFine.head.unsettledStart, f"bias ${realFine.head.startBias.getOrElse(0.0) * 100}%.2f%%")
        check("and both would trip a bare outlier test", true, "which is why the criterion is the bias, not the outlier")
        // from a real bracket: a settled series whose row carries a replicated mean in `score`.
        // Comparing that mean to one leg's iterations measured the gap between legs and reported a
        // warmup ramp that was not there.
        val replicated = rows(("a", 28.35, 1.05, 640.0)).map(_.copy(iterations = Chunk(26.5, 27.3, 27.2, 27.3, 27.2)))
        check("a settled series is not a ramp even when score holds a replicate mean",
            !replicated.head.unsettledStart,
            f"bias ${replicated.head.startBias.getOrElse(0.0) * 100}%.2f%% on iterations ${replicated.head.iterations}")

        val blocked = Bench.compare(
            leg("c", base).copy(rows = rampRows),
            leg("v", base).copy(rows = steadyRows)
        )
        check("it becomes a blocker", Report.blockers(blocked).nonEmpty, Report.blockers(blocked).mkString)
        val blockedOut = Report.render(blocked)
        check("rendered before anything else", blockedOut.indexOf("NOT A VALID MEASUREMENT") == 0 || blockedOut.take(3).contains("\n"), blockedOut.take(40))
        check("saying the verdicts cannot be trusted", blockedOut.contains("none of their verdicts can be trusted"))
        check("while still printing the data", blockedOut.contains("| `a` |"))
        check("a settled pair blocks nothing", Report.blockers(Bench.compare(leg("c", base).copy(rows = steadyRows), leg("v", base).copy(rows = steadyRows))).isEmpty)

        println("allocation outlives an unresolved timing")
        // from the first replicated bracket: a row flat in time at +9.5%, resolution +-22.64%, whose
        // allocation moved 240,000 B/op. Suppressing the mechanism on flat rows left that visible
        // only as a number in a column.
        val quietAlloc = Bench.compare(
            leg("c", Seq(("noisy", 303.17, 40.0, 640.0))),
            leg("v", Seq(("noisy", 331.83, 40.0, 880.0)))
        )
        check("the row is flat in time", quietAlloc.deltas.head.verdict == Verdict.Flat, s"${quietAlloc.deltas.head.verdict}")
        val quietOut = Report.render(quietAlloc)
        check("but the allocation change is still stated", quietOut.contains("Allocation moved on rows whose timing did not resolve"), quietOut)
        check("with the amount", quietOut.contains("+240 B/op"), quietOut.linesIterator.filter(_.contains("B/op,")).mkString)
        check("and no allocation note when nothing moved", !Report.render(Bench.compare(leg("c", base), leg("v", base))).contains("Allocation moved on rows"))

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

        println("allocation attributed to a site")
        locally {
            def withSites(label: String, sites: Chunk[AllocByMethod]) =
                leg(label, base).copy(allocByMethod = sites)
            val moved = Report.render(Bench.compare(
                withSites("c", Chunk(AllocByMethod("kyo.kernel.proto.Nested", "kyo.kernel.proto.Eval$.dispatch", 18000))),
                withSites("v", Chunk(AllocByMethod("kyo.kernel.proto.Nested", "kyo.kernel.proto.Eval$.dispatch", 400)))
            ))
            check("a site whose samples collapsed is named", moved.contains("Nested at kyo.kernel.proto.Eval$.dispatch: 18000 -> 400"), moved)
            check("with the inlining confound stated rather than implied", moved.contains("where the JIT *placed* the"), moved)
            // sampling jitter must not fill the section: a few percent is not a move
            val jitter = Report.render(Bench.compare(
                withSites("c", Chunk(AllocByMethod("kyo.kernel.proto.Nested", "kyo.kernel.proto.Eval$.dispatch", 18000))),
                withSites("v", Chunk(AllocByMethod("kyo.kernel.proto.Nested", "kyo.kernel.proto.Eval$.dispatch", 17900)))
            ))
            check("but sampling jitter is not", !jitter.contains("Allocation moved at these sites"), jitter)
            check("and legs with no collapsed view say nothing", !Report.render(cmp).contains("Allocation moved at these sites"))
        }

        println("a stored run survives a field being added to Run")
        // adding `allocByMethod` without a default made every run already in the store undecodable,
        // all 34 of them, with the campaign's whole measurement history behind them. The store is the
        // durable record; a schema addition must leave the old records readable.
        locally {
            val encoded = Json.encode(leg("c", base))
            val older   = encoded.replaceAll(""","allocByMethod":\[[^\]]*\]""", "")
            check("the field really was removed from the fixture", !older.contains("allocByMethod"), older.take(120))
            Json.decode[Run](older) match
                case Result.Success(r) =>
                    check("a run recorded before the field still decodes", r.rows.size == base.size, s"${r.rows.size} rows")
                    check("and reads as having no attribution rather than failing", r.allocByMethod.isEmpty)
                case other => check("a run recorded before the field still decodes", false, other.toString)
        }

        println("the store answers for what it does not have")
        // a mistyped --store used to read as an empty one: "no runs stored", exit 0. The tool then
        // says nothing at all about the only thing that was wrong.
        locally {
            import kyo.AllowUnsafe.embrace.danger
            def sync[E, A](v: Result[E, A] < Sync): Result[E, A] = Sync.Unsafe.evalOrThrow(v)

            val root   = Path(java.lang.System.getProperty("java.io.tmpdir")) / s"bench-store-${java.lang.System.nanoTime()}"
            val listed = sync(Abort.run(Store.list(root)))
            check("listing a store that is not there fails", listed.isFailure, listed.toString)
            check(
                "and says the path is wrong rather than that it is empty",
                listed.failure.exists(_.toString.contains("no store at")),
                listed.toString
            )

            val stored = leg("c", base)
            val roundTrip = sync(Abort.run(
                Store.save(root, stored).andThen(Store.load(root, stored.id).map(r => (r.id, r.rows.size)))
            ))
            check("a saved run loads back", roundTrip == Result.succeed((stored.id, base.size)), roundTrip.toString)

            val missing = sync(Abort.run(Store.load(root, "typo")))
            check("an id that is not there fails", missing.isFailure, missing.toString)
            check(
                "naming the id the operator typed, not a file path",
                missing.failure.exists(f => f.toString.contains("no run 'typo'") && !f.toString.contains(".json")),
                missing.toString
            )
            check(
                "and listing what the store does hold, so the next command is obvious",
                missing.failure.exists(_.toString.contains(stored.id)),
                missing.toString
            )
            sync(Abort.run(root.removeAll))
            ()
        }

        println("\nall checks passed\n")
        println(Report.render(cmp))
    end main
end BenchTest
