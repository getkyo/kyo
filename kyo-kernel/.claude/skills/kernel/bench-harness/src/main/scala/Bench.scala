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

    /** Package owning the code under test. Sampled time outside it is time no kernel change can move.
      *
      * Stated as the kernel rather than as a list of noise classes, and that inversion is the point. The list this replaced was
      * `Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")`, which asks which frames are noise, and answering that requires
      * enumerating everything that is not the kernel: the benchmark's own generated closures, boxing, JDK internals, the allocator,
      * native scheduler frames. That set is open and grows with every benchmark added, so the list was guaranteed to under-report
      * forever. On the only real profile in the repository it matched `boxToInteger` and nothing else, reporting 29.07% where the
      * answer is 83.97%, understated by 54.9 points and in the direction that flatters the kernel. The kernel is the closed set.
      */
    val KernelPackages = Seq("kyo.kernel.", "kyo.proto.")
    val KernelPackage  = KernelPackages.mkString(" or ")

    /** A frame in one of the kernel implementations under measurement: kyo-kernel's `kyo.kernel` or `kyo.proto`. The benchmark package
      * sits under `kyo.kernel.` and is the workload, not the kernel, so it is excluded here and classified on its own.
      */
    def isKernel(method: String): Boolean =
        !method.startsWith(BenchmarkPackage) && KernelPackages.exists(method.startsWith)

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

    /** Above this share of the measured window spent compiling, the run had not settled and its score is not a steady-state figure.
      *
      * Bounded by evidence rather than validated by it, and the difference is worth stating. Across
      * every row this campaign has stored that carries `compiler.time.profiled`, 52 of them, the
      * compile time inside the measured window is a median of 2 ms and a maximum of 9 ms, which is
      * 0.18% of a 5,000 ms window. The previous limit of 1.0% is 50 ms, so it has never fired and
      * could not have fired on anything measured here.
      *
      * The plan called for tightening it to around the 4 ms of the run that motivated it. That would
      * be wrong: 8 of those 52 rows sit at or above 4 ms and every one of them is ordinary, so the
      * tightened guard would be a false-alarm generator. Nor can the right value be found from the
      * data, because there is no positive case in it: of the 25 rows carrying both an iteration series
      * and a compile-time figure, none is unsettled by the series criterion, so the two signals have
      * never been observed to disagree or agree.
      *
      * 0.5% is therefore what it is: a little under three times the worst share ever seen, which
      * leaves room for a genuinely pathological run to trip it while nothing observed does. The signal
      * actually catching unsettled legs today is `Row.unsettledStart`, from the iteration series, and
      * this one should not be read as a second working guard until something trips it.
      */
    val CompilingShareLimit = 0.5

    val BenchClass  = "kyo.kernel.bench.YetAnotherProtoBench"
    val BenchSource = "kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/YetAnotherProtoBench.scala"
    val AsyncProf   = "/opt/homebrew/opt/async-profiler/lib/libasyncProfiler.dylib"

    case class BracketFailed(reason: String) extends Exception(reason) with NoStackTrace

    type Fail = Abort[BracketFailed | CommandException | FileReadException | FileFsException]

    /** Lines a JVM prints when it rejected a compile command and ran anyway.
      *
      * Exposed rather than buried in the leg runner so it can be tested against the real output that
      * cost two runs: the JVM prints one line and proceeds, so the measurement looks entirely normal
      * and tests nothing.
      */
    def rejectedCompileCommand(out: String): Chunk[String] =
        Chunk.from(out.linesIterator.filter(l => l.contains("CompileCommand: An error occurred") || l.contains("Error: Method pattern")).toSeq)

    /** The part of a failed command's output worth showing.
      *
      * The whole thing is not. Exercising the red-tree gate for the first time produced a correct
      * refusal wrapped in several hundred lines of *passing* test names, with the one line naming the
      * failing test far below the fold, which is a refusal an operator skims past. sbt marks what went
      * wrong with `[error]` and with scalatest's `*** FAILED ***`; those lines and the tail are the
      * diagnosis, and the rest is the log.
      */
    def failureExcerpt(out: String, tail: Int = 12): String =
        val lines  = out.linesIterator.toVector
        val marked = lines.filter(l => l.contains("[error]") || l.contains("*** FAILED ***") || l.contains("TESTS FAILED"))
        val shown  = (marked.takeRight(20) ++ lines.takeRight(tail)).distinct
        if shown.isEmpty then lines.takeRight(tail).mkString("\n")
        else shown.mkString("\n") + (if lines.size > shown.size then s"\n  (${lines.size} lines of output, the rest of it green)" else "")

    def exec(worktree: Path, command: String*)(using Frame): String < (Async & Fail) =
        Command(command*).cwd(worktree).redirectErrorStream(true).textWithExitCode.map { (out, exit) =>
            if exit == ExitCode.Success then out
            else
                Abort.fail(BracketFailed(
                    s"`${command.mkString(" ")}` failed with $exit in $worktree:\n${failureExcerpt(out)}"
                ))
        }

    /** The suite must be green before a leg is measured at all.
      *
      * A faster variant whose suite is red is not a result, and this is the gate that says so. It had
      * never once refused anything, so it was verified by planting a failing test in the throwaway
      * worktree: `sbt --client` exits 1 on a test failure, `exec` aborts, and the leg never runs.
      */
    def requireGreenSuite(worktree: Path, task: String)(using Frame): Unit < (Async & Fail) =
        exec(worktree, "sbt", "--client", task).unit
            .handle(Abort.recoverError[BracketFailed](e =>
                Abort.fail(BracketFailed(s"the suite is red, so nothing measured here would be a result.\n${e.failureOrPanic}"))
            ))

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
            exec(worktree, "sbt", "--client", s"kyo-kernelJVM/Jmh/run -f 1 -rf json -rff ${json.toString} $BenchClass.$row")
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
    // a secondary carries its own per-fork, per-iteration series; that is what lets a per-fork row
    // report that fork's own allocation and compile time instead of the whole run's aggregate
    case class JmhSecondary(score: Double, rawData: Maybe[Chunk[Chunk[Double]]] = Maybe.empty) derives Schema
    case class JmhEntry(
        benchmark: String,
        mode: String,
        primaryMetric: JmhScore,
        secondaryMetrics: Map[String, JmhSecondary],
        // JMH writes these on every entry; an ingested run used to ignore them and call itself -f 1
        forks: Maybe[Int] = Maybe.empty,
        warmupIterations: Maybe[Int] = Maybe.empty,
        measurementIterations: Maybe[Int] = Maybe.empty,
        jvmArgs: Maybe[Chunk[String]] = Maybe.empty
    ) derives Schema

    def parseJmhEntries(raw: String)(using Frame): Chunk[JmhEntry] < Fail =
        Json.decode[Chunk[JmhEntry]](raw) match
            case Result.Failure(e)       => Abort.fail(BracketFailed(s"could not read JMH json: $e"))
            case Result.Panic(e)         => Abort.fail(BracketFailed(s"could not read JMH json: $e"))
            case Result.Success(entries) => entries

    def parseJmh(raw: String)(using Frame): Chunk[Row] < Fail =
        parseJmhEntries(raw).map(_.map(entryRow))

    /** The whole entry as one row: JMH's own score and error over every fork, the iterations of every fork in order. */
    def entryRow(e: JmhEntry): Row =
        Row(
            name = e.benchmark.split('.').last,
            mode = e.mode,
            count = e.primaryMetric.rawData.map(_.size).sum,
            iterations = Chunk.from(e.primaryMetric.rawData.flatten),
            score = e.primaryMetric.primaryScore,
            error = e.primaryMetric.safeError,
            unit = e.primaryMetric.scoreUnit,
            allocPerOp = Maybe.fromOption(e.secondaryMetrics.get("gc.alloc.rate.norm").map(_.score)),
            compilerMsProfiled = Maybe.fromOption(e.secondaryMetrics.get("compiler.time.profiled").map(_.score)),
            compilerMsTotal = Maybe.fromOption(e.secondaryMetrics.get("compiler.time.total").map(_.score))
        )

    /** JMH reports its error at 99.9% confidence; a per-fork row carries the same quantity over that fork's own iterations. */
    val JmhConfidenceAlpha = 0.001

    /** JMH's own aggregate over a series: the mean, and the 99.9% error over all of it, which is what its json `score`/`scoreError` are. */
    def jmhAggregate(xs: Chunk[Double]): (Double, Double) =
        val n    = xs.size
        val mean = if n == 0 then 0.0 else xs.sum / n
        val sd   = if n < 2 then 0.0 else Math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / (n - 1))
        (mean, if n < 2 then 0.0 else Stats.tCritical(n - 1, JmhConfidenceAlpha) * sd / Math.sqrt(n))

    private val LogBenchmark = """^\s*(?:\[info\]\s*)?# Benchmark: (\S+)\s*$""".r
    private val LogFork      = """^\s*(?:\[info\]\s*)?# Fork: (\d+) of (\d+)\s*$""".r
    private val LogWarmup    = """^\s*(?:\[info\]\s*)?# Warmup: (\d+) iterations.*$""".r
    private val LogMeasure   = """^\s*(?:\[info\]\s*)?# Measurement: (\d+) iterations.*$""".r
    private val LogIteration = """^\s*(?:\[info\]\s*)?Iteration\s+\d+: ([\d.]+) (\S+)\s*$""".r
    private val LogSecondary = """^\s*(?:\[info\]\s*)?([\w.]+): ([\d.]+) (\S+)\s*$""".r

    /** The same entries a JMH json would carry, read from the text log JMH prints while it runs: per benchmark, per fork, every measured
      * iteration and the secondaries printed under it (`gc.alloc.rate.norm` and the rest of `-prof gc`).
      *
      * The log is the one artifact of a run that is written as it happens, so it survives when the json does not (a bracket's json vanished
      * from the tree the morning this was written, its log committed and complete). Warmup iterations are skipped: they are not data. The
      * score and error of an entry are recomputed exactly as JMH computes them, the mean and 99.9% error over every measured iteration of
      * every fork, which the tests check against the summary table JMH printed at the end of the same log.
      */
    def parseJmhLog(raw: String): Chunk[JmhEntry] =
        var warmup: Maybe[Int]  = Maybe.empty
        var measure: Maybe[Int] = Maybe.empty
        // benchmark -> (fork index -> iterations, secondary name -> fork index -> values), in encounter order
        val order      = scala.collection.mutable.ArrayBuffer.empty[String]
        val forks      = scala.collection.mutable.Map.empty[String, Int]
        val units      = scala.collection.mutable.Map.empty[String, String]
        val iterations = scala.collection.mutable.Map.empty[(String, Int), scala.collection.mutable.ArrayBuffer[Double]]
        val secondary  = scala.collection.mutable.Map.empty[(String, String, Int), scala.collection.mutable.ArrayBuffer[Double]]
        var bench      = ""
        var fork       = 0
        var inWarmup   = true
        raw.linesIterator.foreach {
            case LogWarmup(n)  => warmup = Maybe(n.toInt)
            case LogMeasure(n) => measure = Maybe(n.toInt)
            case LogBenchmark(name) =>
                bench = name
                if !order.contains(name) then order += name
            case LogFork(k, n) =>
                fork = k.toInt - 1
                forks(bench) = n.toInt
                inWarmup = true
            case LogIteration(v, unit) =>
                inWarmup = false
                units(bench) = unit
                iterations.getOrElseUpdate((bench, fork), scala.collection.mutable.ArrayBuffer.empty) += v.toDouble
            case LogSecondary(name, v, _) if !inWarmup && bench.nonEmpty && name.contains(".") =>
                secondary.getOrElseUpdate((bench, name, fork), scala.collection.mutable.ArrayBuffer.empty) += v.toDouble
            case line if line.contains("# Warmup Iteration") => inWarmup = true
            case _ => ()
        }
        Chunk.from(order.toSeq).map { name =>
            val n     = forks.getOrElse(name, 1)
            val raw   = Chunk.from((0 until n).map(k => Chunk.from(iterations.getOrElse((name, k), Nil).toSeq)))
            val all   = raw.flatten
            val (score, err) = jmhAggregate(all)
            val names = secondary.keys.collect { case (b, s, _) if b == name => s }.toSeq.distinct.sorted
            val secs =
                names.map { s =>
                    val perFork = Chunk.from((0 until n).map(k => Chunk.from(secondary.getOrElse((name, s, k), Nil).toSeq)))
                    s -> JmhSecondary(jmhAggregate(perFork.flatten)._1, Maybe(perFork))
                }.toMap
            JmhEntry(
                benchmark = name,
                mode = "avgt",
                primaryMetric = JmhScore(score, err, units.getOrElse(name, "us/op"), raw),
                secondaryMetrics = secs,
                forks = Maybe(n),
                warmupIterations = warmup,
                measurementIterations = measure
            )
        }
    end parseJmhLog

    /** One fork of an entry as a row of its own: that fork's iterations, their mean, an error at JMH's confidence over them, and the
      * fork's own secondaries where the json carries them per fork. A fork is an independent JVM, which is what a leg is.
      */
    def forkRow(e: JmhEntry, k: Int): Row =
        val xs   = e.primaryMetric.rawData(k)
        val n    = xs.size
        val mean = if n == 0 then 0.0 else xs.sum / n
        val sd   = if n < 2 then 0.0 else Math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / (n - 1))
        val err  = if n < 2 then 0.0 else Stats.tCritical(n - 1, JmhConfidenceAlpha) * sd / Math.sqrt(n)
        def forkMetric(name: String)(agg: Chunk[Double] => Double): Maybe[Double] =
            Maybe.fromOption(e.secondaryMetrics.get(name)).flatMap { m =>
                m.rawData.flatMap(rd => if k < rd.size && rd(k).nonEmpty then Maybe(agg(rd(k))) else Maybe.empty)
            }
        Row(
            name = e.benchmark.split('.').last,
            mode = e.mode,
            count = n,
            iterations = xs,
            score = mean,
            error = err,
            unit = e.primaryMetric.scoreUnit,
            // a per-op rate: the fork's mean; a duration: the fork's total
            allocPerOp = forkMetric("gc.alloc.rate.norm")(v => v.sum / v.size),
            compilerMsProfiled = forkMetric("compiler.time.profiled")(_.sum),
            compilerMsTotal = forkMetric("compiler.time.total")(_.sum)
        )
    end forkRow

    extension (s: JmhScore)
        def primaryScore: Double = s.score
        def safeError: Double    = if s.scoreError.isNaN then 0.0 else s.scoreError

    // --- evidence parsers -------------------------------------------------------

    // the name column runs to end of line. It was previously spelled as a character class, which
    // silently truncated every array type the profiler prints: `java.lang.Object[]` arrived as
    // `java.lang.Object`, so an array allocation and an allocation of its element type became the
    // same row. On the kernel rows the interesting allocation is the stack's Object[].
    private val ProfLine = """(?m)^.*?(\d+)\s+[\d.]+%\s+(\d+)\s+(\S.*?)\s*$""".r

    def parseAlloc(raw: String): Chunk[AllocSite] =
        val perLine = ProfLine.findAllMatchIn(raw).map(m => AllocSite(m.group(3), m.group(1).toLong, m.group(2).toLong)).toSeq
        // a multi-benchmark alloc dump repeats each class once per benchmark section, so a class lands
        // in `Run.alloc` several times; fold the duplicates into one leg-level row, keeping encounter
        // order. Without this `apportion` divides one section's bytes by a denominator summed across all
        // of them, and the conservation check compares a single section's flat samples with the whole
        // leg's collapsed ones (defect 48)
        val byClass = scala.collection.mutable.LinkedHashMap.empty[String, AllocSite]
        perLine.foreach { a =>
            byClass.updateWith(a.cls) {
                case Some(prev) => Some(AllocSite(a.cls, prev.bytes + a.bytes, prev.samples + a.samples))
                case None       => Some(a)
            }
        }
        Chunk.from(byClass.values.toSeq)

    def parseCpu(raw: String): Chunk[CpuSite] =
        Chunk.from(ProfLine.findAllMatchIn(raw).map(m => CpuSite(m.group(3), m.group(1).toLong)).toSeq)

    private val BenchmarkHeader = """(?m)^.*# Benchmark: (\S+)\s*$""".r

    /** A JMH log's profiler tables keyed by the benchmark that produced each: JMH prints one `# Benchmark:` header per row and the
      * profiler's flat table after that row's iterations, so a split at the headers attributes every frame to its row. Merged, as
      * `parseCpu` over the whole log does, the frames of 29 rows are one table and no row can be asked where its time went.
      */
    def parseCpuByBenchmark(raw: String): Map[String, Chunk[CpuSite]] =
        val headers = BenchmarkHeader.findAllMatchIn(raw).toSeq
        headers.zipWithIndex.map { (m, i) =>
            val end   = if i + 1 < headers.size then headers(i + 1).start else raw.length
            val block = raw.substring(m.end, end)
            m.group(1).split('.').last -> parseCpu(block)
        }.filter((_, sites) => sites.nonEmpty).toMap

    /** The collapsed (FlameGraph folded) allocation view: `frame;frame;...;Class_[i] value`.
      *
      * Three things about this format decide whether a parse of it means anything, and all three were
      * got wrong in the first design of this phase:
      *
      *   - **It is root-first.** `frames.head` is `java.lang.Thread.run` on every line. The allocated
      *     class is the *last* frame and the method that allocated it is the one before that, so both
      *     are indexed from the leaf end.
      *   - **The value is a sample count**, because the dump JMH issues carries no `total` option.
      *     Comparing it against the flat table's bytes is comparing two different quantities, and a
      *     conservation gate written that way can never pass.
      *   - **The leaf carries a TLAB marker**, `_[i]` inside and `_[k]` outside, which is part of the
      *     sample and not part of the class name.
      */
    def parseCollapsed(raw: String): Chunk[AllocByMethod] =
        val entries =
            raw.linesIterator.flatMap { line =>
                val cut = line.lastIndexOf(' ')
                if cut <= 0 then None
                else
                    val (stack, value) = (line.substring(0, cut), line.substring(cut + 1).trim)
                    value.toLongOption.flatMap { n =>
                        val frames = stack.split(';').filter(_.nonEmpty).map(_.replace('/', '.'))
                        if frames.length < 2 then None
                        else
                            val cls    = frames.last.replaceAll("_\\[[a-z]\\]$", "")
                            val method = frames(frames.length - 2)
                            // walk out past the type's own frames. Every Nested on the nested-payload
                            // row is minted at `Nested$.apply`, which decides nothing; one frame
                            // further out is who asked for it.
                            val own  = Seq(cls + ".", cls + "$.")
                            val site = frames.init.reverseIterator.find(f => !own.exists(f.startsWith)).getOrElse(method)
                            Some(((cls, method, site), n))
                    }
            }.toSeq
        Chunk.from(
            entries.groupMapReduce(_._1)(_._2)(_ + _).toSeq
                .map((k, n) => AllocByMethod(k._1, k._2, n, Maybe.empty, k._3))
                .sortBy(-_.samples)
        )
    end parseCollapsed

    /** Turns the collapsed sample counts into byte estimates using the flat table's bytes per class.
      *
      * Only ever an apportionment: the profiler weights each sample by the bytes it stands for, and
      * that weighting is not recoverable per frame from a sample count. Within one class the sizes
      * are near-constant, which is what makes the split usable, and it is labelled an estimate.
      */
    def apportion(flat: Chunk[AllocSite], byMethod: Chunk[AllocByMethod]): Chunk[AllocByMethod] =
        val perClass = flat.map(a => a.cls -> a).toMap
        val totals   = byMethod.groupMapReduce(_.cls)(_.samples)(_ + _)
        byMethod.map { m =>
            val estimate =
                for
                    site  <- Maybe.fromOption(perClass.get(m.cls))
                    total <- Maybe.fromOption(totals.get(m.cls)).filter(_ > 0)
                yield site.bytes.toDouble * m.samples / total
            m.copy(bytes = estimate)
        }
    end apportion

    /** Whether the two views describe the same samples, per class.
      *
      * A coverage guard and nothing more: it holds equally for a correct attribution and for one that
      * assigns every sample of a class to a single arbitrary frame. What it does catch is the parse
      * losing lines, which is the failure this file has had four times. Both views must come from one
      * recording, or the comparison is bounded by run-to-run variance instead of by parser
      * correctness: two recordings of the same planted program differed by 0.25% on the same class.
      */
    def allocConservation(flat: Chunk[AllocSite], byMethod: Chunk[AllocByMethod]): Chunk[String] =
        val totals = byMethod.groupMapReduce(_.cls)(_.samples)(_ + _)
        flat.filter(_.samples > 0).flatMap { site =>
            val got = totals.getOrElse(site.cls, 0L)
            if got == site.samples then Chunk.empty
            else Chunk(s"${site.cls}: flat table has ${site.samples} samples, the collapsed view accounts for $got")
        }
    end allocConservation

    // --- one leg ----------------------------------------------------------------

    def openSession(worktree: Path)(using Frame): Session < (Async & Fail) =
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

        def sbt(task: String) = exec(worktree, "sbt", "--client", task).map { out =>
            // A JVM that cannot parse a compile command prints one line and runs anyway, producing a
            // measurement that looks entirely normal and tests nothing. Two runs were spent that way
            // before a log line revealed `CompileCommand: An error occurred during parsing`, and both
            // had already been read as results.
            if rejectedCompileCommand(out).nonEmpty then
                Abort.fail(BracketFailed(
                    "the JVM rejected a compile command and ran anyway; the measurement tests nothing. " +
                        "Use -XX:CompileCommandFile, which does not have to survive shell and sbt quoting:\n" +
                        rejectedCompileCommand(out).take(3).mkString("\n")
                ))
            else out
        }

        // pinned on every leg: these rows allocate megabytes per operation, so ergonomic
        // heap sizing is a live variance source that costs one flag to remove
        val extraVm = (Seq("-Xms4g", "-Xmx4g", "-XX:+UseG1GC") ++ jvmArgs).mkString(" ")

        def measureWith(warmup: Int, n: Int, expected: Int): Chunk[Row] < (Async & Fail) =
            // the results file is deleted first, every attempt. It is a fixed path per label, so a
            // run that matched no benchmarks (which JMH exits 0 for, and which is the exact failure
            // the retry below exists for) would otherwise leave the *previous* attempt's json in
            // place, and this leg would silently adopt the previous leg's numbers with a row count
            // that passes every check.
            json.remove
                .andThen(sbt(
                    s"kyo-kernelJVM/Jmh/run -f $forks -wi $warmup -i $MeasureIterations -r ${IterationSeconds}s " +
                        s"-w ${IterationSeconds}s -prof gc -prof comp -rf json -rff ${json.toString} " +
                        "-jvmArgsAppend \"" + extraVm + "\" " + selector
                ))
                .andThen(json.exists)
                .map { wrote =>
                    Abort.when(!wrote)(BracketFailed(
                        s"leg $label produced no results file at $json. JMH exits 0 when its selector matches " +
                            "nothing, so this is a run that measured no benchmark rather than a run that failed."
                    ))
                }
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

        // both views of the allocation profile come out of one recording, which is the only way the
        // conservation check between them measures the parse rather than run-to-run variance: JMH
        // stops the profiler once and dumps it twice. `text` is printed inline and carries the bytes;
        // `collapsed` is written to a file and carries the stacks.
        val allocDir = worktree / s"alloc-$label"

        def profile(event: String, extra: String = "") =
            sbt(
                s"""kyo-kernelJVM/Jmh/run -f 1 -wi $WarmupIterations -i 1 -r ${IterationSeconds}s -w ${IterationSeconds}s """ +
                    s"""-prof "async:libPath=$AsyncProf;event=$event$extra" $selector"""
            )

        /** The collapsed dumps JMH leaves on disk, wherever under `dir` it decided to put them. */
        def collapsedFiles(using Frame): Chunk[Path] < (Sync & Abort[FileFsException]) =
            allocDir.exists.map {
                case false => Chunk.empty[Path]: Chunk[Path] < Any
                case true =>
                    allocDir.list.map { entries =>
                        Kyo.foreach(entries) { e =>
                            e.list.map(_.filter(_.name.exists(n => n.startsWith("collapsed") && n.endsWith(".csv"))))
                                .handle(Abort.recover[FileFsException](_ => Chunk.empty[Path]))
                        }.map(_.flatten)
                    }
            }

        val logcFile = worktree / s"logc-$label.xml"

        /** The only source of inlining decisions.
          *
          * The `PrintInlining` run this replaces cost a whole JMH invocation to produce strictly less: no denominator per method, no
          * deoptimizations, no receiver profiles, and output interleaved across compiler threads so its tree could not be trusted. One run
          * removed from every leg.
          */
        def compilationLog =
            sbt(
                s"""kyo-kernelJVM/Jmh/run -f 1 -wi $WarmupIterations -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=${logcFile.toString}" $selector"""
            ).andThen(logcFile.read).map(LogCompilation.parse)

        for
            _        <- requireThrowaway(worktree)
            _        <- restore(worktree, sha, paths)
            declared <- declaredRows(worktree)
            // a faster variant whose suite is red is not a result; gate before spending the runs.
            // this also settles the sources: the build formats on compile, so the baseline below
            // must be taken after it or the leg invalidates itself on its own formatting
            _          <- requireGreenSuite(worktree, "kyo-kernelJVM/testOnly kyo.proto.*")
            before     <- readMarkers(worktree, markerSpecs)
            hashBefore <- treeHash(worktree, paths)
            measured   <- measureWith(WarmupIterations, 1, declared)
            allocOut <- if evidence == Evidence.Full then profile("alloc", s";output=text,collapsed;dir=${allocDir.toString}")
            else "": String < Any
            allocSites = parseAlloc(allocOut)
            collapsed <- collapsedFiles.map(fs => Kyo.foreach(fs)(_.read)).map(_.mkString("\n"))
            byMethod   = apportion(allocSites, parseCollapsed(collapsed))
            // the collapsed dump is requested by an option string that has to survive shell, sbt and
            // JMH quoting. When it does not, JMH runs the profiler anyway and prints a normal-looking
            // table, so the leg would report allocation with no attribution and nothing would say the
            // instruction had been dropped. A rejected compile command taught this exact lesson twice.
            _ <- Abort.when(evidence == Evidence.Full && allocSites.nonEmpty && byMethod.isEmpty)(
                BracketFailed(
                    s"leg $label profiled ${allocSites.size} allocated classes but produced no collapsed view, " +
                        s"so nothing can be attributed to a method. Expected a collapsed-*.csv under $allocDir; " +
                        "check that output=text,collapsed reached JMH."
                )
            )
            cpuSites  <- if evidence == Evidence.Full then profile("itimer").map(parseCpu) else Chunk.empty[CpuSite]: Chunk[CpuSite] < Any
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
            jvmArgs = Chunk.from(jvmArgs),
            benchmarkClass = BenchClass,
            alloc = allocSites,
            allocByMethod = byMethod,
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

    /** Fraction of sampled time in classes no kernel change can move.
      *
      * Taken over the sites rather than over a `Run` so it can be checked against a real captured profile, which is the only input a
      * fixture author does not control. The authored fixture it replaced could not fail: two frames, neither of them the benchmark's.
      */
    def noiseShare(cpu: Chunk[CpuSite]): Double =
        val total = cpu.map(_.nanos).sum
        if total == 0L then 0.0
        else cpu.filterNot(c => isKernel(c.method)).map(_.nanos).sum.toDouble / total * 100

    def noiseShare(run: Run): Double = noiseShare(run.cpu)

    /** Package holding the benchmark's own generated code. Its closures are the workload, not noise. */
    val BenchmarkPackage = "kyo.kernel.bench."

    /** Sampled time split three ways, which is the split that does not require deciding a contested question.
      *
      * Two-way is the trap. Calling everything outside `kyo.kernel.proto` immovable classifies `ProtoKernelBench$$anon$95` as
      * unreachable by any kernel change, and that allocation is the entire subject of candidate C3: the benchmark's closures are the
      * workload, and how often they run and whether they allocate is what the kernel's dispatch decides. Reporting the three shares
      * separately states what was measured and leaves the inference to the reader, where it belongs.
      */
    case class CpuPartition(kernel: Double, benchmark: Double, other: Double)

    def cpuPartition(cpu: Chunk[CpuSite]): CpuPartition =
        val total = cpu.map(_.nanos).sum
        if total == 0L then CpuPartition(0.0, 0.0, 0.0)
        else
            def share(p: CpuSite => Boolean) = cpu.filter(p).map(_.nanos).sum.toDouble / total * 100
            CpuPartition(
                kernel = share(c => isKernel(c.method)),
                benchmark = share(_.method.startsWith(BenchmarkPackage)),
                other = share(c => !isKernel(c.method) && !c.method.startsWith(BenchmarkPackage))
            )

    /** The frames making up that share, largest first. The share alone tells the operator to go and look; these are what it would find. */
    def noiseFrames(cpu: Chunk[CpuSite], take: Int): Chunk[(String, Double)] =
        val total = cpu.map(_.nanos).sum
        if total == 0L then Chunk.empty
        else
            // by method: a run's cpu is the merge of its rows' profiles, so one method appears once per
            // row it was sampled in, and listing the same frame three times says less than summing it
            Chunk.from(
                cpu.filterNot(c => isKernel(c.method))
                    .groupBy(_.method).toSeq
                    .map((m, sites) => (m, sites.map(_.nanos).sum))
                    .sortBy(-_._2).take(take)
                    .map((m, n) => (m, n.toDouble / total * 100))
            )

    def noiseFrames(run: Run, take: Int = 3): Chunk[(String, Double)] = noiseFrames(run.cpu, take)

    /** Rows whose measured window still contained meaningful compilation.
      *
      * Deliberately not corrected by warming longer. A benchmark that needs unusual warmup is reporting something about the code under it,
      * bigger methods, recompilation churn, or an unstable profile, and warming past it discards the finding. The flag exists so the cause
      * gets diagnosed.
      */
    def stillCompiling(run: Run): Chunk[(String, Double)] =
        Chunk.from(run.rows.flatMap { r =>
            // the row's own measured window from real data. `r.count` is the total measured iterations
            // JMH recorded for the row across its forks, so it replaces `forks * MeasureIterations`,
            // which fabricated the -i the run actually used and was wrong for anything ingested at a
            // different iteration count. The per-iteration seconds is the one term the json carries no
            // per-row copy of, so it stays the harness default and is wrong only for a run measured at
            // a different -r
            if r.count <= 0 then Maybe.empty
            else
                val measuredMs = r.count * IterationSeconds * 1000.0
                r.compilingShare(measuredMs).filter(_ > CompilingShareLimit).map(share => r.name -> share)
        })

    def sameSession(control: Run, variant: Run): Boolean =
        control.session.id == variant.session.id

    def compare(control: Run, variant: Run): Comparison =
        val deltas = Chunk.from(control.rows).flatMap { c =>
            Chunk.from(variant.row(c.name)).map { v =>
                val percent = (v.score - c.score) / c.score * 100
                // never tighter than either leg's own uncertainty. The drift band alone classified a
                // -9.8% delta as a win on a row whose control leg reported +-15.2%, which is the same
                // defect the replicate statistic had and had already been fixed there: a threshold
                // below the measurement's own error classifies that error as a result.
                val ownError = Math.max(c.relativeError, v.relativeError) * 100
                val drift    = Math.max(band(control, variant), ownError)
                val verdict =
                    // a score whose error is a large fraction of itself cannot support a percentage,
                    // and two rows measured in different modes or units are not a pair at all
                    // (Report.blockers names the mismatch)
                    if c.error > c.score * 0.5 || c.score <= 0.0 || !c.comparableWith(v) then Verdict.BelowResolution
                    else if percent < -drift then (if c.lowerIsBetter then Verdict.Faster else Verdict.Regressed)
                    else if percent > drift then (if c.lowerIsBetter then Verdict.Regressed else Verdict.Faster)
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
                // a single pair cannot support a t threshold, but it can state the floor it used,
                // so a flat row is bounded rather than merely quiet
                Delta(c.name, c, v, percent, verdict, allocDelta, mechanism, Maybe(Resolution(drift, drift * c.score / 100, 0, 0.0)))
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
    /** One arm of a bracket: the sources it measures and the JVM configuration it measures them under.
      *
      * Both, because a comparison is not always between two designs. The campaign's central question,
      * whether forcing one method to inline recovers a regression, is one design under two JVM
      * configurations, and a bracket that could only flip shas could not express it. Every such
      * comparison was therefore run as a single unreplicated leg, which is why they all report a floor
      * rather than a threshold.
      */
    case class Arm(sha: String, jvmArgs: Seq[String] = Nil)

    /** The C V C V C ordering, alternating so machine drift lands in both arms rather than one.
      *
      * Generic in what a leg measures because `bracket` compares two `Arm`s and the test that pins
      * this ordering compares two shas. It was written twice for that reason, once here and once
      * inline in `bracket`, so the test covered a function the runner did not call and the ordering
      * that actually ran was pinned by nothing.
      */
    def bracketPlan[A](control: A, variant: A, legs: Int = 5): Chunk[(String, A)] =
        Chunk.from(
            (0 until Math.max(2, legs)).map { i =>
                if i % 2 == 0 then (s"control-${i / 2 + 1}", control)
                else (s"variant-${i / 2 + 1}", variant)
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
        control: Arm,
        variant: Arm,
        paths: Seq[String],
        markerSpecs: Seq[(String, String, String)],
        rows: Seq[String],
        forks: Int,
        evidence: Evidence,
        legs: Int = 5
    )(using Frame): (Chunk[Run], Chunk[Run]) < (Async & Fail) =
        Kyo.foreach(bracketPlan(control, variant, legs)) { (label, arm) =>
            runLeg(session, worktree, label, arm.sha, paths, markerSpecs, rows, forks, evidence, arm.jvmArgs)
        }.map { ls =>
            (Chunk.from(ls.filter(_.label.startsWith("control"))), Chunk.from(ls.filter(_.label.startsWith("variant"))))
        }
    end bracket

    /** A chain of shas, each step isolating one change from the step before it.
      *
      * The reason this exists rather than a second pair: a two-sha comparison measures the difference
      * between two trees and cannot attribute it, because the partition between the changes inside
      * that diff was never declared. The skill's worked example is exactly this. Two changes shipped
      * together, a node-layout change and a currency hoist; the bundle was faster, the win was
      * credited first to one and then to the other, and both stories were wrong as told. Running the
      * pieces separately settled it, and running the pieces separately is a chain.
      *
      * Each adjacent pair is measured as its own replicated comparison, so every step carries a
      * threshold rather than a single difference of two numbers.
      */
    /** Why a proposed chain is not one, or empty when it is.
      *
      * Pure and separate from the runner so the refusal can be tested without a worktree: the whole
      * point of the guard is that it fires before a single leg is measured.
      */
    def requireChain(shas: Seq[String]): Maybe[String] =
        if shas.size < 3 then
            Maybe(
                s"a chain needs three or more shas; ${shas.size} is a pair, and a pair cannot isolate anything. " +
                    "Use `bracket` for a pair, which reports that it attributes no source-level mechanism."
            )
        else if shas.distinct.size != shas.size then
            Maybe(s"the chain repeats a sha (${shas.diff(shas.distinct).distinct.mkString(", ")}); a step to the same tree isolates nothing")
        else Maybe.empty

    def chain(
        session: Session,
        worktree: Path,
        shas: Seq[String],
        paths: Seq[String],
        markerSpecs: Seq[(String, String, String)],
        rows: Seq[String],
        forks: Int,
        evidence: Evidence,
        legsPerSha: Int = 2
    )(using Frame): Chunk[(String, Chunk[Run])] < (Async & Fail) =
        requireChain(shas) match
        case Maybe.Present(why) => Abort.fail(BracketFailed(why))
        case _ =>
            // interleaved rather than grouped: measuring all of sha A then all of sha B puts any
            // machine drift entirely into the difference between them, which is the mistake the A/A
            // null's alternating split already exists to avoid
            val plan =
                Chunk.from(
                    (1 to Math.max(2, legsPerSha)).flatMap(rep => shas.zipWithIndex.map((sha, i) => (s"s$i-$rep", sha)))
                )
            Kyo.foreach(plan) { (label, sha) =>
                runLeg(session, worktree, label, sha, paths, markerSpecs, rows, forks, evidence)
            }.map { ls =>
                Chunk.from(shas.zipWithIndex.map((sha, i) => sha -> Chunk.from(ls.filter(_.label.startsWith(s"s$i-")))))
            }
    end chain

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
                    Chunk.from((controls ++ variants).flatMap(_.row(name)).map(r => if r.score == 0.0 then 0.0 else r.error / r.score)),
                    // which way is down comes from the row's mode, read off the first control leg
                    controls.headMaybe.flatMap(_.row(name)).map(_.lowerIsBetter).getOrElse(true)
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
                    // two arms measured in different modes or units are not a pair; the row stays
                    // unresolved and Report.blockers names the mismatch
                    val (verdict, resolution) =
                        if c0.comparableWith(v0) then Stats.classify(r, FamilyAlpha, rowNames.size)
                        else (Verdict.BelowResolution, Maybe.empty)
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
                    Delta(r.row, c, v, r.deltaPercent, verdict, allocDelta, mechanism, resolution,
                        common.flatMap(m => Stats.residual(r, m)))
            }.sortBy(d => (d.verdict == Verdict.BelowResolution, d.percent))
        Comparison(controls.head, variants.head, deltas, jitShift(controls.head, variants.head), controls, variants, common)
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
