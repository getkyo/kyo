package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
class LiftingTest extends CompatTest:
    "value(7) resolves to 7" in run {
        val c = CIO.value(7)
        c.map(r => assert(r == 7))
    }
    "fail(throwable) carries the throwable in liftToTry" in run {
        val src: CIO[Int] = CIO.fail(new RuntimeException("oops"))
        src.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "oops")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    "fail(non-throwable E) carries the typed E in liftToTry" in run {
        val src: CIO[Int] = CIO.fail(TestError("not throwable"))
        src.liftToTry.map {
            case Failure(e: TestError) if e.msg == "not throwable" => succeed
            case other                                             => fail(s"expected Failure(TestError(\"not throwable\")), got: $other")
        }
    }
    "defer(thunk) defers evaluation" in run {
        val ctr = new AtomicInteger(0)
        val c   = CIO.defer { ctr.incrementAndGet() }
        // Counter must not have run yet (CIO is lazy until interpreted).
        val pre = ctr.get
        c.map(r => assert(pre == 0 && r == 1))
    }
    "defer body's exception lifts as failure" in run {
        val src: CIO[Any] = CIO.defer {
            throw new RuntimeException("err")
        }
        src.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "err")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    "catching[E] catches matching exceptions as typed failures" in run {
        val src: CIO[Any] = CIO.defer {
            throw new IllegalArgumentException("oops")
        }
        src.liftToTry.map {
            case Failure(t: IllegalArgumentException) => assert(t.getMessage == "oops")
            case other                                => fail(s"expected Failure(IllegalArgumentException), got: $other")
        }
    }
    "get(Try.Success) succeeds" in run {
        val c = CIO.get(Try(42))
        c.liftToTry.map {
            case Success(42) => succeed
            case other       => fail(s"expected Success(42), got: $other")
        }
    }
    "get(Try.Failure) fails with the throwable" in run {
        val tryF: Try[Int] = Failure(new RuntimeException("err"))
        val src: CIO[Int]  = CIO.get(tryF)
        src.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "err")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    "get(Failure(t)).liftToTry resolves to Success(Failure(t))" in run {
        val t             = new RuntimeException("x")
        val src: CIO[Int] = CIO.get(Failure(t))
        src.liftToTry.map {
            case Failure(e) => assert(e eq t)
            case other      => fail(s"expected Failure wrapping the original throwable, got: $other")
        }
    }
    "fromScalaFuture(successful) succeeds" in run {
        val raw: Future[Int] = Future.successful(7)
        val c                = CIO.fromScalaFuture(raw)
        c.liftToTry.map {
            case Success(7) => succeed
            case other      => fail(s"expected Success(7), got: $other")
        }
    }
    "fromScalaFuture(failed) surfaces failure" in run {
        val raw: Future[Int] = Future.failed(new RuntimeException("nope"))
        val c                = CIO.fromScalaFuture(raw)
        c.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "nope")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    "never never completes within a bounded timeout" in run {
        // Use the backend's timeout to bound the never; expect None back.
        val c = CIO.timeout(50.millis)(CIO.never)
        c.map(r => assert(r == None))
    }
    "catching surfaces matching exception as typed failure" in run {
        // CIO.defer { throw new RuntimeException("oops") } must surface
        // the thrown exception as a Failure when accessed via .liftToTry.
        val f = CIO.defer { throw new RuntimeException("oops") }
        f.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "oops")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    "catching does not swallow non-matching throws" in run {
        // Per API-SURFACE: non-matching throws propagate raw (as failure).
        // Here a RuntimeException is thrown unconditionally; it must surface
        // as a failure regardless of the outer type annotation.
        val f = CIO.defer { throw new RuntimeException("not iae") }
        f.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "not iae")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }

    "value(42) resolves to 42" in run {
        val c = CIO.value(42)
        c.map(r => assert(r == 42))
    }

    "CIO.unit.map(_ => 42) resolves to 42" in run {
        val c: CIO[Int] = CIO.unit.map(_ => 42)
        c.map(r => assert(r == 42))
    }

    "CIO.never with 50ms timeout returns None" in run {
        val c = CIO.timeout(50.millis)(CIO.never)
        c.map(r => assert(r == None))
    }

    "fromScalaFuture pending future: timeout returns None" in run {
        val p = scala.concurrent.Promise[Int]()
        val c = CIO.timeout(50.millis)(CIO.fromScalaFuture(p.future))
        c.map(r => assert(r == None))
    }

    "fromScalaFuture failing future: liftToTry surfaces Failure" in run {
        val ex               = new java.lang.RuntimeException("test-error")
        val f: Future[Int]   = Future.failed(ex)
        val c: CIO[Try[Int]] = CIO.fromScalaFuture(f).liftToTry
        c.map {
            case Failure(_) => succeed
            case other      => fail(s"expected Failure(_), got: $other")
        }
    }

    "fromScalaFuture preserves value: resolves to 7" in run {
        val raw: Future[Int] = Future.successful(7)
        val c                = CIO.fromScalaFuture(raw)
        c.map(r => assert(r == 7))
    }

    "defer is evaluated exactly once per lower" in run {
        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        val c       = CIO.defer { counter.incrementAndGet(); counter.get }
        c.map(r => assert(r == 1 && counter.get == 1))
    }

    "value(7).map(_ + 1) returns 8" in run {
        val c: CIO[Int] = CIO.value(7).map(_ + 1)
        c.map(r => assert(r == 8))
    }

    "fail(e).recover(_ => value(0)) returns 0" in run {
        val e = new RuntimeException("oops")
        val c = CIO.fail(e).recover(_ => CIO.value(0))
        c.map(r => assert(r == 0))
    }

    "get(Success(7)) returns 7" in run {
        val c: CIO[Int] = CIO.get(scala.util.Success(7))
        c.map(r => assert(r == 7))
    }

    "get(Failure(e)).liftToTry returns Failure(e)" in run {
        val ex          = new RuntimeException("fail-e")
        val c: CIO[Int] = CIO.get(scala.util.Failure(ex))
        c.liftToTry.map {
            case Failure(t) => assert(t eq ex)
            case other      => fail(s"expected Failure(ex), got: $other")
        }
    }

    "deep flatMap chain 1000 levels does not stack-overflow" in run {
        val n = 1000
        val c = (1 to n).foldLeft(CIO.value(0))((acc, _) =>
            acc.flatMap(v => CIO.value(v + 1))
        )
        c.map(v => assert(v == n, s"expected $n, got $v"))
    }

    "deep map chain 1000 levels does not stack-overflow" in run {
        val n = 1000
        val c = (1 to n).foldLeft(CIO.value(0))((acc, _) =>
            acc.map(_ + 1)
        )
        c.map(v => assert(v == n, s"expected $n, got $v"))
    }
end LiftingTest
