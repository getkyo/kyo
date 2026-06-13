package kyo.test.runner

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kyo.*
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult
import kyo.test.Verbosity

class ConsoleReporterTest extends kyo.test.Test[Any]:

    // ── Helper utilities ────────────────────────────────────────────────────────────────────────

    private def capture(verbosity: Verbosity = Verbosity.Normal, useColors: Boolean = false)(
        body: ConsoleReporter => Unit
    ): String =
        val baos = new ByteArrayOutputStream()
        val ps   = new PrintStream(baos, true, "UTF-8")
        val r    = ConsoleReporter(verbosity, useColors = useColors, out = ps)
        body(r)
        baos.toString("UTF-8")
    end capture

    private def leaf(name: String): LeafInfo =
        LeafInfo(suite = "MySuite", path = Chunk(name), tags = Set.empty)

    // ── Tests ───────────────────────────────────────────────────────────────────────────────────

    "onSuiteStart prints header" in {
        val out = capture() { r =>
            r.onSuiteStart(SuiteInfo("MyTest", "my.MyTest", Maybe.empty))
        }
        assert(out.contains("=== MyTest ==="))
    }

    "onLeafComplete with Passed prints [PASS]" in {
        val out = capture() { r =>
            r.onLeafComplete(leaf("a"), TestResult.Passed(15L.millis))
        }
        assert(out.contains("[PASS]"))
        assert(out.contains("15ms"))
    }

    "onLeafComplete with Failed prints diagram" in {
        val out = capture() { r =>
            r.onLeafComplete(leaf("b"), TestResult.Failed("x == 5\n| |\n  5", Maybe.empty, 5L.millis))
        }
        assert(out.contains("[FAIL]"))
        assert(out.contains("x == 5"))
    }

    "onLeafComplete with TimedOut prints [TIMEOUT]" in {
        val out = capture() { r =>
            r.onLeafComplete(leaf("c"), TestResult.TimedOut(100L.millis))
        }
        assert(out.contains("[TIMEOUT]"))
        assert(out.contains("100ms"))
    }

    "onLeafHeartbeat prints [STUCK] with the leaf path and elapsed" in {
        val out = capture() { r =>
            r.onLeafHeartbeat(leaf("slow-leaf"), 30L.seconds)
        }
        assert(out.contains("[STUCK]"))
        assert(out.contains("slow-leaf"))
        assert(out.contains("30s"))
    }

    "onLeafHeartbeat at Quiet verbosity prints nothing" in {
        val out = capture(Verbosity.Quiet) { r =>
            r.onLeafHeartbeat(leaf("slow-leaf"), 30L.seconds)
        }
        assert(out.isEmpty)
    }

    "onLeafComplete with Pending with reason" in {
        val out = capture() { r =>
            r.onLeafComplete(leaf("d"), TestResult.Pending("not ready"))
        }
        assert(out.contains("[PENDING]"))
        assert(out.contains("not ready"))
    }

    "onLeafComplete with Ignored prints [IGNORED]" in {
        val out = capture() { r =>
            r.onLeafComplete(leaf("e"), TestResult.Ignored(""))
        }
        assert(out.contains("[IGNORED]"))
    }

    "onLeafComplete with Skipped with reason" in {
        val out = capture() { r =>
            r.onLeafComplete(leaf("f"), TestResult.Skipped("condition false"))
        }
        assert(out.contains("[SKIPPED]"))
        assert(out.contains("condition false"))
    }

    "onRunComplete prints summary totals" in {
        val suiteA = SuiteReport(
            "A",
            Chunk(
                Chunk("t1") -> TestResult.Passed(1L.millis),
                Chunk("t2") -> TestResult.Passed(1L.millis),
                Chunk("t3") -> TestResult.Failed("diag", Maybe.empty, 1L.millis)
            ),
            10L.millis
        )
        val suiteB = SuiteReport(
            "B",
            Chunk(
                Chunk("t4") -> TestResult.Passed(1L.millis),
                Chunk("t5") -> TestResult.Failed("diag2", Maybe.empty, 1L.millis)
            ),
            5.millis
        )
        val report = TestReport(Chunk(suiteA, suiteB))
        val out = capture() { r =>
            r.onRunComplete(report)
        }
        assert(out.contains("Results:"))
        assert(out.contains("3"))
        assert(out.contains("2"))
    }

    "Quiet verbosity suppresses per-leaf pass lines" in {
        val out = capture(verbosity = Verbosity.Quiet) { r =>
            r.onLeafComplete(leaf("g"), TestResult.Passed(1L.millis))
        }
        assert(!out.contains("[PASS]"))
        assert(out.isEmpty || !out.contains("[PASS]"))
    }

    "Verbose verbosity prints onLeafStart" in {
        val out = capture(verbosity = Verbosity.Verbose) { r =>
            r.onLeafStart(leaf("h"))
        }
        assert(out.contains("▶"))
        assert(out.contains("h"))
    }

    "colors disabled when useColors is false" in {
        val out = capture(useColors = false) { r =>
            r.onLeafComplete(leaf("i"), TestResult.Failed("x", Maybe.empty, 1L.millis))
        }
        // ANSI color sequences use ESC (U+001B, "") followed by '['; plain [FAIL] brackets are separate.
        val noAnsiEsc = !out.contains("[")
        assert(noAnsiEsc, s"Expected no ANSI color sequences in plain-text output:\n$out")
        assert(out.contains("[FAIL]"), s"Expected plain [FAIL] label in output:\n$out")
    }

    "colors enabled when useColors is true" in {
        val out = capture(useColors = true) { r =>
            r.onLeafComplete(leaf("j"), TestResult.Passed(1L.millis))
        }
        // ANSI color sequences use ESC (U+001B, "") followed by '['; verify they appear in colored mode.
        val hasAnsiEsc = out.contains("[")
        assert(hasAnsiEsc, s"Expected ANSI color sequences in colored output:\n$out")
    }

    "onRunComplete summary with mixed counts" in {
        val suiteResults = Chunk(
            Chunk("t1") -> TestResult.Passed(1L.millis),
            Chunk("t2") -> TestResult.Passed(1L.millis),
            Chunk("t3") -> TestResult.Passed(1L.millis),
            Chunk("t4") -> TestResult.Failed("diag", Maybe.empty, 1L.millis),
            Chunk("t5") -> TestResult.Cancelled("reason", 1L.millis),
            Chunk("t6") -> TestResult.Skipped("halted"),
            Chunk("t7") -> TestResult.Skipped("halted")
        )
        val suite  = SuiteReport("A", suiteResults, 10L.millis)
        val report = TestReport(Chunk(suite))
        val out = capture(useColors = false) { r =>
            r.onRunComplete(report)
        }
        assert(out.contains("3 passed"), s"Expected '3 passed' in:\n$out")
        assert(out.contains("1 failed"), s"Expected '1 failed' in:\n$out")
        assert(out.contains("1 cancelled"), s"Expected '1 cancelled' in:\n$out")
        assert(out.contains("2 skipped"), s"Expected '2 skipped' in:\n$out")
        assert(!out.contains("pending"), s"Expected no 'pending' in:\n$out")
        assert(!out.contains("ignored"), s"Expected no 'ignored' in:\n$out")
        assert(!out.contains("timed out"), s"Expected no 'timed out' in:\n$out")
    }

    // ── Per-suite FAILURES block ─────────────────────────────────────────────────────────────────

    "phase10-suite-1: onSuiteComplete with a failing leaf emits FAILURES block with path and diagram" in {
        val suiteInfo = SuiteInfo("MySuite", "my.MySuite", Maybe.empty)
        val report = SuiteReport(
            "MySuite",
            Chunk(
                Chunk("MySuite", "pass1") -> TestResult.Passed(1L.millis),
                Chunk("MySuite", "fail1") -> TestResult.Failed("x == 5\n|\n3", Maybe.empty, 1L.millis)
            ),
            10L.millis
        )
        val out = capture(useColors = false) { r =>
            r.onSuiteComplete(suiteInfo, report)
        }
        assert(out.contains("FAILURES (1)"), s"Expected 'FAILURES (1)' in:\n$out"): Unit
        assert(out.contains("MySuite > fail1"), s"Expected path in:\n$out"): Unit
        assert(out.contains("[FAIL]"), s"Expected [FAIL] tag in:\n$out"): Unit
        assert(out.contains("x == 5"), s"Expected diagram line in:\n$out"): Unit
        assert(!out.contains("MySuite > pass1"), s"Passing leaf must not appear in FAILURES block:\n$out"): Unit
    }

    "phase10-suite-2: onSuiteComplete with all-passing suite emits no FAILURES block" in {
        val suiteInfo = SuiteInfo("GreenSuite", "my.GreenSuite", Maybe.empty)
        val report = SuiteReport(
            "GreenSuite",
            Chunk(
                Chunk("p1") -> TestResult.Passed(1L.millis),
                Chunk("p2") -> TestResult.Passed(1L.millis)
            ),
            5L.millis
        )
        val out = capture(useColors = false) { r =>
            r.onSuiteComplete(suiteInfo, report)
        }
        assert(!out.contains("FAILURES"), s"Expected no FAILURES block in all-passing suite:\n$out"): Unit
    }

    "phase10-suite-3: long diagram is truncated to MaxDiagramLines with (truncated) marker" in {
        val longDiagram = (1 to (ConsoleReporter.MaxDiagramLines + 5)).map(i => s"line$i").mkString("\n")
        val suiteInfo   = SuiteInfo("TruncSuite", "my.TruncSuite", Maybe.empty)
        val report = SuiteReport(
            "TruncSuite",
            Chunk(
                Chunk("longfail") -> TestResult.Failed(longDiagram, Maybe.empty, 1L.millis)
            ),
            1L.millis
        )
        val out = capture(useColors = false) { r =>
            r.onSuiteComplete(suiteInfo, report)
        }
        assert(out.contains("FAILURES (1)"), s"Expected FAILURES (1) in:\n$out"): Unit
        assert(out.contains("(truncated)"), s"Expected (truncated) marker in:\n$out"): Unit
        assert(out.contains("line1"), s"Expected first diagram line in:\n$out"): Unit
        assert(!out.contains(s"line${ConsoleReporter.MaxDiagramLines + 1}"), s"Expected line beyond cap to be absent:\n$out"): Unit
    }

    "phase10-suite-4: only TimedOut/Failed are failures; Cancelled, Pending, Ignored are not in the FAILURES block" in {
        val suiteInfo = SuiteInfo("MixSuite", "my.MixSuite", Maybe.empty)
        val report = SuiteReport(
            "MixSuite",
            Chunk(
                Chunk("MixSuite", "tout")  -> TestResult.TimedOut(10L.seconds),
                Chunk("MixSuite", "canc")  -> TestResult.Cancelled("setup failed", 1L.millis),
                Chunk("MixSuite", "pend")  -> TestResult.Pending("not ready"),
                Chunk("MixSuite", "ignrd") -> TestResult.Ignored("")
            ),
            5L.millis
        )
        val out = capture(useColors = false) { r =>
            r.onSuiteComplete(suiteInfo, report)
        }
        // Only the timeout is a real failure. The cancelled, pending, and ignored leaves are
        // deliberate non-runs: they are reported as counts on the suite line, never in FAILURES.
        assert(out.contains("FAILURES (1)"), s"Expected FAILURES (1) in:\n$out"): Unit
        assert(out.contains("[TIMEOUT]"), s"Expected [TIMEOUT] in:\n$out"): Unit
        assert(out.contains("MixSuite > tout"), s"Expected TimedOut path in:\n$out"): Unit
        assert(!out.contains("MixSuite > canc"), s"Cancelled must not appear in FAILURES block:\n$out"): Unit
        assert(!out.contains("MixSuite > pend"), s"Pending must not appear in FAILURES block:\n$out"): Unit
        assert(!out.contains("MixSuite > ignrd"), s"Ignored must not appear in FAILURES block:\n$out"): Unit
        assert(out.contains("1 cancelled"), s"Expected '1 cancelled' count on the suite line in:\n$out"): Unit
    }

    // ── ConsoleReporter.autoDetect ──────────────────────────────────────────────────────────────────

    "phase8-test-6: autoDetect returns false when NO_COLOR env-var is set, true when absent" in {
        // ConsoleReporter.detectColors(getEnv) is the injectable production logic.
        // We call it with a stub so the test is deterministic regardless of the CI environment.

        // Case 1: NO_COLOR not set (lookup returns null) => detectColors returns true (use colors)
        assert(
            ConsoleReporter.detectColors(_ => null),
            "Expected detectColors==true when NO_COLOR is absent"
        )

        // Case 2: NO_COLOR set to any value => detectColors returns false (no colors)
        assert(!ConsoleReporter.detectColors(_ => "1"), "Expected detectColors==false when NO_COLOR=1")
        assert(!ConsoleReporter.detectColors(_ => ""), "Expected detectColors==false when NO_COLOR is empty string")

        // Pin the runtime autoDetect value against the same logic driven by the real env
        val actual   = ConsoleReporter.autoDetect
        val expected = ConsoleReporter.detectColors(java.lang.System.getenv)
        assert(actual == expected, s"autoDetect=$actual disagrees with detectColors on the real env")
    }

end ConsoleReporterTest
