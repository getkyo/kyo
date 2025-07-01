package kyo.scheduler

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.Task.Done
import kyo.scheduler.Task.Preempted
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec

class WorkerTest extends AnyFreeSpec with NonImplicitAssertions {

    val executor = Executors.newCachedThreadPool(Threads("test-worker"))

    private def createWorker(
        executor: Executor = _ => (),
        scheduleTask: (Task, Worker) => Unit = (_, _) => ???,
        stop: () => Boolean = () => false,
        stealTask: Worker => Task = _ => null,
        currentCycle: () => Long = () => 0
    ): Worker = {
        val clock = InternalClock(executor)
        new Worker(0, executor, scheduleTask, stealTask, clock, 5) {
            def getCurrentCycle() = currentCycle()
            def shouldStop()      = stop()
        }
    }

    "enqueue" - {
        "adding tasks to the queue" in {
            val worker = createWorker()
            val task1  = TestTask()
            val task2  = TestTask()
            worker.enqueue(task1)
            worker.enqueue(task2)
            assert(worker.load() == 2)
            assert(task1.executions == 0)
            assert(task2.executions == 0)
        }

        "when the task is added to an empty queue" in {
            val worker = createWorker()
            val task   = TestTask()
            worker.enqueue(task)
            assert(worker.load() == 1)
            assert(task.executions == 0)
        }
    }

    "load" - {
        "when queue is empty and no current task" in {
            val worker = createWorker()
            assert(worker.load() == 0)
        }

        "when tasks are in the queue" in {
            val worker = createWorker()
            val task1  = TestTask()
            val task2  = TestTask()
            worker.enqueue(task1)
            worker.enqueue(task2)
            assert(worker.load() == 2)
            assert(task1.executions == 0)
            assert(task2.executions == 0)
        }

        "when a task is executed" in {
            val worker = createWorker()
            val task   = TestTask()
            worker.enqueue(task)
            worker.run()
            assert(worker.load() == 0)
            assert(task.executions == 1)
        }
    }

    "steal" - {
        "when the victim worker has no tasks" in {
            val worker1    = createWorker()
            val worker2    = createWorker()
            val stolenTask = worker1.stealingBy(worker2)
            assert(stolenTask == null)
            assert(worker1.load() == 0)
            assert(worker2.load() == 0)
        }
        "stealing tasks from another worker" in {
            val worker1 = createWorker()
            val worker2 = createWorker()
            val task1   = TestTask()
            val task2   = TestTask()
            worker1.enqueue(task1)
            worker1.enqueue(task2)
            val stolenTask = worker1.stealingBy(worker2)
            assert(stolenTask eq task1)
            assert(worker1.load() == 1)
            assert(worker2.load() == 0)
            assert(task1.executions == 0)
            assert(task2.executions == 0)
        }
        "when the stolen task is null" in {
            val worker1    = createWorker()
            val worker2    = createWorker()
            val stolenTask = worker1.stealingBy(worker2)
            assert(stolenTask == null)
        }
        "when the stolen task is added to the thief's queue" in {
            val worker1 = createWorker()
            val worker2 = createWorker()
            val task1   = TestTask()
            val task2   = TestTask()
            val task3   = TestTask()
            worker1.enqueue(task1)
            worker1.enqueue(task2)
            worker1.enqueue(task3)
            val stolenTask = worker1.stealingBy(worker2)
            assert(stolenTask eq task1)
            assert(worker1.load() == 1)
            assert(worker2.load() == 1)
            assert(task1.executions == 0)
            assert(task2.executions == 0)
            assert(task3.executions == 0)
        }
    }

    "drain" - {
        "draining tasks from the queue" in {
            var scheduledTasks = List.empty[Task]
            val worker         = createWorker(scheduleTask = (task, w) => scheduledTasks = task :: scheduledTasks)
            val task1          = TestTask()
            val task2          = TestTask()
            worker.enqueue(task1)
            worker.enqueue(task2)
            worker.drain()
            assert(scheduledTasks.equals(List(task2, task1)))
            assert(worker.load() == 0)
            assert(task1.executions == 0)
            assert(task2.executions == 0)
        }
        "when the queue is empty" in {
            var scheduledTasks = List.empty[Task]
            val worker         = createWorker(scheduleTask = (task, w) => scheduledTasks = task :: scheduledTasks)
            worker.drain()
            assert(scheduledTasks.isEmpty)
            assert(worker.load() == 0)
        }
    }

    "run" - {
        "executing tasks from the queue" in {
            val worker = createWorker()
            val task1  = TestTask()
            val task2  = TestTask()
            worker.enqueue(task1)
            worker.enqueue(task2)
            worker.run()
            assert(worker.load() == 0)
            assert(task1.executions == 1)
            assert(task2.executions == 1)
        }

        "executing a task that completes" in {
            val worker = createWorker()
            val task   = TestTask(_run = () => Task.Done)
            worker.enqueue(task)
            worker.run()
            assert(worker.load() == 0)
            assert(task.executions == 1)
        }

        "executing a task that gets preempted" in {
            val worker      = createWorker(currentCycle = () => 1)
            var preemptions = 0
            val task = TestTask(
                _run = () =>
                    if (preemptions < 10) {
                        preemptions += 1
                        Preempted
                    } else
                        Done
            )
            worker.enqueue(task)
            worker.run()
            assert(worker.load() == 0)
            assert(task.preemptions == 10)
            assert(task.executions == 11)
        }

        "sets worker local" in {
            val worker    = createWorker()
            var w: Worker = null
            val task = TestTask(_run = () => {
                w = Worker.current()
                Task.Done
            })
            worker.enqueue(task)
            worker.run()
            assert(w eq worker)
            assert(Worker.current() == null)
            assert(worker.load() == 0)
            assert(task.executions == 1)
        }
    }

    "live" - {

        "execute task" in {
            val worker = createWorker(executor)
            val cdl1   = new CountDownLatch(1)
            val cdl2   = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                cdl1.countDown()
                cdl2.await()
                Done
            })
            worker.enqueue(task)
            cdl1.await()
            assert(worker.load() == 1)
            cdl2.countDown()
            eventually(assert(task.executions == 1))
        }

        "pending task" in {
            val worker = createWorker(executor)
            val cdl    = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                cdl.await()
                Done
            })
            worker.enqueue(task)
            eventually(assert(worker.load() == 1))
            cdl.countDown()
            eventually(assert(worker.load() == 0))
            assert(task.executions == 1)
        }

        "blocked worker rejects tasks" - {
            "waiting thread" in {
                val worker = createWorker(executor)
                val cdl1   = new CountDownLatch(1)
                val cdl2   = new CountDownLatch(1)
                val task = TestTask(_run = () => {
                    cdl1.countDown()
                    cdl2.await()
                    Done
                })
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                cdl1.await()
                worker.enqueue(TestTask())
                cdl2.countDown()
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
            "timed waiting thread" in {
                val worker = createWorker(executor)
                val cdl1   = new CountDownLatch(1)
                val cdl2   = new CountDownLatch(1)
                val task = TestTask(_run = () => {
                    cdl1.countDown()
                    cdl2.await(1, TimeUnit.DAYS)
                    Done
                })
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                cdl1.await()
                worker.enqueue(TestTask())
                cdl2.countDown()
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
            "blocked thread" in {
                val worker = createWorker(executor)
                val thread = new AtomicReference[Thread]
                val task = TestTask(_run = () => {
                    thread.set(Thread.currentThread())
                    LockSupport.park()
                    Done
                })
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                eventually(assert(thread.get() != null))
                worker.enqueue(TestTask())
                LockSupport.unpark(thread.get())
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
            "only if not forced" in {
                val worker = createWorker(executor)
                val thread = new AtomicReference[Thread]
                val task = TestTask(_run = () => {
                    thread.set(Thread.currentThread())
                    LockSupport.park()
                    Done
                })
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                eventually(assert(thread.get() != null))
                worker.enqueue(TestTask())
                LockSupport.unpark(thread.get())
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
        }

        "blocked worker is drained" in {
            val drained = new ConcurrentLinkedQueue[Task]
            val worker = createWorker(
                executor,
                (t, w) => {
                    drained.add(t)
                    ()
                }
            )
            val cdl1 = new CountDownLatch(1)
            val cdl2 = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                cdl2.await()
                cdl1.await()
                Done
            })
            for (_ <- 0 until 10) worker.enqueue(task)
            eventually(assert(worker.load() == 10))
            cdl2.countDown()
            eventually(assert(!worker.checkAvailability(0)))
            assert(drained.size() == 9)
            cdl1.countDown()
            eventually(assert(worker.load() == 0))
            assert(task.executions == 1)
        }

        "steal a task from another worker" in {
            val cdl1 = new CountDownLatch(1)
            val cdl2 = new CountDownLatch(1)
            val task1 = TestTask(_run = () => {
                cdl1.await()
                Done
            })
            val task2 = TestTask(_run = () => {
                cdl2.await()
                Done
            })
            val worker1 = createWorker(executor)
            val worker2 = createWorker(executor, stealTask = w => worker1.stealingBy(w))

            worker1.enqueue(task1)
            worker1.enqueue(task2)
            eventually(assert(worker1.load() == 2))
            assert(worker2.load() == 0)

            worker2.wakeup()
            eventually {
                assert(worker1.load() == 1)
                assert(worker2.load() == 1)
            }
            cdl1.countDown()
            cdl2.countDown()
            eventually {
                assert(task2.executions == 1)
                assert(task1.executions == 1)
            }
        }

        "stop" in {
            val started = new CountDownLatch(1)
            val stop    = new AtomicBoolean
            val done    = new CountDownLatch(1)
            executor.execute { () =>
                started.countDown()
                val worker = createWorker(stop = () => stop.get())
                worker.run()
                done.countDown()
            }
            started.await()
            stop.set(true)
            done.await()
        }
    }

    "checkAvailability" - {

        val scheduled = new AtomicInteger

        def withWorker[A](testCode: Worker => A): A = {
            val clock = InternalClock(executor)
            val worker = new Worker(0, executor, (_, _) => { scheduled.incrementAndGet(); () }, _ => null, clock, 10) {
                def getCurrentCycle() = 0L
                def shouldStop()      = false
            }
            testCode(worker)
        }

        "when worker is idle" in withWorker { worker =>
            assert(worker.checkAvailability(0))
        }

        "when worker is running and not stalled or blocked" in withWorker { worker =>
            val task = TestTask()
            worker.enqueue(task)
            eventually(assert(task.executions == 1))
            assert(worker.checkAvailability(0))
        }

        "when task is running longer than time slice" in withWorker { worker =>
            val cdl = new CountDownLatch(1)
            val longRunningTask = TestTask(_run = () => {
                while (cdl.getCount() > 0) {}
                Task.Done
            })
            worker.enqueue(longRunningTask)
            eventually(assert(!worker.checkAvailability(System.currentTimeMillis())))
            cdl.countDown()
        }

        "when worker is blocked" in withWorker { worker =>
            val cdl = new CountDownLatch(1)
            val blockedTask = TestTask(_run = () => {
                cdl.await()
                Task.Done
            })
            worker.enqueue(blockedTask)
            eventually(assert(!worker.checkAvailability(System.currentTimeMillis())))
            cdl.countDown()
        }

        "drains queue when transitioning to stalled state" in withWorker { worker =>
            val cdl = new CountDownLatch(1)
            val stalledTask = TestTask(_run = () => {
                cdl.await()
                Task.Done
            })
            worker.enqueue(stalledTask)
            worker.enqueue(TestTask())
            worker.enqueue(TestTask())
            eventually {
                assert(!worker.checkAvailability(System.currentTimeMillis()))
                assert(worker.load() == 1) // Only the running task should remain
            }
            cdl.countDown()
        }

        "preempts long-running task if queue isn't empty" in withWorker { worker =>
            var preempted = false
            val longRunningTask = TestTask(
                _run = () => {
                    while (!preempted) {}
                    Task.Done
                },
                _preempt = () => preempted = true
            )
            worker.enqueue(longRunningTask)
            worker.enqueue(longRunningTask)
            eventually {
                assert(!worker.checkAvailability(System.currentTimeMillis()))
                assert(preempted)
            }
        }
        "doesn't preempt long-running task if queue is empty" in withWorker { worker =>
            var preempted = false
            val longRunningTask = TestTask(
                _run = () => {
                    while (!preempted) {}
                    Task.Done
                },
                _preempt = () => preempted = true
            )
            worker.enqueue(longRunningTask)
            eventually {
                assert(!worker.checkAvailability(System.currentTimeMillis()))
                assert(!preempted)
            }
        }
        "drains queue only once when transitioning to stalled state" in withWorker { worker =>
            scheduled.set(0)
            val cdl = new CountDownLatch(1)
            val stalledTask = TestTask(_run = () => {
                cdl.await()
                Task.Done
            })
            worker.enqueue(stalledTask)

            for (_ <- 1 to 5) {
                worker.enqueue(TestTask())
            }

            eventually(assert(!worker.checkAvailability(System.currentTimeMillis())))
            worker.enqueue(TestTask())
            assert(!worker.checkAvailability(System.currentTimeMillis()))
            worker.enqueue(TestTask())
            assert(!worker.checkAvailability(System.currentTimeMillis()))

            assert(scheduled.get() == 5)
            cdl.countDown()
            eventually(assert(worker.checkAvailability(System.currentTimeMillis())))
        }
    }
}
