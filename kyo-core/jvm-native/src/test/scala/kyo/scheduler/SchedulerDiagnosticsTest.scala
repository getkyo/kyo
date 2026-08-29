package kyo.scheduler

import kyo.internal.Diagnostics

class SchedulerDiagnosticsTest extends kyo.test.Test[Any]:

    // SchedulerDiagnostics registers a single process-global entry named "kyo-scheduler" that is never closed (the scheduler is
    // process-lifetime). init() is idempotent, so calling it here is a no-op if an earlier fiber already installed it.

    "registers a kyo-scheduler dumper that renders live worker state" in {
        SchedulerDiagnostics.init()
        val dump = Diagnostics.dumpAll()
        assert(dump.contains("=== kyo-scheduler ==="), s"dump missing the kyo-scheduler section: $dump")
        assert(dump.contains("scheduler: currentWorkers="), s"dump missing the scheduler summary line: $dump")
        assert(dump.contains("busyFiberTraces:"), s"dump missing the busy-fiber-traces marker: $dump")
    }

    "exposes a not-closed probe whose cycles sum the workers' executions" in {
        SchedulerDiagnostics.init()
        val found = Diagnostics.probeAll().toMap.get("kyo-scheduler")
        assert(found.isDefined, "no kyo-scheduler probe registered")
        val p = found.get
        // closed must be false so the runner's stranded-op classifier considers this component at all (a closed probe is never flagged).
        assert(!p.closed, "the scheduler probe must report not-closed")
        // cycles is a monotonic execution sum; pending is timing-dependent (any worker holding load), so it is not asserted here.
        assert(p.cycles >= 0L, s"cycles must be a non-negative execution sum, got ${p.cycles}")
    }
end SchedulerDiagnosticsTest
