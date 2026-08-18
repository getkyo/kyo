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
        warmup: Int,
        jvmArgs: Chunk[String],
        benchmarkClass: String
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
            recordedAt = source,
            jvmArgs = jvmArgs,
            benchmarkClass = benchmarkClass
        )

    /** The class every entry names, or the classes joined when a json holds more than one. */
    private def classOf(entries: Chunk[Bench.JmhEntry]): String =
        entries.map(_.benchmark.split('.').dropRight(1).mkString(".")).distinct.mkString(" + ")

    private def jvmArgsOf(entries: Chunk[Bench.JmhEntry]): Chunk[String] =
        entries.headMaybe.flatMap(_.jvmArgs).getOrElse(Chunk.empty)

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
        Bench.parseJmhEntries(raw).map(entries => runEntries(entries, label, sha, session, source, declaredRows))

    /** The same from the text log JMH printed while it ran; see `Bench.parseJmhLog` for why that is worth having. */
    def runLog(raw: String, label: String, sha: String, session: Session, source: String, declaredRows: Int)(using Frame): Run < Bench.Fail =
        runEntries(Bench.parseJmhLog(raw), label, sha, session, source, declaredRows)

    def runEntries(
        entries: Chunk[Bench.JmhEntry],
        label: String,
        sha: String,
        session: Session,
        source: String,
        declaredRows: Int
    )(using Frame): Run < Bench.Fail =
        {
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
                    warmup = entries.headMaybe.flatMap(_.warmupIterations).getOrElse(Bench.WarmupIterations),
                    jvmArgs = jvmArgsOf(entries),
                    benchmarkClass = classOf(entries)
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
        Bench.parseJmhEntries(raw).map(entries => perForkEntries(entries, label, sha, session, source, declaredRows))

    def perForkLog(raw: String, label: String, sha: String, session: Session, source: String, declaredRows: Int)(using Frame): Chunk[Run] < Bench.Fail =
        perForkEntries(Bench.parseJmhLog(raw), label, sha, session, source, declaredRows)

    def perForkEntries(
        entries: Chunk[Bench.JmhEntry],
        label: String,
        sha: String,
        session: Session,
        source: String,
        declaredRows: Int
    )(using Frame): Chunk[Run] < Bench.Fail =
        {
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
                            warmup = warmup,
                            jvmArgs = jvmArgsOf(entries),
                            benchmarkClass = classOf(entries)
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

    /** Attaches a JMH profiler log's per-benchmark CPU tables to a stored run's rows, and stores it back.
      *
      * The CPU pass is its own JMH invocation (`-prof async:event=itimer`, one iteration after warmup), so its timing is not a
      * measurement and only its profile is taken: each row of the run gets the flat table the profiler printed under that row's
      * `# Benchmark:` header, and the run's own `cpu` becomes their merge, which is what the run-level partition reads.
      */
    def attachCpu(run: Run, log: String, source: String)(using Frame): Run < Bench.Fail =
        val byRow = Bench.parseCpuByBenchmark(log)
        if byRow.isEmpty then Abort.fail(Bench.BracketFailed(s"$source holds no profiler tables under any '# Benchmark:' header"))
        else
            val missing = run.rows.map(_.name).filterNot(byRow.contains)
            if missing.nonEmpty then
                Abort.fail(Bench.BracketFailed(
                    s"$source has no profile for ${missing.size} of ${run.rows.size} rows of ${run.id}: ${missing.mkString(", ")}"
                ))
            else
                val rows = run.rows.map(r => r.copy(cpu = byRow(r.name)))
                run.copy(rows = rows, cpu = rows.flatMap(_.cpu))
        end if
    end attachCpu

    def attachCpuFile(store: Path, id: String, log: Path)(using Frame): Run < (Async & Bench.Fail & Abort[FileWriteException]) =
        for
            run <- Store.load(store, id)
            raw <- log.read
            out <- attachCpu(run, raw, log.name.getOrElse(log.toString))
            _   <- Store.save(store, out)
        yield out

    /** Attaches the inlining decisions and compilation metrics from a `-XX:+PrintCompilation`/LogCompilation log to a stored run.
      *
      * A JMH json carries timing and the gc profiler's numbers, never the inlining decisions: those come from the compilation log, a
      * separate artifact of the same fork. Without this, a run built by `ingest` from a json alone had an empty `jit`, so every inlining
      * verdict, the budget selectors, and `jitShift` had nothing to read. This is the compilation-log analogue of `attachCpu`, assembling
      * `jit`/`jit_metrics`/`deopts`/`morphism`/`coverage` exactly as `runLeg` does, with the compile-time window taken from the run's own
      * rows since the log does not carry it.
      */
    def attachJit(run: Run, log: String, source: String)(using Frame): Run < Bench.Fail =
        val parsed = LogCompilation.parse(log)
        val sites  = LogCompilation.inlining(parsed)
        if sites.isEmpty then
            Abort.fail(Bench.BracketFailed(
                s"$source holds no kyo. inlining decisions; a `-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` " +
                    "or LogCompilation log of the same fork is what carries them"
            ))
        else
            run.copy(
                jit = sites,
                jit_metrics = Maybe(LogCompilation.metrics(
                    parsed,
                    run.rows.flatMap(_.compilerMsProfiled).sum,
                    run.rows.flatMap(_.compilerMsTotal).sum
                )),
                deopts = LogCompilation.deoptSummary(parsed),
                morphism = LogCompilation.morphism(parsed),
                coverage = run.coverage ++ parsed.coverage
            )
        end if
    end attachJit

    def attachJitFile(store: Path, id: String, log: Path)(using Frame): Run < (Async & Bench.Fail & Abort[FileWriteException]) =
        for
            run <- Store.load(store, id)
            raw <- log.read
            out <- attachJit(run, raw, log.name.getOrElse(log.toString))
            _   <- Store.save(store, out)
        yield out

    /** Reads a JMH text log and stores the run it describes, whole or per fork. */
    def logFile(
        path: Path,
        label: String,
        sha: String,
        session: Session,
        store: Path,
        declaredRows: Int,
        perFork: Boolean
    )(using Frame): Chunk[Run] < (Async & Bench.Fail & Abort[FileWriteException]) =
        path.read.map { raw =>
            val source = path.name.getOrElse(path.toString)
            val runs =
                if perFork then perForkLog(raw, label, sha, session, source, declaredRows)
                else runLog(raw, label, sha, session, source, declaredRows).map(Chunk(_))
            runs.map(rs => Kyo.foreachDiscard(rs)(r => Store.save(store, r).unit).andThen(rs))
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
