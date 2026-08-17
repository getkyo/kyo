import Model.*
import kyo.*

/** Turns a JMH json produced outside the harness into a stored `Run`.
  *
  * This exists because its absence caused the worst failure of the campaign. Every comparison the
  * harness can make requires a `Run`, and a `Run` could only be built by `runLeg`. So a measurement
  * taken with a raw `sbt Jmh/run`, which is what every isolation experiment needs, had no path into
  * `Bench.compare` or `Report.render`. Faced with that, the operator computed the verdicts by hand in
  * python: deltas, thresholds, significance screens, all of it outside the tool built to prevent
  * exactly that. Four experiments produced conclusions the harness never saw.
  *
  * An ingested run is deliberately weaker than a measured leg, and says so:
  *
  *   - `evidence = Timing`, so the report states that no movement here is attributed
  *   - no markers, so it cannot prove which design produced it
  *   - `treeHash` records the file it came from rather than a tree, so it cannot claim a clean tree
  *
  * Those are not omissions to fix later. An ingested run genuinely does not know those things, and
  * the report must not imply otherwise.
  */
object Ingest:

    case class Failed(what: String) extends Exception(what) with scala.util.control.NoStackTrace

    /** Build a `Run` from a JMH json document.
      *
      * `declaredRows` is what the benchmark class declares. A json holding fewer rows than that is a
      * subset run, and the report already refuses to make a suite-wide claim from one; getting this
      * wrong in the permissive direction is how a four-row subset once passed as a clean suite.
      */
    def run(
        raw: String,
        label: String,
        sha: String,
        session: Session,
        source: String,
        declaredRows: Int
    )(using Frame): Run < Bench.Fail =
        Bench.parseJmh(raw).map { rows =>
            if rows.isEmpty then Abort.fail(Bench.BracketFailed(s"$source holds no benchmark rows"))
            else
                Run(
                    id = s"$label-${sha.take(10)}-${source.hashCode.toHexString}",
                    session = session,
                    label = label,
                    sha = sha,
                    // not a tree hash: this run was not produced from a tree this harness controlled,
                    // and pretending otherwise would let it claim a guard it never passed
                    treeHash = s"ingested:$source",
                    forks = 1,
                    evidence = Evidence.Timing,
                    wholeClass = rows.size >= declaredRows,
                    declaredRows = declaredRows,
                    markers = Chunk.empty,
                    warmup = Bench.WarmupIterations,
                    jit_metrics = Maybe.empty,
                    rows = rows,
                    jit = Chunk.empty,
                    coverage = Chunk.empty,
                    alloc = Chunk.empty, allocByMethod = Chunk.empty,
                    cpu = Chunk.empty,
                    deopts = Chunk.empty,
                    morphism = Chunk.empty,
                    recordedAt = source
                )
        }

    /** Reads a json file and stores the resulting run. */
    def file(
        path: Path,
        label: String,
        sha: String,
        session: Session,
        store: Path,
        declaredRows: Int
    )(using Frame): Run < (Async & Bench.Fail & Abort[FileWriteException]) =
        path.read.map { raw =>
            run(raw, label, sha, session, path.name.getOrElse(path.toString), declaredRows).map { r =>
                Store.save(store, r).andThen(r)
            }
        }

end Ingest
