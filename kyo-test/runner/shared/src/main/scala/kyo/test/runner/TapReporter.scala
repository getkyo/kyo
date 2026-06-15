package kyo.test.runner

import java.io.PrintStream
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult

/** TAP version 13 reporter.
  *
  * Emits the full TAP output (header + plan + lines) in [[onSuiteComplete]], building output directly from the [[SuiteReport]] argument. No
  * per-leaf accumulation is required.
  *
  * Thread-safe: stateless between lifecycle events; each [[onSuiteComplete]] call is independent.
  *
  * @param out
  *   the stream that receives the TAP output
  * @see
  *   [[kyo.test.TestReporter]] the reporter interface that TapReporter implements
  * @see
  *   [[kyo.test.runner.CombinedReporter]] for composing TapReporter with a ConsoleReporter
  * @see
  *   [[kyo.test.TestResult]] the leaf outcomes rendered as TAP ok/not-ok lines
  * @see
  *   [[kyo.test.RunConfig]] where a TapReporter is supplied via `Reporters.tap`
  */
final class TapReporter(out: PrintStream) extends TestReporter:

    def onRunStart(info: RunInfo): Unit = ()

    def onSuiteStart(info: SuiteInfo): Unit = ()

    def onLeafStart(info: LeafInfo): Unit = ()

    def onLeafComplete(info: LeafInfo, result: TestResult): Unit = ()

    def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit =
        val leaves = report.leafResults
        if leaves.isEmpty then
            out.print("TAP version 13\n1..0\n")
            out.flush()
        else
            val sb = new StringBuilder()
            sb.append("TAP version 13\n")
            sb.append(s"1..${leaves.size}\n")
            leaves.iterator.zipWithIndex.foreach { case ((path, result), idx) =>
                val n        = idx + 1
                val testName = s"${info.name} / ${path.mkString(" / ")}"
                sb.append(buildLine(n, testName, result))
                sb.append("\n")
            }
            out.print(sb.toString)
            out.flush()
        end if
    end onSuiteComplete

    def onRunComplete(report: TestReport): Unit = ()

    private def buildLine(n: Int, testName: String, result: TestResult): String =
        result match
            case TestResult.Passed(_, _) =>
                s"ok $n - $testName"
            case TestResult.Failed(diagram, _, _, _) =>
                val yamlBlock = buildFailureYaml(diagram)
                s"not ok $n - $testName\n$yamlBlock"
            case TestResult.Cancelled(reason, _) =>
                // Cancelled is a deliberate skip (an unmet `assume`/`cancel` precondition), not a failure:
                // a TAP SKIP directive on an `ok` line, like pending/ignored/skipped, never `not ok`.
                s"ok $n - $testName # SKIP cancelled: $reason"
            case TestResult.TimedOut(limit) =>
                val yamlBlock = buildMessageYaml(s"timed out after $limit")
                s"not ok $n - $testName\n$yamlBlock"
            case TestResult.Pending(reason) =>
                s"ok $n - $testName # SKIP pending: $reason"
            case TestResult.Ignored(reason) =>
                s"ok $n - $testName # SKIP ignored${if reason.nonEmpty then s": $reason" else ""}"
            case TestResult.Skipped(reason) =>
                s"ok $n - $testName # SKIP $reason"

    private[runner] def buildFailureYaml(diagram: String): String =
        val sb        = new StringBuilder()
        val rawLines  = diagram.split("\n", -1)
        val firstLine = if rawLines.nonEmpty then rawLines(0) else ""
        sb.append("  ---\n")
        sb.append(s"""  message: "${escapeYamlString(firstLine)}"\n""")
        sb.append("  diagram: |\n")
        rawLines.foreach { line =>
            val safeLine = line match
                case "---" => "# ---"
                case "..." => "# ..."
                case other => other
            sb.append(s"    $safeLine\n")
        }
        sb.append("  ...")
        sb.toString
    end buildFailureYaml

    private def buildMessageYaml(message: String): String =
        val sb = new StringBuilder()
        sb.append("  ---\n")
        sb.append(s"""  message: "${escapeYamlString(message)}"\n""")
        sb.append("  ...")
        sb.toString
    end buildMessageYaml

    private def escapeYamlString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

end TapReporter
