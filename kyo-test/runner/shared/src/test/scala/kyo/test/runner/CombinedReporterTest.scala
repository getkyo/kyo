package kyo.test.runner

import kyo.*
import kyo.Chunk
import kyo.Maybe
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult

class CombinedReporterTest extends kyo.test.Test[Any]:

    private class CountingReporter extends TestReporter:
        var leafCompleteCalls                                           = 0
        var runStartCalls                                               = 0
        def onRunStart(info: RunInfo): Unit                             = runStartCalls += 1
        def onSuiteStart(info: SuiteInfo): Unit                         = ()
        def onLeafStart(info: LeafInfo): Unit                           = ()
        def onLeafComplete(info: LeafInfo, result: TestResult): Unit    = leafCompleteCalls += 1
        def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit = ()
        def onRunComplete(report: TestReport): Unit                     = ()
    end CountingReporter

    private class ThrowingReporter extends TestReporter:
        def onRunStart(info: RunInfo): Unit                             = ()
        def onSuiteStart(info: SuiteInfo): Unit                         = ()
        def onLeafStart(info: LeafInfo): Unit                           = ()
        def onLeafComplete(info: LeafInfo, result: TestResult): Unit    = throw new RuntimeException("boom")
        def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit = ()
        def onRunComplete(report: TestReport): Unit                     = ()
    end ThrowingReporter

    private val dummyLeaf   = LeafInfo("suite", Chunk("leaf"), Set.empty)
    private val dummyResult = TestResult.Passed(1L.millis)

    "dispatches to all reporters" in {
        val r1       = new CountingReporter
        val r2       = new CountingReporter
        val r3       = new CountingReporter
        val combined = CombinedReporter(r1, r2, r3)
        combined.onRunStart(RunInfo(1, 1))
        combined.onLeafComplete(dummyLeaf, dummyResult)
        combined.onLeafComplete(dummyLeaf, dummyResult)
        assert(r1.runStartCalls == 1, s"r1.runStartCalls: ${r1.runStartCalls}")
        assert(r1.leafCompleteCalls == 2, s"r1.leafCompleteCalls: ${r1.leafCompleteCalls}")
        assert(r2.runStartCalls == 1, s"r2.runStartCalls: ${r2.runStartCalls}")
        assert(r2.leafCompleteCalls == 2, s"r2.leafCompleteCalls: ${r2.leafCompleteCalls}")
        assert(r3.runStartCalls == 1, s"r3.runStartCalls: ${r3.runStartCalls}")
        assert(r3.leafCompleteCalls == 2, s"r3.leafCompleteCalls: ${r3.leafCompleteCalls}")
    }

    "survives one reporter throwing" in {
        val r1       = new CountingReporter
        val throwing = new ThrowingReporter
        val r2       = new CountingReporter
        val combined = CombinedReporter(r1, throwing, r2)
        // must not propagate the exception from ThrowingReporter
        combined.onLeafComplete(dummyLeaf, dummyResult)
        assert(r1.leafCompleteCalls == 1, s"r1.leafCompleteCalls: ${r1.leafCompleteCalls}")
        assert(r2.leafCompleteCalls == 1, s"r2.leafCompleteCalls: ${r2.leafCompleteCalls}")
    }

    // ── Strict mode with a failed sub-reporter ───────────────────────────────────────────────────

    "strict mode with one failed sub-reporter: peek is non-null and throw does not NPE" in {
        val boom     = new RuntimeException("strict-boom")
        val throwing = new ThrowingReporter
        val combined = new CombinedReporter(Chunk(throwing), strict = true)
        // Accumulate the error without triggering strict throw yet
        combined.onLeafComplete(dummyLeaf, dummyResult)
        // Verify errors queue is non-empty and peek returns non-null
        val top = combined.errors.peek()
        assert(top != null, "errors.peek() returned null but a reporter had thrown")
        // Verify that throwing `top` does not produce a NullPointerException
        val thrown = intercept[Throwable] {
            throw top
        }
        assert(!thrown.isInstanceOf[NullPointerException], s"Expected non-NPE but got: $thrown")
    }

end CombinedReporterTest
