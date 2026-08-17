import Model.*
import kyo.*

/** Acceptance for the investigator, in both directions.
  *
  * The direction that is easy to pass is refutation: a pipeline that answers "refuted" to everything
  * refutes every planted false hypothesis and looks correct. The earlier design of this phase had
  * only that half of its own criterion, so an always-refutes implementation would have passed it.
  *
  * So every hypothesis here is planted twice, once true and once false, and a third time with a flag
  * that did not take, which must be inconclusive rather than either.
  */
object InvestigateTest:

    def check(name: String, cond: Boolean, detail: String = ""): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name${if detail.nonEmpty then s"  <- $detail" else ""}")
        if !cond then throw new AssertionError(name)

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

    def main(args: Array[String]): Unit =
        import kyo.Frame.internal

        println("the rule table")
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

        println("\nthe efficacy gate, which is what makes a refutation mean anything")
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

        println("\nboth directions, on planted hypotheses")
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

        println("\nrendering")
        val rendered = Investigate.render(cmp)
        check("the experiments are offered with the comparison", rendered.contains("Next experiments"), rendered)
        check("with the inconclusive rule stated where it will be read", rendered.contains("refutes nothing"), rendered)
        check("and a quiet comparison offers none", Investigate.render(quiet).isEmpty)

        println("\nall checks passed\n")
        println(rendered)
    end main
end InvestigateTest
