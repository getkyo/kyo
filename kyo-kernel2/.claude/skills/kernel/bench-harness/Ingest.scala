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
  *
  * What it does know it reads from the json: JMH writes `forks` and `warmupIterations` on every
  * entry, and an ingested run used to ignore both and call itself `-f 1` with the harness's default
  * warmup, so a `-f 3` measurement was reported under a header describing a different one.
  */
object Ingest:

    case class Failed(what: String) extends Exception(what) with scala.util.control.NoStackTrace

    private def build(
        rows: Chunk[Row],
        id: String,
        label: String,
        sha: String,
        session: Session,
        source: String,
        declaredRows: Int,
        forks: Int,
        warmup: Int
    ): Run =
        Run(
            id = id,
            session = session,
            label = label,
            sha = sha,
            // not a tree hash: this run was not produced from a tree this harness controlled,
            // and pretending otherwise would let it claim a guard it never passed
            treeHash = s"ingested:$source",
            forks = forks,
            evidence = Evidence.Timing,
            wholeClass = rows.size >= declaredRows,
            declaredRows = declaredRows,
            markers = Chunk.empty,
            warmup = warmup,
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
        Bench.parseJmhEntries(raw).map { entries =>
            if entries.isEmpty then Abort.fail(Bench.BracketFailed(s"$source holds no benchmark rows"))
            else
                build(
                    rows = entries.map(Bench.entryRow),
                    id = s"$label-${sha.take(10)}-${source.hashCode.toHexString}",
                    label = label,
                    sha = sha,
                    session = session,
                    source = source,
                    declaredRows = declaredRows,
                    forks = entries.flatMap(_.forks).maxOption.getOrElse(1),
                    warmup = entries.headMaybe.flatMap(_.warmupIterations).getOrElse(Bench.WarmupIterations)
                )
        }

    /** One `Run` per fork of a json measured at `-f N`.
      *
      * A fork is an independent JVM, which is what a leg is: the harness's own bracket runs its legs
      * at `-f 1` and replicates legs instead of forks. Read as one leg with `forks = N`, a `-f N` json
      * hides the one quantity a threshold rests on, the spread between independent JVMs, and lets the
      * steady-state check inspect only the first JVM's start. Split, each fork's rows carry that
      * fork's iterations, their mean, an error at JMH's confidence over them, and that fork's own
      * secondaries, so `compareReplicated` and `unsettledStart` see what was measured. Forks of one
      * row run back to back rather than interleaved with the other arm, so drift across the run stays
      * assumed, and the report says so.
      */
    def perFork(
        raw: String,
        label: String,
        sha: String,
        session: Session,
        source: String,
        declaredRows: Int
    )(using Frame): Chunk[Run] < Bench.Fail =
        Bench.parseJmhEntries(raw).map { entries =>
            if entries.isEmpty then Abort.fail(Bench.BracketFailed(s"$source holds no benchmark rows"))
            else
                val forks = entries.map(_.primaryMetric.rawData.size).min
                if forks < 1 then Abort.fail(Bench.BracketFailed(s"$source carries no per-fork data to split"))
                else
                    val warmup = entries.headMaybe.flatMap(_.warmupIterations).getOrElse(Bench.WarmupIterations)
                    Chunk.from(0 until forks).map { k =>
                        build(
                            rows = entries.map(Bench.forkRow(_, k)),
                            id = s"$label-f${k + 1}-${sha.take(10)}-${source.hashCode.toHexString}",
                            label = s"$label-f${k + 1}",
                            sha = sha,
                            session = session,
                            source = source,
                            declaredRows = declaredRows,
                            forks = 1,
                            warmup = warmup
                        )
                    }
                end if
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

    /** Reads a json file, splits it per fork, and stores every leg. */
    def filePerFork(
        path: Path,
        label: String,
        sha: String,
        session: Session,
        store: Path,
        declaredRows: Int
    )(using Frame): Chunk[Run] < (Async & Bench.Fail & Abort[FileWriteException]) =
        path.read.map { raw =>
            perFork(raw, label, sha, session, path.name.getOrElse(path.toString), declaredRows).map { runs =>
                Kyo.foreachDiscard(runs)(r => Store.save(store, r).unit).andThen(runs)
            }
        }

end Ingest
