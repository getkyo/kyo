package kyo.test.runner.internal

import kyo.Abort
import kyo.Async
import kyo.AtomicBoolean
import kyo.Channel
import kyo.Closed
import kyo.Fiber
import kyo.Frame
import kyo.Kyo
import kyo.Promise
import kyo.Result
import kyo.Sync
import kyo.kernel.<

/** Bounded pool that caps the count of test leaves executing concurrently.
  *
  * Each instance drains a single bounded `Channel[Unit < Async]` with exactly `workers` detached worker fibers, so
  * total concurrent leaf execution through that instance is capped at `workers` regardless of how many suites push
  * into it. The process-global instance, reached via `LeafPool.submit`, is THE process-global pool: sbt runs
  * test files in parallel on the JVM, and the old per-suite `Meter.initSemaphore(K)` gave a total concurrent
  * leaf-execution degree of (parallel suites) x K, flooding kyo's shared scheduler. Routing every suite through the
  * one global instance bounds that degree at `globalK` across ALL suites in the process.
  *
  * `globalK` restores the INTENDED single-suite concurrency as the whole process's bound: a single suite alone
  * already ran its leaves at `Async.defaultConcurrency` and passed, so capping the global total at one suite's
  * worth is the proven-safe value. Native uses `globalK = 1` (single-threaded; concurrent unwinding crashes
  * libunwind). `capacity = 1024` keeps the workers fed without starving; `put` backpressures past it
  * (deadlock-free, workers drain independently).
  *
  * Workers are detached daemons forked once per instance via a CAS-guarded `ensureStarted`. They outlive any single
  * suite; kyo scheduler threads are daemon, so parked workers never block process exit, and no shutdown mechanism is
  * needed across jvm/js/native. An instance's channel is never closed, so the `Closed` branch is unreachable
  * (handled defensively). Each submitted `Work` completes its own promise, wrapped in `Sync.ensure` so a hypothetical
  * `runLeaf` panic can never hang a suite's ordered await.
  */
final private[runner] class LeafPool(workers: Int, capacity: Int):

    // Unsafe: per-instance work channel built at instance-construction time. This is the tier-3 sanctioned case
    // (initialization of a shared, long-lived value via an unsafe op, mirroring Clock.live at Clock.scala:179-182).
    // The danger import is scoped to this single val; the pool holds the SAFE Channel[Unit < Async], never the
    // Unsafe form.
    private val channel: Channel[Unit < Async] =
        import kyo.AllowUnsafe.embrace.danger
        given kyo.Frame = kyo.Frame.internal
        Channel.Unsafe.init[Unit < Async](capacity).safe
    end channel

    // Unsafe: per-instance one-shot start guard built at instance-construction time (same tier-3 rationale as the
    // channel above). The pool holds the SAFE AtomicBoolean; all use goes through the safe compareAndSet inside
    // ensureStarted.
    private val started: AtomicBoolean =
        import kyo.AllowUnsafe.embrace.danger
        AtomicBoolean.Unsafe.init(false).safe

    /** One-shot worker-spawn guard. CAS `started` false->true; on success fork `workers` detached workers; on
      * failure (already started) do nothing. Called at the top of every `submit`, so workers spawn exactly once
      * across concurrent first submits from parallel suites (the CAS, not the construction, provides the
      * exactly-once property).
      */
    private def ensureStarted(using Frame): Unit < Async =
        started.compareAndSet(false, true).map { won =>
            if won then
                Kyo.foreachDiscard(0 until workers)(_ => Fiber.initUnscoped(worker).unit)
            else ()
        }

    /** Worker loop: take a Work off the channel, run it, recurse. `take` suspends when the channel is empty, so the
      * `workers` worker fibers park (no busy-loop) until a suite pushes. The recursion is tail-position via kyo's
      * trampolined `<` (no JVM stack growth).
      */
    private def worker(using Frame): Unit < Async =
        Abort.run[Closed](channel.take).map {
            case Result.Success(work)      => work.andThen(worker)
            case Result.Failure(_: Closed) =>
                // Unreachable: an instance's channel is never closed. Handle and exit if it ever fires (defensive;
                // no recurse, the worker terminates).
                ()
            case panic: Result.Panic =>
                // A take panic is not expected; log and exit this worker (the other workers-1 workers keep
                // draining; a single dead worker degrades throughput, never correctness, since work is
                // re-pushed by suites).
                java.lang.System.err.println(s"[kyo-test] leaf-pool worker panic: ${panic.exception}")
                ()
        }

    /** Enqueue one leaf computation; return a promise for its result. The sole entry point: callers push leaves
      * and await their promises; the pool bounds the concurrent-run count at `workers`. The Work body completes the
      * promise on success and, via the `Sync.ensure` guard, on the impossible panic path, so the caller's
      * ordered await never hangs.
      */
    def submit[A](comp: A < Async)(using Frame): Promise[A, Any] < Async =
        ensureStarted.andThen {
            Promise.init[A, Any].map { promise =>
                val work: Unit < Async =
                    // Belt-and-suspenders: complete the promise with a panic if the body ever fails to complete
                    // it (runLeaf is total by contract, so this never fires). Guarantees the suite's ordered
                    // await never hangs.
                    Sync.ensure {
                        promise.completeDiscard(Result.panic(LeafPool.LeafPoolPanic))
                    } {
                        comp.map(a => promise.completeDiscard(Result.succeed(a)))
                    }
                channel.put(work).handle(Abort.run[Closed]).map { _ =>
                    // put never fails: the channel is never closed, so the Closed abort is unreachable; discard
                    // it. put DOES backpressure past capacity (suspends), the intended flow control.
                    promise
                }
            }
        }

end LeafPool

/** Companion of [[LeafPool]]. Holds the process-global pool instance and exposes the global `globalK` bound. */
object LeafPool:

    /** Sentinel completed into a leaf's result promise only on the impossible path where the Work body fails to
      * complete it (runLeaf is total by contract, so this never fires in practice).
      */
    private case object LeafPoolPanic extends RuntimeException("kyo-test leaf-pool: work body failed to complete its promise")

    /** The process-global bound on concurrent leaf execution. Same formula as the old per-suite auto-K, hoisted
      * to the whole process. Native = 1 (single-threaded; concurrent unwinding crashes libunwind).
      */
    val globalK: Int =
        if kyo.internal.Platform.isNative || LeakDebug.enabled then 1
        else math.max(1, Async.defaultConcurrency)

    /** THE process-global pool: `globalK` workers, capacity 1024. Every suite routes its leaves through this one
      * instance, so total concurrent leaf execution is bounded at `globalK` regardless of the running-suite count.
      */
    private val global = new LeafPool(globalK, 1024)

    /** Enqueue one leaf computation into the process-global pool; return a promise for its result. Delegates to the
      * global instance's `submit`.
      */
    def submit[A](comp: A < Async)(using Frame): Promise[A, Any] < Async =
        global.submit(comp)

end LeafPool
