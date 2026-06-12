package kyo.test.runner.internal

import kyo.Chunk
import kyo.discard
import kyo.test.TestReport
import kyo.test.TestResult
import scala.collection.mutable.LinkedHashMap

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
        val rendered =
            if failingLeaves.isEmpty then
                withDiscovery
            else
                withDiscovery + "\nTOTAL FAILURES (" + failingLeaves.size + "):\n" + boundedBlock(failureLines(failingLeaves))
        boundedUtf8(rendered)

    end render

    /** Collapse failing leaves that share the same status and reason into one counted line. The common
      * native overflow is a whole browser suite cancelled for the same reason (e.g. no chrome-headless-shell
      * on linux-arm64): hundreds of byte-identical lines. Grouping renders that as a single
      * `[CANCELLED] xN  <reason>` line, which is both smaller and more readable. Distinct failures stay one
      * line each (with their path), so unique failures keep their full identity. Order is the first-occurrence
      * order of each (status, reason) so the output stays deterministic across runs and platforms.
      */
    private def failureLines(failingLeaves: Chunk[(Chunk[String], TestResult)]): Chunk[String] =
        val groups = LinkedHashMap.empty[(String, String), (Int, Chunk[String])]
        failingLeaves.foreach { case (path, result) =>
            val key = (statusTag(result), oneLineReason(result))
            groups.get(key) match
                case Some((n, firstPath)) => groups(key) = (n + 1, firstPath)
                case None                 => groups(key) = (1, path)
        }
        Chunk.from(groups.iterator.map { case ((tag, reason), (count, firstPath)) =>
            if count == 1 then "  " + firstPath.mkString(" > ") + "  " + tag + "  " + reason
            else "  " + tag + " x" + count + "  " + reason
        })
    end failureLines

    /** Backstop that keeps the (already grouped) TOTAL FAILURES block under the Scala Native test-interface
      * writeUTF cap (65535 bytes). Grouping ([[failureLines]]) collapses the common case (many leaves, one
      * shared reason) to a few lines; this only engages in the rare case of very many *distinct* reasons.
      * Emits lines until a byte budget is reached, then a "... and N more" line; each line is also capped in
      * case a single path is pathologically long.
      */
    private val MaxFailuresBlockChars = 50000
    private val MaxFailureLineChars   = 1000

    private def boundedBlock(lines: Chunk[String]): String =
        val sb    = new StringBuilder
        var shown = 0
        val it    = lines.iterator
        while it.hasNext && sb.length < MaxFailuresBlockChars do
            val raw  = it.next()
            val line = if raw.length <= MaxFailureLineChars then raw else raw.substring(0, MaxFailureLineChars) + "..."
            if shown > 0 then discard(sb.append("\n"))
            discard(sb.append(line))
            shown += 1
        end while
        if shown < lines.size then
            discard(sb.append("\n  ... and " + (lines.size - shown) + " more (see per-leaf output above)"))
        sb.toString()
    end boundedBlock

    /** Hard guarantee that the whole summary fits the Scala Native test-interface RPC, on any input.
      *
      * sbt ships `Runner.done()`'s return to the JVM via `DataOutputStream.writeUTF`, which caps the payload
      * at 65535 *bytes* of modified UTF-8 and throws above that. Grouping ([[failureLines]]) and
      * [[boundedBlock]] bound the failure block by line count and characters, which is enough for ASCII;
      * but a block of many *distinct* failures whose reasons carry non-ASCII characters (up to 3 bytes each
      * in modified UTF-8) could still exceed the byte cap while staying under the character cap. This final
      * guard counts modified-UTF-8 bytes and truncates the returned string to [[MaxSummaryBytes]] (a margin
      * below 65535), so the RPC can never overflow. It engages only in that rare multi-byte case; every
      * ASCII summary passes through unchanged via the fast path.
      */
    private val MaxSummaryBytes = 60000

    private def modifiedUtf8Len(c: Char): Int =
        val code = c.toInt
        if code >= 0x0001 && code <= 0x007f then 1
        else if code <= 0x07ff then 2
        else 3
    end modifiedUtf8Len

    private def boundedUtf8(s: String): String =
        // Fast path: even if every char took the 3-byte maximum the string still fits, so no count needed.
        if s.length * 3 <= MaxSummaryBytes then s
        else
            val marker = "\n... (summary truncated to fit the native test-interface RPC)"
            val budget = MaxSummaryBytes - marker.length
            var bytes  = 0
            var i      = 0
            while i < s.length && bytes + modifiedUtf8Len(s.charAt(i)) <= budget do
                bytes += modifiedUtf8Len(s.charAt(i))
                i += 1
            if i >= s.length then s
            else s.substring(0, i) + marker
        end if
    end boundedUtf8

    // A failure is a real red only: a failed assertion or a timeout. Cancelled, Skipped, Pending,
    // and Ignored are deliberate non-runs, already reported as their own counts on the summary line.
    // They never enter TOTAL FAILURES, so the failed count and the failure list cannot disagree.
    private def isFailure(r: TestResult): Boolean =
        r match
            case _: TestResult.Failed   => true
            case _: TestResult.TimedOut => true
            case _                      => false

    private def statusTag(r: TestResult): String =
        r match
            case _: TestResult.Failed    => "[FAIL]"
            case _: TestResult.TimedOut  => "[TIMEOUT]"
            case _: TestResult.Cancelled => "[CANCELLED]"
            case _                       => ""

    /** Upper bound on a single failure-reason line in the summary. The summary is the string
      * `Runner.done()` returns; on Scala Native sbt ships it back over the test-interface RPC via
      * `DataOutputStream.writeUTF` (65535-byte cap). A failing leaf whose diagram is a single very long
      * line (e.g. a rendered SVG with no newlines) would otherwise carry the whole value into the
      * summary and overflow that RPC, crashing the suite's transport. The full diagram is still
      * available per-leaf via the reporters; the summary only needs a short identifying preview. Failing
      * leaves that share this line are grouped (see [[failureLines]]), so it can be generous.
      */
    private val MaxReasonChars = 500

    private def boundedFirstLine(s: String): String =
        val line = s.linesIterator.nextOption().getOrElse("")
        if line.length <= MaxReasonChars then line
        else line.substring(0, MaxReasonChars) + s"... (${line.length} chars total)"
    end boundedFirstLine

    private def oneLineReason(r: TestResult): String =
        r match
            case TestResult.Failed(diagram, cause, _, _) =>
                if diagram.nonEmpty then
                    boundedFirstLine(diagram)
                else
                    boundedFirstLine(cause.fold("")(t => t.getClass.getName + ": " + t.getMessage))
            case TestResult.TimedOut(limit) =>
                "limit: " + formatDuration(limit)
            case TestResult.Cancelled(reason, _) =>
                boundedFirstLine(reason)
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
