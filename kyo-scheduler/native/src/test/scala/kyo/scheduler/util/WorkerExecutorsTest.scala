package kyo.scheduler.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class WorkerExecutorsTest extends AnyFreeSpec with NonImplicitAssertions {

    "worker" - {
        "a submission from an interrupted thread that must grow the pool runs and keeps the caller's interrupt" in {
            val created = new ConcurrentLinkedQueue[Thread]
            val factory = Threads(
                "test-worker-executors",
                r => {
                    val t = new Thread(null, r, "test-worker-executors", 0L, false)
                    val _ = created.add(t)
                    t
                }
            )
            val exec = WorkerExecutors.worker(factory, 1, 1)
            // prestartAllCoreThreads creates the core thread before the retry watchdog, so it is the first thread the factory made.
            val core    = created.peek()
            val release = new CountDownLatch(1)
            val ran     = new CountDownLatch(1)
            try {
                // A pool thread can be parked in the SynchronousQueue only while it is the core thread and idle: every other thread
                // was spawned to run a blocker, which never returns. Blockers are submitted until one lands on the core thread, so
                // no thread is parked and the next submission has to create one through the init gate on this thread.
                var coreOccupied = false
                while (!coreOccupied) {
                    val started = new CountDownLatch(1)
                    val ranOn   = new AtomicReference[Thread](null)
                    exec.execute { () =>
                        ranOn.set(Thread.currentThread())
                        started.countDown()
                        release.await()
                    }
                    assert(started.await(30, TimeUnit.SECONDS), "a blocker never started")
                    coreOccupied = ranOn.get() eq core
                }
                Thread.currentThread().interrupt()
                val failure =
                    try { exec.execute(() => ran.countDown()); None }
                    catch { case e: InterruptedException => Some(e) }
                val interrupted = Thread.interrupted()
                assert(failure.isEmpty, s"the gate's wait must not fail the submission on the caller's interrupt, got $failure")
                assert(interrupted, "the caller's interrupt must survive the submission")
                assert(ran.await(30, TimeUnit.SECONDS), "the submission never ran")
            } finally {
                release.countDown()
                exec.shutdown()
            }
        }
    }
}
