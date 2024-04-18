package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kyo.scheduler.Task.Done
import kyo.scheduler.Task.Preempted
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec

class SchedulerTest extends AnyFreeSpec with NonImplicitAssertions:

    private def withScheduler[T](testCode: Scheduler => T): T =
        val executor          = Executors.newCachedThreadPool(Threads("test-scheduler-worker"))
        val scheduledExecutor = Executors.newSingleThreadScheduledExecutor(Threads("test-scheduler-timer"))
        val scheduler         = new Scheduler(executor, scheduledExecutor)
        try testCode(scheduler)
        finally
            scheduler.shutdown()
            scheduledExecutor.shutdown()
        end try
    end withScheduler

    "schedule" - {
        "enqueues tasks to workers" in withScheduler { scheduler =>
            val cdl = new CountDownLatch(1)
            val task1 = TestTask(_run = () =>
                cdl.await()
                Task.Done
            )
            val task2 = TestTask(_run = () =>
                cdl.await()
                Task.Done
            )
            scheduler.schedule(task1)
            scheduler.schedule(task2)
            eventually(assert(scheduler.loadAvg() > 0))
            cdl.countDown()
            eventually(assert(scheduler.loadAvg() == 0))
            assert(task1.executions == 1)
            assert(task2.executions == 1)
        }

        "handles task that throws exception" in withScheduler { scheduler =>
            val task = TestTask(_run = () => throw new RuntimeException("Test exception."))
            scheduler.schedule(task)
            eventually(assert(task.executions == 1))
        }

        "handles scheduling from within a task" in withScheduler { scheduler =>
            val cdl = new CountDownLatch(1)
            val task = TestTask(_run =
                () =>
                    scheduler.schedule(TestTask(_run = () =>
                        cdl.countDown()
                        Task.Done
                    ))
                    Task.Done
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
            val task1 = TestTask(_run = () =>
                cdl1.await()
                scheduler.schedule(task2)
                scheduler.flush()
                cdl2.await()
                Task.Done
            )
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

    "cycle" - {
        "cycles workers and preempts tasks" in withScheduler { scheduler =>
            val cdl          = new CountDownLatch(1)
            val preemptLatch = new CountDownLatch(1)
            val task = TestTask(
                _preempt = () => preemptLatch.countDown(),
                _run = () =>
                    cdl.await()
                    Task.Done
            )
            scheduler.schedule(task)
            preemptLatch.await()
            cdl.countDown()
            eventually(assert(task.executions == 1))
        }
        "repeatedly cycles and preempts tasks" in withScheduler { scheduler =>
            val cdl          = new CountDownLatch(1)
            val preemptLatch = new CountDownLatch(3)
            val preempt      = new AtomicBoolean
            val task = TestTask(
                _preempt = () =>
                    preemptLatch.countDown()
                    preempt.set(true)
                ,
                _run = () =>
                    if preemptLatch.getCount > 0 then
                        while !preempt.compareAndSet(true, false) do {}
                        Task.Preempted
                    else
                        cdl.await()
                        Task.Done
            )
            scheduler.schedule(task)

            preemptLatch.await()
            eventually(assert(task.preemptions == 3))

            cdl.countDown()
            eventually(assert(task.executions == 4))
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
            assert(!scheduler.asExecutor.isInstanceOf[Scheduler])
        }
    }
end SchedulerTest
