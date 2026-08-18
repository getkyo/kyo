import Model.*
import kyo.*
import kyo.test.*

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
class BenchKnownAnswerTest extends Test[Any]:

    val sweep = Roots.repo / "bench-results" / "exp3"

    // one leaf per suite: the artifact is read once inside it, every check records its claim, and the
    // leaf fails listing every claim that did not hold. This is the mains' counting `check` with the
    // framework holding the exit code
    private val failed = scala.collection.mutable.ListBuffer.empty[String]
    private def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if !cond then failed += (if detail.nonEmpty then s"$name  <- $detail" else name)

    def leg(label: String, rows: Chunk[Row]): Run =
        Run(
            id = label, session = Session("known", "host", "25", 0.0), label = label, sha = "d85ee6821f",
            treeHash = "fixture", forks = 1, evidence = Evidence.Full, wholeClass = true, declaredRows = 15,
            markers = Chunk.empty, warmup = 10, jit_metrics = Maybe.empty, rows = rows, jit = Chunk.empty,
            coverage = Chunk.empty, alloc = Chunk.empty, allocByMethod = Chunk.empty, cpu = Chunk.empty, deopts = Chunk.empty,
            morphism = Chunk.empty, recordedAt = "fixture"
        )

    /** Established by experiments 2 to 4, not by this pipeline. */
    val knownWinners = Set(
        "emittingClausesPayRegionRebuild", "handleLoopAnswersInPlace", "suspensionBaseline",
        "handleLoopFusesContinuation", "continuationBodiesFuse"
    )
    val knownLoser        = "trailingMapsStayLinear"
    val knownAllocChanged = Set(knownLoser)

    "the known answers hold" in {
        for
            dRaw <- (sweep / "default-1.json").read
            fRaw <- (sweep / "forced-1.json").read
            dRows <- Abort.run(Bench.parseJmh(dRaw)).map(_.getOrThrow)
            fRows <- Abort.run(Bench.parseJmh(fRaw)).map(_.getOrThrow)
            control = leg("default", dRows)
            variant = leg("forced", fRows)
            cmp     = Bench.compare(control, variant)
            by      = cmp.deltas.map(d => d.row -> d).toMap

            _ = assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
        yield ()
        end for
    }
end BenchKnownAnswerTest
