import Model.*
import kyo.*

/** Holds the compilation-log parser to the log's own contents.
  *
  * The QA that these parsers passed asserted shape: entries parsed, `nonEmpty`, `bytes > 0`. Every one of those held while the morphism
  * verdict was inverted, the deopt count measured compiler-planted guards instead of runtime events, and 118 call sites resolved to raw ids.
  * A shape assertion cannot fail that way, so the checks here compare the parser's typed output against `oracles.sh`, which derives the same
  * facts from the raw XML by means the parser does not share.
  *
  * Each check names the wrong conclusion it exists to prevent, because that is the thing worth keeping.
  */
object LogCompilationTest extends KyoApp:

    // derived from the source location, not hardcoded: absolute paths made the suite unrunnable
    // anywhere but the machine that wrote it, and one of them named a Metals session directory
    val root      = Roots.repo
    val results   = root / "bench-results" / "exp1"
    val artifacts = root / "qa-artifacts"
    val script    = Roots.harness / "oracles.sh"

    case class Failed(what: String) extends Exception(what) with scala.util.control.NoStackTrace

    /** Ground truth, derived from the artifact rather than copied from it: a constant pasted into a test goes stale silently the moment the
      * capture is replaced, and stale expectations are how a broken parser keeps passing.
      */
    def oracles(using Frame): Map[String, Long] < (Async & Abort[Failed | CommandException]) =
        Command(script.toString, artifacts.toString).redirectErrorStream(true).textWithExitCode.map { (out, exit) =>
            if exit != ExitCode.Success then Abort.fail(Failed(s"oracles.sh failed: $out"))
            else
                Map.from(
                    out.linesIterator.flatMap { line =>
                        line.split("=", 2) match
                            case Array(k, v) => v.trim.toLongOption.map(k.trim -> _)
                            case _           => None
                    }
                )
        }

    var failures = 0

    def check(name: String, cond: Boolean, detail: String): Unit =
        if cond then println(s"  ok   $name")
        else
            failures += 1
            println(s"  FAIL $name\n         $detail")

    def expect(name: String, actual: Long, expected: Long, wrongConclusion: String): Unit =
        check(name, actual == expected, s"got $actual, oracle says $expected. Left uncorrected this reports: $wrongConclusion")

    run {
        for
            raw <- (artifacts / "qa-logc.xml").read
            o   <- oracles
            parsed = LogCompilation.parse(raw)
            tasks    = parsed.tasks
            morphism = LogCompilation.morphism(parsed)
            inlines  = LogCompilation.inlining(parsed)
            deopts   = LogCompilation.deoptSummary(parsed)

            _ = println("\ncall sites and receiver profiles")
            // A site without a receiver profile is one the JIT never profiled as a virtual call,
            // which is the opposite of megamorphic. Treating absence as evidence is what put
            // "measured polymorphic call sites" above sites whose receiver count is zero.
            _ = expect(
                "every site called polymorphic carries receiver data",
                morphism.count(m => m.monomorphic.contains(false) && m.receiverCount == 0).toLong,
                0L,
                "sites reported as measured-polymorphic on no measurement"
            )
            // against the log's own count of receiver attributes, so it fails if the parser ever
            // starts inventing profiles or dropping the few real ones
            _ = expect(
                "exactly the sites the log profiled are treated as profiled",
                tasks.flatMap(_.calls).count(_.profiled).toLong,
                o.getOrElse("calls_with_receiver", -1L),
                "receiver profiles invented or dropped"
            )
            // the oracle belongs in the condition, not only in the message. With `complete` alone,
            // coverage was self-referential and reintroducing the historical 4456-element drop
            // reported 637/637 with every check green.
            _ = Chunk(
                ("call sites", "calls_total"),
                ("method declarations", "methods_total"),
                ("uncommon traps", "traps_total")
            ).foreach { (what, oracleKey) =>
                val cov      = parsed.coverage.find(_.what == what)
                val expected = o.getOrElse(oracleKey, -1L)
                check(
                    s"the parser accounts for every element: $what",
                    cov.exists(c => c.complete && c.seen.toLong == expected),
                    s"${cov.map(_.show).getOrElse("no coverage recorded")}, oracle says $expected exist. " +
                        "A silent partial parse looks identical to a complete one."
                )
            }

            // both directions, on the unfiltered set. The earlier check ran downstream of a filter
            // that removes unprofiled sites and of a `kyo.` prefix that removes every bimorphic site,
            // so inverting the comparison in the parser left all of it green.
            // compared per SITE, not per callee: `morphism` aggregates by callee, and the three
            // bimorphic sites in this capture resolve to two callees, so comparing the aggregate to a
            // site count compares different things and fails on a correct parser.
            _ = expect(
                "the bimorphic sites are exactly the ones classified polymorphic",
                tasks.flatMap(_.calls).count(_.monomorphic.contains(false)).toLong,
                o.getOrElse("calls_bimorphic", -1L),
                "an inverted receiver comparison, which every other check tolerates"
            )
            allMorph = LogCompilation.morphism(parsed, "")
            _ = check(
                "and the rest of the profiled sites are monomorphic",
                allMorph.count(_.monomorphic.contains(true)) > 0,
                s"${allMorph.size} profiled sites, ${allMorph.count(_.monomorphic.contains(true))} monomorphic"
            )

            _ = println("\ndeoptimization")
            // `thread=` is an event that happened at runtime. `bci=` is a guard the compiler
            // planted while compiling, a property of the code shape. Summing both and calling the
            // difference a deoptimization change compares guard censuses.
            _ = expect(
                "only runtime deopt events are counted",
                deopts.map(_.count.toLong).sum,
                o.getOrElse("traps_runtime", -1L),
                "a JIT-cost row reading 'deoptimizations 642 vs 645' built from compiler-planted guards"
            )

            // the planted population is two forms, 512 leading with `bci=` and 121 with `method=`.
            // A parser anchored on the first silently drops 19% and still passes any check derived
            // by subtracting the runtime count from the total.
            _ = expect(
                "both forms of planted guard are counted",
                tasks.map(_.plantedTraps.size.toLong).sum,
                o.getOrElse("traps_planted", -1L),
                "one attribute ordering of uncommon_trap dropped without trace"
            )
            trapCoverage = parsed.coverage.find(_.what == "uncommon traps")
            _ = check(
                "every trap lands in exactly one population",
                tasks.map(_.plantedTraps.size).sum + parsed.runtimeDeopts.size == o.getOrElse("traps_total", -1L).toInt,
                s"planted ${tasks.map(_.plantedTraps.size).sum} + runtime ${parsed.runtimeDeopts.size} != ${o.getOrElse("traps_total", -1L)}"
            )
            _ = check("the trap parse is complete", trapCoverage.exists(_.complete), trapCoverage.map(_.show).getOrElse("none"))

            _ = println("\ncompilation tiers")
            metrics = LogCompilation.metrics(parsed, 0.0, 0.0)
            // both stored production runs recorded 0 C2 tasks for a fork that performed 88
            _ = expect("C2 tasks are counted", metrics.c2Tasks.toLong, o.getOrElse("tasks_c2", -1L), "a fork's top-tier compilation invisible")
            _ = expect("OSR tasks are counted", metrics.osrTasks.toLong, o.getOrElse("tasks_osr", -1L), "the JMH stub loop folded into the standard counts")
            _ = expect("made-not-entrant is counted", metrics.madeNotEntrant.toLong, o.getOrElse("make_not_entrant", -1L), "the real recompilation signal missing")
            // 84 methods have more than one task; only 10 were recompiled at the same tier. The rest
            // are level-3-then-4 promotion, which tiered compilation does to every hot method.
            _ = expect(
                "recompilation excludes ordinary tier escalation",
                metrics.recompiled.toLong,
                o.getOrElse("methods_recompiled_same_tier", -1L),
                "74 tier promotions reported as profile-driven recompilation"
            )

            _ = println("\ncompiler tier")
            // C1 runs for about two warmup iterations and has only a 35-byte gate with no frequency
            // tier, so it refuses callees C2 inlines hot. Of 1969 refusals here 1929 are C1, and
            // 'callee is too large' is 1166 in C1 against 0 in C2. Reporting the C1 set as a method's
            // inlining behaviour aims optimization at code the measured score never runs.
            _ = expect(
                "no C2 refusal in this capture is 'callee is too large'",
                inlines.count(_.reasons.exists(_.contains("too large"))).toLong,
                o.getOrElse("refusals_c2_too_large", -1L),
                "1166 warmup-tier refusals presented as inlining behaviour of the measured code"
            )
            _ = check(
                "reported verdicts come only from C2 tasks",
                inlines.map(_.sites).sum <= tasks.filter(_.level >= 4).map(_.inlines.size).sum,
                s"reported ${inlines.map(_.sites).sum} sites against ${tasks.filter(_.level >= 4).map(_.inlines.size).sum} available in C2 tasks"
            )

            _ = println("\nmethod resolution")
            // The unloaded form carries no bytes/iicount, so a regex demanding both drops it and
            // leaves ids that no later stage can resolve.
            // deliberately checked before the `kyo.` filter: that filter removes every `method#N`
            // entry, so a check downstream of it passes no matter how many ids failed to resolve.
            // That is the same defect as asserting `nonEmpty`, one layer along.
            _ = expect(
                "no inline entry resolves to a raw method id",
                tasks.flatMap(_.inlines).count(_.method.startsWith("method#")).toLong,
                0L,
                "unresolved ids hidden by the kyo. prefix filter rather than fixed, so real entries vanish with them"
            )

            _ = println("\nper-site inline verdicts")
            // 11 of 85 kyo methods carry both verdicts. Folding to the worst one lets a single
            // warmup-era site decide a method's verdict for the whole leg, which is how two runs
            // of one comparison named disjoint mechanisms.
            // `loop$9` is the only kernel-prefixed method with a genuinely mixed C2 verdict here
            // (3 inlined, 7 refused). Naming a method that C2 never records would make this check
            // pass by absence, which is the same defect as asserting nonEmpty.
            mixed = inlines.find(_.method.endsWith("loop$9"))
            _ = check("the mixed-verdict method is present at all", mixed.isDefined, "nothing to check means nothing was checked")
            _ = check(
                "a mixed verdict is reported as a fraction, not as refused",
                mixed.exists(v => !v.alwaysRefused && !v.alwaysInlined),
                s"loop\\$$9 reports ${mixed.map(_.show).getOrElse("nothing")}"
            )
            _ = check(
                "the denominator survives into the rendering",
                mixed.exists(v => v.sites > 1 && v.show.contains("/")),
                s"loop\\$$9 renders as '${mixed.map(_.show).getOrElse("")}'"
            )
            // a real C2 fact worth pinning: the evaluator's drive loop is refused for size at every
            // site it appears, which is a property of the measured code rather than of warmup
            driveLoop = inlines.find(_.method == "kyo.kernel.proto.Eval$::loop")
            _ = check(
                "the drive loop's C2 size refusal is reported",
                driveLoop.exists(v => v.alwaysRefused && v.reasons.exists(_.contains("hot method too big"))),
                s"Eval\\$$::loop reports ${driveLoop.map(_.show).getOrElse("nothing")}"
            )

            // Bench's profiler parsers are checked here rather than in BenchTest because this is
            // where the oracle harness lives; BenchTest is synthetic by design and runs no
            // subprocess. Before this they had no oracle coverage at all, only nonEmpty and
            // bytes > 0, which is the shape assertion this whole design replaced.
            _ = println("\nprofiler parsers, against the same oracle")
            allocRaw <- (artifacts / "qa-alloc.txt").read
            allocSites = Bench.parseAlloc(allocRaw)
            _ = expect(
                "every class in the flat table is parsed",
                allocSites.size.toLong,
                o.getOrElse("alloc_flat_classes", -1L),
                "a partial allocation table read as a complete one"
            )
            _ = expect(
                "and their bytes sum to the table's total",
                allocSites.map(_.bytes).sum,
                o.getOrElse("alloc_flat_bytes", -1L),
                "allocation totals that do not reconcile with the profiler's own aggregation"
            )
            cpuRaw <- (artifacts / "qa-cpu.txt").read
            cpuSites = Bench.parseCpu(cpuRaw)
            _ = check("cpu sites parse", cpuSites.nonEmpty && cpuSites.forall(_.nanos > 0), s"${cpuSites.size} sites")
            // native frames (semaphore_wait_trap, __psynch_cvwait) carry no package and are real
            // entries, so requiring a dot would fail on correct output. "No whitespace in a name"
            // was the previous spelling and it was wrong about real output too: a JVM-internal frame
            // is a C++ signature, `void G1ScanEvacuatedObjClosure::do_oop_work<narrowOop>`, and the
            // space in it belongs to the name. Four such frames were being truncated at `::` by the
            // character class this replaced, one of them reported as a method called `void`.
            _ = check(
                "and no site name is a stray fragment",
                cpuSites.forall(c => c.method.nonEmpty && !c.method.contains('%') && !c.method.trim.headOption.exists(_.isDigit)),
                cpuSites.filter(c => c.method.isEmpty || c.method.contains('%')).take(3).map(c => s"'${c.method}'").mkString(",")
            )
            // the oracle for "the regex did not slide across columns" is the row count, derived from
            // the file by a route the parser does not share
            cpuTableRows = cpuRaw.linesIterator.dropWhile(!_.contains("percent  samples")).drop(2)
                .takeWhile(!_.contains("Async profiler results")).count(_.trim.nonEmpty)
            _ = check("every table row parsed, and nothing outside it", cpuSites.size == cpuTableRows, s"parsed ${cpuSites.size}, table has $cpuTableRows")
            _ = check(
                "a C++ frame keeps its qualified name",
                cpuSites.exists(_.method.contains("G1ParScanThreadState::do_copy_to_survivor_space")),
                cpuSites.map(_.method).filter(_.contains("G1Par")).mkString(",")
            )

            _ = println("\nthe efficacy gate, against two real configurations")
            dflt <- (results / "logc-new-default.xml").read.map(LogCompilation.parse)
            f600 <- (results / "logc-new-freq600.xml").read.map(LogCompilation.parse)
            changes = LogCompilation.diffVerdicts(dflt, f600, "")
            (changed, sizeBefore, sizeAfter) = LogCompilation.efficacy(dflt, f600, "")
            _ = check("raising the budget moved verdicts", changed > 20, s"only $changed methods changed")
            _ = check("size refusals fall when the budget rises", sizeAfter < sizeBefore, s"$sizeBefore -> $sizeAfter")
            // the mechanism the manual read found: a 379-byte continuation body refused for size at
            // the default budget and inlining at 600
            body = changes.find(c => c.method.contains("run$56"))
            _ = check("the continuation body's verdict is in the diff", body.isDefined, changes.take(3).map(_.show).mkString("; "))
            _ = body.foreach(c => println(s"       ${c.show}  (${c.bytes}B)"))
            _ = check("it was refused for size at the default budget", body.exists(_.before.alwaysRefused), body.map(_.before.show).getOrElse(""))
            _ = check("and inlines somewhere once the budget allows", body.exists(_.after.inlined > 0), body.map(_.after.show).getOrElse(""))
            // and the ranking that would have surfaced it without a manual read
            near = LogCompilation.budgetCandidates(dflt, "")
            _ = check("budget-proximity ranking surfaces it", near.exists(_.method.contains("run$56")), near.take(4).map(_.show).mkString("; "))
            _ = near.take(3).foreach(v => println(s"       near budget: ${v.method} ${v.nearBudget.getOrElse("")}"))
            // must-not-fire twin: a log against itself moved nothing
            _ = check("a log diffed against itself shows no change", LogCompilation.diffVerdicts(dflt, dflt, "").isEmpty, "a diff that reports changes against identical input cannot prove a flag took")

            _ <- Console.printLine(
                if failures == 0 then "\nall oracle checks passed\n"
                else s"\n$failures oracle check(s) failed: the parser and the artifact disagree\n"
            )
            _ <- Abort.when(failures > 0)(Failed(s"$failures oracle check(s) failed"))
        yield ()
        end for
    }
end LogCompilationTest
