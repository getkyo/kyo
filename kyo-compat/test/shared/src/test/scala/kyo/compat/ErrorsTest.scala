package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

class ErrorsTest extends CompatTest:

    "recover(handler) recovers from failure" in run {
        val src: CIO[String] = CIO.fail(TestError("oops"))
        val c = src.recover {
            case te: TestError => CIO.defer { s"recovered: ${te.msg}" }
            case other         => CIO.fail(other)
        }
        c.map(r => assert(r == "recovered: oops"))
    }

    "recover doesn't fire on success" in run {
        val ctr           = new AtomicInteger(0)
        val src: CIO[Int] = CIO.defer { 42 }
        val c = src.recover { _ =>
            val _ = ctr.incrementAndGet()
            CIO.defer { 0 }
        }
        c.map(r => assert(r == 42 && ctr.get == 0))
    }

    "fold on success runs effectful onSuccess" in run {
        val src: CIO[Int] = CIO.defer { 1 }
        val c             = src.fold(a => CIO.defer { a + 10 }, _ => CIO.defer { -1 })
        c.map(r => assert(r == 11))
    }

    "fold on failure runs effectful onFail" in run {
        val src: CIO[Nothing] = CIO.fail(TestError("e"))
        val c = src.fold(
            (_: Int) => CIO.defer { 0 },
            {
                case te: TestError => CIO.defer { te.msg.length }
                case _             => CIO.defer { -1 }
            }
        )
        c.map(r => assert(r == 1))
    }

    "fold(defer(7))(a => defer(a * 2), e => defer(0)) resolves to 14" in run {
        val c = CIO.defer(7).fold(a => CIO.defer(a * 2), e => CIO.defer(0))
        c.map(r => assert(r == 14))
    }

    "fold(fail(x))(a => defer(0), e => defer(-1)) resolves to -1" in run {
        val c = CIO.fail(new RuntimeException("x")).fold(a => CIO.defer(0), e => CIO.defer(-1))
        c.map(r => assert(r == -1))
    }

    "unit discards the success value" in run {
        val c = CIO.defer { 42 }.unit
        c.map(r => assert(r == ((): Unit)))
    }

    "unit propagates failure through the error channel" in run {
        val rt: Throwable     = TestError("oops")
        val src: CIO[Nothing] = CIO.fail(rt)
        src.unit.liftToTry.map {
            case Failure(t) => assert(t eq rt, s"expected exact throwable propagated, got $t")
            case other      => fail(s"expected Failure, got $other")
        }
    }

    "liftToTry wraps success to Success" in run {
        val c = CIO.defer { 42 }.liftToTry
        c.map {
            case Success(42) => succeed
            case other       => fail(s"expected Success(42), got: $other")
        }
    }

    "liftToTry wraps failure to Failure" in run {
        val src: CIO[Int] = CIO.fail(TestError("oops"))
        src.liftToTry.map {
            case Failure(e: TestError) if e.msg == "oops" => succeed
            case other                                    => fail(s"expected Failure(TestError(\"oops\")), got: $other")
        }
    }

    "orElse runs fallback on failure" in run {
        val src: CIO[Int] = CIO.fail(TestError("oops"))
        val c             = src.orElse(CIO.defer { 99 })
        c.map(r => assert(r == 99))
    }

    "orElse doesn't fire on success" in run {
        val ctr = new AtomicInteger(0)
        val c = CIO.defer { 42 }.orElse(CIO.defer {
            val _ = ctr.incrementAndGet()
            99
        })
        c.map(r => assert(r == 42 && ctr.get == 0))
    }

    "mapError transforms typed E" in run {
        val src: CIO[Int] = CIO.fail(TestError("oops"))
        val c = src.mapError {
            case te: TestError => new RuntimeException(s"wrapped: ${te.msg}")
            case other         => other
        }
        c.liftToTry.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "wrapped: oops")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    // recover with handler that throws — new failure replaces old
    "recover with handler that throws surfaces the handler's exception" in run {
        val c = CIO.fail(TestError("a")).recover(_ => throw TestError("b"))
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "b", s"expected TestError(b), got $e")
            case other                 => fail(s"expected Failure(TestError(b)), got: $other")
        }
    }

    // fold with onSuccess that throws — surfaces the throw
    "fold with onSuccess that throws surfaces the throw" in run {
        val c = CIO.value(7).fold(_ => throw TestError("a"), _ => CIO.value(0))
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "a", s"expected TestError(a), got $e")
            case other                 => fail(s"expected Failure(TestError(a)), got: $other")
        }
    }

    // fold with onFail that throws — surfaces the throw
    "fold with onFail that throws surfaces the throw" in run {
        val c = CIO.fail(TestError("orig")).fold(_ => CIO.value(0), _ => throw TestError("b"))
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "b", s"expected TestError(b), got $e")
            case other                 => fail(s"expected Failure(TestError(b)), got: $other")
        }
    }

    // orElse with fallback that also fails — fallback's failure surfaces
    "orElse with fallback that also fails surfaces the fallback failure" in run {
        val c = CIO.fail(TestError("a")).orElse(CIO.fail(TestError("b")))
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "b", s"expected TestError(b), got $e")
            case other                 => fail(s"expected Failure(TestError(b)), got: $other")
        }
    }

    // mapError with f that throws — surfaces the throw
    "mapError with f that throws surfaces the throw" in run {
        val c = CIO.fail(TestError("orig")).mapError(_ => throw TestError("b"))
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "b", s"expected TestError(b), got $e")
            case other                 => fail(s"expected Failure(TestError(b)), got: $other")
        }
    }

    // Chained recover — second recover handles the b-error
    "chained recover: second recover handles failure from first recover" in run {
        val c = CIO
            .fail(TestError("a"))
            .recover(_ => CIO.fail(TestError("b")))
            .recover(_ => CIO.value(0))
        c.map(r => assert(r == 0, s"expected 0, got $r"))
    }

    // Chained mapError — each mapper applied in sequence
    "chained mapError applies mappers in sequence" in run {
        val c = CIO
            .fail(TestError("a"))
            .mapError { case te: TestError => TestError(te.msg + "+b"); case other => other }
            .mapError { case te: TestError => TestError(te.msg + "+c"); case other => other }
        c.liftToTry.map {
            case Failure(e: TestError) => assert(e.msg == "a+b+c", s"expected TestError(a+b+c), got $e")
            case other                 => fail(s"expected Failure(TestError(a+b+c)), got: $other")
        }
    }

    "deep recover chain 100 nested propagates correctly" in run {
        val n                 = 100
        val initial: CIO[Int] = CIO.fail(TestError("init"))
        val c = (1 to n).foldLeft(initial)((acc, i) =>
            acc.recover(_ => CIO.fail(TestError(s"layer-$i")))
        )
        c.liftToTry.map { result =>
            result match
                case scala.util.Failure(TestError(msg)) => assert(msg == s"layer-$n", s"expected layer-$n, got $msg")
                case other                              => fail(s"expected Failure(TestError(layer-$n)), got: $other")
        }
    }

    "deep orElse chain 100 fallbacks: last value surfaces" in run {
        val n                          = 100
        val failures: Vector[CIO[Int]] = Vector.tabulate(n)(i => CIO.fail(TestError(s"fail-$i")))
        val success: CIO[Int]          = CIO.value(42)
        val c                          = (failures :+ success).reduce((a, b) => a.orElse(b))
        c.map(v => assert(v == 42, s"expected 42 (from final orElse), got $v"))
    }

end ErrorsTest
