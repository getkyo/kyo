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

    val artifacts = Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/qa-artifacts")
    val script    = Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/kyo-kernel2/.claude/skills/kernel/bench-harness/oracles.sh")

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
            callCoverage = parsed.coverage.find(_.what == "call sites")
            _ = check(
                "the parser accounts for every call element",
                callCoverage.exists(_.complete),
                s"${callCoverage.map(_.show).getOrElse("no coverage recorded")}, oracle says ${o.getOrElse("calls_total", -1L)} exist. " +
                    "A silent partial parse looks identical to a complete one."
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
            enter = inlines.find(_.method.endsWith("Safepoint::enter"))
            _ = check(
                "a mixed verdict is reported as a fraction, not as refused",
                enter.forall(v => !v.alwaysRefused),
                s"Safepoint::enter reports ${enter.map(_.show).getOrElse("nothing")}. " +
                    "A one-site refusal presented as the method's verdict is a coin flip between legs."
            )
            _ = check(
                "the denominator survives into the rendering",
                enter.forall(v => v.sites > 1 && v.show.contains("/")) || enter.forall(_.alwaysInlined),
                s"Safepoint::enter renders as '${enter.map(_.show).getOrElse("")}' with ${enter.map(_.sites).getOrElse(0)} sites"
            )

            _ <- Console.printLine(
                if failures == 0 then "\nall oracle checks passed\n"
                else s"\n$failures oracle check(s) failed: the parser and the artifact disagree\n"
            )
            _ <- Abort.when(failures > 0)(Failed(s"$failures oracle check(s) failed"))
        yield ()
        end for
    }
end LogCompilationTest
