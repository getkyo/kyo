package kyo.scheduler

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.Task.Done
import kyo.scheduler.Task.Preempted
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

class WorkerTest extends AnyFreeSpec with NonImplicitAssertions with Eventually with org.scalatest.BeforeAndAfterEach {

    implicit override val patienceConfig: PatienceConfig =
        PatienceConfig(timeout = Span(15, Seconds), interval = Span(50, Millis))

    val executor = TestExecutors.cached

    // Set to true after each test to stop all workers created during that test
    private var globalStop = new AtomicBoolean(false)

    override def afterEach(): Unit = {
        globalStop.set(true)
        Thread.sleep(50)                      // give workers time to exit run() loop
        globalStop = new AtomicBoolean(false) // fresh for next test
    }

    private def createWorker(
        executor: Executor = _ => (),
        scheduleTask: (Task, Worker) => Unit = (_, _) => ???,
        stop: () => Boolean = () => false,
        stealTask: Worker => Task = _ => null,
        currentEpoch: () => Long = () => 0L
    ): Worker = {
        val testStop = globalStop
        val clock    = InternalClock(executor)
        new Worker(0, executor, scheduleTask, stealTask, clock, 5) {
            def currentInterruptEpoch(): Long = currentEpoch()
            def shouldStop()                  = testStop.get() || stop()
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

    "interrupt prioritization" - {
        // A fiber resets its accumulated runtime when interrupted (Task.resetRuntime), so it is
        // scheduled promptly to observe the interrupt and run its finalizers rather than being
        // deprioritized by the runtime it built up while running.

        "a task whose runtime is reset is scheduled ahead of lower-runtime busy tasks" in {
            val worker = createWorker()
            val order  = new ConcurrentLinkedQueue[String]()

            val victim = TestTask(_run = () => { order.add("victim"); Done })
            victim.addRuntime(1000)
            victim.resetRuntime()

            def busy(name: String): TestTask = {
                var n = 0
                TestTask(_run = () => { order.add(name); n += 1; if (n < 5) Preempted else Done })
            }
            List("busy1", "busy2", "busy3", "busy4").map(busy).foreach(worker.enqueue)
            worker.enqueue(victim)
            worker.run()

            assert(
                order.toArray.toList.indexOf("victim") == 0,
                s"reset task was not scheduled first: ${order.toArray.toList}"
            )
        }

        "a reset task's scheduling delay is independent of queued load" in {
            def victimPosition(numBusy: Int): Int = {
                val worker = createWorker()
                val order  = new ConcurrentLinkedQueue[String]()

                val victim = TestTask(_run = () => { order.add("victim"); Done })
                victim.addRuntime(1000000)
                victim.resetRuntime()

                def busy(name: String): TestTask = {
                    var n = 0
                    TestTask(_run = () => { order.add(name); n += 1; if (n < 5) Preempted else Done })
                }
                (1 to numBusy).map(i => busy(s"b$i")).foreach(worker.enqueue)
                worker.enqueue(victim)
                worker.run()
                order.toArray.toList.indexOf("victim")
            }

            val small = victimPosition(numBusy = 4)
            val large = victimPosition(numBusy = 20)
            assert(
                large <= small,
                s"reset-task scheduling delay grew with load (positions: 4-busy=$small, 20-busy=$large)"
            )
        }

        "a task reset while already queued is scheduled promptly regardless of load" in {
            def victimPosition(numBusy: Int): Int = {
                // The interrupt epoch advances at the moment the victim's runtime is reset, mirroring
                // how Scheduler.notifyInterrupt bumps Scheduler.interruptEpoch on a real interrupt.
                val epoch  = new java.util.concurrent.atomic.AtomicLong(0L)
                val worker = createWorker(currentEpoch = () => epoch.get())
                val order  = new ConcurrentLinkedQueue[String]()

                // High runtime, enqueued before any reset; reset later, while queued, by the first
                // busy task to run (as a fiber's runtime is reset when interrupted mid-flight).
                val victim = TestTask(_run = () => { order.add("victim"); Done })
                victim.addRuntime(1000000)

                var resetYet = false
                def busy(name: String): TestTask = {
                    var n = 0
                    TestTask(_run = () => {
                        if (!resetYet) {
                            victim.resetRuntime()
                            epoch.incrementAndGet() // test-local analog of Scheduler.notifyInterrupt's bump
                            resetYet = true
                        }
                        order.add(name)
                        n += 1
                        if (n < 5) Preempted else Done
                    })
                }
                (1 to numBusy).map(i => busy(s"b$i")).foreach(worker.enqueue)
                worker.enqueue(victim)
                worker.run()
                order.toArray.toList.indexOf("victim")
            }

            val small = victimPosition(numBusy = 4)
            val large = victimPosition(numBusy = 20)
            // Once the epoch advances, the next rebalance re-sifts the reset victim to the queue head
            // (the frozen test clock fires exactly one rebuild), so the victim reaches the same constant
            // position regardless of how many busy tasks are queued: load-independent, not merely bounded.
            assert(
                large == small,
                s"reset-while-queued scheduling position depended on load (positions: 4-busy=$small, 20-busy=$large)"
            )
        }

        "a queued task reset without an epoch advance is not boosted (rebalance is epoch-gated)" in {
            // No epoch advance: rebalance's gate (epoch != lastRebuiltEpoch) stays false, so no rebuild
            // fires and the in-place reset is invisible to the heap. The victim then stays at its natural
            // load-dependent position, proving rebalance does nothing on the common (epoch-unchanged) path.
            def victimPosition(numBusy: Int): Int = {
                val worker = createWorker() // currentEpoch defaults to () => 0L: never advances
                val order  = new ConcurrentLinkedQueue[String]()

                val victim = TestTask(_run = () => { order.add("victim"); Done })
                victim.addRuntime(1000000)

                var resetYet = false
                def busy(name: String): TestTask = {
                    var n = 0
                    TestTask(_run = () => {
                        if (!resetYet) { victim.resetRuntime(); resetYet = true } // reset, but NO epoch bump
                        order.add(name)
                        n += 1
                        if (n < 5) Preempted else Done
                    })
                }
                (1 to numBusy).map(i => busy(s"b$i")).foreach(worker.enqueue)
                worker.enqueue(victim)
                worker.run()
                order.toArray.toList.indexOf("victim")
            }

            val small = victimPosition(numBusy = 4)
            val large = victimPosition(numBusy = 20)
            assert(
                large > small,
                s"victim was boosted without an epoch advance (positions: 4-busy=$small, 20-busy=$large)"
            )
        }

        "repeated epoch advances within one frozen tick fire at most one rebuild (bounded under storm)" in {
            // Every busy task advances the epoch (an interrupt storm), but the frozen test clock keeps
            // now - lastRebuildMs at 0 after the first rebuild, so the minInterval gate fires exactly one
            // rebuild per tick. The victim still reaches the same constant head position as a single advance.
            def victimPosition(numBusy: Int): Int = {
                val epoch  = new java.util.concurrent.atomic.AtomicLong(0L)
                val worker = createWorker(currentEpoch = () => epoch.get())
                val order  = new ConcurrentLinkedQueue[String]()

                val victim = TestTask(_run = () => { order.add("victim"); Done })
                victim.addRuntime(1000000)

                var resetYet = false
                def busy(name: String): TestTask = {
                    var n = 0
                    TestTask(_run = () => {
                        if (!resetYet) { victim.resetRuntime(); resetYet = true }
                        epoch.incrementAndGet() // advance on EVERY run: an interrupt storm
                        order.add(name)
                        n += 1
                        if (n < 5) Preempted else Done
                    })
                }
                (1 to numBusy).map(i => busy(s"b$i")).foreach(worker.enqueue)
                worker.enqueue(victim)
                worker.run()
                order.toArray.toList.indexOf("victim")
            }

            val small = victimPosition(numBusy = 4)
            val large = victimPosition(numBusy = 20)
            assert(
                large == small,
                s"storm of epoch advances moved the victim position with load (positions: 4-busy=$small, 20-busy=$large)"
            )
        }

        "a victim reset in a worker's queue is boosted by that worker's own run loop (worker-local)" in {
            // rebalance operates only on this.queue via queue.rebuild(); the boost is driven entirely by
            // the worker's own run loop with no cross-worker coordination. A single worker boosts its own
            // queued victim once the epoch advances at the reset point.
            val epoch  = new java.util.concurrent.atomic.AtomicLong(0L)
            val worker = createWorker(currentEpoch = () => epoch.get())
            val order  = new ConcurrentLinkedQueue[String]()

            val victim = TestTask(_run = () => { order.add("victim"); Done })
            victim.addRuntime(1000000)

            var resetYet = false
            def busy(name: String): TestTask = {
                var n = 0
                TestTask(_run = () => {
                    if (!resetYet) {
                        victim.resetRuntime()
                        epoch.incrementAndGet()
                        resetYet = true
                    }
                    order.add(name)
                    n += 1
                    if (n < 5) Preempted else Done
                })
            }
            List("b1", "b2", "b3", "b4").map(busy).foreach(worker.enqueue)
            worker.enqueue(victim)
            worker.run()

            val victimIndex = order.toArray.toList.indexOf("victim")
            // b1 triggers the reset (position 0). rebalance fires at the top of the NEXT loop
            // iteration, but that iteration's task is already b2 (carried from b1's addAndPoll).
            // b2 runs at position 1; when b2 calls addAndPoll the rebuilt heap returns victim.
            // So victim is at position 2, not stranded at the back behind all the busy work.
            assert(
                victimIndex == 2,
                s"victim was not boosted to position 2 by its own worker's loop: ${order.toArray.toList}"
            )
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
            val worker      = createWorker()
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
            eventually(assert(!worker.checkAvailability(System.currentTimeMillis())))
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
                def currentInterruptEpoch(): Long = 0L
                def shouldStop()                  = false
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
        "a Stalled worker still preempts its CPU-bound task when fresh work queues up (wedge regression)" in withWorker { worker =>
            // Regression for the scheduler wedge (kyo-core AsyncTest hang under CPU-bound load):
            // once a worker entered Stalled state, checkAvailability short-circuited checkStalling,
            // so a CPU-bound task pinned on a Stalled worker never received another doPreempt even
            // as fresh work queued behind it. The task spun forever and the queue grew unbounded.
            // checkStalling must run for any non-blocked worker, Stalled or not.
            @volatile var preempts = 0
            val release            = new CountDownLatch(1)
            val cpuBound = TestTask(
                _preempt = () => preempts += 1,
                // CPU-bound spin that ignores preemption, modelling a fiber pinned mid-time-slice.
                _run = () => {
                    while (release.getCount() > 0) {}
                    Task.Done
                }
            )
            worker.enqueue(cpuBound)
            worker.enqueue(TestTask()) // queue non-empty so the worker stalls and drains, entering Stalled
            eventually {
                assert(!worker.checkAvailability(System.currentTimeMillis()))
                assert(worker.load() == 1) // filler drained; only the running CPU-bound task remains
            }
            val afterStall = preempts
            // Fresh work arrives AFTER the worker is already Stalled with an empty queue. The old
            // code never re-preempted here; the fix keeps issuing doPreempt while Stalled.
            worker.enqueue(TestTask())
            eventually {
                worker.checkAvailability(System.currentTimeMillis())
                assert(preempts > afterStall, "a Stalled worker must keep preempting its CPU-bound task when new work queues behind it")
            }
            release.countDown()
        }
    }

    "checkAvailability" - {
        "blocked flag makes worker unavailable" in {
            val drained = new java.util.concurrent.ConcurrentLinkedQueue[Task]()
            val started = new CountDownLatch(1)
            val done    = new CountDownLatch(1)
            val worker = createWorker(
                executor = executor,
                scheduleTask = (t, _) => { val _ = drained.add(t) }
            )
            // Start a task that blocks
            val task1 = TestTask(_run = () => {
                started.countDown()
                done.await(5, TimeUnit.SECONDS)
                Task.Done
            })
            worker.enqueue(task1)
            assert(started.await(5, TimeUnit.SECONDS))

            // Add a second task to the queue
            val task2 = TestTask()
            worker.enqueue(task2)

            // Simulate BlockingMonitor setting blocked flag
            worker.blocked = true

            // checkAvailability should return false and drain
            eventually {
                assert(!worker.checkAvailability(System.currentTimeMillis()))
            }
            assert(drained.size() >= 1, "queue should be drained when blocked")

            // Unblock
            worker.blocked = false
            done.countDown()
            eventually(assert(task1.executions == 1))
        }

        "cleared blocked flag restores availability" in {
            val worker = createWorker(executor = executor)
            // No task running, checkStalling won't trigger
            worker.blocked = true
            assert(!worker.checkAvailability(System.currentTimeMillis()))
            worker.blocked = false
            assert(worker.checkAvailability(System.currentTimeMillis()))
        }
    }

    "mountId" - {
        "is set during run and cleared on exit" in {
            val mountIdDuringRun = new java.util.concurrent.atomic.AtomicLong(0)
            val done             = new CountDownLatch(1)
            val worker           = createWorker(executor = executor)
            val task = TestTask(_run = () => {
                mountIdDuringRun.set(worker.mountId)
                done.countDown()
                Task.Done
            })
            worker.enqueue(task)
            assert(done.await(5, TimeUnit.SECONDS))
            assert(mountIdDuringRun.get() != 0, "mountId should be non-zero while running")
            eventually(assert(task.executions == 1))
        }
    }

    "runTask clears interrupt flag" in {
        val flagAfterTask = new AtomicBoolean(false)
        val latch         = new CountDownLatch(1)
        val task1 = TestTask(_run = () => {
            Thread.currentThread().interrupt() // set interrupt flag
            Task.Done
        })
        val task2 = TestTask(_run = () => {
            flagAfterTask.set(Thread.interrupted()) // check if flag leaked
            latch.countDown()
            Task.Done
        })

        val worker = createWorker(executor = executor)
        worker.enqueue(task1)
        worker.enqueue(task2)

        assert(latch.await(5, TimeUnit.SECONDS))
        assert(!flagAfterTask.get(), "interrupt flag should be cleared between tasks")
    }

    "needsInterrupt" in {
        val task = TestTask()
        assert(!task.needsInterrupt())

        task.interrupted = true
        assert(task.needsInterrupt())
    }

    "fatal Throwable from a task wedges the worker (BUG REPRODUCER for FatalFiberTest cascade)" in {
        // Repros the chain: FatalFiberTest throws LinkageError -> Worker.runTask's
        // catch only matches NonFatal -> fatal escapes runTask -> escapes Worker.run's
        // unguarded while(true) -> thread dies, Worker.state stays at Running, mount/
        // mountId/blocked never cleared -> wakeup() can never re-arm this Worker ->
        // subsequent enqueued tasks sit in the dead queue.
        val worker = createWorker(executor = executor)

        val fatalTask = TestTask(_run = () => throw new LinkageError("simulated NoClassDefFoundError"))
        worker.enqueue(fatalTask)

        // Worker thread picks it up and dies executing it.
        eventually {
            assert(fatalTask.executions == 1, "fatal task should have been executed once before the thread died")
        }
        Thread.sleep(200) // let any cleanup paths fire if they exist

        // Now enqueue a trivial second task. A healthy Worker re-arms via wakeup() ->
        // exec.execute(this) and runs it. A wedged Worker has state stuck at Running,
        // so the CAS Idle->Running in wakeup() fails and the task never runs.
        val task2 = TestTask()
        worker.enqueue(task2)
        Thread.sleep(500)

        assert(
            task2.executions == 1,
            s"Worker should recover and execute the next task after fatal, but task2.executions=${task2.executions}"
        )
    }
}
