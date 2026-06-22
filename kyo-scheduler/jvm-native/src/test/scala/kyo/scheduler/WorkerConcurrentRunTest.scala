package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

/** Regression guard for the Worker concurrent-run() / lost-wakeup race.
  *
  * The race: an exiting run()'s ownership release (the idle path or the finally) could overlap with a
  * wakeup-dispatched successor run() of the SAME worker, so two run() invocations executed concurrently
  * (violating the single-owner contract that every plain var in Worker relies on), and under saturation
  * a task enqueued at the release boundary could be stranded with a non-empty queue, a stale state, and
  * no live run() to drain it.
  *
  * Each probe drives the real enqueue -> wakeup -> exec.execute(this) -> run() path on the real executor.
  *
  * Observation barrier (how concurrent run() is detected without touching production code): the task-body
  * witness (maxConcurrentRun). Each run() executes its tasks sequentially on its own mounted thread, so two
  * run()s of one worker overlap in their task-execution loop if and only if two of that worker's tasks
  * execute simultaneously. Each task brackets its body with inFlight.incrementAndGet()/decrementAndGet()
  * and publishes a running max; it also records the executing thread ids so an overlap names the threads.
  * Task-execution-loop exclusion is the dangerous invariant (it is what every plain var in Worker, the
  * counters and mount and currentTask, depends on), so the probes assert maxConcurrentRun == 1.
  *
  * A second metric (maxRunOverlap) is computed for diagnostics but NOT asserted: the Worker's executor is
  * wrapped so each submitted Runnable (one run()) is bracketed on entry/exit (the clock ticker runs on a
  * separate plain executor so only run() dispatches are counted). It is not a clean invariant because the
  * ephemeral-thread model has a benign teardown/startup window: an exiting run() releases ownership in its
  * idle path and then finishes its per-thread teardown (nulling mount/mountId, clearCurrent) on its own
  * thread while a successor wakeup may already have started a fresh run() on a different thread, and under
  * aggressive boundary hammering these teardown/startup tails can chain a few deep. That window is
  * baseline-equivalent and correctness-irrelevant (no two task-execution loops coexist, so maxConcurrentRun
  * stays 1); the metric is surfaced in failure messages only to characterize a regression.
  *
  * Determinism barrier: the race is a thread interleaving, not a deterministic schedule, so each probe
  * loops the boundary tens to hundreds of thousands of times to make the failure reliable rather than
  * probabilistic. On the pre-fix code probe 2 and probe 3 fail well within these counts (observed
  * maxConcurrentRun 2 and 3, with stranded tasks); the loop counts are sized so a single test run
  * exercises the window far past the ~1-in-6 hit rate reported under load. No Async.sleep is used; the
  * producer/worker hand-off is driven by the queue state and busy spins bounded by deadlines.
  */
class WorkerConcurrentRunTest extends AnyFreeSpec with NonImplicitAssertions {

    private val executor: Executor = TestExecutors.cached

    /** A worker harness that exposes the single-owner observation. It builds a real Worker on the real
      * executor; the only test seams are shouldStop, a no-op stealTask, and a re-homing scheduleTask,
      * the same seams WorkerTest already uses.
      */
    final private class Harness {
        val stop = new AtomicBoolean(false)

        // Concurrency witness: incremented while a task body runs, max tracked.
        val inFlight    = new AtomicInteger(0)
        val maxInFlight = new AtomicInteger(0)
        // Distinct threads seen executing this worker's tasks while another was in flight.
        val overlapThreads = new AtomicReference[Set[Long]](Set.empty)

        // One worker IS the whole scheduler here, so re-home a drained task by re-enqueueing it (as
        // Scheduler.schedule does), not a no-op that would mask a strand. enqueue's wakeup re-arms us.
        private val self                           = new AtomicReference[Worker](null)
        private val rehome: (Task, Worker) => Unit = (task, _) => self.get().enqueue(task)

        // Independent witness: bracket each Runnable the Worker submits (one per run()) to count
        // overlapping run()s directly. The clock ticker uses the plain executor, so only runs count here.
        val activeRuns    = new AtomicInteger(0)
        val maxActiveRuns = new AtomicInteger(0)
        private val countingExec: Executor = (r: Runnable) =>
            executor.execute { () =>
                val a = activeRuns.incrementAndGet()
                var m = maxActiveRuns.get()
                while (a > m && !maxActiveRuns.compareAndSet(m, a)) m = maxActiveRuns.get()
                try r.run()
                finally { val _ = activeRuns.decrementAndGet() }
            }

        val worker: Worker = {
            val clock = InternalClock(executor) // clock ticker runs on the plain executor so it is not counted
            val w = new Worker(0, countingExec, rehome, _ => null, clock, 10) {
                def currentInterruptEpoch(): Long = 0L
                def shouldStop()                  = stop.get()
            }
            self.set(w)
            w
        }

        def maxRunOverlap: Int = maxActiveRuns.get()

        /** Brackets a task body with the in-flight witness. */
        def witnessed(body: => Task.Result): Task.Result = {
            val n = inFlight.incrementAndGet()
            if (n > 1) {
                // record both threads currently executing this worker's tasks
                val tid  = Thread.currentThread().threadId()
                var prev = overlapThreads.get()
                while (!overlapThreads.compareAndSet(prev, prev + tid)) prev = overlapThreads.get()
            }
            var seen = maxInFlight.get()
            while (n > seen && !maxInFlight.compareAndSet(seen, n)) seen = maxInFlight.get()
            try body
            finally { val _ = inFlight.decrementAndGet() }
        }

        def maxConcurrentRun: Int   = maxInFlight.get()
        def concurrentSeen: Boolean = maxInFlight.get() > 1
    }

    private def deadlineMs(ms: Long): Long = System.currentTimeMillis() + ms

    "probe 1: blind hammer never overlaps (negative control)" in {
        val h     = new Harness
        val total = 500000
        val ran   = new AtomicInteger(0)
        // One producer enqueues all tasks; no idle-boundary contention, so both a correct and a buggy
        // worker keep maxConcurrentRun=1. Proves the witness does not false-positive on the common path.
        var i = 0
        while (i < total) {
            h.worker.enqueue(TestTask(_run = () => h.witnessed { ran.incrementAndGet(); Task.Done }))
            i += 1
        }
        val end = deadlineMs(30000)
        while (ran.get() < total && System.currentTimeMillis() < end) {}
        h.stop.set(true)
        assert(ran.get() == total, s"probe1 executed=${ran.get()}/$total")
        assert(
            h.maxConcurrentRun == 1,
            s"probe1 maxConcurrentRun=${h.maxConcurrentRun} maxRunOverlap=${h.maxRunOverlap} (expected 1)"
        )
    }

    // Regression guard: the idle/exit boundary in Worker.run must not let two run() invocations of the
    // same worker overlap in their task-execution loop (the single-owner invariant every plain var in
    // Worker depends on). Before the fix this reproduced 8/8 on the JVM and 4/4 on Native.
    "probe 2: boundary hammer never runs the worker concurrently" in {
        // Enqueue only when the worker has drained to empty, so each wakeup lands in the run() exit
        // window. On the unfixed code the unconditional state.set(Idle) in the finally lets a producer's
        // wakeup start a second overlapping run() there (witnessed as maxConcurrentRun >= 2).
        val h      = new Harness
        val rounds = 200000
        val ran    = new AtomicInteger(0)

        var round = 0
        val end   = deadlineMs(60000)
        while (round < rounds && System.currentTimeMillis() < end) {
            // Wait until the worker has gone back to empty so the next enqueue hits the exit window.
            val drainEnd = deadlineMs(5000)
            while (h.worker.load() > 0 && System.currentTimeMillis() < drainEnd) {}
            h.worker.enqueue(TestTask(_run = () => h.witnessed { ran.incrementAndGet(); Task.Done }))
            round += 1
        }
        // Let the worker finish draining the tail.
        val tailEnd = deadlineMs(10000)
        while (ran.get() < round && System.currentTimeMillis() < tailEnd) {}
        h.stop.set(true)

        assert(round == rounds, s"probe2 completed rounds=$round/$rounds")
        assert(ran.get() == round, s"probe2 ran=${ran.get()}/$round (stranded ${round - ran.get()})")
        assert(
            !h.concurrentSeen,
            s"probe2 CONCURRENT run() witnessed: maxConcurrentRun=${h.maxConcurrentRun} maxRunOverlap=${h.maxRunOverlap} threads=${h.overlapThreads.get()} rounds=$round"
        )
        assert(
            h.maxConcurrentRun == 1,
            s"probe2 maxConcurrentRun=${h.maxConcurrentRun} maxRunOverlap=${h.maxRunOverlap} (expected 1)"
        )
    }

    // Regression guard: the same boundary exercised under concurrent injection. No task may be stranded
    // and no two run() invocations may overlap.
    "probe 3: concurrent injection at the boundary strands no task and never overlaps" in {
        // Several injectors enqueue at the drained boundary so their wakeups pile onto the exit window.
        // On the unfixed code a task added after the exiting run's empty-check, with state stomped to Idle
        // and no wakeup left pending, is stranded forever. With re-home active any non-run is a genuine
        // permanent strand; after quiescence every task must have run.
        val h         = new Harness
        val injectors = 4
        val perInj    = 50000
        val total     = injectors * perInj
        val ran       = new AtomicInteger(0)
        val enq       = new AtomicInteger(0)
        val started   = new CountDownLatch(injectors)
        val go        = new CountDownLatch(1)
        val end       = deadlineMs(60000)

        val threads =
            (0 until injectors).map { _ =>
                val t = new Thread(() => {
                    started.countDown()
                    go.await()
                    var k = 0
                    while (k < perInj && System.currentTimeMillis() < end) {
                        // Hug the boundary: only add when the worker looks drained, so the wakeup races its exit.
                        val drainEnd = deadlineMs(5000)
                        while (h.worker.load() > 0 && System.currentTimeMillis() < drainEnd) {}
                        h.worker.enqueue(TestTask(_run = () => h.witnessed { ran.incrementAndGet(); Task.Done }))
                        enq.incrementAndGet()
                        k += 1
                    }
                })
                t.setDaemon(true)
                t
            }
        threads.foreach(_.start())
        started.await()
        go.countDown()
        threads.foreach(_.join(70000))

        val totalEnq = enq.get()
        // Wait for every enqueued task to run. A strand is ran < totalEnq that never advances after all
        // injectors have stopped producing: no live run() and no pending wakeup to recover it.
        val tailEnd = deadlineMs(15000)
        while (ran.get() < totalEnq && System.currentTimeMillis() < tailEnd) {}
        h.stop.set(true)

        assert(
            !h.concurrentSeen,
            s"probe3 CONCURRENT run() witnessed: maxConcurrentRun=${h.maxConcurrentRun} maxRunOverlap=${h.maxRunOverlap} threads=${h.overlapThreads.get()}"
        )
        assert(
            h.maxConcurrentRun == 1,
            s"probe3 maxConcurrentRun=${h.maxConcurrentRun} maxRunOverlap=${h.maxRunOverlap} (expected 1)"
        )
        assert(totalEnq == total, s"probe3 only enqueued $totalEnq/$total before deadline")
        val stranded = totalEnq - ran.get()
        assert(
            stranded == 0,
            s"probe3 STRANDED $stranded task(s): ran=${ran.get()}/$totalEnq queueLoad=${h.worker.load()}"
        )
    }
}
