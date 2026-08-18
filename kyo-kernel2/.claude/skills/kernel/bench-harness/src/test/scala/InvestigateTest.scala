import Model.*
import kyo.*
import kyo.test.*

/** Acceptance for the investigator, in both directions.
  *
  * The direction that is easy to pass is refutation: a pipeline that answers "refuted" to everything
  * refutes every planted false hypothesis and looks correct. The earlier design of this phase had
  * only that half of its own criterion, so an always-refutes implementation would have passed it.
  *
  * So every hypothesis here is planted twice, once true and once false, and a third time with a flag
  * that did not take, which must be inconclusive rather than either.
  */
class InvestigateTest extends Test[Any]:

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

    val session = Session("s-1", "host", "25", 4.0)

    def leg(label: String, rows: Seq[(String, Double, Double, Double)], jit: Chunk[InlineSites])(using Frame): Run =
        Run(
            id = s"$label-x", session = session, label = label, sha = "0123456789", treeHash = "t", forks = 3,
            evidence = Evidence.Full, wholeClass = true, declaredRows = 15, markers = Chunk.empty, warmup = 10,
            jit_metrics = Maybe.empty, rows = BenchTest.rows(rows*), jit = jit, coverage = Chunk.empty,
            alloc = Chunk.empty, allocByMethod = Chunk.empty, cpu = Chunk.empty, deopts = Chunk.empty,
            morphism = Chunk.empty, recordedAt = "now"
        )

    val hot     = "kyo.kernel.proto.Eval$::dispatch"
    val inlined = InlineSites(hot, 607, inlined = 4, refused = 0, Chunk.empty)
    val refused = InlineSites(hot, 607, inlined = 0, refused = 4, Chunk("hot method too big"))


    section("the rule table")
    val ctlRows = Seq(("fused", 100.0, 1.0, 640.0), ("quiet", 50.0, 1.0, 640.0))
    val vntRows = Seq(("fused", 130.0, 1.0, 640.0), ("quiet", 50.2, 1.0, 640.0))
    val cmp     = Bench.compare(leg("c", ctlRows, Chunk(inlined)), leg("v", vntRows, Chunk(refused)))
    val fs      = Investigate.falsifiers(cmp)

    check("a regression next to a lost inlining earns a falsifier", fs.exists(_.hypothesis == Investigate.Hypothesis.StoppedInlining(hot)), fs.map(_.hypothesis.show).mkString("; "))
    check("which re-runs the variant, since that is the leg that lost it", fs.find(_.hypothesis == Investigate.Hypothesis.StoppedInlining(hot)).exists(_.arm == Investigate.Arm.Variant))
    check(
        "and uses the file form, which does not have to survive shell and sbt quoting",
        fs.exists(_.jvmArgs.exists(_.contains("CompileCommandFile"))),
        fs.flatMap(_.jvmArgs).mkString(" ")
    )
    // a comparison with nothing to explain must not manufacture work
    val quiet = Bench.compare(leg("c", ctlRows, Chunk(inlined)), leg("v", ctlRows, Chunk(inlined)))
    check("a comparison with no movement earns no experiments", Investigate.falsifiers(quiet).isEmpty, Investigate.falsifiers(quiet).map(_.hypothesis.show).mkString)

    // a refusal a force-inline flag cannot act on earns no experiment: force-inlining a megamorphic
    // site (no static binding) does nothing, so proposing it spends a session to learn nothing (defect 34)
    val megamorphic = InlineSites(hot, 607, inlined = 0, refused = 4, Chunk("no static binding"))
    val megaFs      = Investigate.falsifiers(Bench.compare(leg("c", ctlRows, Chunk(inlined)), leg("v", vntRows, Chunk(megamorphic))))
    check("a megamorphic stopped-inlining earns no force-inline experiment", !megaFs.exists(_.hypothesis == Investigate.Hypothesis.StoppedInlining(hot)), megaFs.map(_.hypothesis.show).mkString("; "))
    check("but an actionable size refusal still does", fs.exists(_.hypothesis == Investigate.Hypothesis.StoppedInlining(hot)))

    // the cheap direction: contradict the win on the control before redesigning around it
    val winCmp = Bench.compare(leg("c", Seq(("fused", 130.0, 1.0, 640.0)), Chunk(refused)), leg("v", Seq(("fused", 100.0, 1.0, 640.0)), Chunk(inlined)))
    val winFs  = Investigate.falsifiers(winCmp)
    check("a win earns the falsifier that could dissolve it", winFs.exists(_.hypothesis == Investigate.Hypothesis.WinFromInlining(hot)), winFs.map(_.hypothesis.show).mkString("; "))
    check("run on the control, which is the cheap edit", winFs.find(_.hypothesis == Investigate.Hypothesis.WinFromInlining(hot)).exists(_.arm == Investigate.Arm.Control))

    // an allocation cliff earns the escape-analysis question, and this is the real shape: a row
    // flat in time that moved 240,000 B/op
    val allocCmp = Bench.compare(
        leg("c", Seq(("trailing", 100.0, 30.0, 640.0)), Chunk.empty),
        leg("v", Seq(("trailing", 103.0, 30.0, 240640.0)), Chunk.empty)
    )
    val allocFs = Investigate.falsifiers(allocCmp)
    check("an allocation cliff earns the escape-analysis falsifier", allocFs.exists(_.hypothesis == Investigate.Hypothesis.RidesScalarReplacement("trailing")), allocFs.map(_.hypothesis.show).mkString("; "))
    check("even though the row is flat in time", allocCmp.deltas.head.verdict != Verdict.Regressed, allocCmp.deltas.head.verdict.toString)
    check("and it disables exactly one optimization", allocFs.exists(_.jvmArgs == Seq("-XX:-EliminateAllocations")))

    section("the efficacy gate, which is what makes a refutation mean anything")
    val h        = Investigate.Hypothesis.StoppedInlining(hot)
    val baseline = leg("v", vntRows, Chunk(refused))

    // a flag that did nothing. The whole gate exists for this case: without it, the row does not
    // recover, and "did not recover" reads as a refutation of a hypothesis never tested
    val didNotTake = leg("iso", vntRows, Chunk(refused))
    val gateNoTake = Investigate.efficacy(h, baseline, didNotTake)
    check("a flag that did not take is caught", gateNoTake.isDefined, gateNoTake.toString)
    check("and the reason names HotSpot's actual behaviour", gateNoTake.exists(_.contains("hint")), gateNoTake.toString)

    // a flag that took, but moved five other methods with it
    val collateral = leg("iso", ctlRows, Chunk(inlined, InlineSites("kyo.kernel.proto.Safepoint::enter", 50, 0, 3, Chunk("too large"))))
    val baseline2  = leg("v", vntRows, Chunk(refused, InlineSites("kyo.kernel.proto.Safepoint::enter", 50, 3, 0, Chunk.empty)))
    val gateWide   = Investigate.efficacy(h, baseline2, collateral)
    check("a flag that moved other verdicts too is caught", gateWide.isDefined, gateWide.toString)
    check("and says why that happens", gateWide.exists(_.contains("remaining budget")), gateWide.toString)

    val clean = leg("iso", ctlRows, Chunk(inlined))
    check("a flag that took, and only there, passes the gate", Investigate.efficacy(h, baseline, clean).isEmpty, Investigate.efficacy(h, baseline, clean).toString)

    section("both directions, on planted hypotheses")
    val f = fs.find(_.hypothesis == h).get

    // PLANTED TRUE: the flag took and the row returned to the control's number
    val trueIso = leg("iso", Seq(("fused", 100.5, 1.0, 640.0), ("quiet", 50.0, 1.0, 640.0)), Chunk(inlined))
    val trueOut = Investigate.adjudicate(f, "fused", baseline, trueIso, target = 100.0)
    check("a planted TRUE hypothesis is confirmed", trueOut.isInstanceOf[Investigate.Outcome.Confirmed], trueOut.show)

    // PLANTED FALSE: the flag took and the row did not move at all
    val falseIso = leg("iso", Seq(("fused", 130.2, 1.0, 640.0), ("quiet", 50.0, 1.0, 640.0)), Chunk(inlined))
    val falseOut = Investigate.adjudicate(f, "fused", baseline, falseIso, target = 100.0)
    check("a planted FALSE hypothesis is refuted", falseOut.isInstanceOf[Investigate.Outcome.Refuted], falseOut.show)

    // THE THIRD ANSWER: the flag never took, so neither of the above is available. An
    // always-refutes implementation passes the false case and fails here, which is the point
    val untested = Investigate.adjudicate(f, "fused", baseline, leg("iso", vntRows, Chunk(refused)), target = 100.0)
    check("a falsifier that never fired refutes nothing", untested.isInstanceOf[Investigate.Outcome.Inconclusive], untested.show)
    check("and is not quietly filed as a refutation", !untested.show.contains("REFUTED"), untested.show)

    // a partial move is not an answer either: it is neither the prediction nor its absence
    val partial = Investigate.adjudicate(f, "fused", baseline, leg("iso", Seq(("fused", 115.0, 1.0, 640.0)), Chunk(inlined)), target = 100.0)
    check("a half move is inconclusive, not a weak confirmation", partial.isInstanceOf[Investigate.Outcome.Inconclusive], partial.show)

    // and an experiment that could never have separated the two says so, rather than confirming
    // whatever it happened to land on
    val cannotSeparate = Investigate.adjudicate(
        f, "fused",
        leg("v", Seq(("fused", 100.4, 30.0, 640.0)), Chunk(refused)),
        leg("iso", Seq(("fused", 100.0, 30.0, 640.0)), Chunk(inlined)),
        target = 100.0
    )
    check("an experiment with no power to separate says so", cannotSeparate.show.contains("could not have separated"), cannotSeparate.show)

    // the distance test is direction-free: the same planted TRUE outcome adjudicates the same in
    // throughput, where the numbers mean more work per unit of time rather than less time per op
    def thrptLeg(label: String, score: Double, jit: Chunk[InlineSites]): Run =
        leg(label, Seq(("fused", score, 1.0, 640.0)), jit).copy(rows =
            Abort.run(Bench.parseJmh("[" + BenchTest.jmh("fused", score, 1.0, 640.0, mode = "thrpt", unit = "ops/us") + "]")).eval.getOrThrow
        )
    val thrptTrue = Investigate.adjudicate(f, "fused", thrptLeg("v", 130.0, Chunk(refused)), thrptLeg("iso", 100.5, Chunk(inlined)), target = 100.0)
    check("a throughput row adjudicates by distance, not by direction", thrptTrue.isInstanceOf[Investigate.Outcome.Confirmed], thrptTrue.show)
    // but a baseline in avgt against an isolation in thrpt compares nothing with nothing
    val mixed = Investigate.adjudicate(f, "fused", baseline, thrptLeg("iso", 100.5, Chunk(inlined)), target = 100.0)
    check("a mode mismatch between the legs is inconclusive", mixed.isInstanceOf[Investigate.Outcome.Inconclusive], mixed.show)
    check("and says which side was measured how", mixed.show.contains("avgt in us/op") && mixed.show.contains("thrpt in ops/us"), mixed.show)

    section("the quantity a hypothesis is about")
    // found by running the rule table against the campaign's own sweep: the escape-analysis
    // hypothesis is a claim about bytes per operation, and adjudicating it against wall clock
    // reads the wrong column. The row it fires on is one whose timing does not resolve at all.
    check("the escape-analysis hypothesis is judged on allocation", Investigate.quantity(Investigate.Hypothesis.RidesScalarReplacement("r")) == Investigate.Quantity.Allocation)
    check("and an inlining hypothesis on time", Investigate.quantity(h) == Investigate.Quantity.Time)

    // the real numbers from experiment 4. Disabling escape analysis on the unmodified design
    // reproduced the variant's allocation to 23.8 bytes on 2.56 MB
    def allocLeg(label: String, alloc: Double) =
        leg(label, Seq(("trailing", 300.0, 25.0, alloc)), Chunk.empty)
    val eaF = Investigate.Falsifier(
        Investigate.Hypothesis.RidesScalarReplacement("trailing"), Investigate.Arm.Control,
        Seq("-XX:-EliminateAllocations"), "x"
    )
    val eaOut = Investigate.adjudicate(eaF, "trailing", allocLeg("c", 2321410.04), allocLeg("iso", 2561410.32), target = 2561386.53)
    check("a reproduction to six significant figures is confirmed", eaOut.isInstanceOf[Investigate.Outcome.Confirmed], eaOut.show)
    // the band is derived from A/A data (worst observed spread 47.8 B on 2.32 MB) rather than
    // picked; a flat one-byte band refused exactly this result when it was first written
    check("the band is wide enough for the measured A/A spread", Investigate.Quantity.Allocation.resolution(
        BenchTest.rows(("trailing", 300.0, 25.0, 2321410.04)).head,
        BenchTest.rows(("trailing", 300.0, 25.0, 2321457.84)).head
    ) > 47.8)
    // and still narrow enough to resolve the effect it exists for
    val eaNo = Investigate.adjudicate(eaF, "trailing", allocLeg("c", 2321410.04), allocLeg("iso", 2321410.04), target = 2561386.53)
    check("an allocation that did not move is refuted", eaNo.isInstanceOf[Investigate.Outcome.Refuted], eaNo.show)
    // a leg measured without the gc profiler carries no allocation figure at all, and cannot
    // answer an allocation question however its timing came out
    val unprofiled =
        allocLeg("iso", 0.0).copy(rows = Chunk(
            Row("trailing", "avgt", 5, 300.0, 25.0, "us/op", Chunk(300.0), Maybe.empty, Maybe.empty, Maybe.empty)
        ))
    val noAlloc = Investigate.adjudicate(eaF, "trailing", allocLeg("c", 2321410.04), unprofiled, target = 2561386.53)
    check("a leg with no allocation figure is not an answer", noAlloc.show.contains("never measured"), noAlloc.show)

    section("rendering")
    val rendered = Investigate.render(cmp)
    check("the experiments are offered with the comparison", rendered.contains("Next experiments"), rendered)
    check("with the inconclusive rule stated where it will be read", rendered.contains("refutes nothing"), rendered)
    check("and a quiet comparison offers none", Investigate.render(quiet).isEmpty)

end InvestigateTest
