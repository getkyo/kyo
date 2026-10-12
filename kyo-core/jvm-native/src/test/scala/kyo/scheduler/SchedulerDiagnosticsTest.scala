package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.discard
import kyo.internal.Diagnostics

class SchedulerDiagnosticsTest extends kyo.test.Test[Any]:

    // SchedulerDiagnostics registers a single process-global entry named "kyo-scheduler" that is never closed (the scheduler is
    // process-lifetime). init() is idempotent, so calling it here is a no-op if an earlier fiber already installed it.

    "registers a kyo-scheduler dumper that renders live worker state" in {
        SchedulerDiagnostics.init()
        val dump = Diagnostics.dumpAll()
        assert(dump.contains("=== kyo-scheduler ==="), s"dump missing the kyo-scheduler section: $dump")
        assert(dump.contains("scheduler: currentWorkers="), s"dump missing the scheduler summary line: $dump")
        assert(dump.contains(" queued="), s"dump missing the queued counts: $dump")
        assert(dump.contains("busyFiberTraces:"), s"dump missing the busy-fiber-traces marker: $dump")
    }

    "the global scheduler is probed as one not-closed lane per allocated worker" in {
        SchedulerDiagnostics.init()
        val lanes = Diagnostics.probeAll().filter((name, _) => name.startsWith("kyo-scheduler"))
        val names = lanes.map(_._1)
        assert(names.contains("kyo-scheduler/worker-0"), s"no lane for the first worker: $names")
        assert(names.forall(_.matches("kyo-scheduler/worker-\\d+")), s"every scheduler probe must be a worker lane: $names")
        assert(names.distinct.size == names.size, s"a worker has more than one lane: $names")
        assert(lanes.forall((_, p) => !p.closed), s"a closed lane is never classified: $lanes")
    }

    "a task parked inside its own run, with nothing queued, strands no lane" in {
        withScheduler(droppedDispatches = 0) { scheduler =>
            val started = new CountDownLatch(1)
            val release = new CountDownLatch(1)
            fromPlainThread(scheduler.schedule(Task {
                started.countDown()
                release.await()
            }))
            try
                started.await()
                assert(scheduler.busyWorkers() == 1, "the parked task must hold a worker of the scheduler under probe")
                val before = SchedulerDiagnostics.lanes(scheduler)
                val after  = SchedulerDiagnostics.lanes(scheduler)
                assert(before.forall((_, p) => !p.pending), s"a parked executing task is not queued work: $before")
                assert(stranded(before, after).isEmpty, s"$before then $after")
            finally release.countDown()
            end try
        }
    }

    "a task queued on a worker whose dispatch never runs strands that worker's lane" in {
        withScheduler(droppedDispatches = 1) { scheduler =>
            fromPlainThread(scheduler.schedule(Task(())))
            val before = SchedulerDiagnostics.lanes(scheduler)
            val after  = SchedulerDiagnostics.lanes(scheduler)
            assert(stranded(before, after).size == 1, s"$before then $after")
        }
    }

    "a one-worker strand is reported while another worker keeps executing" in {
        // The shape of the global scheduler, where the admission regulator's probe task executes somewhere in nearly every settle window.
        // Here the progress is one task run to completion between the two samples, so the window cannot hold none.
        withScheduler(droppedDispatches = 1) { scheduler =>
            fromPlainThread(scheduler.schedule(Task(())))
            val before = SchedulerDiagnostics.lanes(scheduler)
            val ran    = new CountDownLatch(1)
            fromPlainThread(scheduler.schedule(Task(ran.countDown())))
            ran.await()
            val after = SchedulerDiagnostics.lanes(scheduler)
            assert(after.map(_._2.cycles).sum > before.map(_._2.cycles).sum, s"another worker must have executed: $before then $after")
            val found = stranded(before, after)
            assert(found.size == 1, s"exactly the undispatched worker's lane is stranded: $found in $before then $after")
            assert(found.head.startsWith("worker-"), s"the finding must name the worker: $found")
        }
    }

    /** The kyo-test runner's stranded-op rule, applied lane by lane: present in both samples, not closed, pending at both
      * ends, cycles unchanged.
      */
    private def stranded(before: List[(String, Diagnostics.Probe)], after: List[(String, Diagnostics.Probe)]): List[String] =
        val earlier = before.toMap
        after.collect {
            case (name, a) if earlier.get(name).exists(b => !a.closed && b.pending && a.pending && b.cycles == a.cycles) => name
        }
    end stranded

    /** Placement prefers the calling thread's worker, and a leaf body may run on a worker of the global scheduler, so the leaves submit
      * from a thread no worker has mounted.
      */
    private def fromPlainThread(f: => Unit): Unit =
        val t = new Thread(() => f)
        t.start()
        t.join()
    end fromPlainThread

    private val daemon: ThreadFactory = r =>
        val t = new Thread(r)
        t.setDaemon(true)
        t

    /** A dedicated two-worker scheduler whose executor drops the first `droppedDispatches` worker dispatches, so the probe reads only the
      * work the leaf placed. The regulators' periodic probes would each enqueue a task and make a sample racy, and the concurrency
      * regulator could shrink the window and drain a stranded worker, so their fixed-delay timers are pushed past the leaf's lifetime; the
      * one-shot submits (the worker cycle and the blocking monitor) still run. A stride of two makes every placement see both workers.
      */
    private def withScheduler(droppedDispatches: Int)(f: Scheduler => Unit): Unit =
        val pool    = Executors.newCachedThreadPool(daemon)
        val clock   = Executors.newSingleThreadExecutor(daemon)
        val dropped = new AtomicInteger
        val timer   = new ScheduledThreadPoolExecutor(2, daemon):
            override def scheduleWithFixedDelay(
                command: Runnable,
                initialDelay: Long,
                delay: Long,
                unit: TimeUnit
            ): ScheduledFuture[?] =
                super.scheduleWithFixedDelay(command, 1, 1, TimeUnit.DAYS)
        val config =
            Scheduler.Config.default.copy(cores = 2, coreWorkers = 2, minWorkers = 2, maxWorkers = 2, scheduleStride = 2, stealStride = 2)
        val scheduler = new Scheduler(
            r => if dropped.getAndIncrement() >= droppedDispatches then pool.execute(r),
            clock,
            timer,
            config
        )
        try f(scheduler)
        finally
            scheduler.shutdown()
            discard(timer.shutdownNow())
            discard(clock.shutdownNow())
            discard(pool.shutdownNow())
        end try
    end withScheduler
end SchedulerDiagnosticsTest
