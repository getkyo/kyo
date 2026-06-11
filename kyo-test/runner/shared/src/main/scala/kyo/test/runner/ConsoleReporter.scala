package kyo.test.runner

import java.io.PrintStream
import kyo.Ansi.*
import kyo.Chunk
import kyo.Duration
import kyo.Maybe
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult
import kyo.test.Verbosity

/** Console reporter that renders hierarchical test output with ANSI colors.
  *
  * Output is written to `out` (defaults to `java.lang.System.out`). Colors are controlled by `useColors` (defaults to
  * [[ConsoleReporter.autoDetect]], which is `false` when the `NO_COLOR` environment variable is set).
  *
  * @param verbosity
  *   controls how much output is emitted per leaf
  * @param useColors
  *   when `true`, wraps output in ANSI escape codes; when `false`, emits plain text
  * @param out
  *   the stream to write to; exposed so tests can redirect to a `ByteArrayOutputStream`
  * @see
  *   [[kyo.test.TestReporter]] the reporter interface that ConsoleReporter implements
  * @see
  *   [[kyo.test.Verbosity]] the enum controlling output detail level
  * @see
  *   [[kyo.test.runner.CombinedReporter]] for composing ConsoleReporter with other reporters
  * @see
  *   [[kyo.test.RunConfig]] where a ConsoleReporter is wired as the default reporter
  * @see
  *   [[ConsoleReporter.MaxDiagramLines]] the cap applied to per-failure diagram output in the suite failure block
  */
final class ConsoleReporter(
    verbosity: Verbosity,
    useColors: Boolean = ConsoleReporter.autoDetect,
    out: PrintStream = java.lang.System.out
) extends TestReporter:

    // ── Color guard ────────────────────────────────────────────────────────────────────────────

    private def color(s: String, f: String => String): String =
        if useColors then f(s) else s

    // ── Duration formatting ────────────────────────────────────────────────────────────────────

    private def formatDuration(d: Duration): String =
        val ms = d.toMillis
        if ms < 1000 then
            s"${ms}ms"
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

    private def durationSuffix(d: Duration): String =
        color(s"(${formatDuration(d)})", _.dim)

    // ── Diagram rendering ──────────────────────────────────────────────────────────────────────

    private def printDiagram(diagram: String): Unit =
        out.println()
        diagram.linesIterator.foreach(line => out.println("    " + line))
        out.println()
    end printDiagram

    // ── Path rendering ─────────────────────────────────────────────────────────────────────────

    private def renderPath(path: Chunk[String]): String =
        path.mkString(" › ")

    private def indent(path: Chunk[String]): String =
        "  " * math.max(1, path.length)

    // ── TestReporter lifecycle methods ─────────────────────────────────────────────────────────

    override def onRunStart(info: RunInfo): Unit =
        out.println(s"Running ${info.suiteCount} suite(s) with parallelism ${info.parallelism}")

    override def onSuiteStart(info: SuiteInfo): Unit =
        out.println()
        out.println(color(s"=== ${info.name} ===", _.bold))

    override def onLeafStart(info: LeafInfo): Unit =
        if verbosity == Verbosity.Verbose then
            val ind  = indent(info.path)
            val path = renderPath(info.path)
            out.println(color(s"$ind▶ $path", _.grey.dim))

    override def onLeafComplete(info: LeafInfo, result: TestResult): Unit =
        val ind  = indent(info.path)
        val path = renderPath(info.path)

        result match
            case TestResult.Passed(d, _) =>
                if verbosity != Verbosity.Quiet then
                    out.println(s"$ind${color("[PASS]", _.green.bold)} $path  ${durationSuffix(d)}")

            case TestResult.Failed(diagram, cause, d, _) =>
                out.println(s"$ind${color("[FAIL]", _.red.bold)} $path  ${durationSuffix(d)}")
                if diagram.nonEmpty then
                    printDiagram(diagram)
                else
                    cause.foreach { t =>
                        out.println(s"    Unexpected exception: ${t.getClass.getName}: ${t.getMessage}")
                    }
                end if

            case TestResult.Cancelled(reason, d) =>
                out.println(
                    s"$ind${color("[CANCELLED]", _.red)} $path  ${durationSuffix(d)}  $reason"
                )

            case TestResult.Pending(reason) =>
                if verbosity != Verbosity.Quiet then
                    out.println(s"$ind${color("[PENDING]", _.yellow)} $path  $reason")

            case TestResult.Ignored(reason) =>
                if verbosity != Verbosity.Quiet then
                    out.println(s"$ind${color("[IGNORED]", _.grey)} $path${if reason.nonEmpty then s"  $reason" else ""}")

            case TestResult.TimedOut(limit) =>
                out.println(
                    s"$ind${color("[TIMEOUT]", _.red.bold)} $path  (limit: ${formatDuration(limit)})"
                )

            case TestResult.Skipped(reason) =>
                if verbosity != Verbosity.Quiet then
                    out.println(s"$ind${color("[SKIPPED]", _.grey)} $path  $reason")
        end match
    end onLeafComplete

    override def onLeafHeartbeat(info: LeafInfo, elapsed: Duration): Unit =
        if verbosity != Verbosity.Quiet then
            out.println(color(s"${indent(info.path)}[STUCK] ${renderPath(info.path)}  ${durationSuffix(elapsed)}", _.yellow))

    override def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit =
        val passedCount   = report.leafResults.count(_._2.isInstanceOf[TestResult.Passed])
        val failedCount   = report.leafResults.count(_._2.isInstanceOf[TestResult.Failed])
        val cancelCount   = report.leafResults.count(_._2.isInstanceOf[TestResult.Cancelled])
        val pendingCount  = report.leafResults.count(_._2.isInstanceOf[TestResult.Pending])
        val ignoredCount  = report.leafResults.count(_._2.isInstanceOf[TestResult.Ignored])
        val timedOutCount = report.leafResults.count(_._2.isInstanceOf[TestResult.TimedOut])
        val skippedCount  = report.leafResults.count(_._2.isInstanceOf[TestResult.Skipped])

        val extraCounts = List(
            if cancelCount > 0 then Maybe(color(s"$cancelCount cancelled", _.yellow)) else Maybe.empty,
            if pendingCount > 0 then Maybe(color(s"$pendingCount pending", _.yellow)) else Maybe.empty,
            if ignoredCount > 0 then Maybe(color(s"$ignoredCount ignored", _.grey)) else Maybe.empty,
            if timedOutCount > 0 then Maybe(color(s"$timedOutCount timed out", _.yellow)) else Maybe.empty,
            if skippedCount > 0 then Maybe(color(s"$skippedCount skipped", _.grey)) else Maybe.empty
        ).flatMap(_.toChunk)

        val extraStr = if extraCounts.isEmpty then "" else ", " + extraCounts.mkString(", ")

        val line =
            s"${color("---", _.dim)} ${info.name}: " +
                s"${color(s"$passedCount passed", _.green)}, " +
                s"${color(s"$failedCount failed", _.red)}$extraStr" +
                s"  ${durationSuffix(report.duration)}"

        out.println(line)

        val failingLeaves = report.leafResults.filter { case (_, r) => isSuiteFailure(r) }
        if failingLeaves.nonEmpty then
            out.println(s"FAILURES (${failingLeaves.size}):")
            failingLeaves.foreach { case (path, result) =>
                out.println(s"  ${path.mkString(" > ")}  ${suiteStatusTag(result)}")
                printCompactDetail(result)
            }
        end if
    end onSuiteComplete

    private def isSuiteFailure(r: TestResult): Boolean =
        r match
            case _: TestResult.Failed    => true
            case _: TestResult.TimedOut  => true
            case _: TestResult.Cancelled => true
            case _                       => false

    private def suiteStatusTag(r: TestResult): String =
        r match
            case _: TestResult.Failed    => "[FAIL]"
            case _: TestResult.TimedOut  => "[TIMEOUT]"
            case _: TestResult.Cancelled => "[CANCELLED]"
            case _                       => ""

    private def printCompactDetail(result: TestResult): Unit =
        result match
            case TestResult.Failed(diagram, cause, _, _) =>
                if diagram.nonEmpty then
                    val lines     = diagram.linesIterator.toList
                    val capped    = lines.take(ConsoleReporter.MaxDiagramLines)
                    val truncated = lines.length > ConsoleReporter.MaxDiagramLines
                    capped.foreach(l => out.println("    " + l))
                    if truncated then out.println("    (truncated)")
                else
                    cause.foreach { t =>
                        out.println(s"    ${t.getClass.getName}: ${t.getMessage}")
                    }
                end if
            case TestResult.TimedOut(limit) =>
                out.println(s"    limit: ${formatDuration(limit)}")
            case TestResult.Cancelled(reason, _) =>
                out.println(s"    $reason")
            case _ =>
                ()

    override def onRunComplete(report: TestReport): Unit =
        val passedCount   = report.passed
        val failedCount   = report.failed
        val cancelCount   = report.cancelled
        val pendingCount  = report.pending
        val ignoredCount  = report.ignored
        val timedOutCount = report.timedOut
        val skippedCount  = report.skipped

        val extraCounts = List(
            if cancelCount > 0 then Maybe(color(s"$cancelCount cancelled", _.yellow)) else Maybe.empty,
            if pendingCount > 0 then Maybe(color(s"$pendingCount pending", _.yellow)) else Maybe.empty,
            if ignoredCount > 0 then Maybe(color(s"$ignoredCount ignored", _.grey)) else Maybe.empty,
            if timedOutCount > 0 then Maybe(color(s"$timedOutCount timed out", _.yellow)) else Maybe.empty,
            if skippedCount > 0 then Maybe(color(s"$skippedCount skipped", _.grey)) else Maybe.empty
        ).flatMap(_.toChunk)

        val extraStr = if extraCounts.isEmpty then "" else ", " + extraCounts.mkString(", ")

        val line =
            s"Results: " +
                s"${color(s"$passedCount passed", _.green)}, " +
                s"${color(s"$failedCount failed", _.red)}$extraStr" +
                s"  (total: ${formatDuration(report.totalDuration)})"

        out.println()
        out.println(line)
    end onRunComplete

end ConsoleReporter

/** Companion for [[ConsoleReporter]].
  *
  * To capture plain-text output in tests, pass `useColors = false` and redirect to a `ByteArrayOutputStream`:
  *
  * {{{
  * val baos = new java.io.ByteArrayOutputStream()
  * val ps   = new java.io.PrintStream(baos, true, "UTF-8")
  * val r    = ConsoleReporter(Verbosity.Normal, useColors = false, out = ps)
  * r.onLeafComplete(leaf, result)
  * val output = baos.toString("UTF-8")
  * assert(output.contains("[PASS]"))
  * }}}
  */
object ConsoleReporter:

    /** Maximum number of diagram lines printed per failing leaf in the per-suite `FAILURES` block. Diagrams longer than this cap receive a
      * `(truncated)` marker to keep the report compact.
      */
    val MaxDiagramLines: Int = 12

    /** Returns `true` (enable colors) unless `getEnv("NO_COLOR")` returns a non-null value.
      *
      * The `getEnv` parameter is injected so tests can control the env-var lookup without relying on process-level env state.
      */
    private[runner] def detectColors(getEnv: String => String | Null): Boolean =
        getEnv("NO_COLOR") == null

    /** True unless the `NO_COLOR` environment variable is set (any value). */
    val autoDetect: Boolean =
        detectColors(java.lang.System.getenv)

end ConsoleReporter
