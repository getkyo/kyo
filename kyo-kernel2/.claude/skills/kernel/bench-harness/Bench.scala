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

    /** Fallback when a session did not measure its own spread. Sessions should always measure it. */
    val DriftBand = 4.0

    /** A CPU site attributed fewer samples than this cannot support a percentage. */
    val MinCpuSamples = 30

    /** Classes the benchmark spends time in that no kernel change can move. */
    val KnownNoise = Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")

    /** JIT refusals that are inherent rather than actionable: a 0-byte abstract method at a site that sees every arrow kind is
      * megamorphic by construction, and chasing it wastes a session.
      */
    def actionableJit(e: JitEntry): Boolean =
        !e.inlined &&
            // megamorphic by construction: the site sees every arrow kind, and chasing it wastes a session
            !e.reason.contains("no static binding") && !e.reason.contains("virtual call") &&
            // a warmup artifact rather than a decision: the class simply was not linked yet when the
            // compiler first looked, and the same method inlines fine once it is
            !e.reason.contains("not linked") && !e.reason.contains("never executed")

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
        // comparing git dirs only catches the repository's primary worktree, and the tree that
        // matters is whichever one work happens in, which is always on a branch. A bracket
        // worktree is created detached, so that is the property to require.
        exec(worktree, "git", "rev-parse", "--abbrev-ref", "HEAD").map(_.trim).map { head =>
            Abort.when(head != "HEAD")(
                BracketFailed(
                    s"$worktree is on branch '$head'; brackets need a detached throwaway worktree (git worktree add --detach <path> <sha>)"
                )
            )
        }

    /** Discards whatever a previous bracket left behind, so a session starts from a defined tree.
      *
      * A throwaway worktree is expected to be dirty after use, since every leg restores sources over it. Requiring it to be pristine would
      * refuse the normal case; the tree that must never be scribbled on is the one work happens in, and the detached-HEAD guard already
      * excludes that one.
      */
    def resetWorktree(worktree: Path, paths: Seq[String])(using Frame): Unit < (Async & Fail) =
        exec(worktree, (Seq("git", "restore", "--worktree", "--") ++ paths)*).unit

    /** Asserts a tree carries no uncommitted changes. Used as the post-condition of a reset rather than as an entry requirement. */
    def requireClean(worktree: Path)(using Frame): Unit < (Async & Fail) =
        exec(worktree, "git", "status", "--porcelain").map { out =>
            val dirty = out.linesIterator.filterNot(_.startsWith("??")).toSeq
            Abort.when(dirty.nonEmpty)(BracketFailed(s"$worktree has uncommitted changes; commit before measuring:\n${dirty.mkString("\n")}"))
        }

    /** Hash of the measured sources, so an edit during a run invalidates the leg instead of silently changing what was measured. */
    def treeHash(worktree: Path, paths: Seq[String])(using Frame): String < (Async & Fail) =
        // `ls-files -s` reads the index, so an unstaged edit leaves it unchanged and the guard
        // goes blind to the case it exists for. Diffing the working tree against HEAD sees
        // every modification, staged or not.
        for
            tracked  <- exec(worktree, (Seq("git", "ls-files", "-s", "--") ++ paths)*)
            modified <- exec(worktree, (Seq("git", "diff", "HEAD", "--") ++ paths)*)
        yield (tracked + modified).hashCode.toHexString

    /** Measures this machine's run-to-run spread by repeating one row on an unchanged tree. Classifying against a measured floor is the
      * difference between a verdict and a guess.
      */
    def measureDrift(worktree: Path, row: String, samples: Int = 3)(using Frame): Double < (Async & Fail) =
        val json = worktree / "bench-drift.json"
        def once =
            exec(worktree, "sbt", "--client", s"kyo-kernel2JVM/Jmh/run -f 1 -rf json -rff ${json.toString} $BenchClass.$row")
                .andThen(json.read).map(parseJmh).map(_.head)
        Kyo.foreach(Chunk.from(1 to samples))(_ => once).map { rs =>
            val scores = rs.map(_.score)
            val spread = (scores.max - scores.min) / scores.min * 100
            // two lucky-quiet runs can report a spread far below the real noise floor, and a band
            // tighter than the measurement's own error would classify its own uncertainty as a
            // result. JMH's scoreError is that uncertainty, so the band never goes under it.
            val ownError = rs.map(r => r.error / r.score * 100).max
            Math.max(spread, ownError)
        }
    end measureDrift

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

    def openSession(worktree: Path, driftRow: String)(using Frame): Session < (Async & Fail) =
        for
            _    <- requireThrowaway(worktree)
            // start from a defined tree: a previous bracket leaves its last leg's sources in place
            _    <- resetWorktree(worktree, Cli.protoPaths)
            _    <- requireClean(worktree)
            host <- exec(worktree, "hostname").map(_.trim)
            jvm  <- System.property[String]("java.version", "unknown")
            // measured, never assumed: this is the floor every verdict in the session is read against
            drift <- measureDrift(worktree, driftRow)
            now   <- Clock.now
        yield Session(s"s-${now.toDuration.toMillis}", host, jvm, drift)

    def runLeg(
        session: Session,
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

        val logcFile = worktree / s"logc-$label.xml"

        /** The compilation log carries what PrintInlining cannot: deoptimizations and measured receiver profiles. */
        def compilationLog =
            sbt(
                s"""kyo-kernel2JVM/Jmh/run -f 1 -wi 3 -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=${logcFile.toString}" $selector"""
            ).andThen(logcFile.read).map(LogCompilation.parse)

        for
            _        <- requireThrowaway(worktree)
            _        <- restore(worktree, sha, paths)
            declared <- declaredRows(worktree)
            // a faster variant whose suite is red is not a result; gate before spending the runs.
            // this also settles the sources: the build formats on compile, so the baseline below
            // must be taken after it or the leg invalidates itself on its own formatting
            _          <- sbt("kyo-kernel2JVM/testOnly kyo.kernel.proto.*")
            before     <- readMarkers(worktree, markerSpecs)
            hashBefore <- treeHash(worktree, paths)
            measured  <- measure(1, declared)
            jitEntries <- if evidence == Evidence.Full then jitLog.map(parseJit) else Chunk.empty[JitEntry]: Chunk[JitEntry] < Any
            allocSites <- if evidence == Evidence.Full then profile("alloc").map(parseAlloc) else Chunk.empty[AllocSite]: Chunk[AllocSite] < Any
            cpuSites   <- if evidence == Evidence.Full then profile("itimer").map(parseCpu) else Chunk.empty[CpuSite]: Chunk[CpuSite] < Any
            logc       <- if evidence == Evidence.Full then compilationLog else Chunk.empty[LogCompilation.Task]: Chunk[LogCompilation.Task] < Any
            after      <- readMarkers(worktree, markerSpecs)
            _          <- Abort.when(before != after)(BracketFailed(s"leg $label markers moved mid-run: $before -> $after"))
            hashAfter  <- treeHash(worktree, paths)
            // sources must not be edited while a run is in flight; if they were, the numbers describe no single tree
            _   <- Abort.when(hashBefore != hashAfter)(BracketFailed(s"leg $label sources changed mid-run"))
            now <- Clock.now
        yield Run(
            id = s"$label-${sha.take(10)}-${now.toDuration.toMillis}",
            session = session,
            treeHash = hashAfter,
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
            deopts = LogCompilation.deoptSummary(logc),
            morphism = LogCompilation.morphism(logc),
            recordedAt = now.show
        )
        end for
    end runLeg

    // --- comparison (pure, over stored runs) ------------------------------------

    def band(control: Run, variant: Run): Double =
        val measured = Math.max(control.session.driftPercent, variant.session.driftPercent)
        if measured > 0.0 then measured else DriftBand

    /** Fraction of a run's sampled time in classes no kernel change can move. */
    def noiseShare(run: Run): Double =
        val total = run.cpu.map(_.nanos).sum
        if total == 0L then 0.0
        else run.cpu.filter(c => KnownNoise.exists(c.method.contains)).map(_.nanos).sum.toDouble / total * 100

    def sameSession(control: Run, variant: Run): Boolean =
        control.session.id == variant.session.id

    def compare(control: Run, variant: Run): Comparison =
        val deltas = Chunk.from(control.rows).flatMap { c =>
            Chunk.from(variant.row(c.name)).map { v =>
                val percent = (v.score - c.score) / c.score * 100
                val drift = band(control, variant)
                val verdict =
                    // a score whose error is a large fraction of itself cannot support a percentage
                    if c.error > c.score * 0.5 || c.score <= 0.0 then Verdict.BelowResolution
                    else if percent < -drift then Verdict.Faster
                    else if percent > drift then Verdict.Regressed
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
        Chunk.from(variant.jit.filter(e => e.method.startsWith("kyo.") && (e.inlined || actionableJit(e)))).flatMap { v =>
            control.jitFor(v.method) match
                // only a verdict flip is a mechanism. A byte count that moved while the decision
                // stayed the same changed nothing the CPU can see, and reporting it attributes a
                // movement to something that did not happen
                case Maybe.Present(c) if c.inlined != v.inlined =>
                    Chunk(s"${v.method}: ${c.bytes}B ${if c.inlined then "inlined" else "refused"} -> ${v.bytes}B ${if v.inlined then "inlined" else "refused"}")
                case _ => Chunk.empty
        }

end Bench
