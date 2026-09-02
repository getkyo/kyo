import Model.*
import caseapp.*
import kyo.*

/** Subcommands.
  *
  * `run` measures one leg and stores it. `compare` and `show` read stored runs and never measure, which is what makes a reading
  * reproducible: the same two run ids always produce the same verdict.
  */
@AppName("bench")
case class RunOpts(
    @HelpMessage("throwaway worktree to measure in; must not be the primary one")
    worktree: String,
    @HelpMessage("session id from a previous leg; omit to open a new session (a session groups legs; a bracket estimates its spread from its own replicate legs)")
    session: Option[String] = None,
    @HelpMessage("label for this leg, e.g. control or variant")
    label: String,
    @HelpMessage("commit whose sources the leg measures")
    sha: String,
    @HelpMessage("benchmark rows; omit to measure the whole class, which is what a suite-wide claim requires")
    row: List[String] = Nil,
    @HelpMessage("JMH forks; 3 for a claim, 1 for diagnosis")
    forks: Int = 3,
    @HelpMessage("evidence to collect: full (default) walks the whole ladder, timing measures only wall clock")
    evidence: String = "full",
    @HelpMessage("where runs are stored")
    store: String = "bench-runs"
)

case class CompareOpts(
    @HelpMessage("run id of a control leg, repeatable; give every control leg of a bracket to get its replicated verdict back")
    control: List[String],
    @HelpMessage("run id of a variant leg, repeatable")
    variant: List[String],
    store: String = "bench-runs"
)

case class BracketOpts(
    @HelpMessage("throwaway worktree to measure in; must not be the primary one")
    worktree: String,
    @HelpMessage("commit whose sources the control legs measure")
    control: String,
    @HelpMessage("commit whose sources the variant legs measure; equal to --control for a configuration comparison")
    variant: String,
    @HelpMessage("extra JVM args for the control legs, repeatable")
    controlJvm: List[String] = Nil,
    @HelpMessage("extra JVM args for the variant legs, repeatable; this is how a configuration is compared rather than a design")
    variantJvm: List[String] = Nil,
    @HelpMessage("benchmark rows; omit to measure the whole class, which is what a suite-wide claim requires")
    row: List[String] = Nil,
    @HelpMessage("JMH forks per leg; 1 is usually right here since the bracket replicates legs instead")
    forks: Int = 1,
    @HelpMessage("legs to run; five gives three controls against two variants, and three degrees of freedom")
    legs: Int = 5,
    evidence: String = "full",
    store: String = "bench-runs"
)

case class IngestOpts(
    @HelpMessage("JMH json files to ingest, in order; label and sha are taken from --label/--sha positionally")
    json: List[String],
    @HelpMessage("labels, one per json")
    label: List[String],
    @HelpMessage("commit each json was measured at, one per json (or one for all)")
    sha: List[String],
    @HelpMessage("session id to file them under; they must share one to be comparable")
    session: String = "ingested",
    @HelpMessage("rows the benchmark class declares; a json with fewer is a subset run")
    declaredRows: Int = 15,
    @HelpMessage("split a -f N json into N legs, one per fork (one per JVM), so BenchCompare can give the replicated verdict and check each JVM's steady state")
    perFork: Boolean = false,
    @HelpMessage("the --json files are JMH text logs (sbt output of a Jmh/run), read for their measured iterations and -prof gc secondaries; the log survives when a json does not")
    log: Boolean = false,
    store: String = "bench-runs"
)

case class PlanOpts(
    @HelpMessage("stored runs on the rows you intend to measure; give several legs of the same configuration for a between-leg estimate, which is the quantity a bracket's threshold actually rests on")
    from: List[String],
    @HelpMessage("legs the planned bracket would run")
    legs: Int = 5,
    @HelpMessage("effect size you need to detect, as a percentage")
    target: Double = 5.0,
    store: String = "bench-runs"
)

case class ChainOpts(
    @HelpMessage("throwaway worktree to measure in; must not be the primary one")
    worktree: String,
    @HelpMessage("commits in order, oldest first, each isolating one change from the one before it; three or more, since two is a pair and isolates nothing")
    sha: List[String],
    @HelpMessage("benchmark rows; omit to measure the whole class")
    row: List[String] = Nil,
    @HelpMessage("legs per sha; two gives the chain a spread to threshold against")
    legs: Int = 2,
    forks: Int = 1,
    evidence: String = "timing",
    store: String = "bench-runs"
)

case class ShowOpts(id: String, store: String = "bench-runs")

case class CpuOpts(
    @HelpMessage("stored run whose rows receive the profile; for a per-fork bracket, the head leg of each arm is what the report shows")
    run: String,
    @HelpMessage("JMH log of a `-prof async:event=itimer` invocation over the same rows; one flat table per `# Benchmark:` header")
    log: String,
    store: String = "bench-runs"
)
case class JitOpts(
    @HelpMessage("stored run whose inlining decisions and compilation metrics are filled from the log")
    run: String,
    @HelpMessage("a `-XX:+PrintCompilation ... +PrintInlining` or LogCompilation log of the same fork; a JMH json alone carries no inlining")
    log: String,
    store: String = "bench-runs"
)
case class ListOpts(store: String = "bench-runs")
case class ExpansionOpts(
    @HelpMessage("a file in bench-harness/fixtures-expansion, without .scala")
    fixture: String,
    @HelpMessage("compiler phase to print after")
    phase: String = "inlining"
)

object Cli:

    val markerSpecs = Seq(
        ("fusionLaw", "Arrow.AndThen", "kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala"),
        ("gateCont", "isInstanceOf[Arrow.Cont[", "kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala"),
        ("fastPathsOn", "def fastPathsAllowed: Boolean = true", "kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Debugger.scala")
    )

    val kernelPaths = Seq(
        "kyo-kernel/shared/src",
        "kyo-kernel/jvm/src",
        "kyo-kernel/jvm-native/src",
        "kyo-kernel/js-wasm/src",
        "kyo-data/shared/src"
    )

    def parseEvidence(s: String)(using Frame): Evidence < Abort[Bench.BracketFailed] =
        s.toLowerCase match
            case "full"   => Evidence.Full
            case "timing" => Evidence.Timing
            case other    => Abort.fail(Bench.BracketFailed(s"unknown evidence '$other'; use full or timing"))

    /** How a failure reaches the operator.
      *
      * An expected failure is a diagnosis: one block of text saying what was wrong and what to do,
      * then exit 1. Left to the default path it arrives instead as `Failure(Bench$BracketFailed: ...)`
      * followed by the same text again under `Exception in thread "main"`, because the app runner
      * prints the result and then rethrows any Throwable error. Two renders of one problem, both
      * wearing an internal class name, is what a reader learns to skim.
      *
      * A panic is the opposite case and keeps its stack, because it is a defect in this harness rather
      * than a mistake at the command line, and the frames are the only thing that locates it.
      */
    def guard[A](v: A < (Async & Scope & Abort[Any]))(using Frame): Unit < (Async & Scope) =
        Abort.run[Any](v).map {
            case Result.Success(_) => ()
            case Result.Failure(e) =>
                val message =
                    e match
                        case Bench.BracketFailed(reason) => reason
                        case t: Throwable                => Maybe(t.getMessage).getOrElse(t.toString)
                        case other                       => other.toString
                Console.printLine(s"⛔ $message").andThen(exit(1))
            case Result.Panic(t) =>
                Console.printLine("⛔ the harness itself failed, which is a bug in it and not in the command:")
                    .andThen(Sync.defer(t.printStackTrace()))
                    .andThen(exit(2))
        }

    private def exit(code: Int)(using Frame): Nothing < Sync =
        // Unsafe: an application entrypoint is the one place a process exit is the correct
        // expression of a failure, and it is what keeps the diagnosis above from being followed by
        // the runner's own second rendering of the same error.
        Sync.defer(java.lang.System.exit(code)).andThen(Sync.defer(throw new IllegalStateException("unreachable")))

end Cli

object BenchRun extends KyoCaseApp[RunOpts]:
    run { (opts: RunOpts) =>
        Cli.guard(
        for
            evidence <- Cli.parseEvidence(opts.evidence)
            // a session groups the legs that are compared to each other; the spread comes from the
            // bracket's own replicate legs, not from anything measured when the session opens
            session <- opts.session match
                case Some(id) => Store.session(Path(opts.store), id)
                case None     => Bench.openSession(Path(opts.worktree))
            leg <- Bench.runLeg(
                session = session,
                worktree = Path(opts.worktree),
                label = opts.label,
                sha = opts.sha,
                paths = Cli.kernelPaths,
                markerSpecs = Cli.markerSpecs,
                rows = opts.row,
                forks = opts.forks,
                evidence = evidence
            )
            file <- Store.save(Path(opts.store), leg)
            _    <- Console.printLine(s"stored ${leg.id} (${leg.rows.size} rows, ${leg.evidence}) at $file")
        yield ()
        )
    }
end BenchRun

/** Runs a whole bracket and reports it, which is the only shape that can support a threshold.
  *
  * A single pair can be classified against a drift band but cannot say how small an effect it would
  * have caught, so every flat row it produces is unbounded. This replicates instead, and reports the
  * A/A null first: if the control legs disagree with each other, nothing below that is readable.
  */
object BenchBracket extends KyoCaseApp[BracketOpts]:
    run { (opts: BracketOpts) =>
        Cli.guard(
        for
            evidence <- Cli.parseEvidence(opts.evidence)
            session  <- Bench.openSession(Path(opts.worktree))
            legs <- Bench.bracket(
                session = session,
                worktree = Path(opts.worktree),
                control = Bench.Arm(opts.control, opts.controlJvm),
                variant = Bench.Arm(opts.variant, opts.variantJvm),
                paths = Cli.kernelPaths,
                markerSpecs = Cli.markerSpecs,
                rows = opts.row,
                forks = opts.forks,
                evidence = evidence,
                legs = opts.legs
            )
            (controls, variants) = legs
            _ <- Kyo.foreachDiscard(controls ++ variants)(r => Store.save(Path(opts.store), r).unit)
            aa  = Bench.nullComparison(controls)
            cmp = Bench.compareReplicated(controls, variants)
            // the null's verdict and the steady-state verdict are the same kind of statement, that
            // the numbers below cannot be read, so they are one banner and one exit code. Reporting
            // a dirty null as a line of text and exiting 0 is the failure this tool exists to refuse.
            blockers = Report.nullBlockers(aa, controls.size, required = true) ++ Report.blockers(cmp)
            _ <- Console.printLine(
                if blockers.isEmpty then Report.nullNote(aa)
                else
                    "\n" + "=" * 78 + s"\n\u26d4 NOT A VALID SESSION: ${blockers.size} reason(s).\n" +
                        blockers.map(b => s"  - $b").mkString("\n") + "\n" + "=" * 78 + "\n"
            )
            _ <- Console.printLine(Report.render(cmp))
            _ <- Abort.when(blockers.nonEmpty)(
                Bench.BracketFailed(s"${blockers.size} reason(s) make this session unreadable; see the banner above")
            )
        yield ()
        )
    }
end BenchBracket

/** Brings measurements taken outside the harness under it.
  *
  * Without this the tool can only speak about runs it produced itself, which is what drove an entire
  * campaign's worth of verdicts into hand-written python.
  */
object BenchIngest extends KyoCaseApp[IngestOpts]:
    run { (opts: IngestOpts) =>
        val session = Session(opts.session, "ingested", "unknown", 0.0)
        Cli.guard(
        for
            _ <- Abort.when(opts.json.size != opts.label.size)(
                Bench.BracketFailed(s"${opts.json.size} json files but ${opts.label.size} labels; they must correspond")
            )
            // a third sha for two files means one of the three is going somewhere the operator did
            // not intend, and the leftover would otherwise be dropped without a word
            _ <- Abort.when(opts.sha.size != 1 && opts.sha.size != opts.json.size)(
                Bench.BracketFailed(
                    s"${opts.json.size} json files but ${opts.sha.size} shas; give one per file, or one for all of them"
                )
            )
            runs <- Kyo.foreach(Chunk.from(opts.json.zip(opts.label).zipWithIndex)) { case ((j, l), i) =>
                val sha = opts.sha.lift(i).orElse(opts.sha.headOption).getOrElse("unknown")
                if opts.log then Ingest.logFile(Path(j), l, sha, session, Path(opts.store), opts.declaredRows, opts.perFork)
                else if opts.perFork then Ingest.filePerFork(Path(j), l, sha, session, Path(opts.store), opts.declaredRows)
                else Ingest.file(Path(j), l, sha, session, Path(opts.store), opts.declaredRows).map(Chunk(_))
            }
            _ <- Kyo.foreachDiscard(runs.flatten) { r =>
                Console.printLine(
                    f"ingested ${r.id}%-52s ${r.rows.size}%2d rows  -f ${r.forks} -wi ${r.warmup}  ${if r.wholeClass then "whole class" else "SUBSET"}"
                )
            }
            _ <- Console.printLine(
                if opts.perFork then
                    "\nSplit per fork: each leg above is one JVM. Pass all of one configuration's legs as --control " +
                        "and the other's as --variant to BenchCompare for the replicated verdict; forks of one row ran " +
                        "back to back and not interleaved with the other arm, so drift stays assumed."
                else if runs.flatten.exists(_.forks > 1) then
                    "\nThis json holds several forks per row, read here as one leg each. Ingest it again with " +
                        "--per-fork to get one leg per JVM and the replicated verdict the forks can support."
                else ""
            )
            _ <- Console.printLine(
                "\nThese are timing-only runs: no markers, no tree hash, no evidence ladder. The report will " +
                    "refuse to attribute any movement in them to a mechanism, which is correct, because nothing " +
                    "about how they were produced was under the harness's control."
            )
        yield ()
        )
    }
end BenchIngest

/** Says what a planned session could detect, before it is spent.
  *
  * Three runs were spent reporting a 25% timing regression on a row that resolves to ±22.6% and
  * cannot support a verdict of that size. Each was believed at the time. This answers that question
  * from data already stored.
  */
object BenchPlan extends KyoCaseApp[PlanOpts]:
    run { (opts: PlanOpts) =>
        Cli.guard(
        for
            priors <- Kyo.foreach(Chunk.from(opts.from))(id => Store.load(Path(opts.store), id))
            prior = priors.head
            fs    = Plan.forecast(priors, opts.legs, Bench.FamilyAlpha)
            blind = Plan.blind(fs, opts.target / 100.0)
            _ <- Console.printLine(
                f"Planning ${opts.legs} legs against a target of ±${opts.target}%.1f%%, from ${prior.rows.size} rows across ${priors.size} prior leg(s).\n" +
                    (if priors.size < 2 then "One leg only, so the estimate uses within-leg error, which is a different quantity from the between-leg spread a threshold rests on. Give more legs for a real forecast.\n" else "")
            )
            _ <- Kyo.foreachDiscard(fs)(f => Console.printLine("  " + f.show))
            _ <- Console.printLine(
                if blind.isEmpty then f"\nEvery row can resolve ±${opts.target}%.1f%% at this configuration."
                else
                    f"\n\u26a0\ufe0f  ${blind.size} row(s) cannot resolve ±${opts.target}%.1f%% and will report flat whatever happens:\n" +
                        blind.map(f => s"  - ${f.row}: ${f.lever}").mkString("\n")
            )
        yield ()
        )
    }
end BenchPlan

/** Isolates a source-level mechanism, which a pair of shas structurally cannot.
  *
  * A bracket measures the difference between two trees. Attributing that difference to one change
  * inside it requires the partition to have been declared, and a pair does not declare one. A chain
  * does: each step is one change, and each adjacent comparison is that change's isolated contribution.
  */
object BenchChain extends KyoCaseApp[ChainOpts]:
    run { (opts: ChainOpts) =>
        Cli.guard(
        for
            // before the worktree is touched at all: a chain that is not one should cost nothing
            _ <- Kyo.foreachDiscard(Chunk.from(Bench.requireChain(opts.sha).toList))(w =>
                Abort.fail[Bench.BracketFailed](Bench.BracketFailed(w))
            )
            evidence <- Cli.parseEvidence(opts.evidence)
            session  <- Bench.openSession(Path(opts.worktree))
            steps <- Bench.chain(
                session = session,
                worktree = Path(opts.worktree),
                shas = opts.sha,
                paths = Cli.kernelPaths,
                markerSpecs = Cli.markerSpecs,
                rows = opts.row,
                forks = opts.forks,
                evidence = evidence,
                legsPerSha = opts.legs
            )
            _ <- Kyo.foreachDiscard(steps.flatMap(_._2))(r => Store.save(Path(opts.store), r).unit)
            _ <- Console.printLine(
                s"Chain of ${steps.size} shas, ${opts.legs} legs each. Each comparison below is one step's " +
                    "isolated contribution, which is the only shape that can carry a source-level mechanism.\n"
            )
            blockers <- Kyo.foreach(Chunk.from(steps.sliding(2).toSeq).filter(_.size == 2)) { pair =>
                val (fromSha, fromLegs) = pair(0)
                val (toSha, toLegs)     = pair(1)
                val cmp                 = Bench.compareReplicated(fromLegs, toLegs)
                Console.printLine(s"\n${"-" * 78}\n${fromSha.take(10)} -> ${toSha.take(10)}\n")
                    .andThen(Console.printLine(Report.render(cmp, chainLength = steps.size)))
                    .andThen(Report.blockers(cmp))
            }.map(_.flatten)
            _ <- Abort.when(blockers.nonEmpty)(
                Bench.BracketFailed(s"${blockers.size} step(s) of the chain are not readable:\n${blockers.map(b => s"  - $b").mkString("\n")}")
            )
        yield ()
        )
    }
end BenchChain

object BenchCompare extends KyoCaseApp[CompareOpts]:
    run { (opts: CompareOpts) =>
        Cli.guard(
        for
            controls <- Kyo.foreach(Chunk.from(opts.control))(id => Store.load(Path(opts.store), id))
            variants <- Kyo.foreach(Chunk.from(opts.variant))(id => Store.load(Path(opts.store), id))
            _ <- Abort.when(controls.isEmpty || variants.isEmpty)(
                Bench.BracketFailed("give at least one --control and one --variant")
            )
            // A bracket's replicated verdict used to be visible only while the bracket ran. Once its
            // legs were stored, the only thing recoverable from them was the single-pair statistic,
            // which is the weaker one: on the replicated sweep the pair says five wins and the
            // replicate statistic says three. So the harness's own principle, that a comparison is a
            // pure function over records and two run ids always produce the same verdict, held for
            // the weaker claim and not for the stronger one.
            replicated = controls.size > 1 || variants.size > 1
            cmp        = if replicated then Bench.compareReplicated(controls, variants) else Bench.compare(controls.head, variants.head)
            // the A/A null is a property of the control legs, not of how they were produced, so a
            // stored bracket (or a -f N json split per fork) gets the same check BenchBracket runs
            // live. It needs three control legs; with fewer it is not run and not demanded here,
            // since a single pair never claimed a threshold in the first place.
            aa = Bench.nullComparison(controls)
            _ <- Console.printLine(
                if replicated then
                    s"Replicated over ${controls.size} control and ${variants.size} variant leg(s): the threshold below is " +
                        "estimated from the spread between them.\n"
                else
                    "One control leg against one variant, so the bound below is the legs' own error and not a " +
                        "threshold estimated from replicates. Pass every leg of a bracket to get its real verdict.\n"
            )
            // an ad-hoc compare is not required to run a null: a single pair, or fewer than three
            // controls, never claimed a threshold to self-check. A dirty null is still a blocker
            nullBlockers = Report.nullBlockers(aa, controls.size, required = false)
            blockers     = nullBlockers ++ Report.blockers(cmp)
            // the steady-state banner is the report's own; only the null's verdict is added here
            _ <- Console.printLine(
                if nullBlockers.isEmpty then Report.nullNote(aa)
                else
                    "\n" + "=" * 78 + s"\n⛔ NOT A VALID MEASUREMENT: the A/A null is dirty.\n" +
                        nullBlockers.map(b => s"  - $b").mkString("\n") + "\n" + "=" * 78 + "\n"
            )
            _ <- Console.printLine(Report.render(cmp))
            // a non-steady-state leg or a dirty null fails the run rather than warning inside it. A
            // warning is something a reader skips; an exit code is not, and this tool exists for a
            // reader who demonstrably skips them.
            _ <- Abort.when(blockers.nonEmpty)(
                Bench.BracketFailed(s"${blockers.size} reason(s) make these verdicts unreadable; see the banner above")
            )
        yield ()
        )
    }
end BenchCompare

/** Attaches a CPU profile to a stored run, row by row.
  *
  * The CPU pass is a separate JMH invocation, so it cannot ride in the timing json; without this the
  * only CPU evidence the harness could hold was what its own ladder collected for the one class it
  * knows, and a comparison of two benchmark classes had none.
  */
object BenchCpu extends KyoCaseApp[CpuOpts]:
    run { (opts: CpuOpts) =>
        Cli.guard(
        Ingest.attachCpuFile(Path(opts.store), opts.run, Path(opts.log)).map { r =>
            val p = Bench.cpuPartition(r.cpu)
            Console.printLine(
                f"attached cpu to ${r.id}: ${r.rows.count(_.cpu.nonEmpty)} rows profiled, " +
                    f"kernel ${p.kernel}%.1f%% benchmark ${p.benchmark}%.1f%% other ${p.other}%.1f%% of sampled time"
            )
        }
        )
    }
end BenchCpu

/** Attaches inlining decisions to a stored run from a compilation log.
  *
  * The compilation pass is a separate JMH invocation from the timing json, so a run built by `ingest` from a json has no `jit` at all;
  * this fills it, the compilation metrics, deopts and call morphism from the log of the same fork, the log analogue of `cpu`.
  */
object BenchJit extends KyoCaseApp[JitOpts]:
    run { (opts: JitOpts) =>
        Cli.guard(
        Ingest.attachJitFile(Path(opts.store), opts.run, Path(opts.log)).map { r =>
            val refused = r.jit.count(_.refused > 0)
            Console.printLine(
                s"attached jit to ${r.id}: ${r.jit.size} kyo. methods, $refused with a refusal" +
                    r.jit_metrics.map(m => s"; ${m.tasks} tasks, ${m.c2Tasks} at C2, ${m.runtimeDeopts} runtime deopts").getOrElse("")
            )
        }
        )
    }
end BenchJit

object BenchShow extends KyoCaseApp[ShowOpts]:
    run { (opts: ShowOpts) =>
        Cli.guard(
        Store.load(Path(opts.store), opts.id).map { r =>
            Console.printLine(
                s"""|${r.id}
                    |  sha ${r.sha} label ${r.label} forks ${r.forks} evidence ${r.evidence}
                    |  scope ${if r.wholeClass then s"whole class (${r.declaredRows} rows)" else s"${r.rows.size} selected rows"}
                    |  markers ${r.markers.map(m => s"${m.name}=${m.count}").mkString(" ")}
                    |  rows ${r.rows.size}, jit entries ${r.jit.size}, alloc sites ${r.alloc.size}, cpu sites ${r.cpu.size}""".stripMargin
            )
        }
        )
    }
end BenchShow

object BenchList extends KyoCaseApp[ListOpts]:
    run { (opts: ListOpts) =>
        Cli.guard(
        Store.list(Path(opts.store)).map { runs =>
            if runs.isEmpty then Console.printLine("no runs stored")
            else
                Kyo.foreachDiscard(runs.sortBy(_.recordedAt)) { r =>
                    Console.printLine(f"${r.id}%-44s ${r.label}%-10s ${r.evidence}%-7s ${r.rows.size}%3d rows  ${r.recordedAt}")
                }
        }
        )
    }
end BenchList

object BenchExpansion extends KyoCaseApp[ExpansionOpts]:
    run { (opts: ExpansionOpts) =>
        Cli.guard(Expansion.dump(opts.fixture, opts.phase))
    }
end BenchExpansion
