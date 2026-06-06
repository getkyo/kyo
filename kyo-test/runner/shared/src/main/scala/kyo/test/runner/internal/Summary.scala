package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.TestReport
import kyo.test.TestResult

/** Renders the final summary string from accumulated [[TestReport]] values.
  *
  * The rendered string is the last line(s) printed by the test runner. It covers all 7 result kinds (Passed, Failed, Cancelled, Pending,
  * Ignored, TimedOut, Skipped) and includes a no-tests diagnostic when the run produced no leaves.
  *
  * All three platform runners (JVM, JS, Native) call this single method so the output is byte-identical across platforms given identical
  * input.
  */
private[internal] object Summary:

    /** Renders a human-readable summary of the test run.
      *
      * @param reports
      *   the accumulated per-suite reports; one [[TestReport]] per executed [[sbt.testing.Task]]
      * @param discoveryErrors
      *   error messages from suite discovery failures (e.g., class not found); appended as a warning line when non-empty
      * @param positionalArgs
      *   non-flag arguments passed to the runner (e.g., class names from `testOnly`); used for the no-match diagnostic when the run
      *   produces zero leaves
      * @return
      *   the formatted summary string; if any leaf is non-passing, a `TOTAL FAILURES (N)` block is appended after the summary line so the
      *   tail of an `sbt test` log always contains every failure identity
      */
    def render(
        reports: Iterable[TestReport],
        discoveryErrors: Chunk[String],
        positionalArgs: Chunk[String]
    ): String =
        val allLeaves: Chunk[(Chunk[String], TestResult)] =
            Chunk.from(
                reports.iterator
                    .flatMap(_.suiteReports.iterator)
                    .flatMap(_.leafResults.iterator)
            )

        val counts = allLeaves.foldLeft(TestReport.Counts()) { case (acc, (_, result)) => acc.tally(result) }

        val body =
            if counts.total == 0 && positionalArgs.nonEmpty then
                val filter = positionalArgs.mkString(", ")
                s"kyo-test: no tests matched the filter ($filter)"
            else
                s"kyo-test: ${counts.total} tests, ${counts.passed} passed, ${counts.failed} failed, ${counts.cancelled} cancelled, ${counts.pending} pending, ${counts.ignored} ignored, ${counts.timedOut} timed out, ${counts.skipped} skipped"

        val withDiscovery =
            if discoveryErrors.isEmpty then
                body
            else
                val errorList = discoveryErrors.mkString(", ")
                s"$body\n warning: ${discoveryErrors.size} suite(s) failed to load: $errorList"
            end if
        end withDiscovery

        val failingLeaves = allLeaves.filter { case (_, r) => isFailure(r) }
        if failingLeaves.isEmpty then
            withDiscovery
        else
            val lines = failingLeaves.map { case (path, result) =>
                "  " + path.mkString(" > ") + "  " + statusTag(result) + "  " + oneLineReason(result)
            }
            withDiscovery + "\nTOTAL FAILURES (" + failingLeaves.size + "):\n" + lines.mkString("\n")
        end if

    end render

    private def isFailure(r: TestResult): Boolean =
        r match
            case _: TestResult.Failed    => true
            case _: TestResult.TimedOut  => true
            case _: TestResult.Cancelled => true
            case _                       => false

    private def statusTag(r: TestResult): String =
        r match
            case _: TestResult.Failed    => "[FAIL]"
            case _: TestResult.TimedOut  => "[TIMEOUT]"
            case _: TestResult.Cancelled => "[CANCELLED]"
            case _                       => ""

    private def oneLineReason(r: TestResult): String =
        r match
            case TestResult.Failed(diagram, cause, _, _) =>
                if diagram.nonEmpty then
                    diagram.linesIterator.next()
                else
                    cause.fold("")(t => t.getClass.getName + ": " + t.getMessage)
            case TestResult.TimedOut(limit) =>
                "limit: " + formatDuration(limit)
            case TestResult.Cancelled(reason, _) =>
                reason
            case _ =>
                ""

    private def formatDuration(d: kyo.Duration): String =
        val ms = d.toMillis
        if ms < 1000 then s"${ms}ms"
        else if ms < 60000 then
            val s   = ms / 1000
            val rem = ms % 1000
            if rem == 0 then s"${s}s" else f"${s}.${rem / 100}s"
        else
            val m   = ms / 60000
            val sec = (ms % 60000) / 1000
            if sec == 0 then s"${m}m" else s"${m}m ${sec}s"
        end if
    end formatDuration

end Summary
