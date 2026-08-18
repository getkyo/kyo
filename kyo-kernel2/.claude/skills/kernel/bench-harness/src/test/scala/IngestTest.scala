import Model.*
import kyo.*
import kyo.test.*

/** Holds `Ingest`'s attach paths to a real artifact.
  *
  * A JMH json carries timing and the gc profiler's numbers but never the inlining decisions, which live in a separate compilation log of
  * the same fork. A run built by `ingest` from a json alone therefore had an empty `jit`, and every inlining verdict read nothing.
  * `attachJit` fills it from the log; this decodes the real capture rather than a synthetic one, so the day the parser or the assembly
  * drifts, the empty-jit run comes back.
  */
class IngestTest extends Test[Any]:

    val artifacts = Roots.repo / "qa-artifacts"

    private val failed = scala.collection.mutable.ListBuffer.empty[String]
    private def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if !cond then failed += (if detail.nonEmpty then s"$name  <- $detail" else name)

    def ingested(using Frame): Run =
        Run(
            id = "ingested", session = Session("s", "host", "25", 0.0), label = "l", sha = "abc", treeHash = "t",
            forks = 3, evidence = Evidence.Full, wholeClass = true, declaredRows = 15, markers = Chunk.empty,
            warmup = 10, jit_metrics = Maybe.empty,
            rows = BenchTest.rows(("suspensionBaseline", 88.0, 1.0, 640.0)),
            recordedAt = "now"
        )

    "attachJit fills a json-ingested run's inlining from a compilation log" in {
        for
            raw     <- (artifacts / "qa-logc.xml").read
            withJit <- Ingest.attachJit(ingested, raw, "qa-logc.xml")
            empty   <- Abort.run(Ingest.attachJit(ingested, "<hotspot_log></hotspot_log>", "empty.xml"))
        yield
            check("a json-ingested run starts with no inlining", ingested.jit.isEmpty)
            check("the log fills it with kyo. methods", withJit.jit.nonEmpty, s"${withJit.jit.size}")
            check("every attached method is a kyo. one", withJit.jit.forall(_.method.startsWith("kyo.")), withJit.jit.take(3).map(_.method).mkString(", "))
            check("the compilation metrics are attached", withJit.jit_metrics.isDefined, s"${withJit.jit_metrics}")
            check("with a task count that read the log", withJit.jit_metrics.exists(_.tasks > 0), s"${withJit.jit_metrics.map(_.tasks)}")
            check("and deopts and morphism come from the same parse", withJit.deopts.nonEmpty || withJit.morphism.nonEmpty)
            check("a log with no kyo inlining is refused rather than yielding an empty jit", empty.isFailure, empty.toString)
            assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
    }

end IngestTest
