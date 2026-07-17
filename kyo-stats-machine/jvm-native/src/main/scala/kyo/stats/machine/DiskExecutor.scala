package kyo.stats.machine

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kyo.*
import scala.util.control.NonFatal

/** Runs the whole-disk read on a dedicated daemon thread, never on a `kyo.Async` scheduler worker.
  *
  * A `statvfs`/`statfs`/`GetDiskFreeSpaceEx` against a dead mount blocks the thread that runs it with no
  * suspension point. Running that read on THIS dedicated thread rather than on a scheduler worker means a
  * hung mount can never occupy a worker the sampler's fast fiber (or its teardown) needs, so the sampler
  * does not depend on the scheduler's blocking compensation to keep making progress. `MachineSampler`'s
  * single-in-flight guard admits at most one read at a time, so this executor never queues concurrent reads,
  * and a genuinely hung mount leaks exactly this one thread for the process lifetime (or until `close`).
  *
  * JS/Wasm are single-threaded and have no OS thread to off-load to; the sibling implementation under
  * `js-wasm/` runs the read inline (the scheduler-worker hazard does not arise on the JS event loop).
  */
final private[machine] class DiskExecutor:

    private val factory: ThreadFactory = (r: Runnable) =>
        val t = new Thread(r, "kyo-stats-machine-disk")
        t.setDaemon(true)
        t
    private val exec = Executors.newSingleThreadExecutor(factory)

    /** Submits `body` to the dedicated thread and returns an effect that completes when `body` returns. The
      * returned effect holds no scheduler worker while `body` runs, so wrapping it in `Async.timeout` bounds
      * only the awaiting fiber, never the read itself, and a stuck read parks this thread rather than a worker.
      */
    def run(body: AllowUnsafe ?=> Unit)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val promise = Promise.Unsafe.init[Unit, Any]()
            exec.execute { () =>
                val result: Result[Nothing, Unit < Any] =
                    // Unsafe: the dedicated disk thread is the bridging boundary that runs the blocking read
                    // (body: readDisks + diskReadDone) off the scheduler pool; IOPromise completion is thread-safe.
                    try
                        body
                        Result.succeed(())
                    catch
                        case ex if NonFatal(ex) => Result.panic(ex)
                promise.completeDiscard(result)
            }
            promise.safe.get
        }

    /** Stops the dedicated thread. An idle thread stops immediately; a read still parked in a hung mount is
      * uninterruptible, so this abandons that one daemon thread and it dies at process exit.
      */
    def close(): Unit = discard(exec.shutdownNow())

end DiskExecutor
