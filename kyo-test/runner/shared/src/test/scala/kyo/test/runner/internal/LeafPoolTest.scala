package kyo.test.runner.internal

import kyo.*
import kyo.internal.Platform
import kyo.test.runner.TestExecutionContext
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Behavioral tests for [[LeafPool]].
  *
  * Raw ScalaTest (`AsyncFreeSpec with NonImplicitAssertions`): each test body runs OFF the process-global pool and
  * discharges its computation to a `Future` via the runner's single sbt-edge conversion. Running off-pool is
  * required because each test drives a DEDICATED `new LeafPool(k, cap)` instance (never the process-global pool,
  * never `LeafPool.submit`) and MEASURES that instance's concurrency: a `kyo.test.Test` body would itself occupy a
  * global-pool worker, and awaiting the dedicated pool's submits from there could re-enter the global pool. Off-pool,
  * the awaiting body holds no worker, the dedicated instance owns its own `k` workers, and there is no contention or
  * re-entrancy. The concurrency window is established by deterministic latches (a started-signal `Channel` plus a
  * release `Promise`), not by timing.
  *
  * Pins: at most `k` Work bodies execute concurrently (peak <= k), `k` reach concurrency simultaneously when the
  * channel can hold them (peak == k under the barrier), and every submitted item completes (exact multiset of
  * results) even when the submitted count exceeds the worker count (the channel queues the overflow and the workers
  * drain it).
  */
class LeafPoolTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    /** Discharge a pool computation to a `Future`, off the global pool. The single sbt-edge conversion the runner
      * itself uses: handle `Scope`, fork the computation onto a fresh (non-pool) fiber, and convert to a `Future`.
      */
    private def discharge[A](comp: A < (Async & Abort[Throwable] & Scope))(using Frame): Future[A] =
        val asFuture: Future[A] < Sync =
            Scope.run(comp).handle(Fiber.initUnscoped).map(_.toFuture)
        // Unsafe: the test-edge boundary, identical to TestRunner.runToFuture's sbt-edge bridge. Discharging the
        // terminal Sync to the produced Future is the single sanctioned conversion; everything upstream is pure Kyo.
        import kyo.AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(asFuture)
    end discharge

    // Native is single-threaded (concurrent fiber unwinding crashes libunwind), so a dedicated pool there must run
    // exactly one worker. On JVM/JS, 4 workers give a real concurrency window.
    private val k: Int = if Platform.isNative then 1 else 4

    "pool runs exactly k work bodies concurrently" in {
        // One dedicated instance with k workers; capacity 64 comfortably holds the k barrier items.
        val pool = new LeafPool(k, 64)
        val computation =
            for
                inFlight <- AtomicInt.init(0)
                peak     <- AtomicInt.init(0)
                // Started-signal channel: each item announces it is parked at the barrier; capacity k holds all signals.
                started <- Channel.initUnscoped[Unit](k)
                // Release latch: the body completes it once all k items are parked, freeing them simultaneously.
                release <- Promise.init[Unit, Any]
                // Submit k items. Each item raises inFlight, records the peak, signals started, then parks on the latch.
                // While all k are parked, inFlight == k, so the recorded peak observes exactly k.
                promises <- Kyo.foreach(0 until k) { i =>
                    val work: Unit < Async =
                        Abort.run[Closed] {
                            for
                                now <- inFlight.incrementAndGet
                                _   <- peak.updateAndGet(cur => math.max(cur, now))
                                _   <- started.put(())
                                _   <- release.get
                                _   <- inFlight.decrementAndGet
                            yield i
                        }.unit
                    pool.submit(work)
                }
                // Barrier: wait until all k items are parked (inFlight == k), then release them together.
                _ <- Kyo.foreachDiscard(0 until k)(_ => started.take.handle(Abort.run[Closed]))
                _ <- release.completeDiscard(Result.succeed(()))
                // Await every promise in submission order; this terminates only if every item completed.
                _            <- Kyo.foreach(promises)(_.get)
                observedPeak <- peak.get
            yield assert(
                observedPeak == k,
                s"expected peak == k=$k, but observed $observedPeak"
            )
            end for
        end computation
        discharge(computation)
    }

    "pool queues and completes every item past capacity" in {
        // Same dedicated pool shape (k workers). Submit 3k items with no barrier: the channel queues the overflow
        // past the k workers and every item drains. inFlight is structurally bounded by the k workers, so the peak
        // never exceeds k.
        val pool = new LeafPool(k, 64)
        val n    = k * 3
        val computation =
            for
                inFlight <- AtomicInt.init(0)
                peak     <- AtomicInt.init(0)
                // Result sink: capacity n so no item ever blocks recording its index.
                sink <- Channel.initUnscoped[Int](n)
                promises <- Kyo.foreach(0 until n) { i =>
                    val work: Unit < Async =
                        Abort.run[Closed] {
                            for
                                now <- inFlight.incrementAndGet
                                _   <- peak.updateAndGet(cur => math.max(cur, now))
                                _   <- inFlight.decrementAndGet
                                _   <- sink.put(i)
                            yield ()
                        }.unit
                    pool.submit(work)
                }
                _            <- Kyo.foreach(promises)(_.get)
                collected    <- sink.drain.handle(Abort.run[Closed])
                observedPeak <- peak.get
            yield
                val results = collected.getOrElse(Chunk.empty).sorted
                assert(
                    results == Chunk.from(0 until n),
                    s"expected every item 0..${n - 1} to complete, got $results"
                )
                assert(
                    observedPeak <= k,
                    s"expected peak <= k=$k throughout, but observed $observedPeak"
                )
            end for
        end computation
        discharge(computation)
    }

end LeafPoolTest
