package kyo.test.runner

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kyo.*
import kyo.Chunk
import kyo.Maybe
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

class TapReporterTest extends kyo.test.Test[Any]:

    private def capture(body: TapReporter => Unit): String =
        val baos = new ByteArrayOutputStream()
        val ps   = new PrintStream(baos, true, "UTF-8")
        val r    = TapReporter(ps)
        body(r)
        baos.toString("UTF-8")
    end capture

    private def fireSuite(
        reporter: TapReporter,
        suiteName: String,
        leaves: List[(Chunk[String], TestResult)]
    ): Unit =
        val info   = SuiteInfo(suiteName, s"com.example.$suiteName", Maybe.empty)
        val report = SuiteReport(suiteName, Chunk.from(leaves), 0L.millis)
        reporter.onSuiteStart(info)
        reporter.onSuiteComplete(info, report)
    end fireSuite

    "outputs valid TAP 13 header and plan" in {
        val output = capture { r =>
            r.onRunStart(RunInfo(2, 1))
            fireSuite(
                r,
                "SuiteA",
                List(
                    Chunk("test1") -> TestResult.Passed(1L.millis),
                    Chunk("test2") -> TestResult.Passed(1L.millis)
                )
            )
        }
        assert(output.startsWith("TAP version 13\n"), s"Missing TAP header in: $output")
        assert(output.contains("1..2"), s"Missing plan line in: $output")
        assert(output.contains("ok 1 - "), s"Missing ok 1 in: $output")
        assert(output.contains("ok 2 - "), s"Missing ok 2 in: $output")
    }

    "failures include YAML diagnostic block" in {
        val output = capture { r =>
            r.onRunStart(RunInfo(1, 1))
            fireSuite(
                r,
                "FailSuite",
                List(Chunk("failTest") -> TestResult.Failed("x == 5\n| 5", Maybe.empty, 1L.millis))
            )
        }
        assert(output.contains("not ok 1"), s"Missing 'not ok 1' in: $output")
        assert(output.contains("  ---"), s"Missing YAML start '  ---' in: $output")
        assert(output.contains("  ..."), s"Missing YAML end '  ...' in: $output")
    }

    "skipped tests use # SKIP directive" in {
        val output = capture { r =>
            r.onRunStart(RunInfo(1, 1))
            fireSuite(
                r,
                "SkipSuite",
                List(Chunk("envTest") -> TestResult.Skipped("no env"))
            )
        }
        assert(output.contains("ok 1"), s"Missing 'ok 1' in: $output")
        assert(output.contains("# SKIP no env"), s"Missing '# SKIP no env' in: $output")
    }

    // ── Empty and edge-case suites ─────────────────────────────────────────────────────────────────

    "zero-leaf suite emits exactly TAP version 13 plan 0" in {
        val output = capture { r =>
            val info   = SuiteInfo("EmptySuite", "com.example.EmptySuite", Maybe.empty)
            val report = SuiteReport("EmptySuite", Chunk.empty, 0L.millis)
            r.onSuiteComplete(info, report)
        }
        assert(output == "TAP version 13\n1..0\n", s"Unexpected output for zero-leaf suite: ${output.replace("\n", "\\n")}")
    }

    "buildFailureYaml replaces bare --- line with # ---" in {
        val baos = new ByteArrayOutputStream()
        val ps   = new PrintStream(baos, true, "UTF-8")
        val r    = TapReporter(ps)
        val yaml = r.buildFailureYaml("line1\n---\nline2")
        assert(yaml.contains("# ---"), s"Expected '# ---' in YAML output but got: $yaml")
        assert(!yaml.split("\n").exists(_ == "    ---"), s"Bare '---' line still present in: $yaml")
    }

end TapReporterTest
