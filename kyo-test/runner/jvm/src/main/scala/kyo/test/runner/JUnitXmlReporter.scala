package kyo.test.runner

import java.nio.file.Files
import java.nio.file.Path
import kyo.Chunk
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult

/** JUnit XML reporter (JVM-only).
  *
  * Writes one `TEST-{suiteName}.xml` file per suite into `outputDir`. The XML conforms to the de-facto JUnit XML schema consumed by sbt,
  * Maven, Jenkins, and GitHub Actions test reporters.
  *
  * Stateless: builds XML directly from the `SuiteReport` argument passed to [[onSuiteComplete]]; no per-leaf accumulation is required.
  *
  * @param outputDir
  *   directory where `TEST-*.xml` files are written; must exist before calling [[onSuiteComplete]]
  * @see
  *   [[kyo.test.TestReporter]] the reporter interface that JUnitXmlReporter implements
  * @see
  *   [[kyo.test.runner.CombinedReporter]] for composing JUnitXmlReporter with a ConsoleReporter
  * @see
  *   [[kyo.test.TestResult]] the leaf outcomes serialized into XML failure/skipped elements
  * @see
  *   [[kyo.test.RunConfig]] where a JUnitXmlReporter is supplied via `Reporters.junitXml`
  */
final class JUnitXmlReporter(outputDir: Path) extends TestReporter:

    def onRunStart(info: RunInfo): Unit = ()

    def onSuiteStart(info: SuiteInfo): Unit = ()

    def onLeafStart(info: LeafInfo): Unit = ()

    def onLeafComplete(info: LeafInfo, result: TestResult): Unit = ()

    def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit =
        val xml = buildXml(info.name, info.className, report.leafResults)
        try Files.writeString(outputDir.resolve(s"TEST-${info.name}.xml"), xml): Unit
        catch
            case e: Exception =>
                java.lang.System.err.println(
                    s"[kyo-test] JUnitXmlReporter failed to write ${info.name}.xml: ${e.getMessage}"
                )
        end try
    end onSuiteComplete

    def onRunComplete(report: TestReport): Unit = ()

    private def buildXml(
        suiteName: String,
        className: String,
        leaves: Chunk[(Chunk[String], TestResult)]
    ): String =
        val sb    = new StringBuilder()
        val tests = leaves.size
        val failures = leaves.count(_._2 match
            case _: TestResult.Failed | _: TestResult.TimedOut => true
            case _                                             => false)
        // Cancelled is a deliberate skip (an unmet `assume`/`cancel` precondition), not a failure.
        val skipped = leaves.count(_._2 match
            case _: TestResult.Pending | _: TestResult.Ignored | _: TestResult.Skipped | _: TestResult.Cancelled => true
            case _                                                                                               => false)

        val totalMillis = leaves.foldLeft(0L) { case (acc, (_, result)) =>
            acc + leafDurationMillis(result)
        }
        val totalSeconds = formatSeconds(totalMillis)

        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("\n")
        sb.append(
            s"""<testsuite name="${xmlEscape(
                    suiteName
                )}" tests="$tests" failures="$failures" errors="0" skipped="$skipped" time="$totalSeconds">"""
        )
        sb.append("\n")

        leaves.foreach { case (path, result) =>
            val leafName    = xmlEscape(path.mkString("."))
            val leafMillis  = leafDurationMillis(result)
            val leafSeconds = formatSeconds(leafMillis)
            sb.append(s"""  <testcase classname="${xmlEscape(className)}" name="$leafName" time="$leafSeconds">""")
            sb.append("\n")
            result match
                case TestResult.Passed(_, _) =>
                    ()
                case TestResult.Failed(diagram, _, _, _) =>
                    val firstLine = diagram.split("\n", 2).headOption.getOrElse("")
                    sb.append(
                        s"""    <failure message="${xmlEscape(firstLine)}" type="AssertionFailed">${xmlEscape(diagram)}</failure>"""
                    )
                    sb.append("\n")
                case TestResult.Cancelled(reason, _) =>
                    // A cancelled leaf is a skipped precondition, not a failure: emit <skipped>, not <failure>.
                    sb.append(s"""    <skipped message="${xmlEscape(reason)}"/>""")
                    sb.append("\n")
                case TestResult.TimedOut(limit) =>
                    val msg = s"timed out after $limit"
                    sb.append(
                        s"""    <failure message="${xmlEscape(msg)}" type="TimedOut">${xmlEscape(msg)}</failure>"""
                    )
                    sb.append("\n")
                case TestResult.Pending(reason) =>
                    sb.append(s"""    <skipped message="${xmlEscape(reason)}"/>""")
                    sb.append("\n")
                case TestResult.Ignored(reason) =>
                    val msg = if reason.nonEmpty then reason else "ignored"
                    sb.append(s"""    <skipped message="${xmlEscape(msg)}"/>""")
                    sb.append("\n")
                case TestResult.Skipped(reason) =>
                    sb.append(s"""    <skipped message="${xmlEscape(reason)}"/>""")
                    sb.append("\n")
            end match
            sb.append("  </testcase>\n")
        }

        sb.append("</testsuite>\n")
        sb.toString
    end buildXml

    private def leafDurationMillis(result: TestResult): Long =
        result match
            case TestResult.Passed(d, _)       => d.toMillis
            case TestResult.Failed(_, _, d, _) => d.toMillis
            case TestResult.Cancelled(_, d)    => d.toMillis
            case TestResult.TimedOut(limit)    => limit.toMillis
            case TestResult.Pending(_)         => 0L
            case _: TestResult.Ignored         => 0L
            case TestResult.Skipped(_)         => 0L

    private def formatSeconds(millis: Long): String =
        f"${millis / 1000.0}%.3f"

    private[runner] def xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .map(c => if c < ' ' && c != '\t' && c != '\n' && c != '\r' then '?' else c)
    end xmlEscape

end JUnitXmlReporter
