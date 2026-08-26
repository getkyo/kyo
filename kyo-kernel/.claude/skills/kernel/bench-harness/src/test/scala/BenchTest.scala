import Model.*
import kyo.*
import kyo.test.*

/** Proves the guards fire, using synthetic runs so it needs no benchmark. Each check corresponds to a real failure from the sessions that
  * produced this tool.
  */
class BenchTest extends Test[Any]:
    import BenchTest.*

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


    section("jmh parsing")
    val parsed = rows(("a", 12.5, 0.4, 640.0))
    check("score, error and unit survive", parsed.head.score == 12.5 && parsed.head.error == 0.4 && parsed.head.unit == "us/op")
    check("gc.alloc.rate.norm is picked out of secondary metrics", parsed.head.allocPerOp == Maybe(640.0))
    check("iteration count comes from rawData", parsed.head.count == 5)

    section("classification")
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

    section("direction and comparability (defect 44)")
    // the same movement in throughput: 100 -> 60 ops/us is fewer operations per unit of time
    def thrptRows(entries: (String, Double)*): Chunk[Row] =
        Abort.run(Bench.parseJmh(entries.map((n, s) => jmh(n, s, 1.0, 640.0, mode = "thrpt", unit = "ops/us")).mkString("[", ",", "]"))).eval.getOrThrow
    val ctlT = ctl.copy(rows = thrptRows(("fast", 100.0), ("slow", 20.0)))
    val vntT = vnt.copy(rows = thrptRows(("fast", 60.0), ("slow", 22.0)))
    val byT  = Bench.compare(ctlT, vntT).deltas.map(d => d.row -> d).toMap
    check("a throughput drop reads as a regression", byT("fast").verdict == Verdict.Regressed, s"${byT("fast").verdict}")
    check("a throughput gain reads as faster", byT("slow").verdict == Verdict.Faster, s"${byT("slow").verdict}")
    check("the row knows which way is down", ctlT.rows.forall(!_.lowerIsBetter) && ctl.rows.forall(_.lowerIsBetter))
    // control in avgt, variant in thrpt: no delta exists, and more legs cannot cure it
    val mixed   = Bench.compare(ctl.copy(rows = rows(("fast", 100.0, 1.0, 640.0))), vntT.copy(rows = thrptRows(("fast", 60.0))))
    val mixedBy = mixed.deltas.map(d => d.row -> d).toMap
    check("a mode mismatch is unresolved, never a verdict", mixedBy("fast").verdict == Verdict.BelowResolution, s"${mixedBy("fast").verdict}")
    check("and the blocker names both sides", Report.blockers(mixed).exists(b => b.contains("avgt in us/op") && b.contains("thrpt in ops/us")),
        Report.blockers(mixed).mkString(" | "))
    check("a matched pair raises no such blocker", !Report.blockers(cmp).exists(_.contains("not the same measurement")))
    // the replicated path takes its direction from the row too
    val repT = Bench.compareReplicated(
        Chunk(ctlT, ctlT.copy(id = "c2", rows = thrptRows(("fast", 100.4), ("slow", 20.1))), ctlT.copy(id = "c3", rows = thrptRows(("fast", 99.7), ("slow", 19.9)))),
        Chunk(vntT, vntT.copy(id = "v2", rows = thrptRows(("fast", 60.6), ("slow", 22.4))))
    )
    check("replicated: a throughput drop is a regression", repT.deltas.find(_.row == "fast").exists(_.verdict == Verdict.Regressed),
        repT.deltas.map(d => s"${d.row}=${d.verdict}").mkString(","))
    check("replicated: a throughput gain is a win", repT.deltas.find(_.row == "slow").exists(_.verdict == Verdict.Faster))

    section("mechanism")
    check("an allocation change is reported as the mechanism", by("fast").mechanism.exists(_.contains("allocation")))
    check("a movement with no supporting evidence is flagged unexplained", by("slow").unexplained)
    check("a flat row is never called unexplained", !by("flat").unexplained)
    // both stored e2e runs printed a mechanism beside a +0.8% and a +0.3% delta
    check("a flat row carries no mechanism at all", by("flat").mechanism.isEmpty, by("flat").mechanism.mkString(","))

    section("jit diff")
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

    section("subset guard")
    val subset = Bench.compare(leg("control", base.take(2), whole = false), leg("variant", base.take(2), whole = false))
    val subsetOut = Report.render(subset)
    check("a subset run cannot claim the suite is clean", !subsetOut.contains("across the whole class"))
    check("a subset run says so explicitly", subsetOut.contains("not a statement about the suite"))
    check("a whole-class clean run does make the claim", Report.render(Bench.compare(ctl, ctl)).contains("across the whole class"))

    section("evidence guard")
    val timingOnly = Report.render(Bench.compare(leg("c", base, evidence = Evidence.Timing), leg("v", base, evidence = Evidence.Timing)))
    check("a timing-only run is not presented as attributed", timingOnly.contains("no movement here is attributed"))

    section("session guard")
    check("comparing across sessions is refused", Report.render(Bench.compare(ctl, leg("variant", base, sess = session2))).contains("not comparable"))
    check("same-session comparison carries no warning", !Report.render(cmp).contains("not comparable"))

    section("measured drift")
    val wide  = Session("s-3", "h", "25", 12.0)
    val loose = Bench.compare(leg("c", base, sess = wide), leg("v", Seq(("slow", 22.0, 0.5, 640.0)), sess = wide))
    check("a wide measured band absorbs a delta a narrow one would flag", loose.deltas.head.verdict == Verdict.Flat)
    check("the band says whether it was measured", Report.render(cmp).contains("measured this session"))

    section("forks and noise stamps")
    check("a single-fork run is stamped diagnostic", Report.render(Bench.compare(leg("c", base, forks = 1), leg("v", base, forks = 1))).contains("diagnostic and not a claim"))
    // this fixture must carry a frame the old `KnownNoise` list missed, or it cannot fail. That
    // list was boxing plus two never-matching strings, so a profile of boxing beside kernel code
    // scored identically either way, which is exactly why the 54.9-point understatement survived
    // the whole campaign. The benchmark's own generated code is what it was dropping.
    val noisy = leg(
        "v",
        base,
        cpu = Chunk(
            CpuSite("scala.runtime.BoxesRunTime.boxToInteger", 600),
            CpuSite("kyo.kernel.bench.ProtoKernelBench.loop$9", 500),
            CpuSite("kyo.kernel.proto.Eval$.loop", 400)
        )
    )
    val noisyOut = Report.render(Bench.compare(ctl, noisy))
    // Eval.loop 400 (kernel), loop$9 500 (benchmark's own closure), boxToInteger 600 (other): total 1500
    check("the run-level note splits sampled time three ways", noisyOut.contains("kernel 27%, benchmark 33%, other 40%"), noisyOut)
    check("the benchmark's own generated code is its own share, not lumped as immovable", noisyOut.contains("benchmark 33%"))
    check("and the kernel share is stated as a lower bound", noisyOut.contains("lower bound"), noisyOut)
    check("with the largest frame named whichever bucket it is in", noisyOut.contains("boxToInteger") && noisyOut.contains("ProtoKernelBench.loop$9"))
    // noiseShare still reads the two-way figure for the QA probe, unchanged
    check(
        "noiseShare counts the benchmark's own generated code as non-kernel",
        Math.abs(Bench.noiseShare(noisy) - 73.33) < 0.1,
        f"share ${Bench.noiseShare(noisy)}%.2f%%, expected 73.33%% (1100 of 1500)"
    )

    section("steady state")
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
    // item 11: the measured window is the row's real iteration count, not forks * the harness -i.
    // A run ingested at -i 10 has count=30 over 3 forks, so 100ms of compilation is 0.33% of a 30s
    // window and under the 0.5% limit; the old forks*MeasureIterations (3*5) fabricated a 15s window
    // and read the same 100ms as 0.67%, falsely flagging it
    locally {
        val base3     = leg("x", Seq(("a", 10.0, 0.1, 640.0)))
        val moreIters = base3.copy(rows = base3.rows.map(_.copy(count = 30, compilerMsProfiled = Maybe(100.0))))
        check("the window uses the row's real iteration count, not the harness -i", Bench.stillCompiling(moreIters).isEmpty,
            s"${moreIters.rows.head.count} iterations should give a 30s window; ${Bench.stillCompiling(moreIters)}")
        val fewIters = base3.copy(rows = base3.rows.map(_.copy(count = 5, compilerMsProfiled = Maybe(100.0))))
        check("a genuinely compiling short window is still flagged", Bench.stillCompiling(fewIters).nonEmpty, s"${Bench.stillCompiling(fewIters)}")
    }
    check("the report refuses the deltas", Report.render(Bench.compare(ctl, hot)).contains("NOT STEADY STATE"))
    check("and points at the JIT metrics", Report.render(Bench.compare(ctl, hot)).contains("JIT metrics below"))

    section("replicated comparison")
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

    section("session drift reaches the report")
    // every row rising together across the control legs is the machine warming; one row that
    // does not share it is carrying movement of its own. `Stats.commonMode` measured this and
    // `compareReplicated` threw it away (item 10)
    val driftCtl = Chunk(
        legScores("c1", Seq(("a", 100.0), ("b", 50.0), ("odd", 100.0))),
        legScores("c2", Seq(("a", 103.0), ("b", 51.5), ("odd", 100.1))),
        legScores("c3", Seq(("a", 106.0), ("b", 53.0), ("odd", 100.2)))
    )
    val driftVnt = Chunk(legScores("v1", Seq(("a", 103.0), ("b", 51.5), ("odd", 100.0))), legScores("v2", Seq(("a", 104.5), ("b", 52.2), ("odd", 100.1))))
    val drifted  = Bench.compareReplicated(driftCtl, driftVnt)
    check("the comparison carries the common mode", drifted.commonMode.exists(_ > 3.0), s"${drifted.commonMode}")
    check("a row sharing the drift has a small residual", drifted.deltas.find(_.row == "a").exists(_.driftResidual.exists(_ < 1.0)),
        drifted.deltas.map(d => s"${d.row}=${d.driftResidual}").mkString(","))
    check("the row moving on its own has a large one", drifted.deltas.find(_.row == "odd").exists(_.driftResidual.exists(_ > 2.0)))
    val driftedOut = Report.render(drifted)
    check("the report states the drift", driftedOut.contains("Control legs drifted +"), driftedOut.linesIterator.filter(_.contains("drift")).mkString(" | "))
    check("a single pair says nothing about drift", repFlat.commonMode.isDefined && !Report.render(Bench.compare(ctl, vnt)).contains("Control legs drifted"))

    // `bracket --legs 2`: one leg per arm through the replicated path gives df 0, no threshold, and
    // every row unresolved. The report used to call them "flat rows" (defect 50)
    val oneEach    = Bench.compareReplicated(Chunk(ctlLegs.head), Chunk(vntFlat.head))
    val oneEachOut = Report.render(oneEach)
    check("one leg per arm resolves nothing", oneEach.deltas.forall(_.verdict == Verdict.BelowResolution), oneEach.deltas.map(_.verdict).mkString(","))
    check("and the report says unresolved, not flat", oneEachOut.contains("rows could not be resolved") && !oneEachOut.contains("flat rows carry no resolution"),
        oneEachOut.linesIterator.filter(_.contains("resol")).mkString(" | "))
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
    // the fork-count note is keyed on whether a threshold was earned, not on -f N. Five legs at
    // -f 1 are five independent JVMs, so a replicated session must not disclaim itself: this once
    // printed "-f 1 is diagnostic and not a claim" in the header while the footer reported df 3.
    check("a replicated session does not disclaim the threshold it earned",
        !out.contains("diagnostic and not a claim"),
        out.linesIterator.find(_.contains("JMH -f")).getOrElse(""))
    // a single-pair comparison cannot bound anything, and must say so rather than implying it did
    // a single pair now states the floor it used, which is a real bound, but it must not read as
    // the equal of a replicated threshold
    check("an unreplicated comparison states its floor", Report.render(cmp).contains("floor set by the legs' own"))
    check("and says the spread was not estimated", Report.render(cmp).contains("without estimating the spread"))
    check("without claiming a corrected threshold", !Report.render(cmp).contains("after correcting for"))

    section("the A/A null")
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

    section("only a session that claims a threshold is required to run a null (item 5)")
    // a bracket claims a replicated threshold, so a session too small to run a null cannot check itself and blocks; an ad-hoc single-pair
    // compare never claimed one, so the same absent null must not fail it, which the old unconditional wiring did to every compare and chain
    check("a bracket with too few legs to run a null is blocked", Report.nullBlockers(Maybe.empty, 1, required = true).nonEmpty)
    check("a single-pair compare is not demanded to self-check", Report.nullBlockers(Maybe.empty, 1, required = false).isEmpty)
    // a dirty null is a blocker either way: its verdicts are false by construction
    check("a dirty null blocks a bracket", Report.nullBlockers(dirtyNull, 4, required = true).nonEmpty, dirtyNull.toString)
    check("and a dirty null blocks a compare too", Report.nullBlockers(dirtyNull, 4, required = false).nonEmpty)
    check("a clean null blocks neither", Report.nullBlockers(quietNull, 4, required = true).isEmpty && Report.nullBlockers(quietNull, 4, required = false).isEmpty)

    section("forecasting what a session can resolve")
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

    section("bracket ordering")
    // the same call `bracket` makes, so this pins the ordering that actually runs. It used to
    // pin a copy: the runner built its own plan inline and nothing covered it.
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

    section("a rejected compile command is not a run")
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

    section("steady state blocks the run")
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

    section("ingesting a -f 3 json")
    // a real entry from a -f 3 -wi 10 -i 5 run (evalFixedOverhead, ProtoKernelBench, 2026-08-18)
    val threeForks =
        """[{"jmhVersion":"1.37","benchmark":"kyo.kernel.bench.ProtoKernelBench.evalFixedOverhead","mode":"avgt","threads":1,
          |"forks":3,"jvmArgs":["-Xmx12G"],"warmupIterations":10,"warmupTime":"1 s","measurementIterations":5,"measurementTime":"1 s",
          |"primaryMetric":{"score":0.00744296436977839,"scoreError":0.000046412372460644055,"scoreConfidence":[0.0073965520,0.0074893767],
          |"scoreUnit":"us/op","rawData":[
          |[0.007387874063514112,0.007428055281156307,0.007405881112952978,0.007433665616359325,0.0074173067817215185],
          |[0.007445424670087296,0.007524314753466424,0.007532074671223748,0.007444913811529469,0.007409208090961796],
          |[0.007404996477785638,0.00743015183060406,0.00750321370200856,0.007442552921249445,0.007434831762055182]]},
          |"secondaryMetrics":{"gc.alloc.rate.norm":{"score":24.0,"scoreError":0.0,"scoreUnit":"B/op","rawData":[[24.0,24.0,24.0,24.0,24.0],[24.0,24.0,24.0,24.0,24.0],[26.0,26.0,26.0,26.0,26.0]]}}}]""".stripMargin
    val whole = Abort.run(Ingest.run(threeForks, "kernel", "35d4cbdba0", session, "bracket.json", 15)).eval.getOrThrow
    check("forks come from the json, not a constant", whole.forks == 3, s"forks ${whole.forks}")
    check("warmup comes from the json", whole.warmup == 10, s"warmup ${whole.warmup}")
    check("the whole entry keeps JMH's own score and error", whole.rows.head.score == 0.00744296436977839 && whole.rows.head.error == 0.000046412372460644055)
    check("and every fork's iterations, in order", whole.rows.head.iterations.size == 15 && whole.rows.head.iterations.head == 0.007387874063514112)
    val legs3 = Abort.run(Ingest.perFork(threeForks, "kernel", "35d4cbdba0", session, "bracket.json", 15)).eval.getOrThrow
    check("split per fork gives one leg per JVM", legs3.size == 3, s"${legs3.size} legs")
    check("each leg is one fork", legs3.forall(_.forks == 1) && legs3.map(_.label).toList == List("kernel-f1", "kernel-f2", "kernel-f3"))
    check("with distinct ids", legs3.map(_.id).distinct.size == 3, legs3.map(_.id).mkString(", "))
    val f2 = legs3(1).rows.head
    check("a leg's row holds that fork's iterations", f2.iterations == Chunk(0.007445424670087296, 0.007524314753466424, 0.007532074671223748, 0.007444913811529469, 0.007409208090961796))
    check("and their mean", Math.abs(f2.score - 0.0074711872) < 1e-9, f"${f2.score}%.10f")
    check("and that fork's own secondaries", legs3(2).rows.head.allocPerOp == Maybe(26.0) && legs3(0).rows.head.allocPerOp == Maybe(24.0))
    // the per-fork error is JMH's own quantity: 99.9% over the fork's iterations. Checked against
    // JMH's scoreError on a real single-fork row (suspensionBaseline, screen-0818-f1-head-kernel.json)
    val oneFork =
        """[{"benchmark":"kyo.kernel.bench.ProtoKernelBench.suspensionBaseline","mode":"avgt","forks":1,"warmupIterations":5,"measurementIterations":5,
          |"primaryMetric":{"score":88.64899573703492,"scoreError":1.7773207012169883,"scoreUnit":"us/op",
          |"rawData":[[89.09255084371411,88.67756711438855,88.79948689533862,87.86963996501967,88.80573386671355]]},
          |"secondaryMetrics":{}}]""".stripMargin
    val one = Abort.run(Ingest.perFork(oneFork, "k", "abc", session, "one.json", 15)).eval.getOrThrow
    check("a fork's error reproduces JMH's own", Math.abs(one.head.rows.head.error - 1.7773207012169883) < 1e-6, f"${one.head.rows.head.error}%.10f")
    check("and its mean reproduces JMH's score", Math.abs(one.head.rows.head.score - 88.64899573703492) < 1e-9)
    // the steady-state check now sees every leg: a ramp in fork 2 alone is a blocker
    val ramp2 =
        """[{"benchmark":"kyo.kernel.bench.ProtoKernelBench.a","mode":"avgt","forks":3,"warmupIterations":10,"measurementIterations":5,
          |"primaryMetric":{"score":102.7,"scoreError":5.0,"scoreUnit":"us/op",
          |"rawData":[[100.1,100.0,100.5,99.7,100.2],[140.0,100.0,100.5,99.7,100.2],[100.1,100.0,100.5,99.7,100.2]]},
          |"secondaryMetrics":{}}]""".stripMargin
    val rampLegs = Abort.run(Ingest.perFork(ramp2, "c", "abc", session, "ramp.json", 15)).eval.getOrThrow
    val steadyLegs = Abort.run(Ingest.perFork(ramp2.replace("140.0", "100.3"), "v", "abc", session, "steady.json", 15)).eval.getOrThrow
    val replicatedRamp = Bench.compareReplicated(rampLegs, steadyLegs)
    check("a ramp in the second control leg blocks the replicated comparison",
        Report.blockers(replicatedRamp).exists(_.contains("control leg 2 of 3")), Report.blockers(replicatedRamp).mkString("; "))
    check("and a settled bracket does not", Report.blockers(Bench.compareReplicated(steadyLegs, steadyLegs)).isEmpty)
    // read whole, the same json hides the ramp behind fork one's clean start
    val wholeRamp = Abort.run(Ingest.run(ramp2, "c", "abc", session, "ramp.json", 15)).eval.getOrThrow
    check("which the whole-entry reading could not see", !wholeRamp.rows.head.unsettledStart, s"${wholeRamp.rows.head.iterations}")

    section("a steady-state blocker says which row, which leg, and what the other legs did")
    // the real case (uncachedValuesPayBoxingOnly, proto fork 1 of 3, 2026-08-18): the owner asked whether it was
    // the tool being unclear or one benchmark being noisy, and the message could not answer either
    val realRampJson =
        """[{"benchmark":"kyo.kernel.bench.YetAnotherProtoBench.uncachedValuesPayBoxingOnly","mode":"avgt","forks":3,"warmupIterations":10,"measurementIterations":5,
          |"primaryMetric":{"score":48.16,"scoreError":2.458,"scoreUnit":"us/op",
          |"rawData":[[55.2,48.5,48.9,49.6,48.5],[47.9,47.7,48.0,47.6,47.9],[47.4,47.6,47.5,47.3,47.6]]},
          |"secondaryMetrics":{}}]""".stripMargin
    val realRampLegs = Abort.run(Ingest.perFork(realRampJson, "proto", "35d4cbdba0", session, "real.json", 14)).eval.getOrThrow
    val steadyCtl    = Abort.run(Ingest.perFork(realRampJson.replace("55.2", "48.4"), "kernel", "35d4cbdba0", session, "ctl.json", 14)).eval.getOrThrow
    val realBlockers = Report.blockers(Bench.compareReplicated(steadyCtl, realRampLegs))
    check("one blocker", realBlockers.size == 1, realBlockers.mkString("; "))
    check("naming the row and the leg", realBlockers.head.contains("uncachedValuesPayBoxingOnly (variant leg 1 of 3)"), realBlockers.head)
    check("the first iteration against the rest", realBlockers.head.contains("first iteration 55.2 then 48.5, 48.9, 49.6, 48.5"), realBlockers.head)
    check("that the other legs of the row settled", realBlockers.head.contains("other 2 variant leg(s) of this row settled"), realBlockers.head)
    check("and the cure for that reading", realBlockers.head.contains("one JVM warmed late"), realBlockers.head)
    val allRamp = Abort.run(Ingest.perFork(realRampJson.replace("[47.9,47.7", "[57.9,47.7").replace("[47.4,47.6", "[57.4,47.6"), "proto", "35d4cbdba0", session, "all.json", 14)).eval.getOrThrow
    val allBlockers = Report.blockers(Bench.compareReplicated(steadyCtl, allRamp))
    check("every leg ramping is the other reading", allBlockers.size == 3 && allBlockers.forall(_.contains("every variant leg of this row ramps")), allBlockers.mkString("; "))

    section("a JMH text log ingests like its json")
    // a real slice of the committed -f 3 -wi 20 -prof gc log (handleLoopAnswersInPlace, kernel side, 2026-08-18): JMH's own
    // summary line for it reads `avgt 15 88.626 ± 0.365 us/op`, and the log ingest must reproduce that from the iterations
    val jmhLog =
        """[info] # Warmup: 20 iterations, 1 s each
          |[info] # Measurement: 5 iterations, 1 s each
          |[info] # Benchmark: kyo.kernel.bench.ProtoKernelBench.handleLoopAnswersInPlace
          |[info] # Run progress: 34.48% complete, ETA 00:24:20
          |[info] # Fork: 1 of 3
          |[info] # Warmup Iteration   1: 120.312 us/op
          |[info] # Warmup Iteration  20: 88.702 us/op
          |[info] Iteration   1: 88.294 us/op
          |[info]                  gc.alloc.rate.norm: 640136.636 B/op
          |[info] Iteration   2: 89.468 us/op
          |[info]                  gc.alloc.rate.norm: 640136.623 B/op
          |[info] Iteration   3: 88.241 us/op
          |[info]                  gc.alloc.rate.norm: 640136.612 B/op
          |[info] Iteration   4: 88.917 us/op
          |[info]                  gc.alloc.rate.norm: 640136.619 B/op
          |[info] Iteration   5: 88.894 us/op
          |[info]                  gc.alloc.rate.norm: 640136.619 B/op
          |[info] # Fork: 2 of 3
          |[info] # Warmup Iteration   1: 118.900 us/op
          |[info] Iteration   1: 88.269 us/op
          |[info]                  gc.alloc.rate.norm: 640136.615 B/op
          |[info] Iteration   2: 88.417 us/op
          |[info]                  gc.alloc.rate.norm: 640136.617 B/op
          |[info] Iteration   3: 88.912 us/op
          |[info]                  gc.alloc.rate.norm: 640136.622 B/op
          |[info] Iteration   4: 88.825 us/op
          |[info]                  gc.alloc.rate.norm: 640136.618 B/op
          |[info] Iteration   5: 88.503 us/op
          |[info]                  gc.alloc.rate.norm: 640136.616 B/op
          |[info] # Fork: 3 of 3
          |[info] # Warmup Iteration   1: 119.001 us/op
          |[info] Iteration   1: 88.858 us/op
          |[info]                  gc.alloc.rate.norm: 640136.649 B/op
          |[info] Iteration   2: 88.429 us/op
          |[info]                  gc.alloc.rate.norm: 640136.616 B/op
          |[info] Iteration   3: 88.567 us/op
          |[info]                  gc.alloc.rate.norm: 640136.617 B/op
          |[info] Iteration   4: 88.401 us/op
          |[info]                  gc.alloc.rate.norm: 640136.616 B/op
          |[info] Iteration   5: 88.386 us/op
          |[info]                  gc.alloc.rate.norm: 640136.616 B/op
          |[info] Result "kyo.kernel.bench.ProtoKernelBench.handleLoopAnswersInPlace":
          |[info]   88.626 ±(99.9%) 0.365 us/op [Average]
          |[info]   640136.621 ±(99.9%) 0.010 B/op [Average]
          |[info] Benchmark                                   Mode  Cnt   Score   Error  Units
          |[info] ProtoKernelBench.handleLoopAnswersInPlace   avgt   15  88.626 ± 0.365  us/op""".stripMargin
    val logEntries = Bench.parseJmhLog(jmhLog)
    check("one entry per benchmark header", logEntries.size == 1 && logEntries.head.benchmark.endsWith("handleLoopAnswersInPlace"))
    check("forks and iterations come from the log", logEntries.head.forks == Maybe(3) && logEntries.head.primaryMetric.rawData.map(_.size) == Chunk(5, 5, 5))
    check("warmup iterations are not data", !logEntries.head.primaryMetric.rawData.flatten.contains(120.312))
    // the log prints iterations to three decimals, so the recomputed aggregate can differ from JMH's full-precision one by that much
    check("the score is JMH's own aggregate", Math.abs(logEntries.head.primaryMetric.score - 88.626) < 0.002, f"${logEntries.head.primaryMetric.score}%.4f")
    check("and so is its error", Math.abs(logEntries.head.primaryMetric.scoreError - 0.365) < 0.005, f"${logEntries.head.primaryMetric.scoreError}%.4f")
    check("the gc secondary rides along per fork", logEntries.head.secondaryMetrics("gc.alloc.rate.norm").rawData.map(_.map(_.size)) == Maybe(Chunk(5, 5, 5)))
    val logLegs = Abort.run(Ingest.perForkLog(jmhLog, "kernel", "f3f29d8b4d", session, "bracket.log", 15)).eval.getOrThrow
    check("and the log splits per fork like the json", logLegs.size == 3 && logLegs(1).rows.head.iterations == Chunk(88.269, 88.417, 88.912, 88.825, 88.503))
    check("with each fork's own B/op", logLegs(2).rows.head.allocPerOp.exists(a => Math.abs(a - 640136.6228) < 0.001))
    check("and the whole entry's B/op is JMH's", Math.abs(logEntries.head.secondaryMetrics("gc.alloc.rate.norm").score - 640136.621) < 0.001)

    section("one sha, two benchmark classes")
    // the kernel-vs-proto brackets ingest two classes at one sha, and the report read the equal sha as an A/A
    val kernelJson = threeForks
    val protoJson  = threeForks.replace("ProtoKernelBench.evalFixedOverhead", "YetAnotherProtoBench.evalFixedOverhead")
    val kRun = Abort.run(Ingest.run(kernelJson, "kernel", "f3f29d8b4d", session, "k.json", 15)).eval.getOrThrow
    val pRun = Abort.run(Ingest.run(protoJson, "proto", "f3f29d8b4d", session, "p.json", 14)).eval.getOrThrow
    check("an ingested run knows its benchmark class", kRun.benchmarkClass == "kyo.kernel.bench.ProtoKernelBench" && pRun.benchmarkClass == "kyo.kernel.bench.YetAnotherProtoBench", s"${kRun.benchmarkClass} / ${pRun.benchmarkClass}")
    check("and its jvm arguments", kRun.jvmArgs == Chunk("-Xmx12G"), kRun.jvmArgs.toString)
    val twoClasses = Report.render(Bench.compare(kRun, pRun))
    check("two classes at one sha are named as such", twoClasses.contains("two benchmark classes") && twoClasses.contains("YetAnotherProtoBench"), twoClasses.takeRight(400))
    check("and not read as an A/A", !twoClasses.contains("Either this is an A/A"))
    check("one class at one sha still reads as before", Report.render(Bench.compare(kRun, kRun)).contains("Same sha and no recorded JVM arguments") || Report.render(Bench.compare(kRun, kRun)).contains("configuration comparison") || !Report.render(Bench.compare(kRun, kRun)).contains("two benchmark classes"))

    section("cpu per row")
    val cpuLog =
        """[info] # Benchmark: kyo.kernel.bench.ProtoKernelBench.suspensionBaseline
          |[info] # Run progress: 0.00% complete, ETA 00:00:30
          |[info] Iteration   1: 88.5 us/op
          |[info] Secondary result "kyo.kernel.bench.ProtoKernelBench.suspensionBaseline:async":
          |[info] --- Execution profile ---
          |[info]           ns  percent  samples  top
          |[info]   ----------  -------  -------  ---
          |[info]    600000000   60.00%      600  kyo.kernel.internal.Eval$.go
          |[info]    300000000   30.00%      300  kyo.kernel.bench.ProtoKernelBench$$Lambda.apply
          |[info]    100000000   10.00%      100  java.lang.Integer.valueOf
          |[info] # Benchmark: kyo.kernel.bench.ProtoKernelBench.evalFixedOverhead
          |[info] Iteration   1: 0.007 us/op
          |[info] --- Execution profile ---
          |[info]           ns  percent  samples  top
          |[info]   ----------  -------  -------  ---
          |[info]    900000000   90.00%      900  kyo.kernel.internal.Eval$.apply
          |[info]    100000000   10.00%      100  kyo.kernel.bench.ProtoKernelBench.evalFixedOverhead
          |[info] Benchmark                                Mode  Cnt   Score   Error  Units
          |[info] ProtoKernelBench.evalFixedOverhead       avgt    1   0.007          us/op""".stripMargin
    val byRow = Bench.parseCpuByBenchmark(cpuLog)
    check("one table per benchmark header", byRow.keySet == Set("suspensionBaseline", "evalFixedOverhead"), byRow.keySet.toString)
    check("with that row's frames only", byRow("suspensionBaseline").size == 3 && byRow("evalFixedOverhead").size == 2)
    check("the summary table is not a frame", byRow.values.forall(_.forall(s => !s.method.contains("us/op"))))
    check("the benchmark package is not the kernel", !Bench.isKernel("kyo.kernel.bench.ProtoKernelBench$$Lambda.apply") && Bench.isKernel("kyo.kernel.internal.Eval$.go") && Bench.isKernel("kyo.Arrow$AndThen.apply"))
    val kernelRun = leg("kernel", Seq(("suspensionBaseline", 88.5, 1.0, 640.0), ("evalFixedOverhead", 0.007, 0.0001, 8.0)))
    val withCpu   = Abort.run(Ingest.attachCpu(kernelRun, cpuLog, "cpu.log")).eval.getOrThrow
    check("every row carries its own profile", withCpu.rows.forall(_.cpu.nonEmpty))
    check("and the run's cpu is their merge", withCpu.cpu.size == 5)
    val part = Bench.cpuPartition(withCpu.rows.find(_.name == "suspensionBaseline").get.cpu)
    check("a row's partition reads from its own frames", Math.abs(part.kernel - 60.0) < 1e-9 && Math.abs(part.benchmark - 30.0) < 1e-9 && Math.abs(part.other - 10.0) < 1e-9, s"$part")
    val missingRow = Abort.run(Ingest.attachCpu(kernelRun, cpuLog.replace("evalFixedOverhead", "somethingElse"), "cpu.log")).eval
    check("a log missing a row's profile is refused", missingRow.isFailure, s"$missingRow")
    val variantWithCpu = Abort.run(Ingest.attachCpu(
        leg("variant", Seq(("suspensionBaseline", 160.0, 1.0, 640.0), ("evalFixedOverhead", 0.004, 0.0001, 8.0))),
        cpuLog.replace("kyo.kernel.internal.Eval$.go", "kyo.proto.Eval$.go").replace("600000000   60.00%", "800000000   80.00%"), "cpu.log"
    )).eval.getOrThrow
    val cpuReport = Report.render(Bench.compare(withCpu, variantWithCpu))
    check("the report has a CPU-by-row section", cpuReport.contains("CPU by row"), cpuReport.takeRight(600))
    check("with each side's frames", cpuReport.contains("kyo.kernel.internal.Eval$.go") && cpuReport.contains("kyo.proto.Eval$.go"))
    check("and no section without row profiles", !Report.render(Bench.compare(kernelRun, kernelRun)).contains("CPU by row"))
    // the run-level noise frames come from the rows' merged profiles, where one method appears once per
    // row: the -wi 20 report listed boxToInteger three times as its three largest contributors
    val merged = Chunk(CpuSite("scala.runtime.BoxesRunTime.boxToInteger", 400), CpuSite("kyo.kernel.internal.Eval$.go$1", 300),
        CpuSite("scala.runtime.BoxesRunTime.boxToInteger", 200), CpuSite("java.lang.Integer.valueOf", 100))
    val frames = Bench.noiseFrames(merged, 3)
    check("noise frames are summed by method", frames.map(_._1) == Chunk("scala.runtime.BoxesRunTime.boxToInteger", "java.lang.Integer.valueOf"), frames.toString)
    check("with the summed share", frames.head._2 == 60.0, frames.head._2.toString)

    section("allocation outlives an unresolved timing")
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

    section("nothing resolvable")
    // every row below its own error is an unreadable run, not a clean one
    val unreadable = Bench.compare(
        leg("c", Seq(("a", 10.0, 9.0, 64.0), ("b", 20.0, 19.0, 64.0))),
        leg("v", Seq(("a", 30.0, 9.0, 64.0), ("b", 60.0, 19.0, 64.0)))
    )
    val unreadableOut = Report.render(unreadable)
    check("all rows below resolution", unreadable.deltas.forall(_.verdict == Verdict.BelowResolution))
    check("and the report refuses to call that clean", !unreadableOut.contains("No row regressed beyond the drift band"), unreadableOut.linesIterator.filter(_.contains("regress")).mkString)
    check("saying plainly that it is unreadable", unreadableOut.contains("Nothing was resolvable"))

    section("win and loss")
    check("a change that both wins and loses demands two diagnoses", Report.render(cmp).contains("two diagnoses"))

    section("allocation attributed to a site")
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

    section("a lossy allocation parse blocks the run")
    locally {
        // one recording: the flat table counts 1000 samples of the class, the collapsed view must
        // account for the same 1000. When it accounts for fewer the parse dropped lines (the failure
        // this file has had four times), and the per-method byte figures are then built on a partial count
        def withAlloc(label: String, flat: Long, collapsed: Long) =
            leg(label, base).copy(
                alloc = Chunk(AllocSite("kyo.kernel.proto.Nested", 640000L, flat)),
                allocByMethod = Chunk(AllocByMethod("kyo.kernel.proto.Nested", "kyo.kernel.proto.Eval$.dispatch", collapsed))
            )
        val lossy   = Bench.compare(withAlloc("c", 1000L, 1000L), withAlloc("v", 1000L, 700L))
        val lossyBs = Report.blockers(lossy)
        check("the disagreement is a blocker", lossyBs.exists(b => b.contains("parse lost") && b.contains("Nested")), lossyBs.mkString(" | "))
        check("naming the arm", lossyBs.exists(_.contains("variant")), lossyBs.mkString(" | "))
        val whole  = Bench.compare(withAlloc("c", 1000L, 1000L), withAlloc("v", 1000L, 1000L))
        check("a conserved parse blocks nothing", !Report.blockers(whole).exists(_.contains("parse lost")), Report.blockers(whole).mkString(" | "))
        // a timing leg carries neither view; conservation must stay silent rather than see 0 collapsed
        check("a leg with no allocation views is not flagged", !Report.blockers(cmp).exists(_.contains("parse lost")))
    }

    section("a near-budget method is surfaced whatever the verdict (item 3)")
    locally {
        // 379B refused against the 325B FreqInlineSize budget: one shrink from inlining. The investigator only proposes raising a budget
        // when a row regressed, so on this flat comparison it says nothing; the standing note names it anyway
        val nearV = InlineSites("kyo.kernel.proto.Eval$.loop", 379, inlined = 0, refused = 1, Chunk("hot method too big"))
        val overV = InlineSites("kyo.kernel.proto.Big$.huge", 2000, inlined = 0, refused = 1, Chunk("hot method too big"))
        val flatReport = Report.render(Bench.compare(leg("c", base), leg("v", base, jit = Chunk(nearV, overV))))
        check("a near-budget method is named on a flat report", flatReport.contains("close to a budget") && flatReport.contains("Eval$.loop"), flatReport.takeRight(300))
        check("with how far over it sits", flatReport.contains("FreqInlineSize"), flatReport.takeRight(300))
        check("a method well over the budget is not named", !flatReport.contains("Big$.huge"))
        check("and a report with no near-budget method has no such section", !Report.render(Bench.compare(leg("c", base), leg("v", base))).contains("close to a budget"))
    }

    section("partial inlining moves are named, not only decisive flips (item 1)")
    locally {
        // 3/10 -> 7/10 sites inlined: a real shift that never flips all-inlined <-> all-refused, so jitShift (jitChanges) drops it;
        // diffVerdicts, now reading the per-method sites the runs store, names it
        val cSites = InlineSites("kyo.kernel.proto.Eval$.step", 200, inlined = 3, refused = 7, Chunk("hot method too big"))
        val vSites = InlineSites("kyo.kernel.proto.Eval$.step", 200, inlined = 7, refused = 3, Chunk("hot method too big"))
        val rep    = Report.render(Bench.compare(leg("c", base, jit = Chunk(cSites)), leg("v", base, jit = Chunk(vSites))))
        check("a site-fraction shift that does not flip is named", rep.contains("shifted without flipping") && rep.contains("3/10 -> 7/10"), rep.takeRight(400))
        check("diffVerdicts reads the stored per-method sites both runs carry", LogCompilation.diffVerdicts(Chunk(cSites), Chunk(vSites)).exists(_.method.contains("step")))
        // a decisive flip belongs to jitShift's section and must not be duplicated as a partial shift
        val flipC   = InlineSites("kyo.kernel.proto.Flip$.m", 300, inlined = 4, refused = 0, Chunk.empty)
        val flipV   = InlineSites("kyo.kernel.proto.Flip$.m", 300, inlined = 0, refused = 4, Chunk("hot method too big"))
        val flipRep = Report.render(Bench.compare(leg("c", base, jit = Chunk(flipC)), leg("v", base, jit = Chunk(flipV))))
        check("a decisive flip stays in Inlining changed", flipRep.contains("Inlining changed") && flipRep.contains("Flip$.m"))
        check("and is not repeated as a partial shift", !flipRep.contains("shifted without flipping"), flipRep.takeRight(300))
    }

    section("the report names the checks it could not run (absence names its remedy)")
    locally {
        val timing = Report.render(Bench.compare(leg("c", base, evidence = Evidence.Timing), leg("v", base, evidence = Evidence.Timing)))
        check("a timing pair says no mechanism evidence and the command for it", timing.contains("evidence timing") && timing.contains("--evidence full"), timing.takeRight(300))
        val subset = Report.render(Bench.compare(leg("c", base, whole = false), leg("v", base, whole = false)))
        check("a subset run says it made no whole-class statement", subset.contains("whole class") && subset.contains("without --row"), subset.takeRight(300))
        val singlePair = Report.render(Bench.compare(leg("c", base), leg("v", base)))
        check("a single pair says it ran no A/A null and how to", singlePair.contains("no A/A null") && singlePair.contains("--legs 5"), singlePair.takeRight(300))
        // a full, whole-class, three-control replicated comparison ran every check, so the section stays silent
        val complete = Report.render(Bench.compareReplicated(ctlLegs, vntFlat))
        check("a complete session lists no absences", !complete.contains("Not evaluated here"), complete.takeRight(300))
    }

    section("the unexplained-row advice does not point at evidence a timing run lacks (defect 43)")
    locally {
        val timingRep = Report.render(Bench.compare(
            leg("c", Seq(("a", 100.0, 1.0, 640.0)), evidence = Evidence.Timing),
            leg("v", Seq(("a", 130.0, 1.0, 640.0)), evidence = Evidence.Timing)))
        check("a timing run's unexplained row names the absent evidence and its command",
            timingRep.contains("no mechanism evidence") && timingRep.contains("--evidence full"), timingRep.takeRight(400))
        check("and does not send the operator to an inlining log that was never collected", !timingRep.contains("inlining log"), timingRep.takeRight(400))
        val fullRep = Report.render(Bench.compare(leg("c", Seq(("a", 100.0, 1.0, 640.0))), leg("v", Seq(("a", 130.0, 1.0, 640.0)))))
        check("a full run's unexplained row points at the evidence above it", fullRep.contains("inlining verdicts above"), fullRep.takeRight(400))
    }

    section("the JIT table states its scope on a multi-row leg (defect 49)")
    locally {
        // compile time is summed across the leg's rows, but the task census is one fork's (defect 30):
        // presenting them as one census is the defect. The table now says so when the leg is multi-row
        val jm         = JitMetrics(msInWindow = 10.0, msTotal = 20.0, tasks = 100, c2Tasks = 80, recompiled = 5, lastCompileAt = 1.0)
        val multiRow   = Report.render(Bench.compare(leg("c", base).copy(jit_metrics = Maybe(jm)), leg("v", base).copy(jit_metrics = Maybe(jm))))
        check("a multi-row leg says its task census is one fork's", multiRow.contains("one row's fork only"), multiRow.takeRight(400))
        val singleRow  = Seq(("a", 10.0, 0.1, 640.0))
        val singleRep  = Report.render(Bench.compare(leg("c", singleRow).copy(jit_metrics = Maybe(jm)), leg("v", singleRow).copy(jit_metrics = Maybe(jm))))
        check("a single-row leg needs no scope caveat", singleRep.contains("JIT cost") && !singleRep.contains("one row's fork only"), singleRep.takeRight(300))
    }

    section("what two shas cannot say")
    // the skill's worked example: a node-layout change and a currency hoist shipped together, the
    // bundle was faster, the win was credited first to one and then to the other, and both
    // stories were wrong as told. A pair of shas cannot separate them, and the report has to say
    // so rather than leave the reader to supply the mechanism themselves.
    locally {
        def atSha(label: String, sha: String) =
            leg(label, base).copy(sha = sha)
        val designPair = Report.render(Bench.compare(atSha("c", "aaaaaaaaaa"), atSha("v", "bbbbbbbbbb")))
        check("a two-sha comparison refuses source-level attribution", designPair.contains("no source-level mechanism is attributable"), designPair)
        check("and says what would fix it", designPair.contains("chain of shas"), designPair)

        // the same sha under two sets of JVM args has nothing to partition, so the note would be
        // noise and is not printed
        val configPair = Report.render(Bench.compare(atSha("c", "aaaaaaaaaa"), atSha("v", "aaaaaaaaaa")))
        check("a configuration comparison gets no such note", !configPair.contains("no source-level mechanism"), configPair)

        // one step of a declared chain is exactly the case where attribution IS available
        val step = Report.render(Bench.compare(atSha("c", "aaaaaaaaaa"), atSha("v", "bbbbbbbbbb")), chainLength = 4)
        check("a chain step says the delta is isolated", step.contains("isolated contribution"), step)
        check("and does not also refuse attribution", !step.contains("no source-level mechanism"), step)
    }

    section("a chain needs three shas")
    locally {
        val pair = Bench.requireChain(Seq("a", "b"))
        check("two shas is refused before any leg is run", pair.isDefined, pair.toString)
        check(
            "with the reason, and where to go instead",
            pair.exists(w => w.contains("cannot isolate anything") && w.contains("bracket")),
            pair.toString
        )
        check("three distinct shas is a chain", Bench.requireChain(Seq("a", "b", "c")).isEmpty, Bench.requireChain(Seq("a", "b", "c")).toString)
        // a step to the same tree measures nothing and would report a threshold for it anyway
        val repeated = Bench.requireChain(Seq("a", "b", "a"))
        check("a repeated sha is refused", repeated.isDefined, repeated.toString)
        check("and named", repeated.exists(_.contains("a")), repeated.toString)
    }

    section("a dirty A/A null stops the session")
    // it used to print a line with a cross on it and exit 0. Every row an A/A null classifies is
    // a false positive by construction, so a dirty null is the strongest statement available that
    // the session is unreadable, and it was the one statement the tool made in passing.
    locally {
        val clean = Bench.nullComparison(ctlLegs)
        check("a clean null blocks nothing", Report.nullBlockers(clean, ctlLegs.size, required = true).isEmpty, Report.nullBlockers(clean, ctlLegs.size, required = true).mkString)
        check("and says so once", Report.nullNote(clean).contains("clean"), Report.nullNote(clean))

        // the same alternating contamination the null's own must-fire fixture uses: every other
        // leg is slow, so the two arms genuinely differ
        val dirty = Bench.nullComparison(Chunk(
            legScores("c1", Seq(("a", 100.0))), legScores("c2", Seq(("a", 130.0))),
            legScores("c3", Seq(("a", 100.4))), legScores("c4", Seq(("a", 130.4)))
        ))
        val dirtyBlockers = Report.nullBlockers(dirty, 4, required = true)
        check("a dirty null is a blocker", dirtyBlockers.nonEmpty, dirty.map(_.deltas.map(d => s"${d.row}=${d.verdict}").mkString(",")).getOrElse("no null"))
        check("naming the rows it falsely classified", dirtyBlockers.exists(_.contains("a +")), dirtyBlockers.mkString)
        check("and saying why they are false", dirtyBlockers.exists(_.contains("false by construction")), dirtyBlockers.mkString)
        check("a clean null is not reported as a blocker", Report.nullNote(dirty).isEmpty, Report.nullNote(dirty))

        // a session that cannot run a null at all is worse than one whose null failed: it cannot
        // check itself, and that used to print "not enough control legs" and carry on
        val tooFew = Report.nullBlockers(Bench.nullComparison(Chunk(ctlLegs(0))), 1, required = true)
        check("a session that cannot run a null is blocked too", tooFew.nonEmpty, tooFew.mkString)
        check("and told what it would take", tooFew.exists(_.contains("Five legs")), tooFew.mkString)
    }

    section("a configuration comparison says what it configured")
    // the campaign's headline, continuationBodiesFuse at -6.8% under a forced inline, is stored as
    // two runs with the same sha and the same treeHash and nothing recording the forcing. It could
    // be re-read, re-compared and re-reported forever without a reader being able to say what it
    // had measured.
    locally {
        def cfg(label: String, args: String*) =
            leg(label, base).copy(jvmArgs = Chunk.from(args))
        val forced = Report.render(Bench.compare(
            cfg("c"),
            cfg("v", "-XX:CompileCommandFile=/tmp/inline.txt")
        ))
        check("the varied argument is named", forced.contains("-XX:CompileCommandFile=/tmp/inline.txt"), forced)
        check("and called a configuration comparison", forced.contains("configuration comparison"), forced)
        // a same-sha pair with nothing recorded is the pre-fix state, and must say so rather than
        // presenting itself as a clean comparison
        val silent = Report.render(Bench.compare(cfg("c"), cfg("v")))
        check("a same-sha pair with no recorded args admits it", silent.contains("nothing here says"), silent)
        check("and offers the two readings", silent.contains("A/A") && silent.contains("unrecoverable"), silent)
        // two different shas is a design comparison and keeps the partition refusal
        val design = Report.render(Bench.compare(leg("c", base).copy(sha = "aaa"), leg("v", base).copy(sha = "bbb")))
        check("a design comparison still refuses attribution", design.contains("no source-level mechanism"), design)
    }

    section("each row states its own resolution")
    // the footer reports only the worst resolution across flat rows, which cannot answer the
    // question a flat row actually raises: close to resolving, or nowhere near? On the replicated
    // sweep `emittingClausesPayRegionRebuild` came back flat at -12.5% against its own ±12.6%,
    // missing by a tenth of a point, and the table gave no way to see that.
    locally {
        val rep = Report.render(Bench.compareReplicated(ctlLegs, vntSlow))
        check("the table has a resolves column", rep.contains("| resolves |"), rep.linesIterator.take(8).mkString("\n"))
        // a resolution per row, not one number repeated in the footer
        val cells = rep.linesIterator.filter(_.startsWith("| ")).flatMap(_.split('|').map(_.trim).filter(_.startsWith("±"))).toList
        check("every data row carries one", cells.size == ctlLegs.head.rows.size, s"${cells.size} cells for ${ctlLegs.head.rows.size} rows")
        check("and they are real numbers, not a placeholder", cells.forall(c => c.drop(1).takeWhile(_ != '%').toDoubleOption.isDefined), cells.mkString(","))
        // a single-pair comparison has a bound too, from the legs' own error, and must show it
        val pair = Report.render(Bench.compare(ctl, vnt))
        check("a single pair shows its bound as well", pair.contains("±"), pair.linesIterator.filter(_.contains("`fast`")).mkString)
    }

    section("a score that can be read")
    // found by running a real bracket: a cleanly separated regression on a nanosecond row printed
    // as `0.01 ± 0.00` against `0.01 ± 0.00` beside `+26.2%`. The real numbers were 0.005853 and
    // 0.007364, obvious on sight, and the error column read as though there were none.
    locally {
        check("a nanosecond score keeps its digits", Report.score(0.005853) == "0.005853", Report.score(0.005853))
        check("and so does its error", Report.score(0.000317) == "0.000317", Report.score(0.000317))
        check("an ordinary score stays readable", Report.score(26.82) == "26.82", Report.score(26.82))
        check("a large one does not grow a tail", Report.score(314.62) == "314.6", Report.score(314.62))
        check("a sub-unit score gains precision", Report.score(0.56) == "0.5600", Report.score(0.56))
        check("zero is zero", Report.score(0.0) == "0", Report.score(0.0))
        // the real pair from the bracket, rendered into a row, must not collapse to one value
        val nano = Report.render(Bench.compare(
            leg("c", Seq(("evalFixedOverhead", 0.005853, 0.000317, 8.0))),
            leg("v", Seq(("evalFixedOverhead", 0.007364, 0.000111, 8.0)))
        ))
        check("the two legs render as different numbers", nano.contains("0.005853") && nano.contains("0.007364"), nano.linesIterator.filter(_.contains("evalFixed")).mkString)
    }

    section("a refusal that can be read")
    // the red-tree gate was exercised for the first time by planting a failing test in the
    // throwaway worktree. It refused, correctly, and buried the one line naming the failing test
    // under several hundred lines of passing ones, which is a refusal an operator skims past.
    locally {
        val green = (1 to 200).map(i => s"[info] - some passing test $i (1 millisecond)")
        val log = (green.take(120) ++ Seq(
            "[info] - the one that failed *** FAILED *** (2 milliseconds)"
        ) ++ green.drop(120) ++ Seq(
            "[error] Failed tests:",
            "[error] \tkyo.kernel.proto.SomeTest",
            "[info] *** 1 TEST FAILED ***"
        )).mkString("\n")
        val excerpt = Bench.failureExcerpt(log)
        check("the failing test is in the excerpt", excerpt.contains("*** FAILED ***"), excerpt)
        check("and so is sbt's own summary of what failed", excerpt.contains("kyo.kernel.proto.SomeTest"), excerpt)
        check("while the passing ones are not carried wholesale", excerpt.linesIterator.count(_.contains("some passing test")) < 15, excerpt)
        check("and the reader is told how much was left out", excerpt.contains("the rest of it green"), excerpt)
        // a command that failed with no error markers at all still has to say something
        val bare = Bench.failureExcerpt((1 to 40).map(i => s"line $i").mkString("\n"))
        check("output with no error markers still yields its tail", bare.contains("line 40"), bare)
    }

    section("a stored run survives a field being added to Run")
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

    section("the store answers for what it does not have")
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

end BenchTest

/** The synthetic runs the suites build from: a JMH entry, its rows, and a leg around them. */
object BenchTest:

    def jmh(name: String, score: Double, error: Double, alloc: Double, compileMs: Double = 0.0, mode: String = "avgt", unit: String = "us/op"): String =
        s"""{"benchmark":"kyo.kernel.bench.ProtoKernelBench.$name","mode":"$mode","forks":3,
           |"primaryMetric":{"score":$score,"scoreError":$error,"scoreUnit":"$unit","rawData":[[1,2,3,4,5]]},
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
end BenchTest
