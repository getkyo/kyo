package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.util.ThreadUserTime
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span

/** Stress tests for CPUStallMonitor blocking compensation — runs on both JVM and Native.
  *
  * These tests validate that the monitor correctly detects stalled workers and does NOT falsely detect active workers, under various load
  * conditions. They do NOT call requestInterrupt() or trigger Thread.interrupt() dispatch, which is unsafe on Native when targeting
  * multiple threads.
  *
  * Uses minimal scheduler instances to avoid resource exhaustion on Native from thread leaks. Related assertions are grouped into single
  * scheduler blocks.
  *
  * See the JVM-only CPUStallMonitorInterruptStressTest for interrupt dispatch tests.
  */
class CPUStallMonitorStressTest extends AnyFreeSpec with NonImplicitAssertions {

    private def withScheduler[A](testCode: Scheduler => A): A = {
        val scheduler = new Scheduler(TestExecutors.cached, TestExecutors.scheduled)
        try testCode(scheduler)
        finally scheduler.shutdown()
    }

    private def blockedWorkerCount(scheduler: Scheduler): Int =
        scheduler.status().workers.count(w => (w ne null) && w.isBlocked)

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

    // === Test 1: Low-load stall detection and no false positives ===
    // Combines two scenarios in one scheduler to minimize resource usage.
    "low load — stall detection and no false positives" in withScheduler { scheduler =>
        // Part A: blocking task IS detected as blocked
        val started = new CountDownLatch(1)
        val done    = new CountDownLatch(1)

        val blockingTask = TestTask(_run = () => {
            started.countDown()
            done.await()
            Task.Done
        })

        scheduler.schedule(blockingTask)
        assert(started.await(5, TimeUnit.SECONDS))

        eventually(timeout(scaled(Span(5, Seconds)))) {
            assert(
                blockedWorkerCount(scheduler) >= 1,
                "worker running blocking task should be detected as blocked"
            )
        }

        done.countDown()
        eventually(timeout(scaled(Span(5, Seconds)))) {
            assert(blockingTask.executions == 1, "blocking task should complete")
        }

        // Part B: active task is NOT detected as blocked
        // Wait for blocked flag to clear from Part A
        Thread.sleep(50)
        val baselineAfterA = blockedWorkerCount(scheduler)

        val iterations = new AtomicInteger(0)
        val stop       = new AtomicBoolean(false)

        val activeTask = TestTask(_run = () => {
            // Pure busy-spin — no yield, no syscalls. Ensures CPU time advances
            // continuously between monitor samples even under contention.
            while (!stop.get()) {
                val _ = iterations.incrementAndGet()
            }
            Task.Done
        })

        scheduler.schedule(activeTask)
        eventually(assert(iterations.get() > 1000))

        // Under parallel test processes, transient false positives can occur before
        // the pressure-scaled threshold adapts. Use eventually to allow the flag to clear.
        eventually(timeout(scaled(Span(5, Seconds)))) {
            assert(
                blockedWorkerCount(scheduler) <= baselineAfterA,
                "active CPU-bound worker should not be detected as blocked"
            )
        }

        val before = iterations.get()
        Thread.sleep(20)
        assert(iterations.get() > before, "task should continue executing")

        stop.set(true)
        eventually(assert(activeTask.executions == 1))
    }

    // === Test 2: CPU saturation — blocking task detected under CPU load ===
    "CPU saturation — blocking task still detected" in withScheduler { scheduler =>
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
            assert(started.await(10, TimeUnit.SECONDS))

            eventually(timeout(scaled(Span(10, Seconds)))) {
                assert(
                    blockedWorkerCount(scheduler) >= 1,
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

    // === Test 3: CPU contention — active task not persistently blocked ===
    "CPU contention — active task not persistently blocked" in withScheduler { scheduler =>
        // Use 3 busy threads — enough for contention without exhausting Native resources
        val (busyThreads, busyStop) = spawnBusyThreads(3)
        try {
            val iterations = new AtomicInteger(0)
            val stop       = new AtomicBoolean(false)

            val task = TestTask(_run = () => {
                while (!stop.get()) {
                    val _ = iterations.incrementAndGet()
                }
                Task.Done
            })

            scheduler.schedule(task)

            eventually(timeout(scaled(Span(10, Seconds)))) {
                assert(iterations.get() > 100, "task should get CPU time even under contention")
            }

            Thread.sleep(200)

            var notBlocked = 0
            var checks     = 0
            val checkEnd   = System.nanoTime() + 300000000L // 300ms
            while (System.nanoTime() < checkEnd) {
                if (blockedWorkerCount(scheduler) == 0)
                    notBlocked += 1
                checks += 1
                Thread.sleep(10)
            }

            assert(
                notBlocked > 0,
                s"active task under contention should not be persistently blocked ($checks checks, $notBlocked not-blocked)"
            )

            stop.set(true)
            eventually(timeout(scaled(Span(10, Seconds)))) {
                assert(task.executions == 1)
            }
        } finally
            cleanupBusyThreads(busyThreads, busyStop)
    }

    // === Test 4: Mixed workload + blocking compensation ===
    // Combines discrimination test and blocking compensation in one scheduler.
    // Uses 1 blocking + 1 active to work on systems with as few as 2 CPUs.
    "mixed workload — discrimination and compensation" in withScheduler { scheduler =>
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
        assert(activeStarted.await(10, TimeUnit.SECONDS))

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
                blockedWorkerCount(scheduler) >= 1,
                s"expected at least 1 blocked worker, got ${blockedWorkerCount(scheduler)}"
            )
        }

        blockingDone.countDown()
        activeStop.set(true)
        eventually(timeout(scaled(Span(5, Seconds)))) {
            assert(blockingTask.executions == 1)
            assert(activeTask.executions == 1)
        }
    }

    // === Test 5: wake() backpressure + blocked flag reset ===
    // Combines lightweight tests in one scheduler.
    "wake backpressure and blocked flag reset" in withScheduler { scheduler =>
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
        Thread.sleep(30) // let stale flags clear
        val baseline = blockedWorkerCount(scheduler)
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
        assert(started.await(5, TimeUnit.SECONDS))

        eventually(timeout(scaled(Span(5, Seconds)))) {
            assert(blockedWorkerCount(scheduler) > baseline, "should detect new blocked worker")
        }

        release.countDown()
        assert(resumed.await(5, TimeUnit.SECONDS))

        eventually(timeout(scaled(Span(5, Seconds)))) {
            assert(blockedWorkerCount(scheduler) <= baseline, "blocked flag should clear after resume")
        }
    }
}
