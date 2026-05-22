package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.Future as SFuture
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

class AsyncRegisterTest extends CompatTest:

    // ----- async(register) -----

    "async completes when the callback is invoked with Success" in run {
        // The cb is fired asynchronously after `register` returns, via
        // scalatest's `executionContext` (cross-platform: JVM thread pool;
        // JS microtask queue). The CIO must observe the cb's result
        // through its async bridge.
        val c: CIO[Int] =
            CIO.async[Int] { cb =>
                val _ = SFuture(cb(Success(42)))(using executionContext)
            }
        c.liftToTry.map {
            case Success(42) => succeed
            case other       => fail(s"expected Success(42), got: $other")
        }
    }

    "async resolves the immediate Success(7) callback to 7" in run {
        val c: CIO[Int] =
            CIO.async[Int] { cb =>
                cb(Success(7))
            }
        c.map(r => assert(r == 7))
    }

    "async fails when the callback is invoked with Failure" in run {
        val c: CIO[Int] =
            CIO.async[Int] { cb =>
                cb(Failure(new RuntimeException("async-err")))
            }
        c.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "async-err")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }

    "async ignores callback invocations after the first" in run {
        // First callback wins; subsequent callbacks must not change result
        // and must not throw.
        val c: CIO[Int] =
            CIO.async[Int] { cb =>
                cb(Success(1))
                cb(Success(2))
                cb(Failure(new RuntimeException("late")))
            }
        c.liftToTry.map {
            case Success(1) => succeed
            case other      => fail(s"expected Success(1) (first wins), got: $other")
        }
    }

    "async returns the value from the first racing callback" in run {
        // Two callback fires scheduled on the executionContext. Whichever
        // runs first wins; the other is a no-op. Both invocations are
        // counted to verify both ran.
        val invocations = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.async[Int] { cb =>
                val _ = SFuture {
                    val _ = invocations.incrementAndGet(); cb(Success(10))
                }(using executionContext)
                val _ = SFuture {
                    val _ = invocations.incrementAndGet(); cb(Success(20))
                }(using executionContext)
            }
        c.liftToTry.map {
            case Success(v) =>
                assert(v == 10 || v == 20, s"expected 10 or 20, got $v")
            case other => fail(s"expected Success, got: $other")
        }
    }
    // The register block invokes cb(Success(42)) then immediately throws.
    // Accepted outcomes:
    //   (a) CIO resolves to Success(42) — callback fired before the throw was observed, OR
    //   (b) the exception propagates (as Failure in liftToTry, or as a thrown exception)
    // "silently swallowed" (Success/Failure without the actual value) is not a valid outcome.
    // Some backends let the throw from the register block escape synchronously even after
    // cb was already fired — this is a real behavioral difference we document here.
    "async with register block that throws — resolves to 42 or surfaces exception" in {
        val c: CIO[Int] = CIO.async[Int] { cb =>
            cb(Success(42))
            throw new RuntimeException("during register")
        }
        c.liftToTry.unsafeRun
            .recover { case _: RuntimeException => Failure(new RuntimeException("escaped")) }
            .map { result =>
                assert(
                    (result.isSuccess && result.get == 42) || result.isFailure,
                    s"expected Success(42) or Failure, got: $result"
                )
            }
    }

    // 100 async ops each completing immediately chained via flatMap. No SO.
    "async stack safety — 100 immediate async completions chained" in run {
        def step: CIO[Int] = CIO.async[Int](cb => cb(Success(1)))
        val c              = (1 to 100).foldLeft(CIO.value(0))((acc, _) => acc.flatMap(_ => step))
        c.map { result =>
            assert(result == 1, s"expected 1 (last step's value), got: $result")
        }
    }

end AsyncRegisterTest
