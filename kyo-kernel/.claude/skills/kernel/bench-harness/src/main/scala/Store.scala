import Model.*
import kyo.*

/** Persists runs so a comparison never re-runs a benchmark.
  *
  * Storing the whole ladder rather than a summary is what makes a later question answerable: what the JIT decided about a method, or which
  * classes an allocation profile attributed bytes to, stays available months after the run without repeating it.
  */
object Store:

    def dir(root: Path): Path = root / "runs"

    def save(root: Path, run: Run)(using Frame): Path < (Sync & Abort[FileWriteException]) =
        val file = dir(root) / s"${run.id}.json"
        file.write(Json.encode(run)).andThen(file)

    def load(root: Path, id: String)(using Frame): Run < (Sync & Abort[FileFsException | FileReadException | Bench.BracketFailed]) =
        val file = dir(root) / s"$id.json"
        file.exists.map {
            case true =>
                file.read.map { raw =>
                    Json.decode[Run](raw) match
                        case Result.Success(r) => r
                        case other             => Abort.fail(Bench.BracketFailed(s"run $id is unreadable: $other"))
                }
            // the raw FileNotFoundException names a path the operator never typed and says nothing
            // about what they could have typed instead. `bench list` is one command away, so the
            // failure runs it for them.
            case false =>
                ids(root).map { known =>
                    Abort.fail(Bench.BracketFailed(
                        s"no run '$id' in store $root" +
                            (if known.isEmpty then ", which holds no runs"
                             else s". It holds ${known.size}:\n" + known.map(k => s"  $k").mkString("\n"))
                    ))
                }
        }
    end load

    /** The run ids a store holds, without decoding any of them. */
    def ids(root: Path)(using Frame): Chunk[String] < (Sync & Abort[FileFsException | Bench.BracketFailed]) =
        requireStore(root).andThen(
            dir(root).list.map(_.flatMap(f => Chunk.from(f.name.filter(_.endsWith(".json")).map(_.dropRight(5)))).sorted)
        )

    /** Fails when the store is not there at all.
      *
      * A missing store used to read as an empty one: `bench list --store <typo>` printed "no runs
      * stored" and exited 0, so a mistyped path was indistinguishable from a store whose runs really
      * were gone. That is the shape of failure this whole harness exists to refuse, arriving through
      * its own front door.
      */
    def requireStore(root: Path)(using Frame): Unit < (Sync & Abort[Bench.BracketFailed]) =
        dir(root).exists.map { ok =>
            Abort.when(!ok)(Bench.BracketFailed(
                s"no store at $root (there is no ${dir(root).name.getOrElse("runs")}/ directory under it). " +
                    "A store is created by `run` or `bracket`; if you expected runs here, the path is wrong."
            ))
        }

    def list(root: Path)(using Frame): Chunk[Run] < (Sync & Abort[FileFsException | FileReadException | Bench.BracketFailed]) =
        requireStore(root).andThen {
            dir(root).list.map { files =>
                Kyo.foreach(files.filter(_.name.exists(_.endsWith(".json")))) { f =>
                    f.read.map { raw =>
                        Json.decode[Run](raw) match
                            case Result.Success(r) => r
                            case other             => Abort.fail(Bench.BracketFailed(s"${f.name} is unreadable: $other"))
                    }
                }
            }
        }

    /** Recovers a session from any run that recorded it, so later legs join the same one. */
    def session(root: Path, id: String)(using Frame): Session < (Sync & Abort[FileFsException | FileReadException | Bench.BracketFailed]) =
        list(root).map { runs =>
            runs.find(_.session.id == id) match
                case Some(r) => r.session
                case None    => Abort.fail(Bench.BracketFailed(s"no stored run belongs to session $id"))
        }

end Store

/** Renders a comparison.
  *
  * The rules the skill states are enforced here rather than remembered: a verdict about the whole class is unreachable from a subset run, a
  * movement with nothing in the evidence behind it is labelled unexplained rather than narrated, and a run that skipped the ladder cannot
  * present itself as attributed.
  */
object Report:

    /** Conditions under which the numbers below are not readable, whatever they say.
      *
      * Both members are the same failure: a score that mixes warm and cold code. They were previously
      * a warning line each, one in the middle of the report and one near the end, which is a placement
      * that assumes the reader is disciplined. The operator this tool exists for is demonstrably not,
      * and glanced past exactly such a line on a leg whose first iteration was an outlier.
      *
      * So they become blockers: rendered first, under a banner, and the process exits non-zero. The
      * data is still printed in full, because suppressing it would trade one silent failure for
      * another; what changes is that the run cannot be mistaken for a clean one.
      */
    def blockers(c: Comparison): Chunk[String] =
        // every leg of each arm, not the first: a replicated comparison carries its legs, and a ramp
        // in leg two of three is exactly as much a mixture of warm and cold code as one in leg one.
        // The message says which row, which leg of which arm, and whether that row's other legs
        // settled, because the two readings have different cures: one JVM warming late is a re-run
        // with more warmup or a look at what compiles late in it; every leg ramping is a row that
        // needs more warmup than it was given; and a row whose legs merely disagree is noise the
        // resolution column already carries, which more warmup will not change
        def arm(name: String, legs: Chunk[Run]): Chunk[String] =
            val n    = legs.size
            val rows = c.deltas.map(_.row).toSet
            val unsettled =
                Chunk.from(legs.zipWithIndex).flatMap { (leg, k) =>
                    leg.rows.filter(r => rows.contains(r.name) && r.unsettledStart).map { r =>
                        val others   = legs.zipWithIndex.filter(_._2 != k).flatMap((l, _) => l.row(r.name))
                        val settled  = others.count(!_.unsettledStart)
                        val first    = f"${r.iterations.head}%.1f"
                        val rest     = r.iterations.tail.map(x => f"$x%.1f").mkString(", ")
                        val ramp     = r.warmupRamp.map(x => f"${x * 100}%.0f%%").getOrElse("?")
                        val where    = if n == 1 then s"$name" else s"$name leg ${k + 1} of $n"
                        val reading =
                            if n == 1 then "re-run with more warmup"
                            else if settled == others.size then
                                s"the other $settled $name leg(s) of this row settled, so one JVM warmed late: re-run with more warmup, " +
                                    "or look at what compiles late in it (-prof comp, LogCompilation)"
                            else if settled == 0 then
                                s"every $name leg of this row ramps, so the row needs more warmup than it was given"
                            else
                                s"$settled of ${others.size} other $name leg(s) settled: re-run with more warmup"
                        s"${r.name} ($where) never settled: first iteration $first then $rest ($ramp ramp); $reading"
                    }
                }
            val compiling =
                Chunk.from(legs.zipWithIndex).flatMap { (leg, k) =>
                    val where = if n == 1 then name else s"$name leg ${k + 1} of $n"
                    Bench.stillCompiling(leg).map((r, p) => f"$r%s ($where) spent ${p}%.1f%% of its measured window compiling")
                }
            unsettled ++ compiling
        end arm
        // a row whose two arms were measured in different modes or units has no delta: the
        // percentage compares us/op with ns/op, or time with throughput. It is left unresolved in the
        // table and named here, because a reader who sees "below resolution" would otherwise re-run
        // with more legs, which cannot cure it
        val mismatched =
            c.deltas.filter(d => !d.control.comparableWith(d.variant)).map { d =>
                s"${d.row}: control measured as ${d.control.mode} in ${d.control.unit}, variant as ${d.variant.mode} in ${d.variant.unit}; " +
                    "the two are not the same measurement and no delta exists between them. Re-run one side in the other's mode and unit."
            }
        // the flat allocation table and the collapsed per-method view come from one recording, so
        // per class they must account for the same samples. When they disagree the parse dropped
        // lines, which is the failure this file has had four times, and every byte figure the report
        // attributes to a method is then built on an incomplete count. Gated on both views present so
        // a timing leg, which has neither, does not false-alarm.
        val allocLost =
            def scan(name: String, legs: Chunk[Run]): Chunk[String] =
                Chunk.from(legs.zipWithIndex).flatMap { (leg, k) =>
                    if leg.alloc.isEmpty || leg.allocByMethod.isEmpty then Chunk.empty
                    else
                        val where = if legs.size == 1 then name else s"$name leg ${k + 1} of ${legs.size}"
                        Bench.allocConservation(leg.alloc, leg.allocByMethod).map(m =>
                            s"$m ($where): the flat allocation table and the collapsed view disagree, so the parse lost " +
                                "samples and the per-method byte figures are unreliable."
                        )
                }
            scan("control", c.allControlLegs) ++ scan("variant", c.allVariantLegs)
        arm("control", c.allControlLegs) ++ arm("variant", c.allVariantLegs) ++ mismatched ++ allocLost
    end blockers

    /** What the A/A null establishes, and what it refuses.
      *
      * The null compares control legs against each other, so every row it classifies is a false
      * positive by construction. That makes a dirty null the strongest possible statement that the
      * session below is unreadable, and it was reported as a line of text that let the process exit 0.
      * The unsettled-leg finding is the same shape and was already fixed this way: a reader who skims
      * a thirty-line report skims a warning inside it, so this is a blocker with an exit code.
      *
      * A session with too few control legs to run a null at all is also a blocker, and for a stronger
      * reason: it is not that the check failed, it is that the session cannot check itself.
      */
    /** Blockers the A/A null raises. `required` says whether a session that could not run a null at all is a failure.
      *
      * A dirty null is always a blocker: its verdicts are false by construction, so nothing below it is readable. The absence of a null is
      * a blocker only for a session that claims a replicated threshold, a bracket. An ad-hoc single-pair `compare` never claimed one and
      * so is not demanded to self-check; wiring the "too few legs" message to it unconditionally, as an earlier design did, failed every
      * single-pair compare and every default two-leg chain.
      */
    def nullBlockers(null_ : Maybe[Comparison], controlLegs: Int, required: Boolean): Chunk[String] =
        null_ match
            case Maybe.Present(n) =>
                val named = n.deltas.filter(_.verdict != Verdict.Flat)
                if named.isEmpty then Chunk.empty
                else
                    Chunk(
                        s"the A/A null classified ${named.size} row(s) comparing control legs against each other: " +
                            named.map(d => f"${d.row} ${d.percent}%+.1f%% (${d.verdict})").mkString(", ") +
                            ". Those verdicts are false by construction, so nothing below is readable."
                    )
            case _ if required =>
                Chunk(
                    s"$controlLegs control leg(s) is too few to run an A/A null, so this session cannot check itself. " +
                        "Five legs gives three controls and is what the threshold below assumes."
                )
            case _ => Chunk.empty

    def nullNote(null_ : Maybe[Comparison]): String =
        null_ match
            case Maybe.Present(n) if n.deltas.forall(_.verdict == Verdict.Flat) =>
                f"A/A null: clean, no control row classified against another control leg (${n.deltas.size} rows).\n"
            case _ => ""

    /** What a two-sha comparison is structurally unable to say.
      *
      * A comparison of two commits measures the difference between two trees. Any statement of the
      * form "this row moved *because of* change X" additionally requires that X was isolated, and two
      * shas do not carry that: the partition between the changes inside the diff was never declared,
      * so the harness has nothing to attribute to.
      *
      * This is the failure the skill records as its worked example. Two changes shipped together, a
      * node-layout change and a currency hoist; the bundle was faster and the win was confidently
      * attributed first to one and then to the other, and both stories were wrong as told. What
      * settled it was running the pieces separately, which is a chain of shas rather than a pair.
      *
      * A configuration comparison, the same sha under two sets of JVM args, has nothing to partition
      * and gets no note.
      */
    def partitionNote(control: Run, variant: Run, chainLength: Int): String =
        if control.sha == variant.sha && control.benchmarkClass.nonEmpty && variant.benchmarkClass.nonEmpty
            && control.benchmarkClass != variant.benchmarkClass
        then
            // one sha, two benchmark classes: two implementations measured by their own classes, so no
            // source-level mechanism is attributable between them here, and it is not an A/A
            s"\nSame sha, two benchmark classes (`${control.benchmarkClass}` against `${variant.benchmarkClass}`): this compares the " +
                "implementations those classes exercise, row by row as they are named. Nothing between them is attributed by " +
                "source; the ladder on each side is what says where the time and the bytes went."
        else if control.sha == variant.sha then
            // a configuration comparison varies nothing but the JVM arguments, so those ARE the
            // change under test and the report is the only place a reader can see them. The
            // campaign's headline was stored without them and could be re-read forever without
            // anyone being able to say what it had measured.
            val diff = variant.jvmArgs.diff(control.jvmArgs) ++ control.jvmArgs.diff(variant.jvmArgs)
            if diff.isEmpty then
                if control.jvmArgs.isEmpty && variant.jvmArgs.isEmpty then
                    "\n⚠️  Same sha and no recorded JVM arguments on either leg, so nothing here says " +
                        "what was varied. Either this is an A/A, or it was measured before the harness " +
                        "recorded configuration and the difference is unrecoverable."
                else ""
            else
                s"\nSame sha, so this is a configuration comparison and the difference is exactly:\n" +
                    diff.map(a => s"  $a").mkString("\n")
        else if chainLength > 2 then
            s"\nThis is one step of a $chainLength-sha chain, so the delta above is the isolated contribution of " +
                "this step and nothing else moved with it."
        else
            "\n⚠️  Two shas, so no source-level mechanism is attributable from this comparison. The diff " +
                "between them may contain any number of changes and the partition between them was never declared, " +
                "so a sentence of the form 'this moved because of change X' is not supported by anything here, " +
                "however plausible X is. Declare a chain of shas to isolate one."

    /** A score rendered so its own digits survive.
      *
      * Two fixed decimals was the previous format and it hid the numbers behind a verdict. The row
      * `evalFixedOverhead` measures a few nanoseconds, so a real and cleanly separated regression
      * printed as `0.01 ± 0.00` against `0.01 ± 0.00` beside a `+26.2%`, with the error column reading
      * as though there were no error at all. The actual values were 0.005853 and 0.007364, which any
      * reader could have judged on sight.
      *
      * The skill requires that a reader can judge a result without asking what was measured or how
      * confident it is, and requires the error column specifically because a delta smaller than the
      * combined error is not a result. A format that rounds both to zero defeats both rules at once.
      */
    def score(x: Double): String =
        val a = Math.abs(x)
        if a == 0.0 then "0"
        else if a >= 100 then f"$x%.1f"
        else if a >= 1 then f"$x%.2f"
        else if a >= 0.01 then f"$x%.4f"
        else f"$x%.6f"

    def icon(v: Verdict): String =
        v match
            case Verdict.Faster          => "🟢"
            case Verdict.Flat            => "⚪"
            case Verdict.Regressed       => "🔴"
            case Verdict.BelowResolution => "🔵"

    /** Frames shown per side in the per-row CPU section. */
    val CpuFramesPerRow = 6

    def render(c: Comparison, chainLength: Int = 2): String =
        val control = c.control
        val variant = c.variant
        val scope =
            if control.wholeClass && variant.wholeClass then s"all ${control.declaredRows} rows"
            else s"${c.deltas.size} selected rows of ${control.declaredRows}, so this says nothing about the rest"
        val evidence =
            if control.evidence == Evidence.Full && variant.evidence == Evidence.Full then "full ladder"
            else "timing only, so no movement here is attributed"
        // keyed on whether any row earned a real threshold, not on the fork count. Five legs at -f 1
        // are five independent JVMs, so the claim rests on replication; keying this on forks made
        // every replicated bracket disclaim itself in the header while reporting df 3 in the footer.
        // `exists` and not `forall`: one unresolved row must not suppress the note for a session that
        // earned a threshold elsewhere.
        val earnedThreshold = c.deltas.exists(_.resolution.exists(_.df > 0))
        val forksNote       = if earnedThreshold then "" else ", no threshold was estimated, so this is diagnostic and not a claim"
        val drift     = Bench.band(control, variant)
        val bandNote =
            if control.session.driftPercent > 0 then f"drift $drift%.1f%% measured this session"
            else f"drift $drift%.1f%% assumed, not measured"

        val header =
            s"""|Control `${control.sha.take(10)}` (${control.label}) against variant `${variant.sha.take(10)}` (${variant.label}).
                |JMH -f ${variant.forks}$forksNote, $scope, $evidence, $bandNote.
                |Markers control ${control.markers.map(m => s"${m.name}=${m.count}").mkString(" ")} | variant ${variant.markers.map(m => s"${m.name}=${m.count}").mkString(" ")}
                |
                || | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
                ||---|---|---|---|---|---|---|---|---|---|""".stripMargin

        // a leg whose first measured iteration sits far from the rest was still warming up, and its
        // score and error both absorb that without showing it. Only the per-iteration series reveals
        // it, and for an ingested run it is the only steady-state signal that exists at all.
        val RampLimit = 0.05
        val ramped =
            c.deltas.flatMap { d =>
                Chunk.from(Seq(
                    d.control.warmupRamp.filter(_ > RampLimit).map(r => (d.row, "control", r)),
                    d.variant.warmupRamp.filter(_ > RampLimit).map(r => (d.row, "variant", r))
                ).flatMap(_.toOption))
            }
        val rampNote =
            if ramped.isEmpty then ""
            else
                "\n\u26a0\ufe0f  A leg's first measured iteration sits far from the rest, so it was still warming up and " +
                    "its score includes that ramp:\n" +
                    ramped.map((row, side, r) => f"  - $row%s ($side%s) first iteration is ${r * 100}%.0f%% from the median of the rest")
                        .mkString("\n")

        val resolutionNote =
            val unbounded = c.deltas.count(d => d.flatButUnbounded || d.verdict == Verdict.BelowResolution)
            val bounded   = c.deltas.flatMap(_.resolution)
            if bounded.nonEmpty then
                val worst = bounded.map(_.percent).max
                // df 0 marks a single-pair comparison: the bound is the legs' own reported error, not
                // a threshold estimated from replicates, and the two are not the same claim
                if bounded.head.df == 0 then
                    f"\nEvery flat row below is flat to within +-${worst}%.2f%%, the floor set by the legs' own reported " +
                        "error. These legs were not replicated, so this bounds the result without estimating the spread; " +
                        "a replicated bracket would give a real threshold and this does not."
                else
                    // the worst row's own lever, not a blanket "more legs": a floor-bound row is tightened only by more iterations per
                    // fork, and telling its reader to add legs is the advice item 6 exists to stop
                    val worstRes = bounded.maxBy(_.percent)
                    f"\nEvery flat row below is flat to within its own resolution, at worst +-${worst}%.2f%% " +
                        f"(alpha ${bounded.head.alpha}%.5f after correcting for ${c.deltas.size} rows, df ${bounded.head.df}). " +
                        s"To resolve the widest of them: ${worstRes.lever}."
            else if unbounded > 0 then
                // these rows are not flat: with one leg per arm there is no spread to estimate a
                // threshold from, so every row is unresolved. The previous text called them "flat rows",
                // which is what a reader would then believe about them
                s"\n⚠️  $unbounded rows could not be resolved: one leg per arm gives no spread to estimate a threshold from, so " +
                    "no row can be called flat or moved. Run at least two legs per arm (three controls for the A/A null)."
            else ""

        // the session's common-mode drift, and the rows that did not share it. Reported, never spent:
        // the replicate design cancels a movement every row shares, and the resolution already
        // carries each row's own spread. What the reader could not see before is which rows moved on
        // their own across the control legs by more than their threshold absorbs
        val driftNote =
            c.commonMode match
                case Maybe.Present(m) =>
                    val own = c.deltas.filter(_.driftsBeyondResolution)
                    f"\nControl legs drifted ${m}%+.1f%% across the session (median row trend, first leg to last), which the replicate " +
                        "design cancels." +
                        (if own.isEmpty then " No row's own control trend departs from it by more than its resolution."
                         else
                             " Rows whose own control trend departs from it by more than their resolution, so their verdict rides that movement:\n" +
                                 own.map(d => f"  - ${d.row}: ${d.driftResidual.getOrElse(0.0)}%.1f%% from the common mode against " +
                                     f"±${d.resolution.map(_.percent).getOrElse(0.0)}%.1f%%; look at that row's control legs before reading its delta")
                                     .mkString("\n"))
                case _ => ""

        val body = c.deltas.map { d =>
            val delta = if d.verdict == Verdict.BelowResolution then "below resolution" else f"${d.percent}%+.1f%%"
            // this row's OWN threshold, not the worst across the table. The footer reports only the
            // worst, which cannot answer the question a flat row actually raises: was it close to
            // resolving, or nowhere near? On the replicated sweep two rows at -12.5% and -10.7% came
            // back flat while a -7.1% row was a win, and the table gave no way to see why.
            val res = d.resolution.map(r => f"±${r.percent}%.1f%%").getOrElse("-")
            val alloc = d.allocDelta.map(a => f"$a%+.0f").getOrElse("-")
            val mech =
                if d.mechanism.nonEmpty then d.mechanism.mkString("; ")
                else if d.unexplained then "**none found**"
                else "-"
            f"| ${icon(d.verdict)} | `${d.row}` | ${d.control.mode} ${d.control.unit} | ${d.variant.count} | " +
                s"${score(d.control.score)} ± ${score(d.control.error)} | ${score(d.variant.score)} ± ${score(d.variant.error)} | $delta | $res | $alloc | $mech |"
        }.mkString("\n")

        val jit =
            if c.jitChanges.isEmpty then ""
            else "\nInlining changed:\n" + c.jitChanges.map(s => s"  - $s").mkString("\n")

        // `jitChanges` (jitShift) names only the decisive flips, a method inlined at every site on one
        // side and refused at every site on the other. A method whose site fractions moved without
        // flipping, six of ten refusals becoming two, is a real change the flip filter drops;
        // diffVerdicts, now reading the stored per-method sites both runs carry, names those too
        val partialJit =
            val flips = c.jitChanges.mkString("\n")
            val moved = LogCompilation.diffVerdicts(c.control.jit, c.variant.jit)
                .filterNot(v => flips.contains(v.method))
                .filter(v => v.before.sites > 0 && v.after.sites > 0)
            if moved.isEmpty then ""
            else
                "\nInlining verdicts shifted without flipping:\n" +
                    moved.map(v => s"  - ${v.method}: inlined ${v.before.inlined}/${v.before.sites} -> ${v.after.inlined}/${v.after.sites} sites").mkString("\n")

        val deoptShift =
            val cd = control.deopts.map(d => d.reason -> d.count).toMap
            val moved = variant.deopts.filter(d => Math.abs(d.count - cd.getOrElse(d.reason, 0)) > cd.getOrElse(d.reason, 0) / 4 + 5)
            if moved.isEmpty then ""
            else
                "\nDeoptimization changed, which no timing or allocation figure explains:\n" +
                    moved.map(d => s"  - ${d.reason}: ${cd.getOrElse(d.reason, 0)} -> ${d.count}").mkString("\n")

        val steadyState =
            val cs = Bench.stillCompiling(control)
            val vs = Bench.stillCompiling(variant)
            if cs.isEmpty && vs.isEmpty then ""
            else
                "\n\U0001f6d1 NOT STEADY STATE. These rows were still being compiled while they were measured, so the scores mix compiled " +
                    "and compiling code. Do not read the deltas above until this is diagnosed. Warming longer would hide it: a benchmark " +
                    "that needs unusual warmup is reporting something about the code under it.\n" +
                    (cs.map((r, p) => f"  - control $r%s spent ${p}%.1f%% of its window compiling") ++
                        vs.map((r, p) => f"  - variant $r%s spent ${p}%.1f%% of its window compiling")).mkString("\n") +
                    "\n  Start from the JIT metrics below: more tasks, more recompilation or more deopts on one leg is the cause, not noise."

        val jitTable =
            (control.jit_metrics, variant.jit_metrics) match
                case (Maybe.Present(c), Maybe.Present(v)) =>
                    def row(n: String, a: Double, b: Double, unit: String = "") =
                        val d = if a == 0.0 then "" else f" (${(b - a) / a * 100}%+.0f%%)"
                        f"| $n%-24s | $a%10.0f$unit | $b%10.0f$unit |$d |"
                    // the two halves of this table are different scopes on a multi-row leg. Compile
                    // time is summed across the leg's rows (-prof comp reports it per fork), but the
                    // task census is one row's fork only: a multi-row leg forks one JVM per benchmark
                    // and each writes the same LogFile, so the last one wins. Saying so stops the table
                    // reading as a single census until per-row compilation logs land
                    val rows     = Math.max(control.rows.size, variant.rows.size)
                    val scopeNote =
                        if rows > 1 then
                            s"\n(compile time is summed across the leg's $rows rows; the task census below it is one row's fork only, " +
                                "until per-row compilation logs land)"
                        else ""
                    "\n\nJIT cost, a property of the design more than of the run:" + scopeNote + "\n" +
                        "| metric | control | variant | |\n|---|---|---|---|\n" +
                        Seq(
                            row("compiling in window", c.msInWindow, v.msInWindow, "ms"),
                            row("compiling total", c.msTotal, v.msTotal, "ms"),
                            row("compilation tasks", c.tasks.toDouble, v.tasks.toDouble),
                            row("C2 tasks", c.c2Tasks.toDouble, v.c2Tasks.toDouble),
                            row("OSR tasks", c.osrTasks.toDouble, v.osrTasks.toDouble),
                            row("methods recompiled", c.recompiled.toDouble, v.recompiled.toDouble),
                            // runtime events only. The planted-guard census used to sit in this row
                            // and swamped it: 633 guards against 6 real deoptimizations.
                            row("runtime deopts", c.runtimeDeopts.toDouble, v.runtimeDeopts.toDouble),
                            row("made not entrant", c.madeNotEntrant.toDouble, v.madeNotEntrant.toDouble),
                            row("last compile at", c.lastCompileAt, v.lastCompileAt, "s")
                        ).mkString("\n")
                case _ => ""

        val polymorphic =
            // only sites the JIT actually profiled a receiver for. The previous version filtered on
            // `!monomorphic`, which included every unprofiled site, and printed 46 of 47 of them
            // under a heading promising measured receiver counts while each had a receiver count of
            // zero. Absence of a profile is not evidence of polymorphism.
            val poly = variant.morphism.filter(_.monomorphic.contains(false)).take(3)
            if poly.isEmpty then ""
            else
                "\nPolymorphic call sites, among the few the JIT profiled a receiver for:\n" +
                    poly.map(m => s"  - ${m.callee} ${m.count} calls, ${m.receiverCount} to the top receiver").mkString("\n")

        // an allocation change is exact and per-operation, so it does not need the timing to resolve.
        // A row can be flat in time and still allocate 240,000 B/op more, and suppressing the
        // mechanism on flat rows hid exactly that: the only visible trace was a number in a column.
        val allocMoved =
            c.deltas.filter(d => d.verdict != Verdict.Regressed && d.allocDelta.exists(a => Math.abs(a) > 1.0))
        val allocNote =
            if allocMoved.isEmpty then ""
            else
                "\n\u2139\ufe0f  Allocation moved on rows whose timing did not resolve. Allocation is exact and per-operation, " +
                    "so this is a real change regardless of what the timing could or could not show:\n" +
                    allocMoved.map(d => f"  - ${d.row}%s ${d.allocDelta.getOrElse(0.0)}%+.0f B/op, timing ${d.percent}%+.1f%% (${d.verdict})").mkString("\n")

        // which method minted the class whose bytes moved. The flat table can only say that `Nested`
        // is half the allocation; this says half the allocation is `Nested`, minted at one site.
        val allocSites =
            val before = control.allocByMethod.map(m => (m.cls, m.method) -> m).toMap
            val after  = variant.allocByMethod.map(m => (m.cls, m.method) -> m).toMap
            val moved =
                (before.keySet ++ after.keySet).toSeq.flatMap { k =>
                    val b = before.get(k).map(_.samples).getOrElse(0L)
                    val a = after.get(k).map(_.samples).getOrElse(0L)
                    // a quarter of the larger side, so ordinary sampling jitter does not fill the list
                    if Math.abs(a - b) * 4 > Math.max(a, b) then Seq((k, b, a)) else Nil
                }.sortBy((_, b, a) => -Math.abs(a - b)).take(6)
            if moved.isEmpty then ""
            else
                "\nAllocation moved at these sites, by sample count:\n" +
                    moved.map((k, b, a) => s"  - ${k._1} at ${k._2}: $b -> $a").mkString("\n") +
                    "\n  These are whole-leg figures, not per row, and the frame named is where the JIT *placed* the " +
                    "allocation. An inlining change relocates it with nothing about the allocation changing, so this " +
                    "is never independent of the inlining verdicts above."

        // methods the variant refuses for size while sitting close enough to a budget that one shrink
        // could inline them. The investigator only proposes raising the budget when a row regressed, so
        // on a flat or winning report a method sitting 379B against a 325B budget was invisible; it is a
        // standing lever whatever this comparison's verdict, so it is named here unconditionally
        val budgetNote =
            val near = c.variant.jit.flatMap(v => v.nearBudget.map(b => s"  - ${v.method}: $b"))
            if near.isEmpty then ""
            else
                "\n\n📏 Refused for size but close to a budget, one shrink from inlining whatever the verdict above:\n" +
                    near.mkString("\n")

        val reds        = c.deltas.filter(_.verdict == Verdict.Regressed)
        val wins        = c.deltas.filter(_.verdict == Verdict.Faster)
        val unexplained = c.deltas.filter(_.unexplained)

        val sessionWarning =
            if Bench.sameSession(control, variant) then ""
            else
                // comparing across sessions is what made a parity row look like a regression
                "\u274c These runs are from different sessions, so the deltas below are not comparable. " +
                    "Re-measure the control beside the variant.\n\n"

        // the three-way split of the variant's sampled time. The retired two-way version called
        // everything outside the kernel immovable, which lumped the benchmark's own generated closures,
        // the workload the kernel's dispatch decides, in with genuinely external frames like
        // boxToInteger. Stated as three shares with the largest frames over ALL of them (not the
        // non-kernel subset, which ranked a 40% frame under a sentence about a 36% one), so the reader
        // sees the biggest lever whichever bucket it sits in. The kernel share is a lower bound: inlined
        // kernel code is credited to the frame it was inlined into.
        val cpuNote =
            val cpu = variant.cpu
            if cpu.isEmpty then ""
            else
                val p     = Bench.cpuPartition(cpu)
                val total = cpu.map(_.nanos).sum.toDouble
                val frames =
                    cpu.groupBy(_.method).toSeq
                        .map((m, sites) => (m, sites.map(_.nanos).sum.toDouble))
                        .sortBy(-_._2).take(3)
                        .map((m, n) => f"\n      ${n / total * 100}%5.1f%%  $m")
                        .mkString
                f"\n\u2139\ufe0f  Sampled time: kernel ${p.kernel}%.0f%%, benchmark ${p.benchmark}%.0f%%, other ${p.other}%.0f%%. " +
                    "The kernel share is a lower bound, inlined kernel code is credited to its inlining frame; the benchmark share is " +
                    "its own generated code, the workload the kernel drives, not fixed overhead. Largest frames:" + frames

        // per-row CPU, when both sides carry a row profile: where each row's time went on each side,
        // the kernel/benchmark/other split and the frames behind it, so a delta can be read against the
        // code that spent it rather than against a run-level share
        val cpuByRow =
            val rows = c.deltas.filter(d => d.control.cpu.nonEmpty && d.variant.cpu.nonEmpty)
            if rows.isEmpty then ""
            else
                def side(name: String, cpu: Chunk[CpuSite]): String =
                    val p     = Bench.cpuPartition(cpu)
                    val total = cpu.map(_.nanos).sum.toDouble
                    val top =
                        cpu.sortBy(-_.nanos).take(CpuFramesPerRow)
                            .map(s => f"${s.nanos / total * 100}%5.1f%% ${s.method}")
                            .mkString("\n        ", "\n        ", "")
                    f"    $name%-8s kernel ${p.kernel}%5.1f%%  benchmark ${p.benchmark}%5.1f%%  other ${p.other}%5.1f%%$top"
                "\n\n\uD83D\uDD25 CPU by row (sampled time, top frames each side):\n" +
                    rows.map { d =>
                        s"  ${icon(d.verdict)} `${d.row}` ${f"${d.percent}%+.1f%%"}\n" +
                            side("control", d.control.cpu) + "\n" + side("variant", d.variant.cpu)
                    }.mkString("\n")

        val bothWays =
            if wins.isEmpty || reds.isEmpty then ""
            else
                "\n\u26a0\ufe0f  This change both wins and loses. Those are two diagnoses, not one tradeoff: the loss usually turns out " +
                    "removable, and accepting it early ships a defect the same afternoon's work would have deleted."

        val resolved = c.deltas.count(d => d.verdict != Verdict.BelowResolution)

        val verdictLine =
            // every row unresolvable is not a clean result. The branch order previously fell through
            // to the green all-clear whenever no row regressed, including when no row could be read
            // at all, and the resolution note stayed silent because it counted only Flat rows.
            if resolved == 0 && c.deltas.nonEmpty then
                s"\n\u26a0\ufe0f  Nothing was resolvable: all ${c.deltas.size} rows fell below the measurement's own error. " +
                    "This is not a clean run, it is an unreadable one; re-measure with more forks before drawing any conclusion."
            else if reds.nonEmpty then
                "\n🔴 Regressed, so the work is unfinished until each is diagnosed or ruled on:\n" +
                    reds.map(d => f"  - ${d.row} ${d.percent}%+.1f%%").mkString("\n")
            else if !(control.wholeClass && variant.wholeClass) then
                // the claim this refuses to make is the exact false one a subset run invited before
                "\n⚪ No selected row regressed. This was a subset run, so it is not a statement about the suite."
            else "\n🟢 No row regressed beyond the drift band, across the whole class."

        val ladder =
            if unexplained.isEmpty then ""
            else
                // do not send the operator to an inlining log that does not exist. On a Timing pair the
                // jit and allocation evidence was never collected, so "check the inlining log" is
                // homework pointing at empty data (defect 43); name the absent evidence and its command
                // instead. On a Full pair the evidence is above, so point at it
                val timing = control.evidence == Evidence.Timing || variant.evidence == Evidence.Timing
                val advice =
                    if timing then "no mechanism evidence was collected (this pair is --evidence timing); re-run --evidence full for inlining and allocation data"
                    else "check this row's allocation sites and inlining verdicts above before proposing a mechanism"
                "\n⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:\n" +
                    unexplained.map(d => s"  - ${d.row}: $advice").mkString("\n")

        // absence is a result, and it names its remedy. The operator this tool exists for forgets to go
        // looking, so the report enumerates the checks that did not run, why, and the exact command that
        // would run them, converting silence into a checklist. Everything here is computable from the two
        // runs and the leg count the comparison carries
        val notEvaluated =
            val items = Chunk.from(Seq(
                Option.when(control.evidence == Evidence.Timing || variant.evidence == Evidence.Timing)(
                    "no inlining, allocation or CPU evidence: this pair was measured with --evidence timing. " +
                        "To get it, re-run: bench bracket --evidence full"),
                Option.when(!(control.wholeClass && variant.wholeClass))(
                    "no statement about the whole class: this was a subset run of selected rows. " +
                        "To get it, re-run without --row"),
                Option.when(c.allControlLegs.size < 3)(
                    s"no A/A null, so the session did not check itself: ${c.allControlLegs.size} control leg(s), fewer than the three a " +
                        "self-check needs. To get it, re-run: bench bracket --legs 5")
            ).flatten)
            if items.isEmpty then ""
            else "\n\nNot evaluated here, and how to evaluate it:\n" + items.map(s => s"  - $s").mkString("\n")

        val blockerBanner =
            val bs = blockers(c)
            if bs.isEmpty then ""
            else
                "\n" + "=" * 78 + "\n\u26d4 NOT A VALID MEASUREMENT: " + bs.size + " leg(s) did not reach steady state.\n" +
                    "The rows below are printed in full, and none of their verdicts can be trusted: a score that\n" +
                    "mixes warm and cold code is not a measurement of the code. Re-run with more warmup.\n" +
                    bs.map(b => s"  - $b").mkString("\n") + "\n" + "=" * 78 + "\n"

        val partition = partitionNote(control, variant, chainLength)

        // the next experiment is part of the report, not something to be asked for. A delta with no
        // falsifier attached is where "it is slower, so replace it" comes from.
        val investigation = Investigate.render(c)

        s"$blockerBanner$sessionWarning$header\n$body$rampNote$resolutionNote$driftNote$jit$partialJit$deoptShift$polymorphic$allocSites$allocNote$partition$verdictLine$ladder$steadyState$jitTable$bothWays$cpuNote$cpuByRow$budgetNote$investigation$notEvaluated"
    end render

end Report
