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

    /** JIT refusals that are inherent rather than actionable.
      *
      * The reason vocabulary is the compilation log's, verified against a capture: `callee is too large` 1166, `no static binding` 205,
      * `callee uses too much stack` 173, `not inlineable` 121, `callee's klass not linked yet` 60, `low call site frequency` 19,
      * `already compiled into a big method` 6, `hot method too big` 5. The strings this filtered on before came from `PrintInlining`'s vocabulary:
      * "never executed" matches nothing the log emits, and "virtual call" appears twice in C2, so neither was doing useful work.
      */
    def actionableJit(v: InlineSites): Boolean =
        v.refused > 0 &&
            // megamorphic by construction: the site sees every arrow kind, and chasing it wastes a session
            !v.reasons.exists(_.contains("no static binding")) &&
            // a warmup artifact rather than a decision: the class simply was not linked yet when the
            // compiler first looked, and the same method inlines fine once it is
            !v.reasons.exists(_.contains("klass not linked"))

    /** One warmup configuration for every step of a leg.
      *
      * The measurement and the evidence runs must reach the same compilation state, or a timing from a well-warmed JVM gets attributed to
      * inlining decisions captured from a colder one. Ten warmup iterations rather than five because `-prof comp` showed compilation still
      * running into the first measured iterations at five.
      */
    val WarmupIterations  = 10
    val MeasureIterations = 5
    val IterationSeconds  = 1

    /** Above this share of the measured window spent compiling, the run had not settled and its score is not a steady-state figure. */
    val CompilingShareLimit = 1.0

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

    /** Measures this machine's run-to-run spread by repeating one row on an unchanged tree.
      *
      * No longer on the session path: a bracket estimates its spread from its own replicate legs, on the same tree it measures. This remains
      * for a single-pair comparison, which cannot replicate and therefore has nothing better to classify against.
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
                    allocPerOp = Maybe.fromOption(e.secondaryMetrics.get("gc.alloc.rate.norm").map(_.score)),
                    compilerMsProfiled = Maybe.fromOption(e.secondaryMetrics.get("compiler.time.profiled").map(_.score)),
                    compilerMsTotal = Maybe.fromOption(e.secondaryMetrics.get("compiler.time.total").map(_.score))
                )
            }

    extension (s: JmhScore)
        def primaryScore: Double = s.score
        def safeError: Double    = if s.scoreError.isNaN then 0.0 else s.scoreError

    // --- evidence parsers -------------------------------------------------------

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
            now  <- Clock.now
        // no drift measurement here any more. A bracket estimates its spread from its own replicate
        // legs, which is both a better estimate and one taken on the same tree as the measurement;
        // the old session drift was three extra runs on HEAD sources belonging to neither leg, and
        // keeping both left two competing noise estimates with the report using the worse one.
        yield Session(s"s-${now.toDuration.toMillis}", host, jvm, 0.0)

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
        /** Extra JVM arguments for this leg. Without these a configuration probe is inexpressible and
          * every falsifier that is a JVM flag has to be run by hand, which is how the two experiments
          * that diagnosed a live regression were actually run: by a shell script the harness knew
          * nothing about.
          */
        jvmArgs: Seq[String] = Nil,
        attempts: Int = 3
    )(using Frame): Run < (Async & Fail) =
        val selector   = if rows.isEmpty then s"$BenchClass.*" else rows.map(r => s"$BenchClass.$r").mkString(" ")
        val wholeClass = rows.isEmpty
        val json       = worktree / s"bench-$label.json"

        def sbt(task: String) = exec(worktree, "sbt", "--client", task)

        // pinned on every leg: these rows allocate megabytes per operation, so ergonomic
        // heap sizing is a live variance source that costs one flag to remove
        val extraVm = (Seq("-Xms4g", "-Xmx4g", "-XX:+UseG1GC") ++ jvmArgs).mkString(" ")

        def measureWith(warmup: Int, n: Int, expected: Int): Chunk[Row] < (Async & Fail) =
            sbt(
                s"kyo-kernel2JVM/Jmh/run -f $forks -wi $warmup -i $MeasureIterations -r ${IterationSeconds}s " +
                    s"-w ${IterationSeconds}s -prof gc -prof comp -rf json -rff ${json.toString} " +
                    "-jvmArgsAppend \"" + extraVm + "\" " + selector
            )
                .andThen(json.read)
                .map(parseJmh)
                .map { parsed =>
                    val want = if wholeClass then expected else rows.size
                    if parsed.size == want then parsed
                    // the first invocation after a recompile can match nothing; a short read is a
                    // failed run, never a clean one
                    else if n < attempts then measureWith(warmup, n + 1, expected)
                    else Abort.fail(BracketFailed(s"leg $label produced ${parsed.size}/$want rows after $attempts attempts"))
                }

        def profile(event: String) =
            sbt(
                s"""kyo-kernel2JVM/Jmh/run -f 1 -wi $WarmupIterations -i 1 -r ${IterationSeconds}s -w ${IterationSeconds}s """ +
                    s"""-prof "async:libPath=$AsyncProf;event=$event" $selector"""
            )

        val logcFile = worktree / s"logc-$label.xml"

        /** The only source of inlining decisions.
          *
          * The `PrintInlining` run this replaces cost a whole JMH invocation to produce strictly less: no denominator per method, no
          * deoptimizations, no receiver profiles, and output interleaved across compiler threads so its tree could not be trusted. One run
          * removed from every leg.
          */
        def compilationLog =
            sbt(
                s"""kyo-kernel2JVM/Jmh/run -f 1 -wi $WarmupIterations -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=${logcFile.toString}" $selector"""
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
            measured   <- measureWith(WarmupIterations, 1, declared)
            allocSites <- if evidence == Evidence.Full then profile("alloc").map(parseAlloc) else Chunk.empty[AllocSite]: Chunk[AllocSite] < Any
            cpuSites   <- if evidence == Evidence.Full then profile("itimer").map(parseCpu) else Chunk.empty[CpuSite]: Chunk[CpuSite] < Any
            logc       <- if evidence == Evidence.Full then compilationLog else LogCompilation.Parsed(Chunk.empty, Chunk.empty, 0, Chunk.empty): LogCompilation.Parsed < Any
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
            warmup = WarmupIterations,
            rows = measured,
            jit = LogCompilation.inlining(logc),
            coverage = logc.coverage,
            alloc = allocSites,
            cpu = cpuSites,
            jit_metrics =
                if evidence == Evidence.Full then
                    Maybe(LogCompilation.metrics(
                        logc,
                        measured.flatMap(_.compilerMsProfiled).sum,
                        measured.flatMap(_.compilerMsTotal).sum
                    ))
                else Maybe.empty,
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

    /** Rows whose measured window still contained meaningful compilation.
      *
      * Deliberately not corrected by warming longer. A benchmark that needs unusual warmup is reporting something about the code under it,
      * bigger methods, recompilation churn, or an unstable profile, and warming past it discards the finding. The flag exists so the cause
      * gets diagnosed.
      */
    def stillCompiling(run: Run): Chunk[(String, Double)] =
        val measuredMs = run.forks * MeasureIterations * IterationSeconds * 1000.0
        Chunk.from(run.rows.flatMap { r =>
            r.compilingShare(measuredMs).filter(_ > CompilingShareLimit).map(share => r.name -> share)
        })

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
                // a row inside the band has not moved, so nothing explains it. Both stored e2e runs
                // printed a mechanism beside +0.8% and +0.3% deltas, which invites the reader to
                // believe a cause was found for a difference that is not there.
                val mechanism =
                    if verdict == Verdict.Flat || verdict == Verdict.BelowResolution then Chunk.empty
                    else
                        Chunk.from(Seq(
                            allocDelta.filter(d => Math.abs(d) > 1.0).map(d => f"allocation ${d}%+.0f B/op"),
                            jitShift(control, variant).headMaybe.map(m => s"inlining changed: $m")
                        ).flatMap(_.toOption))
                Delta(c.name, c, v, percent, verdict, allocDelta, mechanism)
            }
        }.sortBy(d => (d.verdict == Verdict.BelowResolution, d.percent))
        Comparison(control, variant, deltas, jitShift(control, variant))
    end compare

    /** Family-wise error rate a session is willing to accept across all its rows. */
    val FamilyAlpha = 0.05

    /** Which design each leg of a bracket measures, in order.
      *
      * Control, variant, control, variant, control. Interleaved because measuring all the controls
      * first and all the variants second confounds the design with everything that drifts over the
      * session: the variant would always run on a hotter machine, and that bias points the same way
      * every time. Replicated because a threshold needs a spread estimated from more than one pair,
      * and three controls against two variants gives three degrees of freedom.
      *
      * Pure and separately testable: the ordering is the part worth checking, and checking it should
      * not cost a half-hour run.
      */
    def bracketPlan(controlSha: String, variantSha: String, legs: Int = 5): Chunk[(String, String)] =
        Chunk.from(
            (0 until Math.max(2, legs)).map { i =>
                if i % 2 == 0 then (s"control-${i / 2 + 1}", controlSha)
                else (s"variant-${i / 2 + 1}", variantSha)
            }
        )

    /** One bracket: every leg of `bracketPlan`, measured in order, split into controls and variants.
      *
      * The legs are run through the same `runLeg` as a single measurement, so every guard applies to
      * each of them: the suite must be green, markers must not move, sources must not change mid-run.
      */
    def bracket(
        session: Session,
        worktree: Path,
        controlSha: String,
        variantSha: String,
        paths: Seq[String],
        markerSpecs: Seq[(String, String, String)],
        rows: Seq[String],
        forks: Int,
        evidence: Evidence,
        legs: Int = 5
    )(using Frame): (Chunk[Run], Chunk[Run]) < (Async & Fail) =
        Kyo.foreach(bracketPlan(controlSha, variantSha, legs)) { (label, sha) =>
            runLeg(session, worktree, label, sha, paths, markerSpecs, rows, forks, evidence)
        }.map { legs =>
            (Chunk.from(legs.filter(_.label.startsWith("control"))), Chunk.from(legs.filter(_.label.startsWith("variant"))))
        }

    /** Compares replicate legs, which is the only shape that can support a threshold.
      *
      * A session measures control, variant, control, variant, control. Pooling the spread across those replicates is what makes a verdict
      * answerable for its own uncertainty; the single-pair `compare` above can classify against a drift band but cannot say how small an
      * effect it would have caught, so every flat row it produces is unbounded.
      */
    def compareReplicated(controls: Chunk[Run], variants: Chunk[Run]): Comparison =
        val rowNames = Chunk.from(controls.headMaybe.map(_.rows.map(_.name)).getOrElse(Chunk.empty))
        val reps =
            rowNames.map { name =>
                Stats.Replicated(
                    name,
                    Chunk.from(controls.flatMap(_.row(name)).map(_.score)),
                    Chunk.from(variants.flatMap(_.row(name)).map(_.score)),
                    // each leg's own relative error, so the threshold can never sit below it
                    Chunk.from((controls ++ variants).flatMap(_.row(name)).map(r => if r.score == 0.0 then 0.0 else r.error / r.score))
                )
            }
        val common = Stats.commonMode(reps)
        val deltas =
            reps.flatMap { r =>
                for
                    c0 <- Chunk.from(controls.headMaybe.flatMap(_.row(r.row)))
                    v0 <- Chunk.from(variants.headMaybe.flatMap(_.row(r.row)))
                yield
                    // the rows carried on the delta are the replicate MEANS, not the first leg's
                    // scores. Showing leg one beside a mean-based percentage let the table read
                    // "6.17 -> 6.39 ... +0.0%", where a reader computing the displayed numbers gets
                    // +3.6% and the harness is reporting something else entirely.
                    val c = c0.copy(score = r.controlMean, error = r.pooledSd.getOrElse(c0.error))
                    val v = v0.copy(score = r.variantMean, error = r.pooledSd.getOrElse(v0.error))
                    val (verdict, resolution) = Stats.classify(r, FamilyAlpha, rowNames.size)
                    val allocDelta =
                        for
                            ca <- c.allocPerOp
                            va <- v.allocPerOp
                        yield va - ca
                    val mechanism =
                        if verdict == Verdict.Flat || verdict == Verdict.BelowResolution then Chunk.empty
                        else
                            Chunk.from(Seq(
                                allocDelta.filter(d => Math.abs(d) > 1.0).map(d => f"allocation ${d}%+.0f B/op"),
                                jitShift(controls.head, variants.head).headMaybe.map(m => s"inlining changed: $m")
                            ).flatMap(_.toOption))
                    Delta(r.row, c, v, r.deltaPercent, verdict, allocDelta, mechanism, resolution)
            }.sortBy(d => (d.verdict == Verdict.BelowResolution, d.percent))
        Comparison(controls.head, variants.head, deltas, jitShift(controls.head, variants.head))
    end compareReplicated

    /** The A/A null: control legs against each other, through the identical pipeline.
      *
      * Any row this classifies, and any mechanism it names, is false by construction. It is the only check here that can fail in the
      * direction that matters, since every other one asks the harness to stay silent and is therefore satisfied by silence.
      */
    def nullComparison(controls: Chunk[Run]): Maybe[Comparison] =
        // three control legs is the documented session (C V C V C), so requiring four made this
        // unreachable in the harness's own workflow: the one check that can fail in the direction
        // that matters never ran.
        if controls.size < 3 then Maybe.empty
        else
            // split by alternation, not by time. Contiguous halves group adjacent-in-time legs, which
            // understates leg-to-leg spread and puts any warm-up trend entirely in the numerator: over
            // six legs carrying a 3% monotone trend, contiguous halves report a regression of +1.79%
            // where alternating halves correctly report flat. The real comparison interleaves control
            // and variant legs, so the null has to interleave too or it is not measuring the same thing.
            val indexed = controls.zipWithIndex
            val a       = Chunk.from(indexed.filter((_, i) => i % 2 == 0).map((r, _) => r))
            val b       = Chunk.from(indexed.filter((_, i) => i % 2 == 1).map((r, _) => r))
            if a.isEmpty || b.isEmpty then Maybe.empty else Maybe(compareReplicated(a, b))

    /** Methods whose inlining verdict moved decisively between the two runs.
      *
      * Decisive means unanimous on both sides: every site inlined in one leg and every site refused in the other. Anything less is a
      * fraction that moved, and those move on their own. In a captured pair, 11 of 85 kyo methods carried both verdicts, and for two of them
      * a single site out of six decided the method's reported verdict. Two runs of one identical comparison named disjoint "mechanisms" that
      * way, one of them a 2-byte method "refused" for size. Only a byte count moving with a unanimous flip can be a mechanism, so only that
      * is reported as one.
      */
    def jitShift(control: Run, variant: Run): Chunk[String] =
        Chunk.from(variant.jit.filter(_.method.startsWith("kyo."))).flatMap { v =>
            control.jitFor(v.method) match
                case Maybe.Present(c) if c.alwaysInlined && v.alwaysRefused =>
                    Chunk(s"${v.method}: ${c.bytes}B inlined at all ${c.sites} sites -> ${v.bytes}B refused at all ${v.sites} (${v.reasons.headMaybe.getOrElse("no reason")})")
                case Maybe.Present(c) if c.alwaysRefused && v.alwaysInlined =>
                    Chunk(s"${v.method}: ${c.bytes}B refused at all ${c.sites} sites -> ${v.bytes}B inlined at all ${v.sites}")
                case _ => Chunk.empty
        }

    /** Methods whose site fractions moved without flipping decisively.
      *
      * Reported separately and never as a mechanism: this is the population that produced the irreproducible diffs, so naming it keeps it
      * visible without letting it explain a delta.
      */
    def jitUnstable(control: Run, variant: Run): Chunk[String] =
        Chunk.from(variant.jit.filter(_.method.startsWith("kyo."))).flatMap { v =>
            control.jitFor(v.method) match
                case Maybe.Present(c) if (c.refused != v.refused || c.inlined != v.inlined) && !(c.alwaysInlined && v.alwaysRefused) && !(c.alwaysRefused && v.alwaysInlined) =>
                    Chunk(s"${v.method}: refused ${c.refused}/${c.sites} -> ${v.refused}/${v.sites}")
                case _ => Chunk.empty
        }

end Bench
