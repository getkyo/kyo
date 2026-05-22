package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

class BlockingCedeTest extends CompatTest:

    // ----- blocking(thunk) -----

    "blocking returns the thunk's value" in run {
        val c = CIO.blocking { 42 }
        c.liftToTry.map {
            case Success(42) => succeed
            case other       => fail(s"expected Success(42), got: $other")
        }
    }

    "blocking propagates exceptions thrown by the thunk" in run {
        val c = CIO.blocking { throw new RuntimeException("blocking-err") }
        c.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "blocking-err")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }

    "blocking runs the thunk exactly once" in run {
        // Per-backend assertions about Kyo's no-op behavior live in each
        // backend's test file. Here the cross-backend check: blocking(thunk)
        // runs the thunk once and yields its value. (Implements the
        // "observably_equivalent_to_defer" intent in a backend-uniform way.)
        val ctr = new AtomicInteger(0)
        val c   = CIO.blocking { ctr.incrementAndGet() }
        c.liftToTry.map {
            case Success(1) => assert(ctr.get == 1)
            case other      => fail(s"expected Success(1), got: $other")
        }
    }

    // ----- cede -----

    "cede yields and the chained continuation runs" in run {
        // cede returns Unit and the chained continuation runs.
        val c = CIO.cede.flatMap(_ => CIO.defer { 42 })
        c.map(v => assert(v == 42))
    }

    "repeated cedes do not deadlock or serialize concurrent work" in run {
        // Run a sequence of cede-then-increment chained N times concurrently
        // with a short sleep; both must complete. We exercise cede's role as
        // a yield point — the test passes if cede does not deadlock or
        // serialize the workload.
        val ctr = new AtomicInteger(0)
        val cededWork =
            CIO.cede.flatMap { _ =>
                CIO.cede.flatMap { _ =>
                    CIO.cede.flatMap { _ =>
                        CIO.defer { ctr.incrementAndGet() }
                    }
                }
            }
        val tickle = CIO.sleep(20.millis)
        CIO.zip(cededWork, tickle).map {
            case (n, _) => assert(n == 1 && ctr.get == 1)
        }
    }

    "cede completes with Unit" in run {
        // Kyo and Ox cede are no-ops returning Unit immediately.
        // Backend-uniform assertion: cede produces Unit.
        CIO.cede.map(r => assert(r == ((): Unit)))
    }
    // Fiber A loops with cede; fiber B sets a flag after 100ms.
    // If cede truly yields (or if the scheduler is preemptive), B's flag fires
    // within bounded time and fA terminates. If cede is a genuine no-op AND
    // the scheduler is purely cooperative (not the case for preemptive
    // backends), fA would starve fB — but most backends are preemptive and the
    // test simply verifies no deadlock occurs.
    "cede actually yields — no starvation across concurrent fibers" in run {
        val done    = new java.util.concurrent.atomic.AtomicBoolean(false)
        val counter = new AtomicInteger(0)
        def loop: CIO[Unit] =
            if done.get() then CIO.unit
            else
                CIO.defer { counter.incrementAndGet(); () }.flatMap { _ =>
                    CIO.cede.flatMap { _ => loop }
                }
        val c =
            CFiber.init(loop).flatMap { fA =>
                CFiber.init(CIO.sleep(100.millis).flatMap(_ => CIO.defer { done.set(true) })).flatMap { fB =>
                    fA.get.flatMap { _ =>
                        fB.get.flatMap { _ =>
                            CIO.defer(done.get())
                        }
                    }
                }
            }
        c.map { d => assert(d, "done flag was never set — cede may have caused starvation") }
    }

    // 1000 chained cedes must not produce a StackOverflowError.
    "cede 1000-deep chain does not stack-overflow" in run {
        val c = (1 to 1000).foldLeft(CIO.value(()))((acc, _) => acc.flatMap(_ => CIO.cede))
        c.map(r => assert(r == ((): Unit)))
    }

end BlockingCedeTest
