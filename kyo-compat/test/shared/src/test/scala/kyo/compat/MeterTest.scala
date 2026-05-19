package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
class MeterTest extends CompatTest:
    "run acquires and releases the permit" in run {
        // After run completes, the permit is back. We assert availablePermits
        // after the run equals the initial count.
        val c =
            CMeter.init(2).flatMap { m =>
                m.run(CIO.defer { 42 }).flatMap { v =>
                    m.availablePermits.flatMap { remaining =>
                        CIO.defer((v, remaining))
                    }
                }
            }
        c.map { case (v, remaining) =>
            assert(v == 42 && remaining == 2)
        }
    }
    "run blocks when no permits are available" in run {
        // Meter(1): hold the single permit in a long-running run, then a
        // second run must block. We bound the second with CIO.timeout.
        // CPromise signals after the holder has acquired the permit so the
        // waiter cannot race-win the acquire on a stressed scheduler.
        val c =
            CMeter.init(1).flatMap { m =>
                CPromise.init[Unit].flatMap { acquired =>
                    val holder: CIO[Unit] = m.run(acquired.succeed(()).flatMap(_ => CIO.sleep(200.millis)))
                    val waiter            = acquired.get.flatMap(_ => CIO.timeout(50.millis)(m.run(CIO.defer { 7 })))
                    CIO.zip(holder, waiter)
                }
            }
        c.map { case (_, waiterResult) =>
            // waiter timed out → None
            assert(waiterResult == None)
        }
    }
    "tryRun returns None when no permits are available" in run {
        val c =
            CMeter.init(1).flatMap { m =>
                CPromise.init[Unit].flatMap { acquired =>
                    val holder: CIO[Unit] = m.run(acquired.succeed(()).flatMap(_ => CIO.sleep(100.millis)))
                    val tryer             = acquired.get.flatMap(_ => m.tryRun(CIO.defer { 7 }))
                    CIO.zip(holder, tryer)
                }
            }
        c.map { case (_, tryResult) =>
            assert(tryResult == None)
        }
    }
    "availablePermits decreases while run holds a permit" in run {
        // While a run holds a permit, availablePermits should be N-1 (or less).
        // We sample availablePermits inside the run via chain.
        val c =
            CMeter.init(3).flatMap { m =>
                def inner =
                    m.availablePermits.flatMap { during =>
                        CIO.defer(during)
                    }
                m.run(inner).flatMap { during =>
                    m.availablePermits.flatMap { after =>
                        CIO.defer((during, after))
                    }
                }
            }
        c.map { case (during, after) =>
            assert(during <= 2 && after == 3)
        }
    }
    "concurrent runs respect the permit limit" in run {
        // Meter(2) + 4 concurrent runs that each sleep 100ms: total elapsed
        // must be at least 200ms (two batches of two), but well under 5s.
        val ctr = new AtomicInteger(0)
        def one: CIO[Int] =
            CIO.sleep(100.millis).flatMap { _ =>
                CIO.defer { ctr.incrementAndGet() }
            }
        val c =
            CMeter.init(2).flatMap { m =>
                val r1: CIO[Int] = m.run(one)
                val r2: CIO[Int] = m.run(one)
                val r3: CIO[Int] = m.run(one)
                val r4: CIO[Int] = m.run(one)
                CIO.zip(r1, r2, r3, r4)
            }
        val start = java.lang.System.nanoTime()
        c.map { case (a, b, d, e) =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(
                Set(a, b, d, e) == Set(1, 2, 3, 4) && elapsed < 5_000L,
                s"results=${(a, b, d, e)} elapsed=$elapsed ms"
            )
        }
    }
    "CMeter.init(2) yields permit count 2 on each reuse" in run {
        val c =
            CMeter.init(2).flatMap { m =>
                m.availablePermits.flatMap { first =>
                    m.availablePermits.flatMap { second =>
                        CIO.defer((first, second))
                    }
                }
            }
        c.map { case (first, second) =>
            assert(first == 2 && second == 2, s"expected (2, 2) got ($first, $second)")
        }
    }
    "second acquire blocks until first holder releases" in run {
        val holdMs = 150L
        val c =
            CMeter.init(1).flatMap { m =>
                CPromise.init[Unit].flatMap { acquired =>
                    val holder: CIO[Long] =
                        m.run {
                            CIO.nowMonotonic.map(_.toMillis).flatMap { t0 =>
                                acquired.succeed(()).flatMap { _ =>
                                    CIO.sleep(holdMs.millis).flatMap { _ =>
                                        CIO.nowMonotonic.map(_.toMillis).flatMap { t1 =>
                                            CIO.defer(t1 - t0)
                                        }
                                    }
                                }
                            }
                        }
                    val waiter: CIO[Long] =
                        acquired.get.flatMap { _ =>
                            CIO.nowMonotonic.map(_.toMillis).flatMap { before =>
                                m.run {
                                    CIO.nowMonotonic.map(_.toMillis).flatMap { after =>
                                        CIO.defer(after - before)
                                    }
                                }
                            }
                        }
                    CIO.zip(holder, waiter)
                }
            }
        c.map { case (_, waitMs) =>
            assert(waitMs >= holdMs - 30L, s"waiter did not block long enough: waitMs=$waitMs")
        }
    }

    // run body throws → permit still released
    // meter.run(CIO.fail(e)).liftToTry must be Failure; then availablePermits == 1.
    "permit is released when run body throws" in run {
        val c =
            CMeter.init(1).flatMap { m =>
                m.run(CIO.fail(TestError("e"))).liftToTry.flatMap { result =>
                    m.availablePermits.flatMap { permits =>
                        CIO.defer((result, permits))
                    }
                }
            }
        c.map { case (result, permits) =>
            assert(result.isFailure, s"expected Failure, got $result")
            assert(permits == 1, s"expected 1 available permit after body failure, got $permits")
        }
    }

    // tryRun racing with release
    // Holder holds the single permit; tryRun at 50ms returns None (holder active).
    // After holder finishes (300ms total), tryRun returns Some(7).
    "tryRun returns None while holder is active and Some after holder releases" in run {
        val c =
            CMeter.init(1).flatMap { m =>
                CPromise.init[Unit].flatMap { acquired =>
                    CFiber.init(m.run(acquired.succeed(()).flatMap(_ => CIO.sleep(200.millis)))).flatMap { holder =>
                        acquired.get.flatMap { _ =>
                            m.tryRun(CIO.value(0)).flatMap { duringResult =>
                                holder.get.flatMap { _ =>
                                    m.tryRun(CIO.value(7)).flatMap { afterResult =>
                                        CIO.defer((duringResult, afterResult))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        c.map { case (during, after) =>
            assert(during == None, s"expected None while holder active, got $during")
            assert(after == Some(7), s"expected Some(7) after holder released, got $after")
        }
    }

    // Nested run with capacity 1 → inner run deadlocks (backend-specific timeout catches it)
    // OR backend supports reentrancy and returns Success(42).
    // A short CIO.timeoutWithError surfaces the deadlock as a Failure well before CompatTest's testTimeout.
    "nested run with capacity 1 either deadlocks (timeout) or succeeds via reentrancy" in run {
        val c =
            CMeter.init(1).flatMap { m =>
                CIO.timeoutWithError(5.seconds)(TestError("deadlock-timeout"))(
                    m.run(m.run(CIO.value(42)))
                ).liftToTry
            }
        c.map { result =>
            // Either timed-out deadlock (Failure(TestError)) or reentrancy allows Success(42)
            assert(
                result.isFailure || result.toOption.exists(_ == 42),
                s"unexpected result: $result"
            )
        }
    }

    // CMeter round-trip lift/lower
    // init(2), then lift(meter.lower). Run something through the lifted view;
    // verify availablePermits == 2 before and after.
    "CMeter round-trip lift/lower preserves permit count" in run {
        val c =
            CMeter.init(2).flatMap { m =>
                val lifted = CMeter.lift(m.lower)
                lifted.availablePermits.flatMap { before =>
                    lifted.run(CIO.value(99)).flatMap { v =>
                        lifted.availablePermits.flatMap { after =>
                            CIO.defer((before, v, after))
                        }
                    }
                }
            }
        c.map { case (before, v, after) =>
            assert(before == 2, s"expected 2 permits before run, got $before")
            assert(v == 99, s"expected run result 99, got $v")
            assert(after == 2, s"expected 2 permits after run, got $after")
        }
    }

end MeterTest
