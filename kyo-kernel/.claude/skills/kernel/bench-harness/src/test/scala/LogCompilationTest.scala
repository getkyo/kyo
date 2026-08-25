import Model.*
import kyo.*
import kyo.test.*

/** Holds the compilation-log parser to the log's own contents.
  *
  * The QA that these parsers passed asserted shape: entries parsed, `nonEmpty`, `bytes > 0`. Every one of those held while the morphism
  * verdict was inverted, the deopt count measured compiler-planted guards instead of runtime events, and 118 call sites resolved to raw ids.
  * A shape assertion cannot fail that way, so the checks here compare the parser's typed output against `oracles.sh`, which derives the same
  * facts from the raw XML by means the parser does not share.
  *
  * Each check names the wrong conclusion it exists to prevent, because that is the thing worth keeping.
  */
class LogCompilationTest extends Test[Any]:

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

    // one leaf per suite: the artifact is read once inside it, every check records its claim, and the
    // leaf fails listing every claim that did not hold. This is the mains' counting `check` with the
    // framework holding the exit code
    private val failed = scala.collection.mutable.ListBuffer.empty[String]
    private def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if !cond then failed += (if detail.nonEmpty then s"$name  <- $detail" else name)

    def expect(name: String, actual: Long, expected: Long, wrongConclusion: String): Unit =
        check(name, actual == expected, s"got $actual, oracle says $expected. Left uncorrected this reports: $wrongConclusion")

    "the parsers agree with the oracle derivations" in {
        for
            raw <- (artifacts / "qa-logc.xml").read
            o   <- oracles
            parsed = LogCompilation.parse(raw)
            tasks    = parsed.tasks
            morphism = LogCompilation.morphism(parsed)
            inlines  = LogCompilation.inlining(parsed)
            deopts   = LogCompilation.deoptSummary(parsed)

            _ = assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
        yield ()
        end for
    }
end LogCompilationTest
