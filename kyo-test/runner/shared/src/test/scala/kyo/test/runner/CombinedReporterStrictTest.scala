package kyo.test.runner

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kyo.*
import kyo.Chunk
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult

/** Tests for [[CombinedReporter]] strict mode: 4 leaves covering fan-out, strict throw, non-strict warning, and clean run.
  */
class CombinedReporterStrictTest extends kyo.test.Test[Any]:

    private val boom = new RuntimeException("reporter-boom")

    private class CountingReporter extends TestReporter:
        var leafCount                                                   = 0
        def onRunStart(info: RunInfo): Unit                             = ()
        def onSuiteStart(info: SuiteInfo): Unit                         = ()
        def onLeafStart(info: LeafInfo): Unit                           = ()
        def onLeafComplete(info: LeafInfo, result: TestResult): Unit    = leafCount += 1
        def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit = ()
        def onRunComplete(report: TestReport): Unit                     = ()
    end CountingReporter

    private class FailingReporter extends TestReporter:
        def onRunStart(info: RunInfo): Unit                             = throw boom
        def onSuiteStart(info: SuiteInfo): Unit                         = throw boom
        def onLeafStart(info: LeafInfo): Unit                           = throw boom
        def onLeafComplete(info: LeafInfo, result: TestResult): Unit    = throw boom
        def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit = throw boom
        def onRunComplete(report: TestReport): Unit                     = throw boom
    end FailingReporter

    private val dummyLeaf   = LeafInfo("suite", Chunk("leaf"), Set.empty)
    private val dummyResult = TestResult.Passed(1L.millis)
    private val dummyRun    = RunInfo(1, 1)

    private def dummyReport: TestReport =
        val sr = SuiteReport("suite", Chunk((Chunk("leaf"), dummyResult)), 1L.millis)
        TestReport(Chunk(sr))

    /** Redirect System.err for the duration of `body`, return captured output. */
    private def captureStderr(body: => Unit): String =
        val baos = new ByteArrayOutputStream()
        val ps   = new PrintStream(baos, true, "UTF-8")
        val old  = java.lang.System.err
        java.lang.System.setErr(ps)
        try
            body
        finally
            java.lang.System.setErr(old)
        end try
        baos.toString("UTF-8")
    end captureStderr

    // ── Test 3: okReporter still sees every event when failingReporter is first ────────────────

    "test-3: CombinedReporter(failingReporter, okReporter): okReporter receives every event" in {
        val ok      = new CountingReporter
        val failing = new FailingReporter
        val _ = captureStderr { // suppress the per-event stderr lines
            val combined = CombinedReporter(failing, ok)
            combined.onRunStart(dummyRun)
            combined.onLeafComplete(dummyLeaf, dummyResult)
            combined.onLeafComplete(dummyLeaf, dummyResult)
        }
        assert(ok.leafCount == 2, s"ok reporter saw ${ok.leafCount} leaf events, expected 2")
    }

    // ── Test 4: strict=true rethrows first accumulated exception in onRunComplete ───────────────

    "test-4: CombinedReporter(strict=true): onRunComplete throws the first accumulated exception" in {
        val failing  = new FailingReporter
        val combined = new CombinedReporter(Chunk(failing), strict = true)
        val _ = captureStderr {
            combined.onLeafComplete(dummyLeaf, dummyResult) // accumulate boom
        }
        val thrown = intercept[RuntimeException] {
            captureStderr {
                combined.onRunComplete(dummyReport)
            }
        }
        assert(thrown.getMessage == "reporter-boom", s"expected 'reporter-boom', got '${thrown.getMessage}'")
    }

    // ── Test 5: strict=false: onRunComplete returns normally; stderr has warning ───────────────

    "test-5: CombinedReporter(strict=false): onRunComplete returns; stderr has warning" in {
        val failing  = new FailingReporter
        val combined = new CombinedReporter(Chunk(failing), strict = false)
        val _ = captureStderr {
            combined.onLeafComplete(dummyLeaf, dummyResult) // accumulate boom
        }
        val stderr = captureStderr {
            combined.onRunComplete(dummyReport) // should NOT throw
        }
        assert(stderr.contains("kyo-test: warning:") && stderr.contains("reporter(s) failed"), s"stderr was: $stderr")
    }

    // ── Test 6: no failing reporters: no warning, no throw ───────────────────────────────────

    "test-6: CombinedReporter with no failing reporters: no stderr warning, no throw" in {
        val ok       = new CountingReporter
        val combined = new CombinedReporter(Chunk(ok), strict = true)
        val stderr = captureStderr {
            combined.onLeafComplete(dummyLeaf, dummyResult)
            combined.onRunComplete(dummyReport)
        }
        assert(!stderr.contains("kyo-test: warning"), s"unexpected warning in stderr: $stderr")
    }

end CombinedReporterStrictTest
