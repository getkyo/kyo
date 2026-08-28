package kyo.scheduler

import java.util.concurrent.CountDownLatch
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import scala.util.control.NoStackTrace

class SchedulerTest extends AnyFreeSpec with NonImplicitAssertions {

    "schedule" - {
        "enqueues tasks to workers" in withScheduler { scheduler =>
            val cdl = new CountDownLatch(1)
            val task1 = TestTask(_run = () => {
                cdl.await()
                Task.Done
            })
            val task2 = TestTask(_run = () => {
                cdl.await()
                Task.Done
            })
            scheduler.schedule(task1)
            scheduler.schedule(task2)
            eventually(assert(scheduler.loadAvg() > 0))
            cdl.countDown()
            eventually(assert(scheduler.loadAvg() == 0))
            assert(task1.executions == 1)
            assert(task2.executions == 1)
        }

        "handles task that throws exception" in withScheduler { scheduler =>
            val task = TestTask(_run = () => throw new RuntimeException("Test exception.") with NoStackTrace)
            scheduler.schedule(task)
            eventually(assert(task.executions == 1))
        }

        "handles scheduling from within a task" in withScheduler { scheduler =>
            val cdl = new CountDownLatch(1)
            val task = TestTask(_run =
                () => {
                    scheduler.schedule(TestTask(_run = () => {
                        cdl.countDown()
                        Task.Done
                    }))
                    Task.Done
                }
            )
            scheduler.schedule(task)
            cdl.await()
        }
    }

    "flush" - {
        "flushes tasks from the current worker" in withScheduler { scheduler =>
            val cdl1  = new CountDownLatch(1)
            val cdl2  = new CountDownLatch(1)
            val task2 = TestTask()
            val task1 = TestTask(_run = () => {
                cdl1.await()
                scheduler.schedule(task2)
                scheduler.flush()
                cdl2.await()
                Task.Done
            })
            scheduler.schedule(task1)
            cdl1.countDown()
            eventually {
                assert(task2.executions == 1)
                assert(task1.executions == 0)
            }
            cdl2.countDown()
            eventually(assert(task1.executions == 1))
        }

        "handles being called when no current worker" in withScheduler { scheduler =>
            scheduler.flush()
        }
    }

    "asExecutor" - {
        "returns an executor that schedules tasks" in withScheduler { scheduler =>
            val executor = scheduler.asExecutor
            val cdl      = new CountDownLatch(1)
            executor.execute(() => cdl.countDown())
            cdl.await()
        }
    }

    "asExecutionContext" - {
        "returns an execution context that schedules tasks" in withScheduler { scheduler =>
            val ec  = scheduler.asExecutionContext
            val cdl = new CountDownLatch(1)
            ec.execute(() => cdl.countDown())
            cdl.await()
        }
    }

    "shutdown" - {
        "stops the scheduler and its components" in withScheduler { scheduler =>
            scheduler.shutdown()
        }
    }

    "busyFiberTraces" - {
        "returns empty when the scheduler is idle" in withScheduler { scheduler =>
            eventually(assert(scheduler.loadAvg() == 0))
            assert(scheduler.busyFiberTraces().isEmpty)
        }

        "covers all busy workers, not first-only" in withScheduler { scheduler =>
            val n   = 3
            val cdl = new CountDownLatch(1)
            val tasks = List.fill(n)(TestTask(_run = () => {
                cdl.await()
                Task.Done
            }))
            tasks.foreach(scheduler.schedule)
            // The snapshot deliberately includes loaded-but-unmounted workers (a task queued on a
            // worker whose thread has not mounted yet) with an empty mount name, so the coverage
            // assertions read the mounted entries: all n busy workers must appear, each on a
            // distinct mount.
            eventually(assert(scheduler.busyFiberTraces().count(_.mount.nonEmpty) >= n))
            val result = scheduler.busyFiberTraces().filter(_.mount.nonEmpty)
            assert(result.size >= n)
            assert(result.map(_.mount).distinct.size == result.size)
            assert(result.forall(_.fiberTrace == ""))
            cdl.countDown()
            eventually(assert(scheduler.loadAvg() == 0))
        }

        "is total under concurrent worker mutation" in withScheduler { scheduler =>
            val cdl = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                cdl.await()
                Task.Done
            })
            scheduler.schedule(task)
            eventually(assert(scheduler.busyFiberTraces().nonEmpty))
            // Release the latch so the worker completes and nulls currentTask concurrently with the probe loop below,
            // exercising the busy -> idle transition the accessor must read without throwing.
            cdl.countDown()
            val results = (1 to 100).map(_ => scheduler.busyFiberTraces())
            assert(results.forall(_ != null))
            assert(results.forall(_.forall(_.mount != null)))
            eventually(assert(scheduler.loadAvg() == 0))
        }
    }

    "blocking compensation" - {
        // The scheduler's contract (class scaladoc: "When blocking occurs, the concurrency regulator observes increased scheduling delays
        // and responds by expanding the worker pool"): a task that blocks its carrier (a parked I/O driver, a blocking fiber) must NOT
        // starve a runnable task. A blocker here parks its carrier (latch await = LockSupport.park, detected by the BlockingMonitor) and is
        // not interruptible (needsInterrupt=false, the default), so a fresh runnable task runs only if the pool keeps runnable capacity
        // while the carrier stays parked. If it does not, that task is queued behind the blockers indefinitely.

        "blocked carriers under host CPU load must not wedge the pool (blocked-carrier floor)" in {
            // Every carrier is blocked while the host is busy with non-scheduler CPU work. The concurrency regulator probes by sleeping
            // 1ms and measuring the wakeup delay; under host CPU contention that delay exceeds its ~800us shrink threshold, so it shrinks
            // (or fails to grow) the pool below the blocked count and a freshly scheduled task is never served. The blocked-carrier floor
            // clamps currentWorkers >= blocked + minWorkers regardless of jitter, keeping minWorkers runnable carriers, so the task runs.
            // The load is real CPU contention, not an injected jitter value, so this exercises the real probe -> shrink -> floor path;
            // keep it that way rather than reducing it to a mocked measurement.
            val cfg = Scheduler.Config.default.copy(cores = 4, coreWorkers = 4, minWorkers = 2, maxWorkers = 400)
            val hostThreads =
                Runtime.getRuntime().availableProcessors() * 4 // 4x oversubscription: reliably drives the regulator's probe jitter over its shrink threshold, even on a 4-vCPU CI runner
            val load = java.util.concurrent.Executors.newFixedThreadPool(hostThreads, kyo.scheduler.util.Threads("host-load"))
            withScheduler(cfg) { s =>
                val gate   = new CountDownLatch(1)
                val canary = new CountDownLatch(1)
                try {
                    // Real host CPU load (NOT scheduler tasks): pure external contention delaying the regulator's probe wakeup.
                    (0 until hostThreads).foreach(_ =>
                        load.execute(() => {
                            var x = 0L
                            while (!Thread.currentThread().isInterrupted()) { x += 1; if (x == Long.MinValue) println(x) }
                        })
                    )
                    // More blockers than carriers (2x coreWorkers): the extra blockers stay queued and refill any carrier the pool
                    // grows, so a transient growth cannot un-wedge it (many producers contending for few carriers). Real,
                    // non-interruptible, indefinitely parked, like a parked I/O driver carrier.
                    (0 until 8).foreach(_ =>
                        s.schedule(new Task {
                            def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result = {
                                try gate.await()
                                catch { case _: InterruptedException => Thread.interrupted(): Unit }
                                Task.Done
                            }
                        })
                    )
                    // Wait (poll interval, not a fixed sleep) until the carriers are blocked, so the canary is scheduled into the
                    // wedge. The 4x host load runs throughout, so the regulator sees sustained jitter and shrinks the pool. Assert on
                    // the loop's own read that broke the wait, not a fresh re-sample: under heavy load the BlockingMonitor is
                    // CPU-starved and its blocked flags flicker, so a second sample can momentarily dip below 4.
                    // 60s hang-guard: the barrier is "at least 4 carriers eventually park"; under sustained 4x host load the parking can take many
                    // seconds, so the deadline only breaks a pool that never parks, never a slow one.
                    val deadline = java.lang.System.nanoTime() + 60000000000L
                    var blk0     = 0
                    while (
                        {
                            blk0 = s.status().workers.count(w => (w ne null) && w.isBlocked); blk0 < 4
                        } && java.lang.System.nanoTime() < deadline
                    )
                        Thread.sleep(5)
                    assert(blk0 >= 4, s"carriers never became blocked within the hang-guard (blocked=$blk0)")
                    // Fresh runnable canary: it must be served. Without the floor the jitter-driven regulator shrinks the pool below
                    // the blocked count and the canary is starved; the floor keeps minWorkers runnable carriers, so it runs.
                    s.schedule(TestTask(_run = () => { canary.countDown(); Task.Done }))
                    val served = canary.await(60, java.util.concurrent.TimeUnit.SECONDS)
                    val st     = s.status()
                    val r      = st.concurrency.regulator
                    val blk    = st.workers.count(w => (w ne null) && w.isBlocked)
                    assert(
                        served,
                        s"WEDGE: a fresh task was starved while $blk carriers blocked and host jitter high. The regulator shrank / failed to " +
                            s"grow the pool below the blocked count instead of holding a blocked-carrier floor. " +
                            s"[currentWorkers=${st.currentWorkers} blocked=$blk runnable=${st.currentWorkers - blk} " +
                            s"jitter=${r.measurementsJitter}ns step=${r.step} updates=${r.updates}]"
                    )
                } finally {
                    gate.countDown()
                    load.shutdownNow()
                    load.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS): Unit
                }
            }
        }
    }

    "regulator harness liveness" - {
        // Each Scheduler permanently pins TWO timer-pool threads with infinite loops: the blocking-monitor scan loop
        // (BlockingMonitor's submitted task) and the worker-cycle loop (Scheduler.cycleTask). The concurrency and admission
        // regulators run their probes as PERIODIC tasks on that same pool (collectInterval=10ms), so a pool with headroom
        // beyond those 2 threads fires them freely. The shared Scheduler.defaultTimerExecutor is size 4, and Scheduler.get
        // (the global singleton) already pins 2 of its threads for the whole JVM, so a regulator-dependent test sharing that
        // pool is unreliable. withScheduler gives every test its own adequately sized, torn-down timer pool by default. This
        // leaf guards that: a live regulator fires within a bounded deadline given a dedicated pool with headroom.
        val liveCfg = Scheduler.Config.default.copy(cores = 2, coreWorkers = 2, minWorkers = 2, maxWorkers = 4)

        "an adequately sized dedicated timer pool keeps the regulator firing" in withScheduler(liveCfg) { s =>
            // probesSent increments every ~10ms; a live regulator fires many within a few seconds. Poll to a 30s hang-guard: the barrier is that
            // the regulator eventually fires, so only a frozen pool runs the deadline out.
            val deadline = java.lang.System.nanoTime() + 30000000000L
            while (s.status().concurrency.regulator.probesSent <= 10 && java.lang.System.nanoTime() < deadline)
                Thread.sleep(10)
            val probes = s.status().concurrency.regulator.probesSent
            assert(probes > 10, s"regulator never fired in the harness (probesSent=$probes) despite an 8-thread dedicated timer pool")
        }
    }

    private def withScheduler[A](testCode: Scheduler => A): A =
        withScheduler(Scheduler.Config.default)(testCode)

    /** Runs `testCode` with a fresh Scheduler on its OWN adequately sized, torn-down timer pool. This is the default for every
      * scheduler test, so none can accidentally hit the starving shared pool.
      *
      * Each Scheduler permanently pins 2 timer-pool threads with infinite loops (the blocking-monitor scan loop and the
      * worker-cycle loop), so the regulator's periodic probes only fire when the pool has headroom beyond those 2. The process
      * Scheduler.defaultTimerExecutor is size 4, of which Scheduler.get (the global singleton) already pins 2 for the whole
      * JVM, so a test sharing it silently freezes the regulator (probesSent stays ~0). A dedicated pool sized well past the
      * 2 pinned loops removes that trap, and shutting it down afterwards avoids thread accumulation on Native.
      */
    private def withScheduler[A](cfg: Scheduler.Config)(testCode: Scheduler => A): A = {
        val timer     = java.util.concurrent.Executors.newScheduledThreadPool(8, kyo.scheduler.util.Threads("test-timer"))
        val scheduler = new Scheduler(TestExecutors.cached, TestExecutors.scheduled, timer, cfg)
        try testCode(scheduler)
        finally { scheduler.shutdown(); timer.shutdownNow(): Unit }
    }
}
