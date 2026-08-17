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
            tasks = LogCompilation.parse(raw)
            morphism = LogCompilation.morphism(tasks)
            inlines  = LogCompilation.inlining(tasks)
            deopts   = LogCompilation.deoptSummary(tasks)

            _ = println("\ncall sites and receiver profiles")
            // A site without a receiver profile is one the JIT never profiled as a virtual call,
            // which is the opposite of megamorphic. Treating absence as evidence is what put
            // "measured polymorphic call sites" above sites whose receiver count is zero.
            _ = expect(
                "every site called polymorphic carries receiver data",
                morphism.count(m => !m.monomorphic && m.receiverCount == 0),
                0,
                s"${morphism.count(m => !m.monomorphic && m.receiverCount == 0)} sites reported as measured-polymorphic on no measurement"
            )
            _ = expect(
                "the parser accounts for every call element",
                tasks.map(_.calls.size).sum.toLong,
                o.getOrElse("calls_total", -1L),
                "a silent partial parse, so coverage never appears in any output"
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
            enterSites = tasks.flatMap(_.inlines).filter(_.method.endsWith("Safepoint::enter"))
            _ = check(
                "a method inlined at most sites is not reported refused",
                inlines.find(_.method.endsWith("Safepoint::enter")).forall(_.inlined),
                s"Safepoint::enter has ${enterSites.count(_.inlined)} inlined and ${enterSites.count(!_.inlined)} refused sites, " +
                    "and is reported refused. A one-site refusal presented as the method's verdict is a coin flip between legs."
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
