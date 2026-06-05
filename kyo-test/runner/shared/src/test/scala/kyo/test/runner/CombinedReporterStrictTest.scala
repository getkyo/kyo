package kyo.test.runner

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
  *
  * Diagnostics are captured by injecting a per-instance `diagnostics` sink into the reporter under test, never by mutating
  * process-global `java.lang.System.err`. Under the runner's concurrent leaf pool, overlapping `System.setErr` swaps from
  * sibling leaves stomp each other, which made the stderr-capture variant of these tests flaky.
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

    /** A diagnostics sink that records every line, for assertions; and a no-op sink for tests that only need suppression. */
    final private class RecordingSink extends (String => Unit):
        private val lines = new StringBuilder
        def apply(s: String): Unit =
            lines.append(s).append('\n'); ()
        def text: String = lines.toString
    end RecordingSink

    private val silent: String => Unit = _ => ()

    // ── Test 3: okReporter still sees every event when failingReporter is first ────────────────

    "test-3: CombinedReporter(failingReporter, okReporter): okReporter receives every event" in {
        val ok       = new CountingReporter
        val failing  = new FailingReporter
        val combined = new CombinedReporter(Chunk(failing, ok), diagnostics = silent)
        combined.onRunStart(dummyRun)
        combined.onLeafComplete(dummyLeaf, dummyResult)
        combined.onLeafComplete(dummyLeaf, dummyResult)
        assert(ok.leafCount == 2, s"ok reporter saw ${ok.leafCount} leaf events, expected 2")
    }

    // ── Test 4: strict=true rethrows first accumulated exception in onRunComplete ───────────────

    "test-4: CombinedReporter(strict=true): onRunComplete throws the first accumulated exception" in {
        val failing  = new FailingReporter
        val combined = new CombinedReporter(Chunk(failing), strict = true, diagnostics = silent)
        combined.onLeafComplete(dummyLeaf, dummyResult) // accumulate boom
        val thrown = intercept[RuntimeException] {
            combined.onRunComplete(dummyReport)
        }
        assert(thrown.getMessage == "reporter-boom", s"expected 'reporter-boom', got '${thrown.getMessage}'")
    }

    // ── Test 5: strict=false: onRunComplete returns normally; diagnostics has warning ──────────

    "test-5: CombinedReporter(strict=false): onRunComplete returns; stderr has warning" in {
        val failing  = new FailingReporter
        val sink     = new RecordingSink
        val combined = new CombinedReporter(Chunk(failing), strict = false, diagnostics = sink)
        combined.onLeafComplete(dummyLeaf, dummyResult) // accumulate boom
        combined.onRunComplete(dummyReport)             // should NOT throw
        val diag = sink.text
        assert(diag.contains("kyo-test: warning:") && diag.contains("reporter(s) failed"), s"diagnostics were: $diag")
    }

    // ── Test 6: no failing reporters: no warning, no throw ───────────────────────────────────

    "test-6: CombinedReporter with no failing reporters: no stderr warning, no throw" in {
        val ok       = new CountingReporter
        val sink     = new RecordingSink
        val combined = new CombinedReporter(Chunk(ok), strict = true, diagnostics = sink)
        combined.onLeafComplete(dummyLeaf, dummyResult)
        combined.onRunComplete(dummyReport)
        assert(!sink.text.contains("kyo-test: warning"), s"unexpected warning in diagnostics: ${sink.text}")
    }

end CombinedReporterStrictTest
