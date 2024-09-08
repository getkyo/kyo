package kyo

import cats.effect.IO as CatsIO
import cats.effect.kernel.Fiber as CatsFiber
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import kyo.*
import kyo.kernel.Platform
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.Eventually.*
import scala.concurrent.Future
import scala.concurrent.duration.*

class CatsTest extends Test:

    def runCatsIO[T](v: CatsIO[T]): Future[T] =
        v.unsafeToFuture()

    def runKyo(v: => Assertion < (Abort[Throwable] & Cats)): Future[Assertion] =
        Cats.run(v).unsafeToFuture()

    "Abort ordering" - {
        "kyo then cats" in runKyo {
            object catsFailure extends RuntimeException
            object kyoFailure  extends RuntimeException
            val a = Abort.fail(kyoFailure)
            val b = Cats.get(CatsIO.raiseError(catsFailure))
            Abort.run(a.map(_ => b)).map {
                case Result.Fail(ex) =>
                    assert(ex == kyoFailure)
                case _ =>
                    fail()
            }
        }
        "cats then kyo" in runKyo {
            object catsFailure extends RuntimeException
            object kyoFailure  extends RuntimeException
            val a = Cats.get(CatsIO.raiseError(catsFailure))
            val b = Abort.fail(kyoFailure)
            Abort.run(a.map(_ => b)).map {
                case Result.Panic(ex) =>
                    assert(ex == catsFailure)
                case ex =>
                    println(ex)
                    fail()
            }
        }
    }

    "A < Cats" in runKyo {
        val a: Int < Cats = Cats.get(CatsIO.pure(10))
        val b             = a.map(_ * 2)
        b.map(i => assert(i == 20))
    }

    "nested" in runKyo {
        val a: (String < Cats) < Cats = Cats.get(CatsIO.pure(Cats.get(CatsIO.pure("Nested"))))
        val b: String < Cats          = a.flatten
        b.map(s => assert(s == "Nested"))
    }

    "fibers" in runKyo {
        for
            v1 <- Cats.get(CatsIO.pure(1))
            v2 <- Async.run(2).map(_.get)
            v3 <- Cats.get(CatsIO.pure(3))
        yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }

    "interrupts" - {

        import java.util.concurrent.atomic.LongAdder

        def kyoLoop(a: LongAdder = new LongAdder): Unit < IO =
            IO(a.increment()).map(_ => kyoLoop(a))

        def catsLoop(a: LongAdder = new LongAdder): CatsIO[Unit] =
            CatsIO.delay(a.increment()).flatMap(_ => catsLoop(a))

        if Platform.isJVM then

            "cats to kyo" in runCatsIO {
                for
                    f <- Cats.run(kyoLoop()).start
                    _ <- f.cancel
                    r <- f.join
                yield assert(r.isCanceled)
                end for
            }
            "kyo to cats" in runKyo {
                for
                    f <- Async.run(Cats.run(catsLoop()))
                    _ <- f.interrupt(Result.Panic(new Exception))
                    r <- f.getResult
                yield assert(r.isPanic)
                end for
            }
            "both" in runCatsIO {
                val v =
                    for
                        _ <- Cats.get(catsLoop())
                        _ <- Async.run(kyoLoop())
                    yield ()
                for
                    f <- Cats.run(v).start
                    _ <- f.cancel
                    r <- f.join
                yield assert(r.isCanceled)
                end for
            }
        end if
    }

    "Error handling" - {
        "Kyo Abort to Cats IO error" in runKyo {
            val kyoAbort  = Abort.fail(new Exception("Kyo error"))
            val converted = Cats.get(CatsIO.fromEither(Abort.run(kyoAbort).eval.toEither))
            Abort.run[Throwable](converted).map {
                case Result.Fail(ex) => assert(ex.getMessage() == "Kyo error")
                case _               => fail("Expected a String error")
            }
        }

        "Cats IO error to Kyo Abort" in runKyo {
            val catsError = CatsIO.raiseError[Int](new Exception("Cats error"))
            val converted = Cats.get(catsError)
            Abort.run[Throwable](converted).map {
                case Result.Fail(error: Exception) => assert(error.getMessage == "Cats error")
                case _                             => fail("Expected an Exception")
            }
        }
    }

end CatsTest
