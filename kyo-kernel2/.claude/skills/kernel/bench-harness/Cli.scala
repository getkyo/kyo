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
    @HelpMessage("session id from a previous leg; omit to open a new session, which measures this machine's drift first")
    session: Option[String] = None,
    @HelpMessage("row used to measure session drift when opening one")
    driftRow: String = "suspensionBaseline",
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
    @HelpMessage("run id of the control leg")
    control: String,
    @HelpMessage("run id of the variant leg")
    variant: String,
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

case class ShowOpts(id: String, store: String = "bench-runs")
case class ListOpts(store: String = "bench-runs")

object Cli:

    val markerSpecs = Seq(
        ("SuspendWith", "SuspendWith", "kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Arrow.scala"),
        ("applyFolded", "applyFolded", "kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Arrow.scala"),
        ("partial", "def partial", "kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Eval.scala")
    )

    val protoPaths = Seq(
        "kyo-kernel2/shared/src/main/scala/kyo/kernel/proto",
        "kyo-kernel2/shared/src/test/scala/kyo/kernel/proto"
    )

    def parseEvidence(s: String)(using Frame): Evidence < Abort[Bench.BracketFailed] =
        s.toLowerCase match
            case "full"   => Evidence.Full
            case "timing" => Evidence.Timing
            case other    => Abort.fail(Bench.BracketFailed(s"unknown evidence '$other'; use full or timing"))

end Cli

object BenchRun extends KyoCaseApp[RunOpts]:
    run { (opts: RunOpts) =>
        for
            evidence <- Cli.parseEvidence(opts.evidence)
            // a session carries the machine's measured drift; reusing one keeps legs comparable,
            // opening one costs two extra runs and is what makes the band a measurement
            session <- opts.session match
                case Some(id) => Store.session(Path(opts.store), id)
                case None     => Bench.openSession(Path(opts.worktree), opts.driftRow)
            leg <- Bench.runLeg(
                session = session,
                worktree = Path(opts.worktree),
                label = opts.label,
                sha = opts.sha,
                paths = Cli.protoPaths,
                markerSpecs = Cli.markerSpecs,
                rows = opts.row,
                forks = opts.forks,
                evidence = evidence
            )
            file <- Store.save(Path(opts.store), leg)
            _    <- Console.printLine(s"stored ${leg.id} (${leg.rows.size} rows, ${leg.evidence}) at $file")
        yield ()
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
        for
            evidence <- Cli.parseEvidence(opts.evidence)
            session  <- Bench.openSession(Path(opts.worktree), opts.row.headOption.getOrElse("suspensionBaseline"))
            legs <- Bench.bracket(
                session = session,
                worktree = Path(opts.worktree),
                control = Bench.Arm(opts.control, opts.controlJvm),
                variant = Bench.Arm(opts.variant, opts.variantJvm),
                paths = Cli.protoPaths,
                markerSpecs = Cli.markerSpecs,
                rows = opts.row,
                forks = opts.forks,
                evidence = evidence,
                legs = opts.legs
            )
            (controls, variants) = legs
            _ <- Kyo.foreachDiscard(controls ++ variants)(r => Store.save(Path(opts.store), r).unit)
            _ <- Bench.nullComparison(controls) match
                case Maybe.Present(n) =>
                    val named = n.deltas.count(_.verdict != Verdict.Flat)
                    Console.printLine(
                        if named == 0 then "A/A null: clean, no control row classified against another control leg.\n"
                        else s"\u274c A/A null: $named row(s) classified comparing controls against each other. " +
                            "Those verdicts are false by construction, so the comparison below is not readable.\n"
                    )
                case _ => Console.printLine("A/A null: not enough control legs to run one.\n")
            cmp = Bench.compareReplicated(controls, variants)
            _ <- Console.printLine(Report.render(cmp))
            _ <- Abort.when(Report.blockers(cmp).nonEmpty)(
                Bench.BracketFailed(s"${Report.blockers(cmp).size} leg(s) did not reach steady state; the verdicts above are not readable")
            )
        yield ()
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
        for
            _ <- Abort.when(opts.json.size != opts.label.size)(
                Bench.BracketFailed(s"${opts.json.size} json files but ${opts.label.size} labels; they must correspond")
            )
            runs <- Kyo.foreach(Chunk.from(opts.json.zip(opts.label).zipWithIndex)) { case ((j, l), i) =>
                Ingest.file(
                    Path(j), l,
                    opts.sha.lift(i).orElse(opts.sha.headOption).getOrElse("unknown"),
                    session, Path(opts.store), opts.declaredRows
                )
            }
            _ <- Kyo.foreachDiscard(runs) { r =>
                Console.printLine(f"ingested ${r.id}%-52s ${r.rows.size}%2d rows  ${if r.wholeClass then "whole class" else "SUBSET"}")
            }
            _ <- Console.printLine(
                "\nThese are timing-only runs: no markers, no tree hash, no evidence ladder. The report will " +
                    "refuse to attribute any movement in them to a mechanism, which is correct, because nothing " +
                    "about how they were produced was under the harness's control."
            )
        yield ()
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
                        blind.map(f => s"  - ${f.row}").mkString("\n") +
                        "\n\nMore forks will not help: calibration showed tripling them moved the resolution by 0.05 points, " +
                        "because the variance is between legs rather than within them. More legs, or a quieter machine."
            )
        yield ()
    }
end BenchPlan

object BenchCompare extends KyoCaseApp[CompareOpts]:
    run { (opts: CompareOpts) =>
        for
            control <- Store.load(Path(opts.store), opts.control)
            variant <- Store.load(Path(opts.store), opts.variant)
            cmp = Bench.compare(control, variant)
            _ <- Console.printLine(Report.render(cmp))
            // a non-steady-state leg fails the run rather than warning inside it. A warning is
            // something a reader skips; an exit code is not, and this tool exists for a reader who
            // demonstrably skips them.
            _ <- Abort.when(Report.blockers(cmp).nonEmpty)(
                Bench.BracketFailed(s"${Report.blockers(cmp).size} leg(s) did not reach steady state; the verdicts above are not readable")
            )
        yield ()
    }
end BenchCompare

object BenchShow extends KyoCaseApp[ShowOpts]:
    run { (opts: ShowOpts) =>
        Store.load(Path(opts.store), opts.id).map { r =>
            Console.printLine(
                s"""|${r.id}
                    |  sha ${r.sha} label ${r.label} forks ${r.forks} evidence ${r.evidence}
                    |  scope ${if r.wholeClass then s"whole class (${r.declaredRows} rows)" else s"${r.rows.size} selected rows"}
                    |  markers ${r.markers.map(m => s"${m.name}=${m.count}").mkString(" ")}
                    |  rows ${r.rows.size}, jit entries ${r.jit.size}, alloc sites ${r.alloc.size}, cpu sites ${r.cpu.size}""".stripMargin
            )
        }
    }
end BenchShow

object BenchList extends KyoCaseApp[ListOpts]:
    run { (opts: ListOpts) =>
        Store.list(Path(opts.store)).map { runs =>
            if runs.isEmpty then Console.printLine("no runs stored")
            else
                Kyo.foreachDiscard(runs.sortBy(_.recordedAt)) { r =>
                    Console.printLine(f"${r.id}%-44s ${r.label}%-10s ${r.evidence}%-7s ${r.rows.size}%3d rows  ${r.recordedAt}")
                }
        }
    }
end BenchList
