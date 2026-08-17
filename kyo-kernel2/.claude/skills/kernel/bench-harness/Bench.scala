import Model.*
import kyo.*
import scala.util.control.NoStackTrace

/** Runs one leg of a bracket, collecting the whole evidence ladder, and compares stored runs.
  *
  * The guards exist because their absence corrupted or wasted a real measurement:
  *
  *   - the bracket refuses to run in the tree you commit from
  *   - designs flip with `git restore --worktree`, which never writes the index (`git checkout <sha> -- <paths>` stages what it restores, so
  *     an interrupted bracket leaks the comparison design into the next commit)
  *   - markers are read before and after each leg, so a number carries proof of which design produced it
  *   - the suite must be green before a leg is measured at all
  *   - a leg's row count is checked against the benchmark class, retried, then failed; never reported as clean
  *   - a verdict about the whole class is unreachable from a subset run
  */
object Bench:

    /** Observed run-to-run spread on this machine. A delta inside it is not a result. */
    val DriftBand = 4.0

    val BenchClass  = "kyo.kernel.bench.ProtoKernelBench"
    val BenchSource = "kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/ProtoKernelBench.scala"
    val AsyncProf   = "/opt/homebrew/opt/async-profiler/lib/libasyncProfiler.dylib"

    case class BracketFailed(reason: String) extends Exception(reason) with NoStackTrace

    type Fail = Abort[BracketFailed | CommandException | FileReadException]

    def exec(worktree: Path, command: String*)(using Frame): String < (Async & Fail) =
        Command(command*).cwd(worktree).redirectErrorStream(true).textWithExitCode.map { (out, exit) =>
            if exit == ExitCode.Success then out
            else Abort.fail(BracketFailed(s"$exit from ${command.mkString(" ")}\n$out"))
        }

    /** Refuses a worktree that is the repository's primary one. A bracket writes over sources; doing that in the tree commits come from is
      * how uncommitted work and a whole redesign were destroyed.
      */
    def requireThrowaway(worktree: Path)(using Frame): Unit < (Async & Fail) =
        for
            gitDir    <- exec(worktree, "git", "rev-parse", "--absolute-git-dir").map(_.trim)
            commonDir <- exec(worktree, "git", "rev-parse", "--path-format=absolute", "--git-common-dir").map(_.trim)
            _ <- Abort.when(gitDir == commonDir)(
                BracketFailed(s"$worktree is the primary worktree; brackets need a throwaway one (git worktree add --detach)")
            )
        yield ()

    /** Working-tree-only restore. Never `checkout`, which would also stage. */
    def restore(worktree: Path, sha: String, paths: Seq[String])(using Frame): Unit < (Async & Fail) =
        exec(worktree, (Seq("git", "restore", s"--source=$sha", "--worktree", "--") ++ paths)*).unit

    def readMarkers(worktree: Path, markers: Seq[(String, String, String)])(using Frame): Chunk[Marker] < (Sync & Fail) =
        Kyo.foreach(Chunk.from(markers)) { (name, pattern, path) =>
            val file = worktree / path
            file.exists.map {
                case false => Marker(name, 0)
                case true  => file.readLines.map(ls => Marker(name, ls.count(_.contains(pattern))))
            }
        }

    /** Counts benchmarks by bare `@Benchmark` lines. Matching the substring also matches `@BenchmarkMode`, which inflates the expectation
      * and turns a complete run into a reported failure.
      */
    def declaredRows(worktree: Path)(using Frame): Int < (Sync & Fail) =
        (worktree / BenchSource).readLines.map(_.count(_.trim == "@Benchmark"))

    // --- JMH json ---------------------------------------------------------------

    case class JmhScore(score: Double, scoreError: Double, scoreUnit: String, rawData: Chunk[Chunk[Double]]) derives Schema
    case class JmhSecondary(score: Double) derives Schema
    case class JmhEntry(
        benchmark: String,
        mode: String,
        primaryMetric: JmhScore,
        secondaryMetrics: Map[String, JmhSecondary]
    ) derives Schema

    def parseJmh(raw: String)(using Frame): Chunk[Row] < Fail =
        Json.decode[Chunk[JmhEntry]](raw) match
            case Result.Failure(e) => Abort.fail(BracketFailed(s"could not read JMH json: $e"))
            case Result.Panic(e)   => Abort.fail(BracketFailed(s"could not read JMH json: $e"))
            case Result.Success(entries) =>
                entries.map { e =>
                Row(
                    name = e.benchmark.split('.').last,
                    mode = e.mode,
                    count = e.primaryMetric.rawData.map(_.size).sum,
                    score = e.primaryMetric.primaryScore,
                    error = e.primaryMetric.safeError,
                    unit = e.primaryMetric.scoreUnit,
                    allocPerOp = Maybe.fromOption(e.secondaryMetrics.get("gc.alloc.rate.norm").map(_.score))
                )
            }

    extension (s: JmhScore)
        def primaryScore: Double = s.score
        def safeError: Double    = if s.scoreError.isNaN then 0.0 else s.scoreError

    // --- evidence parsers -------------------------------------------------------

    private val JitLine = """([\w.$]+::[\w$]+) \((\d+) bytes\).*?(inline \(hot\)|inline|failed to inline: [\w' ]+)""".r

    def parseJit(raw: String): Chunk[JitEntry] =
        val found = JitLine.findAllMatchIn(raw).map { m =>
            val verdict = m.group(3)
            JitEntry(m.group(1), m.group(2).toInt, verdict.startsWith("inline"), verdict)
        }
        // keep the worst verdict per method: a callee refused anywhere on a hot path is the fact that matters
        Chunk.from(found.toSeq.groupBy(_.method).values.map(es => es.find(!_.inlined).getOrElse(es.head)))
    end parseJit

    private val ProfLine = """\s*(\d+)\s+[\d.]+%\s+\d+\s+([\w.$/<>]+)""".r

    def parseAlloc(raw: String): Chunk[AllocSite] =
        Chunk.from(ProfLine.findAllMatchIn(raw).map(m => AllocSite(m.group(2), m.group(1).toLong)).toSeq)

    def parseCpu(raw: String): Chunk[CpuSite] =
        Chunk.from(ProfLine.findAllMatchIn(raw).map(m => CpuSite(m.group(2), m.group(1).toLong)).toSeq)

    // --- one leg ----------------------------------------------------------------

    def runLeg(
        worktree: Path,
        label: String,
        sha: String,
        paths: Seq[String],
        markerSpecs: Seq[(String, String, String)],
        rows: Seq[String],
        forks: Int,
        evidence: Evidence,
        attempts: Int = 3
    )(using Frame): Run < (Async & Fail) =
        val selector   = if rows.isEmpty then s"$BenchClass.*" else rows.map(r => s"$BenchClass.$r").mkString(" ")
        val wholeClass = rows.isEmpty
        val json       = worktree / s"bench-$label.json"

        def sbt(task: String) = exec(worktree, "sbt", "--client", task)

        def measure(n: Int, expected: Int): Chunk[Row] < (Async & Fail) =
            sbt(s"kyo-kernel2JVM/Jmh/run -f $forks -prof gc -rf json -rff ${json.toString} $selector")
                .andThen(json.read)
                .map(parseJmh)
                .map { parsed =>
                    val want = if wholeClass then expected else rows.size
                    if parsed.size == want then parsed
                    // the first invocation after a recompile can match nothing; a short read is a
                    // failed run, never a clean one
                    else if n < attempts then measure(n + 1, expected)
                    else Abort.fail(BracketFailed(s"leg $label produced ${parsed.size}/$want rows after $attempts attempts"))
                }

        def profile(event: String) =
            sbt(s"""kyo-kernel2JVM/Jmh/run -f 1 -prof "async:libPath=$AsyncProf;event=$event" $selector""")

        def jitLog =
            sbt(
                s"""kyo-kernel2JVM/Jmh/run -f 1 -wi 5 -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining" $selector"""
            )

        for
            _        <- requireThrowaway(worktree)
            _        <- restore(worktree, sha, paths)
            declared <- declaredRows(worktree)
            before   <- readMarkers(worktree, markerSpecs)
            // a faster variant whose suite is red is not a result; gate before spending the runs
            _         <- sbt("kyo-kernel2JVM/testOnly kyo.kernel.proto.*")
            measured  <- measure(1, declared)
            jitEntries <- if evidence == Evidence.Full then jitLog.map(parseJit) else Chunk.empty[JitEntry]: Chunk[JitEntry] < Any
            allocSites <- if evidence == Evidence.Full then profile("alloc").map(parseAlloc) else Chunk.empty[AllocSite]: Chunk[AllocSite] < Any
            cpuSites   <- if evidence == Evidence.Full then profile("itimer").map(parseCpu) else Chunk.empty[CpuSite]: Chunk[CpuSite] < Any
            after      <- readMarkers(worktree, markerSpecs)
            _          <- Abort.when(before != after)(BracketFailed(s"leg $label markers moved mid-run: $before -> $after"))
            now        <- Clock.now
        yield Run(
            id = s"$label-${sha.take(10)}-${now.toDuration.toMillis}",
            label = label,
            sha = sha,
            forks = forks,
            evidence = evidence,
            wholeClass = wholeClass,
            declaredRows = declared,
            markers = before,
            rows = measured,
            jit = jitEntries,
            alloc = allocSites,
            cpu = cpuSites,
            recordedAt = now.show
        )
        end for
    end runLeg

    // --- comparison (pure, over stored runs) ------------------------------------

    def compare(control: Run, variant: Run): Comparison =
        val deltas = Chunk.from(control.rows).flatMap { c =>
            Chunk.from(variant.row(c.name)).map { v =>
                val percent = (v.score - c.score) / c.score * 100
                val verdict =
                    // a score whose error is a large fraction of itself cannot support a percentage
                    if c.error > c.score * 0.5 || c.score <= 0.0 then Verdict.BelowResolution
                    else if percent < -DriftBand then Verdict.Faster
                    else if percent > DriftBand then Verdict.Regressed
                    else Verdict.Flat
                val allocDelta =
                    for
                        ca <- c.allocPerOp
                        va <- v.allocPerOp
                    yield va - ca
                val mechanism = Chunk.from(Seq(
                    allocDelta.filter(d => Math.abs(d) > 1.0).map(d => f"allocation ${d}%+.0f B/op"),
                    jitShift(control, variant).headMaybe.map(m => s"inlining changed: $m")
                ).flatMap(_.toOption))
                Delta(c.name, c, v, percent, verdict, allocDelta, mechanism)
            }
        }.sortBy(d => (d.verdict == Verdict.BelowResolution, d.percent))
        Comparison(control, variant, deltas, jitShift(control, variant))
    end compare

    /** Methods whose size or inlining verdict moved between the two runs. This diff is what named the mechanism the timing could not. */
    def jitShift(control: Run, variant: Run): Chunk[String] =
        Chunk.from(variant.jit).flatMap { v =>
            control.jitFor(v.method) match
                case Maybe.Present(c) if c.bytes != v.bytes || c.inlined != v.inlined =>
                    Chunk(s"${v.method}: ${c.bytes}B ${if c.inlined then "inlined" else "refused"} -> ${v.bytes}B ${if v.inlined then "inlined" else "refused"}")
                case _ => Chunk.empty
        }

end Bench
