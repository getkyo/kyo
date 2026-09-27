package kyo.scheduler.util

import java.util.List as JList
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span

/** Covers the retry wrapper that shields the scheduler from scala-native's lost `SynchronousQueue` handoff.
  *
  * The delegate here is a stub whose acceptance is decoupled from execution, so a submission can be accepted and
  * never run (the defect), accepted and run late (the retry race), or rejected. Assertions are on what ran and on
  * the tracking set, never on how long a retry took.
  */
class HandoffRetryExecutorTest extends AnyFreeSpec with NonImplicitAssertions {

    // Bounded only as a hang canary: every assertion below is a state the executor reaches or does not.
    implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds))

    private val factory = Threads("test-handoff-retry")

    /** Delegate that runs submissions on real threads, dropping the first `drops` of them.
      *
      * `rejectOnCall` / `throwOnCall` / `interruptOnCall` fail one specific arrival by its ordinal: call 1 is the initial
      * submission, call 2 its first retry. Once shut down, every arrival is rejected, as a real pool's is.
      */
    private class StubPool(drops: Int, rejectOnCall: Int = 0, throwOnCall: Int = 0, interruptOnCall: Int = 0)
        extends AbstractExecutorService {
        val accepted               = new AtomicInteger(0)
        @volatile private var down = false
        private val dropped        = new AtomicInteger(0)

        def execute(command: Runnable): Unit = {
            if (down) throw new RejectedExecutionException("stub is shut down")
            val call = accepted.incrementAndGet()
            if (call == rejectOnCall) throw new RejectedExecutionException("stub")
            if (call == throwOnCall) throw new IllegalStateException("rogue executor")
            if (call == interruptOnCall) throw new InterruptedException("stub")
            if (dropped.getAndIncrement() < drops) () // accepted, never run: the defect
            else {
                val t = factory.newThread(command)
                t.start()
            }
        }

        def shutdown(): Unit                                         = down = true
        def shutdownNow(): JList[Runnable]                           = { down = true; new java.util.ArrayList[Runnable] }
        def isShutdown(): Boolean                                    = down
        def isTerminated(): Boolean                                  = down
        def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    "a dropped submission is retried and the task runs exactly once" in {
        val pool = new StubPool(drops = 1)
        val exec = new HandoffRetryExecutor(pool, factory)
        val runs = new AtomicInteger(0)
        val ran  = new CountDownLatch(1)
        exec.execute { () => runs.incrementAndGet(); ran.countDown() }
        assert(ran.await(30, TimeUnit.SECONDS), "the retry never mounted the dropped submission")
        assert(runs.get() == 1)
        assert(pool.accepted.get() >= 2, "the drop must have been re-submitted")
        exec.shutdown()
    }

    "a submission that runs late alongside its retry still executes once" in {
        val pool           = new StubPool(drops = 0)
        val exec           = new HandoffRetryExecutor(pool, factory)
        val runs           = new AtomicInteger(0)
        val ran            = new CountDownLatch(1)
        val task: Runnable = () => { runs.incrementAndGet(); ran.countDown() }
        // Submit the same tracked runnable through the wrapper twice over: the started flag is what makes the
        // duplicate a no-op, so both arrivals together must still produce one run.
        exec.execute(task)
        assert(ran.await(30, TimeUnit.SECONDS))
        eventually(assert(runs.get() == 1, s"expected exactly one run, got ${runs.get()}"))
        exec.shutdown()
    }

    "the tracking set drains once submissions start" in {
        val pool = new StubPool(drops = 0)
        val exec = new HandoffRetryExecutor(pool, factory)
        val ran  = new CountDownLatch(4)
        for (_ <- 1 to 4) exec.execute(() => ran.countDown())
        assert(ran.await(30, TimeUnit.SECONDS))
        eventually(assert(exec.pendingSize == 0, s"pending did not drain, size=${exec.pendingSize}"))
        exec.shutdown()
    }

    "a rejected retry is kept while the pool is live and the task still runs" in {
        // Call 1 is accepted and dropped, call 2 (the first retry) is rejected while the pool is live: the entry
        // must be kept so call 3 delivers.
        val pool = new StubPool(drops = 1, rejectOnCall = 2)
        val exec = new HandoffRetryExecutor(pool, factory)
        val ran  = new CountDownLatch(1)
        exec.execute(() => ran.countDown())
        assert(ran.await(30, TimeUnit.SECONDS), "a rejection while live must not abandon the submission")
        exec.shutdown()
    }

    "the watchdog survives a rogue executor failure and keeps serving" in {
        val pool = new StubPool(drops = 1, throwOnCall = 2)
        val exec = new HandoffRetryExecutor(pool, factory)
        val ran  = new CountDownLatch(1)
        exec.execute(() => ran.countDown())
        assert(ran.await(30, TimeUnit.SECONDS), "a non-fatal throw must not kill the watchdog")
        exec.shutdown()
    }

    "a burst of dropped submissions all run exactly once" in {
        val n    = 8
        val pool = new StubPool(drops = n)
        val exec = new HandoffRetryExecutor(pool, factory)
        val runs = new AtomicInteger(0)
        val ran  = new CountDownLatch(n)
        for (_ <- 1 to n) exec.execute { () => runs.incrementAndGet(); ran.countDown() }
        assert(ran.await(30, TimeUnit.SECONDS), s"only ${n - ran.getCount()} of $n submissions ran")
        eventually(assert(runs.get() == n, s"expected $n runs, got ${runs.get()}"))
        eventually(assert(exec.pendingSize == 0))
        exec.shutdown()
    }

    "a submission the live pool refuses does not reach the caller and still runs exactly once" in {
        val pool    = new StubPool(drops = 0, throwOnCall = 1)
        val exec    = new HandoffRetryExecutor(pool, factory)
        val runs    = new AtomicInteger(0)
        val ran     = new CountDownLatch(1)
        val failure =
            try { exec.execute { () => runs.incrementAndGet(); ran.countDown() }; None }
            catch { case e: Throwable => Some(e) }
        assert(failure.isEmpty, s"the refusal must not reach the caller, got $failure")
        assert(exec.submitFailures == 1)
        assert(ran.await(30, TimeUnit.SECONDS), "the refused submission was never re-submitted")
        eventually(assert(runs.get() == 1, s"expected exactly one run, got ${runs.get()}"))
        eventually(assert(exec.pendingSize == 0))
        exec.shutdown()
    }

    "a submission refused with an InterruptedException runs and keeps the caller's interrupt" in {
        val pool    = new StubPool(drops = 0, interruptOnCall = 1)
        val exec    = new HandoffRetryExecutor(pool, factory)
        val ran     = new CountDownLatch(1)
        val failure =
            try { exec.execute(() => ran.countDown()); None }
            catch { case e: InterruptedException => Some(e) }
        val interrupted = Thread.interrupted()
        assert(failure.isEmpty, s"the refusal must not reach the caller, got $failure")
        assert(interrupted, "the caller's interrupt must be re-armed")
        assert(exec.submitFailures == 1)
        assert(ran.await(30, TimeUnit.SECONDS), "the refused submission was never re-submitted")
        exec.shutdown()
    }

    "a shut-down pool's rejection reaches the caller and nothing is tracked" in {
        val pool = new StubPool(drops = 0)
        val exec = new HandoffRetryExecutor(pool, factory)
        val runs = new AtomicInteger(0)
        exec.shutdown()
        val failure =
            try { exec.execute(() => { val _ = runs.incrementAndGet() }); None }
            catch { case e: RejectedExecutionException => Some(e) }
        assert(failure.isDefined, "a shut-down pool's rejection is the caller's to handle")
        assert(exec.pendingSize == 0)
        assert(exec.submitFailures == 0)
        assert(runs.get() == 0)
    }
}
