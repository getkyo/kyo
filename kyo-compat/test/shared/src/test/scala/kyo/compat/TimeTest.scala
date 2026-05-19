package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
class TimeTest extends CompatTest:
    "sleep delays without crashing" in run {
        // Trigger a sleep and verify it returns Unit. Wall-clock-precision
        // assertions (`elapsed >= 30ms`) live in each backend's test file.
        CIO.sleep(20.millis).map(r => assert(r == ((): Unit)))
    }
    "now returns without error" in run {
        // Type is java.time.Instant on all backends.
        CIO.now.unit.map(_ => succeed)
    }
    "nowMonotonic is non-decreasing across two reads" in run {
        // The Duration type returned by `nowMonotonic` is FiniteDuration on all backends.
        // We read elapsed millis via `CIO.nowMonotonic.map(_.toMillis)` and chain
        // through `flatMap` so both reads happen under the same scope/runtime.
        val c =
            CIO.nowMonotonic.map(_.toMillis).flatMap { a =>
                CIO.sleep(2.millis).flatMap { _ =>
                    CIO.nowMonotonic.map(_.toMillis).flatMap { b =>
                        CIO.defer((a, b))
                    }
                }
            }
        c.map { case (a, b) => assert(b >= a, s"expected $b >= $a") }
    }
    "timeout returns Some on completion within d" in run {
        val c = CIO.timeout(500.millis)(CIO.defer { 42 })
        c.map(r => assert(r == Some(42)))
    }
    "timeout returns None on expiry" in run {
        // Use never as the inner so we deterministically hit the timeout.
        val c = CIO.timeout(50.millis)(CIO.never)
        c.map(r => assert(r == None))
    }
    "timeoutWithError fails with custom error on expiry" in run {
        val c: CIO[Nothing] = CIO.timeoutWithError(50.millis)(TestError("expired"))(CIO.never)
        c.liftToTry.map {
            case Failure(e: TestError) if e.msg == "expired" => succeed
            case other                                       => fail(s"expected Failure(TestError(\"expired\")), got: $other")
        }
    }
    "delay waits then runs" in run {
        val c = CIO.delay(20.millis)(CIO.defer { 42 })
        c.map(r => assert(r == 42))
    }
    "now returns Instant close to system clock" in run {
        // CIO.now returns java.time.Instant; check it's within 5s of system clock.
        val sys = java.lang.System.currentTimeMillis()
        val c =
            CIO.now.flatMap { now =>
                CIO.defer {
                    val deltaMs = math.abs(now.toEpochMilli - sys)
                    deltaMs < 5_000L
                }
            }
        c.map(r => assert(r, "CIO.now was more than 5s from system clock"))
    }
    "sleep delays at least the requested duration (wall-clock)" in run {
        val start = java.lang.System.nanoTime()
        CIO.sleep(50.millis).map { _ =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(elapsed >= 30L && elapsed < 5_000L, s"elapsed=$elapsed ms")
        }
    }
    "delay waits at least the requested duration (wall-clock)" in run {
        val start = java.lang.System.nanoTime()
        CIO.delay(50.millis)(CIO.defer { 42 }).map { out =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(out == 42 && elapsed >= 30L && elapsed < 5_000L, s"out=$out elapsed=$elapsed ms")
        }
    }
    "FiniteDuration: 500L.millis materializes as FiniteDuration with correct millis" in run {
        // actually suspends for the expected duration, measured via CIO.nowMonotonic.
        val d: FiniteDuration = 500L.millis
        assert(d.toMillis == 500L, s"expected 500ms, got ${d.toMillis}ms")
        val c =
            CIO.nowMonotonic.map(_.toMillis).flatMap { before =>
                CIO.sleep(500L.millis).flatMap { _ =>
                    CIO.nowMonotonic.map(_.toMillis).flatMap { after =>
                        CIO.defer(after - before)
                    }
                }
            }
        c.map { elapsedMs =>
            assert(elapsedMs >= 400L && elapsedMs < 30_000L, s"elapsed=$elapsedMs ms (expected >= 400ms)")
        }
    }
    "FiniteDuration: (1.second + 250.millis).toMillis == 1250" in run {
        val d: FiniteDuration = 1.second + 250.millis
        val ms: Long          = d.toMillis
        CIO.value(ms).map { result =>
            assert(result == 1250L, s"expected 1250ms, got ${result}ms")
        }
    }
    "java.time.Instant: CIO.now toEpochMilli is within 5s of System.currentTimeMillis" in run {
        val sys = java.lang.System.currentTimeMillis()
        CIO.now.map { instant =>
            val deltaMs = math.abs(instant.toEpochMilli - sys)
            assert(deltaMs < 5_000L, s"deltaMs=$deltaMs (expected < 5000ms)")
        }
    }
    "FiniteDuration: CIO.timeout(50.millis)(CIO.never) resolves to None" in run {
        val c = CIO.timeout(50.millis)(CIO.never)
        c.map { r =>
            assert(r == None, s"expected None, got $r")
        }
    }
    // ----- FiniteDuration extended API tests -----
    "FiniteDuration: subtraction (1.second + 250.millis - 100.millis).toMillis == 1150" in run {
        val d: FiniteDuration = 1.second + 250.millis - 100.millis
        CIO.value(d.toMillis).map { result =>
            assert(result == 1150L, s"expected 1150ms, got ${result}ms")
        }
    }
    "FiniteDuration: multiply by Long (2.seconds * 3L).toSeconds == 6" in run {
        val d: FiniteDuration = 2.seconds * 3L
        CIO.value(d.toSeconds).map { result =>
            assert(result == 6L, s"expected 6s, got ${result}s")
        }
    }
    "FiniteDuration: divide by Long (2.seconds / 2L).toSeconds == 1" in run {
        val d: FiniteDuration = 2.seconds / 2L
        CIO.value(d.toSeconds).map { result =>
            assert(result == 1L, s"expected 1s, got ${result}s")
        }
    }
    "FiniteDuration: multiply by Long (3.seconds * 2L).toMillis == 6000" in run {
        val d: FiniteDuration = 3.seconds * 2L
        CIO.value(d.toMillis).map { result =>
            assert(result == 6000L, s"expected 6000ms, got ${result}ms")
        }
    }
    "FiniteDuration: isZero — Duration.Zero is zero, 1.second is not" in run {
        val zero: FiniteDuration = Duration.Zero
        CIO.value((zero.length == 0L, 1.second.length == 0L)).map { case (zeroIsZero, secondIsZero) =>
            assert(zeroIsZero, "Duration.Zero.length == 0L should be true")
            assert(!secondIsZero, "1.second.length == 0L should be false")
        }
    }
    "FiniteDuration: ordering — 1.second < 2.seconds" in run {
        val a: FiniteDuration = 1.second
        val b: FiniteDuration = 2.seconds
        CIO.value(a < b).map { result =>
            assert(result, "1.second < 2.seconds should be true")
        }
    }
    "FiniteDuration: min and max" in run {
        val a: FiniteDuration = 1.second
        val b: FiniteDuration = 2.seconds
        CIO.value((a.min(b).toMillis, a.max(b).toMillis)).map { case (minMs, maxMs) =>
            assert(minMs == 1000L, s"min(1s, 2s) should be 1000ms, got $minMs")
            assert(maxMs == 2000L, s"max(1s, 2s) should be 2000ms, got $maxMs")
        }
    }
    "FiniteDuration: compareTo — 1.second.compareTo(2.seconds) < 0" in run {
        val a: FiniteDuration = 1.second
        val b: FiniteDuration = 2.seconds
        CIO.value(a.compareTo(b)).map { result =>
            assert(result < 0, s"1.second.compareTo(2.seconds) should be < 0, got $result")
        }
    }
    "FiniteDuration: Duration.Zero constant has zero length" in run {
        val zero: FiniteDuration = Duration.Zero
        CIO.value(zero.length == 0L).map { result =>
            assert(result, "Duration.Zero.length == 0L should be true")
        }
    }
    "sleep(0) returns immediately within bounded window" in run {
        val c =
            CIO.nowMonotonic.map(_.toMillis).flatMap { t1 =>
                CIO.sleep(0.millis).flatMap { _ =>
                    CIO.nowMonotonic.map(_.toMillis).flatMap { t2 =>
                        CIO.defer(t2 - t1)
                    }
                }
            }
        c.map { deltaMs =>
            assert(deltaMs < 500L, s"sleep(0) took ${deltaMs}ms — expected < 500ms")
        }
    }

    // sleep(50ms) + sleep(100ms) in parallel should take ~100ms, not ~150ms.
    // Allow 250ms for CI noise.
    "concurrent sleeps complete in parallel — total time ~max not sum" in run {
        val c =
            CIO.nowMonotonic.map(_.toMillis).flatMap { t1 =>
                CIO.zip(CIO.sleep(50.millis), CIO.sleep(100.millis)).flatMap { _ =>
                    CIO.nowMonotonic.map(_.toMillis).flatMap { t2 =>
                        CIO.defer(t2 - t1)
                    }
                }
            }
        c.map { deltaMs =>
            assert(deltaMs < 250L, s"concurrent sleeps took ${deltaMs}ms — expected < 250ms (parallel, not sequential)")
        }
    }

    // Race semantics — accept either Some(7) or None, never crash.
    "timeout when inner completes near deadline — Some or None (not crash)" in run {
        val c = CIO.timeout(50.millis)(CIO.sleep(50.millis).flatMap(_ => CIO.defer(7)))
        c.map { result =>
            assert(result == Some(7) || result == None, s"expected Some(7) or None, got: $result")
        }
    }

    // timeout(200ms)(timeout(50ms)(never)) — inner fires → None; outer wraps → Some(None).
    // We accept result.flatten.isEmpty to cover both cases.
    "nested timeouts — inner fires first, result is effectively None" in run {
        val c = CIO.timeout(200.millis)(CIO.timeout(50.millis)(CIO.never))
        c.map { result =>
            assert(result.flatten.isEmpty, s"expected Some(None) or None, got: $result")
        }
    }

    "timeoutWithError when inner succeeds in time — returns inner value" in run {
        val c = CIO.timeoutWithError(500.millis)(TestError("nope"))(CIO.value(42))
        c.map { result =>
            assert(result == 42, s"expected 42, got: $result")
        }
    }

    "timeoutWithError when inner fails in time — propagates inner failure not custom error" in run {
        import scala.util.Failure
        val c = CIO.timeoutWithError(500.millis)(TestError("custom"))(CIO.fail(TestError("inner")))
        c.liftToTry.map {
            case Failure(e: TestError) if e.msg == "inner" => succeed
            case other                                     => fail(s"expected Failure(TestError(\"inner\")), got: $other")
        }
    }

    // Body still runs; result is 42. The 1ns delay is essentially a no-op-with-yield.
    "delay with 1.nanos duration runs the body and returns its value" in run {
        val c = CIO.delay(1.nanos)(CIO.defer(42))
        c.map { result =>
            assert(result == 42, s"expected 42, got: $result")
        }
    }
    "Duration extensions: Int variant 5.seconds.toMillis == 5000" in run {
        val d: FiniteDuration = 5.seconds
        CIO.value(d.toMillis).map(r => assert(r == 5000L, s"expected 5000, got $r"))
    }

    "0.seconds equals Duration.Zero" in run {
        val d: FiniteDuration = 0.seconds
        CIO.value(d.length == 0L).map(r => assert(r))
    }

    "negative duration: (-1).second.toMillis == -1000" in run {
        val d: FiniteDuration = (-1).second
        CIO.value(d.toMillis).map(r => assert(r == -1000L, s"expected -1000, got $r"))
    }

    "Duration extensions: 1.nano.toNanos == 1L" in run {
        val d: FiniteDuration = 1.nano
        CIO.value(d.toNanos).map(r => assert(r == 1L, s"expected 1, got $r"))
    }

    "Duration extensions: 1.day.toMillis == 86400000L" in run {
        val d: FiniteDuration = 1.day
        CIO.value(d.toMillis).map(r => assert(r == 86_400_000L, s"expected 86400000, got $r"))
    }

    "all 14 duration unit methods compile on both Long and Int receivers" in run {
        val longSum: FiniteDuration =
            1L.nano + 1L.nanos + 1L.micro + 1L.micros + 1L.milli + 1L.millis +
                1L.second + 1L.seconds + 1L.minute + 1L.minutes + 1L.hour + 1L.hours +
                1L.day + 1L.days
        val intSum: FiniteDuration =
            1.nano + 1.nanos + 1.micro + 1.micros + 1.milli + 1.millis +
                1.second + 1.seconds + 1.minute + 1.minutes + 1.hour + 1.hours +
                1.day + 1.days
        CIO.value((longSum.toNanos > 0L, intSum.toNanos > 0L)).map { case (a, b) =>
            assert(a && b)
        }
    }

end TimeTest
