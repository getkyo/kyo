import Model.*
import kyo.*

/** The known-answer fixture: a real comparison whose truth was established independently.
  *
  * Every other check in this suite is a null. It asks the harness to stay silent, and a pipeline that
  * always stays silent passes all of them. A tool validated only that way converges on abstaining,
  * which is safe and useless.
  *
  * This one asks it to speak, about a comparison measured on this machine and diagnosed down to the
  * mechanism by four separate experiments:
  *
  *   - forcing `Eval$::dispatch$1` to inline improves five rows by 5 to 10%
  *   - it regresses `trailingMapsStayLinear` by 23.8%
  *   - fourteen rows have byte-identical allocation, to the tenth of a byte
  *   - that one row allocates 239,976 B/op more, which a separate run showed is exactly the scalar
  *     replacement the enlarged compilation unit destroyed
  *
  * So the harness must find the winners, find the loser, and name allocation as the mechanism on the
  * row where allocation actually moved and on no other. Getting any of those wrong is a defect, and
  * none of it is expressible as staying quiet.
  */
object BenchKnownAnswerTest extends KyoApp:

    val sweep = Roots.repo / "bench-results" / "exp3"

    var failures = 0

    def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if cond then println(s"  ok   $name")
        else
            failures += 1
            println(s"  FAIL $name${if detail.nonEmpty then s"\n         $detail" else ""}")

    def leg(label: String, rows: Chunk[Row]): Run =
        Run(
            id = label, session = Session("known", "host", "25", 0.0), label = label, sha = "d85ee6821f",
            treeHash = "fixture", forks = 1, evidence = Evidence.Full, wholeClass = true, declaredRows = 15,
            markers = Chunk.empty, warmup = 10, jit_metrics = Maybe.empty, rows = rows, jit = Chunk.empty,
            coverage = Chunk.empty, alloc = Chunk.empty, cpu = Chunk.empty, deopts = Chunk.empty,
            morphism = Chunk.empty, recordedAt = "fixture"
        )

    /** Established by experiments 2 to 4, not by this pipeline. */
    val knownWinners = Set(
        "emittingClausesPayRegionRebuild", "handleLoopAnswersInPlace", "suspensionBaseline",
        "handleLoopFusesContinuation", "continuationBodiesFuse"
    )
    val knownLoser        = "trailingMapsStayLinear"
    val knownAllocChanged = Set(knownLoser)

    run {
        for
            dRaw <- (sweep / "default-1.json").read
            fRaw <- (sweep / "forced-1.json").read
            dRows <- Abort.run(Bench.parseJmh(dRaw)).map(_.getOrThrow)
            fRows <- Abort.run(Bench.parseJmh(fRaw)).map(_.getOrThrow)
            control = leg("default", dRows)
            variant = leg("forced", fRows)
            cmp     = Bench.compare(control, variant)
            by      = cmp.deltas.map(d => d.row -> d).toMap

            _ = println("\nthe fixture loaded")
            _ = check("all fifteen rows on both sides", dRows.size == 15 && fRows.size == 15, s"${dRows.size} and ${fRows.size}")
            _ = check("and a delta for each", cmp.deltas.size == 15, s"${cmp.deltas.size}")

            _ = println("\nit must find the regression")
            loser = by.get(knownLoser)
            _ = check("the regressed row is not called flat", loser.exists(_.verdict != Verdict.Flat), s"${loser.map(_.verdict)}")
            _ = check("it is called a regression", loser.exists(_.verdict == Verdict.Regressed), s"${loser.map(_.verdict)}")
            _ = check("of roughly the measured size", loser.exists(d => d.percent > 15.0 && d.percent < 35.0), f"${loser.map(_.percent).getOrElse(0.0)}%.1f%%")

            _ = println("\nit must name allocation, on exactly the row where allocation moved")
            allocNamed = cmp.deltas.filter(_.mechanism.exists(_.contains("allocation"))).map(_.row).toSet
            _ = check("allocation is named on the row that allocated more", allocNamed.contains(knownLoser), allocNamed.mkString(","))
            _ = check("and on no other row", allocNamed == knownAllocChanged, s"named on ${allocNamed.mkString(",")}")
            // fourteen rows are byte-identical, so any mechanism on them would be invented
            _ = check(
                "the byte-identical rows carry no allocation mechanism",
                cmp.deltas.filterNot(d => knownAllocChanged.contains(d.row)).forall(!_.mechanism.exists(_.contains("allocation"))),
                cmp.deltas.filter(d => !knownAllocChanged.contains(d.row) && d.mechanism.nonEmpty).map(_.row).mkString(",")
            )

            _ = println("\nit must find the wins")
            found = knownWinners.filter(r => by.get(r).exists(_.verdict == Verdict.Faster))
            _ = check("at least three of the five known winners are called faster", found.size >= 3, s"found ${found.size}: ${found.mkString(",")}")
            _ = check("and none of them is called a regression", knownWinners.forall(r => by.get(r).forall(_.verdict != Verdict.Regressed)))

            _ = println("\nit must not invent movement")
            // a change that both wins and loses is two diagnoses, and the report says so
            out = Report.render(cmp)
            _ = check("the report flags the win-and-loss shape", out.contains("two diagnoses"), "a mixed result presented as a single tradeoff")
            _ = check("and the regression is in the headline", out.contains(knownLoser), "the regressed row absent from the verdict line")

            _ <- Console.printLine(
                if failures == 0 then "\nall known-answer checks passed\n"
                else s"\n$failures known-answer check(s) failed\n"
            )
            _ <- Abort.when(failures > 0)(Bench.BracketFailed(s"$failures known-answer check(s) failed"))
        yield ()
        end for
    }
end BenchKnownAnswerTest
