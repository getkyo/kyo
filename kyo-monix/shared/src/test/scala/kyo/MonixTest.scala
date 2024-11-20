package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.kernel.Platform
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.compatible.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.Future

class MonixTest extends AsyncFreeSpec:
    given CanEqual[Throwable, Throwable] = CanEqual.derived

    def runMonix[T](v: Task[T]): Future[T] =
        v.runToFuture

    def runKyo(v: => Assertion < (Abort[Throwable] & Async)): Future[Assertion] =
        Monix.run(v).runToFuture

    "Monix" - {
        "get" - {
            "should convert Task to Kyo effect" in runKyo {
                val task = Task.pure(42)
                val kyo  = Monix.get(task)
                kyo.map(result => assert(result == 42))
            }

            "should handle Task failures" in runKyo {
                val ex   = new Exception("Test exception")
                val task = Task.raiseError(ex)
                val kyo  = Monix.get(task)
                Abort.run[Throwable](kyo).map {
                    case Result.Fail(e) => assert(e == ex)
                    case _              => fail("Expected Fail result")
                }
            }
        }

        "run" - {
            "should convert Kyo effect to Task" in runMonix {
                val kyo: Int < (Abort[Nothing] & Async) = Async.run(42).map(_.get)
                val task                                = Monix.run(kyo)
                task.map(result => assert(result == 42))
            }

            "should handle Kyo failures" in runMonix {
                val ex   = new Exception("Test exception")
                val kyo  = Abort.fail[Throwable](ex)
                val task = Monix.run(kyo)
                task.attempt.map {
                    case Left(e)  => assert(e == ex)
                    case Right(_) => fail("Expected Left result")
                }
            }
        }
    }

    "interrupts" - {
        val debug = false

        def kyoLoop(started: CountDownLatch, done: CountDownLatch)(using f: Frame): Unit < IO =
            def loop(i: Int): Unit < IO =
                IO {
                    if i == 0 then
                        IO(started.countDown()).andThen(loop(i + 1))
                    else loop(i + 1)
                }
            val ensure =
                if debug then IO(println(s"Kyo ENSURED ${f.position.show}")).andThen(IO(done.countDown()))
                else IO(done.countDown())
            IO.ensure(ensure)(loop(0))
        end kyoLoop

        def monixLoop(started: CountDownLatch, done: CountDownLatch)(using f: Frame): Task[Unit] =
            def loop(i: Int): Task[Unit] =
                Task.defer {
                    if i == 0 then Task(started.countDown()) *> loop(i + 1)
                    else loop(i + 1)
                }
            val ensure =
                if debug then Task(println(s"Monix ENSURED ${f.position.show}")) *> Task(done.countDown())
                else Task(done.countDown())
            loop(0).guarantee(ensure)
        end monixLoop

        // inline maintains error message
        inline def kyoWait(inline latch: CountDownLatch, inline duration: Duration): Assertion < IO =
            IO {
                val millis = duration.toMillis
                assert(
                    latch.await(millis, TimeUnit.MILLISECONDS) && latch.getCount() == 0,
                    s"Latch had count ${latch.getCount()} after ${duration.show}"
                )
            }

        inline def monixWait(inline latch: CountDownLatch, inline duration: Duration): Task[Assertion] =
            Task {
                val millis = duration.toMillis
                assert(
                    latch.await(millis, TimeUnit.MILLISECONDS),
                    s"Latch had count ${latch.getCount()} after ${duration.show}"
                )
            }

        if Platform.isJVM then

            "runMonix" - {
                "monix to kyo" in runMonix {
                    val started = new CountDownLatch(1)
                    val done    = new CountDownLatch(1)
                    for
                        f <- Monix.run(kyoLoop(started, done)).start
                        _ <- Task(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        _ <- Task(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(done.getCount == 0)
                    end for
                }

                "parallel loops" in runMonix {
                    val started  = new CountDownLatch(2)
                    val done     = new CountDownLatch(2)
                    val parallel = Task.parZip2(monixLoop(started, done), Monix.run(kyoLoop(started, done)))
                    for
                        f <- parallel.start
                        _ <- monixWait(started, 100.millis)
                        _ <- f.cancel
                        _ <- monixWait(done, 100.millis)
                    yield assert(done.getCount == 0)
                    end for
                }

                "race loops" in runMonix {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val race    = Task.race(monixLoop(started, done), monixLoop(started, done))
                    for
                        f <- race.start
                        _ <- Task(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        _ <- Task(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(done.getCount == 0)
                    end for
                }
            }

            "runKyo" - {
                "kyo to monix" in runKyo {
                    val started = new CountDownLatch(1)
                    val done    = new CountDownLatch(1)
                    val panic   = Result.Panic(new Exception)
                    for
                        f <- Async.run(Monix.get(monixLoop(started, done)))
                        _ <- kyoWait(started, 100.millis)
                        _ <- f.interrupt(panic)
                        r <- f.getResult
                        _ <- kyoWait(done, 100.millis)
                    yield assert(r == panic)
                    end for
                }

                "parallel" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        val monix = Monix.get(monixLoop(started, done))
                        val kyo   = kyoLoop(started, done)
                        Async.parallel(monix, kyo)
                    end parallelEffect
                    for
                        f <- Async.run(parallelEffect)
                        _ <- kyoWait(started, 100.millis)
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- kyoWait(done, 100.millis)
                    yield assert(r.isPanic)
                    end for
                }

                "race" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val race    = Async.race(Monix.get(monixLoop(started, done)), kyoLoop(started, done))
                    for
                        f <- Async.run(race)
                        _ <- kyoWait(started, 100.millis)
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- kyoWait(done, 100.millis)
                    yield assert(r.isPanic)
                    end for
                }
            }
        end if
    }

end MonixTest
