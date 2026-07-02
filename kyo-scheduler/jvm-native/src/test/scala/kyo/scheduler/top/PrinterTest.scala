package kyo.scheduler.top

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class PrinterTest extends AnyFreeSpec with NonImplicitAssertions {

    def reg = RegulatorStatus(0, 0.0, 0.0, 0, 0, 0, 0)

    def worker(id: Int, running: Boolean, blocked: Boolean, stalled: Boolean, exec: Long, done: Long, stolen: Long, lost: Long, load: Int) =
        WorkerStatus(id, running, s"w$id", "", blocked, stalled, exec, 0L, done, stolen, lost, load, 0L)

    "compact" - {
        "folds worker counters, counts states, and skips null slots" in {
            val w0 = worker(0, running = true, blocked = false, stalled = false, exec = 10, done = 8, stolen = 2, lost = 0, load = 3)
            val w1 = worker(1, running = false, blocked = true, stalled = true, exec = 20, done = 15, stolen = 1, lost = 4, load = 7)
            val status = Status(
                currentWorkers = 2,
                allocatedWorkers = 4,
                loadAvg = 1.5,
                flushes = 99,
                activeThreads = 3,
                totalThreads = 6,
                workers = Seq(w0, null, w1),
                admission = AdmissionStatus(50, 0, 0, reg),
                concurrency = ConcurrencyStatus(reg)
            )
            val line = Printer.compact(status)
            assert(line.startsWith("kyo.sched ts="))
            assert(line.contains(" cur=2 alloc=4"))
            assert(line.contains(" active=1 blocked=1 stalled=1"))
            assert(line.contains(" load=10 loadAvg=1.5000"))
            assert(line.contains(" threads=3/6"))
            assert(line.contains(" exec=30 done=23 stolen=3 lost=4"))
            assert(line.contains(" admit=50"))
            assert(!line.contains("\n"))
        }

        "renders zeros for an empty worker set" in {
            val status = Status(0, 0, 0.0, 0, 0, 0, Seq.empty, AdmissionStatus(100, 0, 0, reg), ConcurrencyStatus(reg))
            val line   = Printer.compact(status)
            assert(line.contains(" active=0 blocked=0 stalled=0"))
            assert(line.contains(" exec=0 done=0 stolen=0 lost=0"))
            assert(line.contains(" admit=100"))
        }
    }
}
