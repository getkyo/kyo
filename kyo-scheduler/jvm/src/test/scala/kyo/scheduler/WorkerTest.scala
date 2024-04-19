package kyo.scheduler

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.Task.Done
import kyo.scheduler.Task.Preempted
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec

class WorkerTest extends AnyFreeSpec with NonImplicitAssertions:

    private def createWorker(
        executor: Executor = _ => (),
        scheduleTask: (Task, Worker) => Unit = (_, _) => ???,
        stealTask: Worker => Task = _ => null,
        getCurrentCycle: () => Long = () => 0
    ): Worker =
        val clock = Clock(executor)
        new Worker(0, executor, scheduleTask, stealTask, getCurrentCycle, clock)
    end createWorker

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
            val stolenTask = worker1.steal(worker2)
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
            val stolenTask = worker1.steal(worker2)
            assert(stolenTask eq task1)
            assert(worker1.load() == 1)
            assert(worker2.load() == 0)
            assert(task1.executions == 0)
            assert(task2.executions == 0)
        }
        "when the stolen task is null" in {
            val worker1    = createWorker()
            val worker2    = createWorker()
            val stolenTask = worker1.steal(worker2)
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
            val stolenTask = worker1.steal(worker2)
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
            val worker      = createWorker(getCurrentCycle = () => 1)
            var preemptions = 0
            val task = TestTask(
                _run = () =>
                    if preemptions < 10 then
                        preemptions += 1
                        Preempted
                    else
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
            val task = TestTask(_run = () =>
                w = Worker.current()
                Task.Done
            )
            worker.enqueue(task)
            worker.run()
            assert(w eq worker)
            assert(Worker.current() == null)
            assert(worker.load() == 0)
            assert(task.executions == 1)
        }
    }

    "live" - {
        def withExecutor[T](f: Executor => T): T =
            val exec = Executors.newCachedThreadPool(Threads("test"))
            try f(exec)
            finally
                exec.shutdown()
                ()
            end try
        end withExecutor

        "execute task" in withExecutor { exec =>
            val worker = createWorker(exec)
            val cdl1   = new CountDownLatch(1)
            val cdl2   = new CountDownLatch(1)
            val task = TestTask(_run = () =>
                cdl1.countDown()
                cdl2.await()
                Done
            )
            worker.enqueue(task)
            cdl1.await()
            assert(worker.load() == 1)
            cdl2.countDown()
            eventually(assert(task.executions == 1))
        }

        "pending task" in withExecutor { exec =>
            val worker = createWorker(exec)
            val cdl    = new CountDownLatch(1)
            val task = TestTask(_run = () =>
                cdl.await()
                Done
            )
            worker.enqueue(task)
            eventually(assert(worker.load() == 1))
            cdl.countDown()
            eventually(assert(worker.load() == 0))
            assert(task.executions == 1)
        }

        "blocked worker rejects tasks" - {
            "waiting thread" in withExecutor { exec =>
                val worker = createWorker(exec)
                val cdl1   = new CountDownLatch(1)
                val cdl2   = new CountDownLatch(1)
                val task = TestTask(_run = () =>
                    cdl1.countDown()
                    cdl2.await()
                    Done
                )
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                cdl1.await()
                eventually(assert(!worker.enqueue(TestTask())))
                cdl2.countDown()
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
            "timed waiting thread" in withExecutor { exec =>
                val worker = createWorker(exec)
                val cdl1   = new CountDownLatch(1)
                val cdl2   = new CountDownLatch(1)
                val task = TestTask(_run = () =>
                    cdl1.countDown()
                    cdl2.await(1, TimeUnit.DAYS)
                    Done
                )
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                cdl1.await()
                eventually(assert(!worker.enqueue(TestTask())))
                cdl2.countDown()
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
            "blocked thread" in withExecutor { exec =>
                val worker = createWorker(exec)
                val thread = new AtomicReference[Thread]
                val task = TestTask(_run = () =>
                    thread.set(Thread.currentThread())
                    LockSupport.park()
                    Done
                )
                worker.enqueue(task)
                eventually(assert(worker.load() == 1))
                eventually(assert(thread.get() != null))
                eventually(assert(!worker.enqueue(TestTask())))
                LockSupport.unpark(thread.get())
                eventually(assert(worker.load() == 0))
                assert(task.executions == 1)
            }
        }

        "blocked worker is drained" in withExecutor { exec =>
            val drained = new ConcurrentLinkedQueue[Task]
            val worker = createWorker(
                exec,
                (t, w) =>
                    drained.add(t)
                    ()
            )
            val cdl   = new CountDownLatch(1)
            val block = new AtomicBoolean
            val task = TestTask(_run = () =>
                while !block.get() do {}
                cdl.await()
                Done
            )
            for _ <- 0 until 10 do
                worker.enqueue(task)
            eventually(assert(worker.load() == 10))
            block.set(true)
            eventually(assert(worker.handleBlocking()))
            assert(drained.size() == 9)
            cdl.countDown()
            eventually(assert(worker.load() == 0))
            assert(task.executions == 1)
        }

        "steal a task from another worker" in withExecutor { exec =>
            val cdl1 = new CountDownLatch(1)
            val cdl2 = new CountDownLatch(1)
            val task1 = TestTask(_run = () =>
                cdl1.await()
                Done
            )
            val task2 = TestTask(_run = () =>
                cdl2.await()
                Done
            )
            val worker1 = createWorker(exec)
            val worker2 = createWorker(exec, stealTask = w => worker1.steal(w))

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
    }
end WorkerTest
