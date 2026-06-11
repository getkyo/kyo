package kyo.test.runner

import kyo.*
import kyo.test.LeafInfo
import kyo.test.RunConfig
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult
import kyo.test.Verbosity

class ReportersTest extends kyo.test.Test[Any]:

    private class StubReporter extends TestReporter:
        def onRunStart(info: RunInfo): Unit                             = ()
        def onSuiteStart(info: SuiteInfo): Unit                         = ()
        def onLeafStart(info: LeafInfo): Unit                           = ()
        def onLeafComplete(info: LeafInfo, result: TestResult): Unit    = ()
        def onSuiteComplete(info: SuiteInfo, report: SuiteReport): Unit = ()
        def onRunComplete(report: TestReport): Unit                     = ()
    end StubReporter

    // Test 1: RunConfig().reporter equals Maybe.empty (compile-time + runtime)
    "RunConfig default reporter is Maybe.empty" in {
        val config: RunConfig      = RunConfig()
        val r: Maybe[TestReporter] = config.reporter
        assert(r == Maybe.empty)
        assert(r.isEmpty)
    }

    // Test 2: resolveReporter with absent reporter returns a ConsoleReporter
    "resolveReporter with absent reporter returns ConsoleReporter" in {
        val config   = RunConfig(reporter = Maybe.empty)
        val resolved = kyo.test.runner.TestRunner.resolveReporter(config)
        assert(resolved.getClass.getSimpleName == "ConsoleReporter", s"expected ConsoleReporter but got ${resolved.getClass.getSimpleName}")
    }

    // Test 3: resolveReporter with present reporter returns that reporter unchanged
    "resolveReporter with present reporter returns that reporter" in {
        val custom   = new StubReporter
        val config   = RunConfig(reporter = Maybe(custom))
        val resolved = kyo.test.runner.TestRunner.resolveReporter(config)
        assert(resolved eq custom, "expected the exact same reporter instance")
    }

    // Test 4: Reporters.console returns a ConsoleReporter
    "Reporters.console returns ConsoleReporter" in {
        val r = Reporters.console(Verbosity.Normal)
        assert(r.getClass.getSimpleName == "ConsoleReporter", s"expected ConsoleReporter but got ${r.getClass.getSimpleName}")
    }

    // Test 5: Reporters.combined wraps a ConsoleReporter in a CombinedReporter
    "Reporters.combined wraps console reporter in CombinedReporter" in {
        val console  = Reporters.console(Verbosity.Normal)
        val combined = Reporters.combined(console)
        assert(
            combined.getClass.getSimpleName == "CombinedReporter",
            s"expected CombinedReporter but got ${combined.getClass.getSimpleName}"
        )
    }

end ReportersTest
