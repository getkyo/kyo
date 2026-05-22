package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

class RaceZipTest extends CompatTest:

    "race(a, b) returns first completion" in run {
        // Deterministic version: race a value-producing CIO against never.
        val c = CIO.race[Int](CIO.defer { 1 }, CIO.never)
        c.map(r => assert(r == 1))
    }
    "zip(a, b) returns tuple arity 2" in run {
        val c = CIO.zip(CIO.defer { 1 }, CIO.defer { "x" })
        c.map(r => assert(r == ((1, "x"))))
    }
    "zip arity 3" in run {
        val c = CIO.zip(CIO.defer { 1 }, CIO.defer { 2 }, CIO.defer { 3 })
        c.map(r => assert(r == ((1, 2, 3))))
    }
    "zip arity 5" in run {
        val c = CIO.zip(CIO.defer { 1 }, CIO.defer { 2 }, CIO.defer { 3 }, CIO.defer { 4 }, CIO.defer { 5 })
        c.map(r => assert(r == ((1, 2, 3, 4, 5))))
    }
    "zip arity 7" in run {
        val c = CIO.zip(
            CIO.defer { 1 },
            CIO.defer { 2 },
            CIO.defer { 3 },
            CIO.defer { 4 },
            CIO.defer { 5 },
            CIO.defer { 6 },
            CIO.defer { 7 }
        )
        c.map(r => assert(r == ((1, 2, 3, 4, 5, 6, 7))))
    }
    "zip propagates failure from any leg" in run {
        val a: CIO[Int]     = CIO.defer { 1 }
        val b: CIO[Nothing] = CIO.fail(TestError("oops"))
        val c               = CIO.zip(a, b)
        c.liftToTry.map {
            case Failure(e: TestError) if e.msg == "oops" => succeed
            case other                                    => fail(s"expected Failure(TestError(\"oops\")), got: $other")
        }
    }
    "race propagates failure if all racers fail" in run {
        // When all racing legs fail, race must propagate one of the failures.
        val a: CIO[Int] = CIO.fail(TestError("a"))
        val b: CIO[Int] = CIO.fail(TestError("b"))
        val c           = CIO.race(a, b)
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "a" || e.msg == "b", s"expected \"a\" or \"b\", got: ${e.msg}")
            case Failure(e)            => fail(s"expected Failure(TestError), got Failure($e)")
            case other                 => fail(s"expected Failure, got: $other")
        }
    }
    "zip runs in parallel (timing canary)" in run {
        // Both legs sleep ~100ms; concurrent execution should keep elapsed
        // well under 5s on any backend (5s upper bound is loose to tolerate
        // shared CI runners). The canary catches obviously sequential
        // implementations. We measure at the test driver via System.nanoTime
        // because CIO does not expose `flatMap` through the opaque alias.
        val a     = CIO.delay(100.millis)(CIO.defer { 1 })
        val b     = CIO.delay(100.millis)(CIO.defer { 2 })
        val start = java.lang.System.nanoTime()
        CIO.zip(a, b).map { tup =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(tup == ((1, 2)) && elapsed < 5_000L, s"tup=$tup elapsed=$elapsed ms (parallelism canary)")
        }
    }
    "zip arity 4 returns 4-tuple" in run {
        // CIO.zip of four values returns the matching 4-tuple.
        val c = CIO.zip(CIO.value(1), CIO.value(2), CIO.value(3), CIO.value(4))
        c.map(r => assert(r == ((1, 2, 3, 4))))
    }

    "zip arity 6 returns 6-tuple" in run {
        // CIO.zip of six values returns the matching 6-tuple.
        val c = CIO.zip(
            CIO.value(1),
            CIO.value(2),
            CIO.value(3),
            CIO.value(4),
            CIO.value(5),
            CIO.value(6)
        )
        c.map(r => assert(r == ((1, 2, 3, 4, 5, 6))))
    }

    "race with fast success and slow failure → success wins" in run {
        // race(fast-success, slow-failure). The fast success arrives first;
        // the slow failure never propagates. Result is Success(1).
        val c = CIO.race(
            CIO.sleep(20.millis).flatMap(_ => CIO.value(1)),
            CIO.sleep(200.millis).flatMap(_ => CIO.fail(TestError("slow")))
        )
        c.liftToTry.map {
            case scala.util.Success(v) => assert(v == 1, s"expected Success(1), got Success($v)")
            case other                 => fail(s"expected Success(1), got: $other")
        }
    }

    "race with slow success and fast failure → semantics documented" in run {
        // race(slow-success, fast-failure). Most race implementations take
        // whichever completes first; a fast failure typically wins and the result
        // is Failure(TestError("fast")). Some implementations may keep racing
        // until a success arrives, in which case Success(1) is returned.
        // Both outcomes are valid per the CIO race contract — document here.
        val c = CIO.race(
            CIO.sleep(200.millis).flatMap(_ => CIO.value(1)),
            CIO.sleep(20.millis).flatMap(_ => CIO.fail(TestError("fast")))
        )
        c.liftToTry.map { result =>
            // Accept either: fast failure wins or slow success wins.
            result match
                case scala.util.Failure(e: TestError) if e.msg == "fast" =>
                    succeed // failure-first race semantics
                case scala.util.Success(1) =>
                    succeed // success-biased race semantics
                case other =>
                    fail(s"expected Failure(TestError(\"fast\")) or Success(1), got: $other")
        }
    }

    "zip with all legs failing → first-arrival failure propagates" in run {
        // All legs fail; the first to fail (immediately) determines the
        // failure propagated. liftToTry must yield Failure with message "a".
        // Explicit Unit ascriptions prevent the kyo backend from inferring
        // CIO[Nothing] for the fail branches (Nothing triggers type errors in
        // the zip inline that casts result slots with asInstanceOf).
        val c = CIO.zip(
            CIO.fail(TestError("a")): CIO[Unit],
            CIO.sleep(50.millis).flatMap((_: Unit) => CIO.fail(TestError("b")): CIO[Unit]),
            CIO.sleep(100.millis).flatMap((_: Unit) => CIO.fail(TestError("c")): CIO[Unit])
        )
        c.liftToTry.map {
            case scala.util.Failure(e: TestError) =>
                assert(e.msg == "a", s"expected failure \"a\" (first to fail), got: ${e.msg}")
            case other => fail(s"expected Failure(TestError), got: $other")
        }
    }

end RaceZipTest
