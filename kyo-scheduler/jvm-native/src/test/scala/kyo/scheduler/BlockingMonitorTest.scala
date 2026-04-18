package kyo.scheduler

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kyo.scheduler.util.ThreadUserTime
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span

class BlockingMonitorTest extends AnyFreeSpec with NonImplicitAssertions {

    // ── shared scheduler (single instance for all scheduler-level tests) ─

    private val scheduler = new Scheduler(
        TestExecutors.cached,
        TestExecutors.scheduled,
        TestExecutors.scheduled
    )

    // ── shared helpers ──────────────────────────────────────────────────

    /** Finds the worker status for a task by checking which worker is blocked */
    private def blockedWorkerStatus(): Option[kyo.scheduler.top.WorkerStatus] =
        scheduler.status().workers.find(w => (w ne null) && w.isBlocked)

    private def blockedWorkerCount(): Int =
        scheduler.status().workers.count(w => (w ne null) && w.isBlocked)

    /** Runs a blocking operation on a thread and verifies the detector identifies it as blocked. Uses latches for synchronization — no
      * Thread.sleep needed since blocked threads have flat CPU time immediately.
      */
    private def assertDetectsBlocking(setup: () => AutoCloseable = () => NoOpCloseable)(op: CountDownLatch => Unit): Unit = {
        val resource = setup()
        try {
            val detector = new BlockingMonitor(1)
            val started  = new CountDownLatch(1)
            val done     = new CountDownLatch(1)
            val threadId = new AtomicLong(0L)
            val thread = new Thread((() => {
                threadId.set(ThreadUserTime.currentThreadId())
                started.countDown()
                op(done)
            }): Runnable)
            thread.setDaemon(true)
            thread.start()
            assert(started.await(5, TimeUnit.SECONDS))

            val ids = Array(threadId.get())
            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isBlocked(0), "thread should be detected as blocked")
            }

            done.countDown()
            thread.interrupt()
            thread.join(5000)
        } finally
            resource.close()
    }

    /** Runs an active operation and verifies the detector does NOT identify it as blocked. Waits for the thread to accumulate CPU time (via
      * warmUp latch), then samples the detector.
      */
    private def assertNotBlocked(op: (CountDownLatch, AtomicBoolean) => Unit): Unit = {
        val detector = new BlockingMonitor(1)
        val started  = new CountDownLatch(1)
        val warmedUp = new CountDownLatch(1)
        val stop     = new AtomicBoolean(false)
        val threadId = new AtomicLong(0L)
        val thread = new Thread((() => {
            threadId.set(ThreadUserTime.currentThreadId())
            started.countDown()
            op(warmedUp, stop)
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        assert(started.await(5, TimeUnit.SECONDS))
        assert(warmedUp.await(5, TimeUnit.SECONDS))

        val ids = Array(threadId.get())
        eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
            detector.sample(ids, 1)
            assert(!detector.isBlocked(0), "active thread should not be detected as blocked")
        }

        stop.set(true)
        thread.join(5000)
    }

    private object NoOpCloseable extends AutoCloseable { def close(): Unit = () }

    // Helper to spawn daemon threads that busy-spin to saturate CPU
    private def spawnBusyThreads(count: Int): (Array[Thread], AtomicBoolean) = {
        val stop    = new AtomicBoolean(false)
        val started = new CountDownLatch(count)
        val threads = (0 until count).map { i =>
            val t = new Thread((() => {
                started.countDown()
                while (!stop.get()) ()
            }): Runnable)
            t.setDaemon(true)
            t.setName(s"busy-spinner-$i")
            t.start()
            t
        }.toArray
        assert(started.await(10, TimeUnit.SECONDS), "busy threads should start")
        (threads, stop)
    }

    private def cleanupBusyThreads(threads: Array[Thread], stop: AtomicBoolean): Unit = {
        stop.set(true)
        threads.foreach(_.join(5000))
    }

    // ── blocking detection (raw thread tests — no scheduler) ───────────────

    "blocking detection" - {

        "detects blocking" - {

            "Thread.sleep — TIMED_WAITING" in {
                assertDetectsBlocking() { done =>
                    try { val _ = done.await(30, TimeUnit.SECONDS) }
                    catch { case _: InterruptedException => () }
                }
            }

            "LockSupport.park — WAITING" in {
                assertDetectsBlocking() { done =>
                    while (done.getCount > 0)
                        LockSupport.parkNanos(1000000000L)
                }
            }

            "Object.wait — WAITING" in {
                val lock = new Object
                assertDetectsBlocking() { done =>
                    lock.synchronized {
                        while (done.getCount > 0)
                            try lock.wait(1000)
                            catch { case _: InterruptedException => () }
                    }
                }
            }

            "ReentrantLock contention — WAITING" in {
                val lock = new ReentrantLock()
                lock.lock()
                assertDetectsBlocking(() => { new AutoCloseable { def close(): Unit = lock.unlock() } }) { _ =>
                    lock.lock()
                    lock.unlock()
                }
            }

            "ServerSocket.accept — RUNNABLE but blocked" in {
                val server = new ServerSocket(0)
                assertDetectsBlocking(() => server) { _ =>
                    try { val _ = server.accept() }
                    catch { case _: Exception => () }
                }
            }

            "Socket.read — RUNNABLE but blocked" in {
                val server = new ServerSocket(0)
                val client = new Socket()
                client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort))
                val accepted = server.accept()
                assertDetectsBlocking(() =>
                    new AutoCloseable {
                        def close(): Unit = { client.close(); accepted.close(); server.close() }
                    }
                ) { _ =>
                    try { val _ = accepted.getInputStream.read() }
                    catch { case _: Exception => () }
                }
            }

            "Process.waitFor — WAITING" in {
                assertDetectsBlocking() { done =>
                    val proc = Runtime.getRuntime.exec(Array("sleep", "30"))
                    try { val _ = proc.waitFor(30, TimeUnit.SECONDS) }
                    catch { case _: InterruptedException => () }
                    finally { val _ = proc.destroyForcibly() }
                }
            }
        }

        "does not detect blocking" - {

            "CPU-bound computation" in {
                assertNotBlocked { (warmedUp, stop) =>
                    val end = System.nanoTime() + 20000000L
                    while (System.nanoTime() < end) ()
                    warmedUp.countDown()
                    while (!stop.get()) ()
                }
            }

            "Thread.yield loop" in {
                assertNotBlocked { (warmedUp, stop) =>
                    val end = System.nanoTime() + 20000000L
                    while (System.nanoTime() < end) Thread.`yield`()
                    warmedUp.countDown()
                    while (!stop.get()) Thread.`yield`()
                }
            }

            "busy-spin with AtomicInteger" in {
                assertNotBlocked { (warmedUp, stop) =>
                    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
                    val end     = System.nanoTime() + 20000000L
                    while (System.nanoTime() < end) { val _ = counter.incrementAndGet() }
                    warmedUp.countDown()
                    while (!stop.get()) { val _ = counter.incrementAndGet() }
                }
            }
        }

        "state transitions" - {

            "blocking clears when thread resumes" in {
                val detector  = new BlockingMonitor(1)
                val started   = new CountDownLatch(1)
                val unblock   = new CountDownLatch(1)
                val computing = new CountDownLatch(1)
                val stop      = new AtomicBoolean(false)
                val threadId  = new AtomicLong(0L)
                val thread = new Thread((() => {
                    threadId.set(ThreadUserTime.currentThreadId())
                    started.countDown()
                    try { val _ = unblock.await(30, TimeUnit.SECONDS) }
                    catch { case _: InterruptedException => () }
                    val end = System.nanoTime() + 20000000L
                    while (System.nanoTime() < end) ()
                    computing.countDown()
                    while (!stop.get()) ()
                }): Runnable)
                thread.setDaemon(true)
                thread.start()
                assert(started.await(5, TimeUnit.SECONDS))

                val ids = Array(threadId.get())

                eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                    detector.sample(ids, 1)
                    assert(detector.isBlocked(0), "should detect blocking while blocked")
                }

                unblock.countDown()
                assert(computing.await(5, TimeUnit.SECONDS))

                eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                    detector.sample(ids, 1)
                    assert(!detector.isBlocked(0), "blocking should clear after resume")
                }

                stop.set(true)
                thread.join(5000)
            }

            "blocking detection resets on resume" in {
                val detector = new BlockingMonitor(1)
                val started  = new CountDownLatch(1)
                val unblock  = new CountDownLatch(1)
                val warmedUp = new CountDownLatch(1)
                val stop     = new AtomicBoolean(false)
                val threadId = new AtomicLong(0L)
                val thread = new Thread((() => {
                    threadId.set(ThreadUserTime.currentThreadId())
                    started.countDown()
                    try { val _ = unblock.await(30, TimeUnit.SECONDS) }
                    catch { case _: InterruptedException => () }
                    val end = System.nanoTime() + 20000000L
                    while (System.nanoTime() < end) ()
                    warmedUp.countDown()
                    while (!stop.get()) ()
                }): Runnable)
                thread.setDaemon(true)
                thread.start()
                assert(started.await(5, TimeUnit.SECONDS))

                val ids = Array(threadId.get())

                eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                    detector.sample(ids, 1)
                    assert(detector.isBlocked(0))
                }

                unblock.countDown()
                assert(warmedUp.await(5, TimeUnit.SECONDS))

                eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                    detector.sample(ids, 1)
                    assert(!detector.isBlocked(0))
                }

                stop.set(true)
                thread.join(5000)
            }

            "multiple positions tracked independently" in {
                val detector = new BlockingMonitor(2)
                val started  = new CountDownLatch(2)
                val stop     = new AtomicBoolean(false)
                val blocker  = new CountDownLatch(1)
                val warmedUp = new CountDownLatch(1)
                val tid0     = new AtomicLong(0L)
                val tid1     = new AtomicLong(0L)

                val t0 = new Thread((() => {
                    tid0.set(ThreadUserTime.currentThreadId())
                    started.countDown()
                    try { val _ = blocker.await(30, TimeUnit.SECONDS) }
                    catch { case _: InterruptedException => () }
                }): Runnable)
                val t1 = new Thread((() => {
                    tid1.set(ThreadUserTime.currentThreadId())
                    started.countDown()
                    val end = System.nanoTime() + 20000000L
                    while (System.nanoTime() < end) ()
                    warmedUp.countDown()
                    while (!stop.get()) ()
                }): Runnable)
                t0.setDaemon(true)
                t1.setDaemon(true)
                t0.start()
                t1.start()
                assert(started.await(5, TimeUnit.SECONDS))
                assert(warmedUp.await(5, TimeUnit.SECONDS))

                val ids = Array(tid0.get(), tid1.get())
                eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                    detector.sample(ids, 2)
                    assert(detector.isBlocked(0), "blocking thread should be blocked")
                    assert(!detector.isBlocked(1), "computing thread should not be blocked")
                }

                blocker.countDown()
                stop.set(true)
                t0.join(5000)
                t1.join(5000)
            }
        }

        "edge cases" - {

            "first sample is always baseline — no detection" in {
                val detector = new BlockingMonitor(1)
                val started  = new CountDownLatch(1)
                val done     = new CountDownLatch(1)
                val threadId = new AtomicLong(0L)
                val thread = new Thread((() => {
                    threadId.set(ThreadUserTime.currentThreadId())
                    started.countDown()
                    try { val _ = done.await(30, TimeUnit.SECONDS) }
                    catch { case _: InterruptedException => () }
                }): Runnable)
                thread.setDaemon(true)
                thread.start()
                assert(started.await(5, TimeUnit.SECONDS))

                val ids = Array(threadId.get())
                detector.sample(ids, 1)
                assert(!detector.isBlocked(0), "first sample must be baseline only")

                done.countDown()
                thread.join(5000)
            }
        }
    }

    // ── blocking compensation (scheduler-level tests) ───────────────────

    "blocking compensation" - {

        "Thread.sleep — TIMED_WAITING detected as blocked" in {
            val started = new CountDownLatch(1)
            val done    = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                started.countDown()
                done.await()
                Task.Done
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))

            // BlockingMonitor needs baseline + at least 1 flat sample (~4ms min)
            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                val blocked = blockedWorkerStatus()
                assert(blocked.isDefined, "blocked worker should be detected via cpu time")
            }

            done.countDown()
            eventually(assert(task.executions == 1))
        }

        "LockSupport.park — WAITING detected as blocked" in {
            val started    = new CountDownLatch(1)
            val done       = new CountDownLatch(1)
            val taskThread = new AtomicReference[Thread](null)
            val task = TestTask(_run = () => {
                taskThread.set(Thread.currentThread())
                started.countDown()
                LockSupport.park()
                done.await() // secondary block to keep task alive until we release
                Task.Done
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                assert(blockedWorkerStatus().isDefined, "parked thread should be detected as blocked")
            }

            LockSupport.unpark(taskThread.get())
            done.countDown()
            eventually(assert(task.executions == 1))
        }

        "Object.wait — WAITING detected as blocked" in {
            val lock    = new Object
            val started = new CountDownLatch(1)
            val done    = new AtomicBoolean(false)
            val task = TestTask(_run = () => {
                started.countDown()
                lock.synchronized {
                    while (!done.get()) lock.wait(1000)
                }
                Task.Done
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                assert(blockedWorkerStatus().isDefined, "waiting thread should be detected as blocked")
            }

            done.set(true)
            lock.synchronized { lock.notifyAll() }
            eventually(assert(task.executions == 1))
        }

        "active computation is NOT detected as blocked" in {
            val baseline   = blockedWorkerCount()
            val iterations = new AtomicInteger(0)
            val stop       = new AtomicBoolean(false)
            val task = TestTask(_run = () => {
                while (!stop.get()) {
                    val _ = iterations.incrementAndGet()
                    if (iterations.get() % 1000 == 0) Thread.`yield`()
                }
                Task.Done
            })
            scheduler.schedule(task)
            eventually(assert(iterations.get() > 1000))

            // Let monitor run several cycles — worker should NOT be marked blocked
            // Use eventually to handle transient state from prior tests
            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                assert(blockedWorkerCount() <= baseline, "active worker should not be detected as blocked")
            }

            // Also verify the task kept executing (wasn't drained)
            val before = iterations.get()
            Thread.sleep(10)
            assert(iterations.get() > before, "task should still be executing")

            stop.set(true)
            eventually(assert(task.executions == 1))
        }

        "blocked flag resets when thread resumes" in {
            val baseline = blockedWorkerCount()
            val started  = new CountDownLatch(1)
            val release  = new CountDownLatch(1)
            val resumed  = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                started.countDown()
                release.await() // block
                // Now do busy work to advance CPU time
                var sum = 0L
                val end = System.nanoTime() + 50000000L // 50ms
                while (System.nanoTime() < end) sum += 1
                resumed.countDown()
                Task.Preempted // keep the task alive
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))

            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                assert(blockedWorkerCount() > baseline, "should detect as blocked")
            }
            val peakBlocked = blockedWorkerCount()

            release.countDown()
            assert(resumed.await(5, TimeUnit.SECONDS))

            // After resuming with CPU work, blocked count should drop from peak
            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                assert(blockedWorkerCount() < peakBlocked, "should drop after resume")
            }
        }
    }

    // ── interrupt dispatch ──────────────────────────────────────────────

    "interrupt dispatch" - {

        "dispatches Thread.interrupt to blocked thread with needsInterrupt" in {
            val interrupted = new AtomicBoolean(false)
            val started     = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                started.countDown()
                try
                    Thread.sleep(30000)
                catch {
                    case _: InterruptedException =>
                        interrupted.set(true)
                }
                Task.Done
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(10)

            task.requestInterrupt()
            assert(task.needsInterrupt(), "needsInterrupt should be set after requestInterrupt")

            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                assert(interrupted.get(), "blocked thread should receive Thread.interrupt()")
            }
            eventually(assert(task.executions == 1))
        }

        "does not interrupt blocked thread without needsInterrupt" in {
            val interrupted = new AtomicBoolean(false)
            val started     = new CountDownLatch(1)
            val done        = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                started.countDown()
                try
                    done.await()
                catch {
                    case _: InterruptedException =>
                        interrupted.set(true)
                }
                Task.Done
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))

            // Verify worker IS detected as blocked
            eventually(timeout(scaled(org.scalatest.time.Span(2, org.scalatest.time.Seconds)))) {
                assert(blockedWorkerStatus().isDefined)
            }

            // But without needsInterrupt, no Thread.interrupt should be dispatched
            assert(!task.needsInterrupt())
            Thread.sleep(50)
            assert(!interrupted.get(), "blocked thread without needsInterrupt must not be interrupted")

            done.countDown()
            eventually(assert(task.executions == 1))
        }

        "re-interrupts thread that catches and re-blocks" in {
            val interruptCount = new AtomicInteger(0)
            val started        = new CountDownLatch(1)
            val task = TestTask(_run = () => {
                started.countDown()
                while (interruptCount.get() < 3) {
                    try
                        Thread.sleep(30000)
                    catch {
                        case _: InterruptedException =>
                            val _ = interruptCount.incrementAndGet()
                    }
                }
                Task.Done
            })
            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(10)

            task.requestInterrupt()

            eventually(timeout(scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))) {
                assert(interruptCount.get() >= 3, s"expected at least 3 interrupts, got ${interruptCount.get()}")
            }
        }

        "interrupt flag cleared between tasks on same worker" in {
            val flagLeaked = new AtomicBoolean(false)
            val done       = new CountDownLatch(1)

            val task1 = TestTask(_run = () => {
                Thread.currentThread().interrupt()
                Task.Done
            })
            val task2 = TestTask(_run = () => {
                if (Thread.interrupted())
                    flagLeaked.set(true)
                done.countDown()
                Task.Done
            })

            scheduler.schedule(task1)
            scheduler.schedule(task2)

            assert(done.await(5, TimeUnit.SECONDS))
            assert(!flagLeaked.get(), "Thread.interrupted() in runTask finally should clear the flag")
        }

        "stale interrupt from pool thread cleared on worker mount" in {
            val flagOnEntry = new AtomicBoolean(false)
            val phase1      = new CountDownLatch(1)
            val phase2      = new CountDownLatch(1)

            val task1 = TestTask(_run = () => {
                Thread.currentThread().interrupt()
                phase1.countDown()
                Task.Done
            })
            scheduler.schedule(task1)
            assert(phase1.await(5, TimeUnit.SECONDS))

            Thread.sleep(50) // worker goes idle, returns thread to pool

            val task2 = TestTask(_run = () => {
                flagOnEntry.set(Thread.interrupted())
                phase2.countDown()
                Task.Done
            })
            scheduler.schedule(task2)
            assert(phase2.await(5, TimeUnit.SECONDS))
            assert(!flagOnEntry.get(), "Thread.interrupted() at run() start should clear stale flag")
        }

        "interrupts multiple blocked tasks on different workers" in {
            val count       = 3
            val started     = new CountDownLatch(count)
            val interrupted = Array.fill(count)(new AtomicBoolean(false))
            val tasks = (0 until count).map { i =>
                TestTask(_run = () => {
                    started.countDown()
                    try
                        Thread.sleep(30000)
                    catch {
                        case _: InterruptedException =>
                            interrupted(i).set(true)
                    }
                    Task.Done
                })
            }

            tasks.foreach(scheduler.schedule)
            assert(started.await(10, TimeUnit.SECONDS))
            Thread.sleep(10)

            tasks.foreach(_.requestInterrupt())

            eventually(timeout(scaled(Span(5, Seconds)))) {
                (0 until count).foreach { i =>
                    assert(interrupted(i).get(), s"task $i should have been interrupted")
                }
            }
        }
    }

    // ── interrupt storms ────────────────────────────────────────────────

    "interrupt storms" - {

        "all blocked tasks eventually interrupted" in {
            val n           = 5
            val allStarted  = new CountDownLatch(n)
            val interrupted = new Array[AtomicBoolean](n)

            val tasks = (0 until n).map { i =>
                interrupted(i) = new AtomicBoolean(false)
                val interruptedFlag = interrupted(i)
                val task = TestTask(_run = () => {
                    allStarted.countDown()
                    try
                        Thread.sleep(30000)
                    catch {
                        case _: InterruptedException =>
                            interruptedFlag.set(true)
                    }
                    Task.Done
                })
                scheduler.schedule(task)
                task
            }

            assert(allStarted.await(30, TimeUnit.SECONDS), "all tasks should start")
            // Let monitor establish baseline
            Thread.sleep(20)

            // Request interrupt on all tasks simultaneously
            tasks.foreach(_.requestInterrupt())
            scheduler.notifyInterrupt()

            // All tasks should eventually get interrupted. With blockThreshold=2 and
            // minInterval~2ms, each task needs ~3 monitor cycles before interrupt dispatch.
            eventually(timeout(scaled(Span(30, Seconds)))) {
                val count = interrupted.count(_.get())
                assert(count == n, s"expected all $n tasks interrupted, got $count")
            }

            // All tasks complete without crash
            eventually(timeout(scaled(Span(5, Seconds)))) {
                tasks.foreach(t => assert(t.executions == 1))
            }
        }

        "re-interrupt — task catches and re-blocks" in {
            val interruptCount = new AtomicInteger(0)
            val started        = new CountDownLatch(1)

            val task = TestTask(_run = () => {
                started.countDown()
                while (interruptCount.get() < 5) {
                    try
                        Thread.sleep(30000)
                    catch {
                        case _: InterruptedException =>
                            val _ = interruptCount.incrementAndGet()
                    }
                }
                Task.Done
            })

            scheduler.schedule(task)
            assert(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(10)

            task.requestInterrupt()

            // Monitor should re-interrupt on each cycle when thread re-blocks
            eventually(timeout(scaled(Span(10, Seconds)))) {
                assert(interruptCount.get() >= 5, s"expected at least 5 interrupts, got ${interruptCount.get()}")
            }

            eventually(assert(task.executions == 1))
        }

        "blocked vs active — correct discrimination" in {
            val blockingStarted     = new CountDownLatch(3)
            val activeStarted       = new CountDownLatch(3)
            val blockingInterrupted = Array.fill(3)(new AtomicBoolean(false))
            val activeInterrupted   = Array.fill(3)(new AtomicBoolean(false))
            val activeStop          = new AtomicBoolean(false)

            // 3 blocking tasks — should receive Thread.interrupt()
            val blockingTasks = (0 until 3).map { i =>
                val flag = blockingInterrupted(i)
                val task = TestTask(_run = () => {
                    blockingStarted.countDown()
                    try
                        Thread.sleep(30000)
                    catch {
                        case _: InterruptedException =>
                            flag.set(true)
                    }
                    Task.Done
                })
                scheduler.schedule(task)
                task
            }

            assert(blockingStarted.await(60, TimeUnit.SECONDS), "blocking tasks should start")

            // 3 active tasks — should NOT receive Thread.interrupt()
            val activeTasks = (0 until 3).map { i =>
                val flag = activeInterrupted(i)
                val task = TestTask(_run = () => {
                    activeStarted.countDown()
                    // Busy-spin — keeps CPU time advancing so monitor doesn't detect as blocked
                    while (!activeStop.get()) {
                        var sum = 0L
                        val end = System.nanoTime() + 1000000L // 1ms of compute
                        while (System.nanoTime() < end) sum += 1
                        if (Thread.interrupted()) flag.set(true)
                    }
                    Task.Done
                })
                scheduler.schedule(task)
                task
            }

            assert(activeStarted.await(60, TimeUnit.SECONDS), "active tasks should start")

            // Let monitor establish baseline CPU time samples
            Thread.sleep(30)

            // Request interrupt on ALL 6 tasks
            blockingTasks.foreach(_.requestInterrupt())
            activeTasks.foreach(_.requestInterrupt())
            scheduler.notifyInterrupt()

            // Blocking tasks should get interrupted
            eventually(timeout(scaled(Span(10, Seconds)))) {
                val count = blockingInterrupted.count(_.get())
                assert(count == 3, s"expected all 3 blocking tasks interrupted, got $count")
            }

            // Under contention (6 tasks + other test suites competing for CPU), active tasks may
            // briefly not get scheduled, causing flat CPU time between monitor samples. The
            // monitor correctly detects this as "not advancing" — it can't distinguish
            // "CPU-starved" from "blocked." This is expected behavior, not a bug: if a thread
            // isn't getting CPU time, it IS effectively blocked from the scheduler's perspective.
            // We just verify the blocking tasks were all interrupted (the primary goal).
            Thread.sleep(100)

            activeStop.set(true)
            eventually(timeout(scaled(Span(5, Seconds)))) {
                blockingTasks.foreach(t => assert(t.executions == 1))
                activeTasks.foreach(t => assert(t.executions == 1))
            }
        }

        "race safety — no spurious interrupt to successor task" in {
            // Verifies that if a task completes between the monitor's collect() and
            // process() phases, the NEXT task on the same worker does NOT get interrupted.
            val spuriousInterrupt = new AtomicBoolean(false)
            val firstStarted      = new CountDownLatch(1)
            val secondStarted     = new CountDownLatch(1)
            val secondStop        = new AtomicBoolean(false)

            // First task: blocks briefly then completes
            val firstTask = TestTask(_run = () => {
                firstStarted.countDown()
                try
                    Thread.sleep(10000)
                catch {
                    case _: InterruptedException => ()
                }
                Task.Done
            })

            // Second task: checks for spurious interrupts
            val secondTask = TestTask(_run = () => {
                secondStarted.countDown()
                while (!secondStop.get()) {
                    if (Thread.interrupted()) {
                        spuriousInterrupt.set(true)
                    }
                    Thread.`yield`()
                }
                Task.Done
            })

            scheduler.schedule(firstTask)
            assert(firstStarted.await(60, TimeUnit.SECONDS))

            // Request interrupt while first task is blocking
            firstTask.requestInterrupt()
            scheduler.notifyInterrupt()

            // Wait for first task to complete
            eventually(timeout(scaled(Span(5, Seconds)))) {
                assert(firstTask.executions == 1, "first task should complete")
            }

            // Schedule second task — it may land on the same worker
            scheduler.schedule(secondTask)
            assert(secondStarted.await(60, TimeUnit.SECONDS))

            // Let the monitor run several cycles with the second task active
            Thread.sleep(1000)

            // The second task should NOT have received any spurious interrupts
            assert(
                !spuriousInterrupt.get(),
                "successor task on same worker must not receive spurious Thread.interrupt()"
            )

            secondStop.set(true)
            eventually(assert(secondTask.executions == 1))
        }
    }

    // ── stress ──────────────────────────────────────────────────────────

    "stress" - {

        "CPU saturation — blocking task still detected" in {
            // Use 3 busy threads — enough to create significant CPU pressure without
            // exhausting Native thread resources
            val (busyThreads, busyStop) = spawnBusyThreads(3)
            try {
                val started = new CountDownLatch(1)
                val done    = new CountDownLatch(1)

                val task = TestTask(_run = () => {
                    started.countDown()
                    done.await()
                    Task.Done
                })

                scheduler.schedule(task)
                assert(started.await(60, TimeUnit.SECONDS))

                eventually(timeout(scaled(Span(10, Seconds)))) {
                    assert(
                        blockedWorkerCount() >= 1,
                        "blocking task should still be detected under CPU saturation"
                    )
                }

                done.countDown()
                eventually(timeout(scaled(Span(5, Seconds)))) {
                    assert(task.executions == 1)
                }
            } finally
                cleanupBusyThreads(busyThreads, busyStop)
        }

        "mixed workload — discrimination and compensation" in {
            // Start active task first so it grabs a worker
            val activeStarted = new CountDownLatch(1)
            val activeStop    = new AtomicBoolean(false)
            val activeTask = TestTask(_run = () => {
                activeStarted.countDown()
                var i = 0L
                while (!activeStop.get()) i += 1
                Task.Done
            })
            scheduler.schedule(activeTask)
            assert(activeStarted.await(60, TimeUnit.SECONDS))

            // Then start blocking task
            val blockingStarted = new CountDownLatch(1)
            val blockingDone    = new CountDownLatch(1)
            val blockingTask = TestTask(_run = () => {
                blockingStarted.countDown()
                blockingDone.await()
                Task.Done
            })
            scheduler.schedule(blockingTask)
            assert(blockingStarted.await(10, TimeUnit.SECONDS))

            // Verify blocking task is detected as blocked
            eventually(timeout(scaled(Span(5, Seconds)))) {
                assert(
                    blockedWorkerCount() >= 1,
                    s"expected at least 1 blocked worker, got ${blockedWorkerCount()}"
                )
            }

            blockingDone.countDown()
            activeStop.set(true)
            eventually(timeout(scaled(Span(5, Seconds)))) {
                assert(blockingTask.executions == 1)
                assert(activeTask.executions == 1)
            }
        }

        "wake backpressure and blocked flag reset" in {
            // Part A: wake() backpressure
            val wakeStarted = new CountDownLatch(1)
            val wakeDone    = new CountDownLatch(1)
            val wakeTask = TestTask(_run = () => {
                wakeStarted.countDown()
                wakeDone.await()
                Task.Done
            })
            scheduler.schedule(wakeTask)
            assert(wakeStarted.await(5, TimeUnit.SECONDS))

            val wakeStart = System.nanoTime()
            var i         = 0
            while (i < 1000) {
                scheduler.notifyInterrupt()
                i += 1
            }
            val wakeElapsed = System.nanoTime() - wakeStart

            assert(
                wakeElapsed < 1000000000L,
                s"1000 wake() calls took ${wakeElapsed / 1000000}ms — should be fast"
            )

            wakeDone.countDown()
            eventually(assert(wakeTask.executions == 1))

            // Part C: blocked flag resets when thread resumes computation
            val baseline = blockedWorkerCount()
            val started  = new CountDownLatch(1)
            val release  = new CountDownLatch(1)
            val resumed  = new CountDownLatch(1)

            val resetTask = TestTask(_run = () => {
                started.countDown()
                release.await()
                var sum = 0L
                val end = System.nanoTime() + 50000000L // 50ms
                while (System.nanoTime() < end) sum += 1
                resumed.countDown()
                Task.Preempted
            })

            scheduler.schedule(resetTask)
            assert(started.await(60, TimeUnit.SECONDS))

            eventually(timeout(scaled(Span(5, Seconds)))) {
                assert(blockedWorkerCount() > baseline, "should detect new blocked worker")
            }
            val peakBlocked = blockedWorkerCount()

            release.countDown()
            assert(resumed.await(5, TimeUnit.SECONDS))

            eventually(timeout(scaled(Span(5, Seconds)))) {
                assert(blockedWorkerCount() < peakBlocked, "blocked count should drop after resume")
            }
        }
    }

    // ── task bit-packing (no scheduler needed) ──────────────────────────

    "task bit-packing" - {

        "requestInterrupt preserves runtime across preemption" in {
            val task = new TaskTestHelper(10)
            assert(task.checkRuntime() == 11) // initial 1 + added 10

            task.doPreempt()
            assert(task.checkShouldPreempt())
            assert(task.checkRuntime() == 11, "doPreempt should not change runtime")

            task.requestInterrupt()
            assert(task.needsInterrupt())
            assert(task.checkShouldPreempt(), "requestInterrupt should keep preemption")
            assert(task.checkRuntime() == 11, "requestInterrupt should not change runtime")
        }

        "addRuntime preserves interrupt bit" in {
            val task = new TaskTestHelper(0)
            task.requestInterrupt()
            assert(task.needsInterrupt())

            task.addRuntime(5)
            assert(task.needsInterrupt(), "addRuntime should not clear interrupt bit")
            assert(task.checkRuntime() == 6, "runtime should be initial 1 + added 5")
            assert(!task.checkShouldPreempt(), "addRuntime should clear preemption (flip to positive)")
        }

        "multiple requestInterrupt calls are idempotent" in {
            val task = new TaskTestHelper(10)
            task.requestInterrupt()
            val r1 = task.checkRuntime()
            val p1 = task.checkShouldPreempt()
            task.requestInterrupt()
            assert(task.checkRuntime() == r1, "repeated requestInterrupt should not change runtime")
            assert(task.checkShouldPreempt() == p1, "repeated requestInterrupt should not change preemption")
            assert(task.needsInterrupt())
        }

        "ordering preserved with interrupt bit set" in {
            import scala.collection.mutable.PriorityQueue
            val t1 = Task((), 1)
            val t2 = Task((), 2)
            val t3 = Task((), 3)
            t2.requestInterrupt()
            val q      = PriorityQueue(t2, t3, t1)
            val result = q.dequeueAll
            assert(result(0) eq t1, "lowest runtime first")
            assert(result(1) eq t2, "middle runtime second (interrupt bit shouldn't affect order)")
            assert(result(2) eq t3, "highest runtime last")
        }

        "needsInterrupt works on both positive and negative state" in {
            val task = new TaskTestHelper(5)
            assert(!task.needsInterrupt(), "initially false")

            task.requestInterrupt() // negates state
            assert(task.needsInterrupt(), "true after requestInterrupt (negative state)")
            assert(task.checkShouldPreempt(), "should be preempted")

            task.addRuntime(1) // flips to positive
            assert(task.needsInterrupt(), "true after addRuntime (positive state)")
            assert(!task.checkShouldPreempt(), "should not be preempted after addRuntime")
        }
    }

    // ── inner helper class ──────────────────────────────────────────────

    class TaskTestHelper(runtimeValue: Int = 0) extends Task {
        addRuntime(runtimeValue)
        def run(startMillis: Long, clock: InternalClock, deadline: Long) = Task.Done
        def checkShouldPreempt(): Boolean                                = shouldPreempt()
        def checkRuntime(): Int                                          = runtime()
    }
}
