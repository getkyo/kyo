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

        def kyoLoop(started: CountDownLatch, done: CountDownLatch): Unit < IO =
            def loop(i: Int): Unit < IO =
                IO {
                    if i == 0 then
                        IO(started.countDown()).andThen(loop(i + 1))
                    else
                        loop(i + 1)
                }
            IO.ensure(IO(done.countDown()))(loop(0))
        end kyoLoop

        def monixLoop(started: CountDownLatch, done: CountDownLatch): Task[Unit] =
            def loop(i: Int): Task[Unit] =
                Task.defer {
                    if i == 0 then
                        Task(started.countDown())
                            .flatMap(_ => loop(i + 1))
                    else
                        loop(i + 1)
                }
            loop(0).guarantee(Task(done.countDown()))
        end monixLoop

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

                "both" in runMonix {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val v =
                        for
                            _ <- Monix.get(monixLoop(started, done))
                            _ <- Async.run(kyoLoop(started, done))
                        yield ()
                    for
                        f <- Monix.run(v).start
                        _ <- Task(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        _ <- Task(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(done.getCount == 0)
                    end for
                }

                "parallel loops" in runMonix {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        Monix.run {
                            val loop1 = Monix.get(monixLoop(started, done))
                            val loop2 = kyoLoop(started, done)
                            Async.parallel[Throwable, Unit, Unit, Any](loop1, loop2)
                        }
                    for
                        f <- parallelEffect.start
                        _ <- Task(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.cancel
                        _ <- Task(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(done.getCount == 0)
                    end for
                }

                "race loops" in runMonix {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def raceEffect =
                        Monix.run {
                            val loop1 = Monix.get(monixLoop(started, done))
                            val loop2 = kyoLoop(started, done)
                            Async.race[Throwable, Unit, Any](loop1, loop2)
                        }
                    for
                        f <- raceEffect.start
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
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt(panic)
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r == panic)
                    end for
                }

                "both" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    val v =
                        for
                            _ <- Monix.get(monixLoop(started, done))
                            _ <- kyoLoop(started, done)
                        yield ()
                    for
                        f <- Async.run(v)
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }

                "parallel loops" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def parallelEffect =
                        val loop1 = Monix.get(monixLoop(started, done))
                        val loop2 = kyoLoop(started, done)
                        Async.parallel[Throwable, Unit, Unit, Any](loop1, loop2)
                    end parallelEffect
                    for
                        f <- Async.run(parallelEffect)
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }

                "race loops" in runKyo {
                    val started = new CountDownLatch(2)
                    val done    = new CountDownLatch(2)
                    def raceEffect =
                        val loop1 = Monix.get(monixLoop(started, done))
                        val loop2 = kyoLoop(started, done)
                        Async.race(loop1, loop2)
                    end raceEffect
                    for
                        f <- Async.run(raceEffect)
                        _ <- IO(started.await(100, TimeUnit.MILLISECONDS))
                        _ <- f.interrupt
                        r <- f.getResult
                        _ <- IO(done.await(100, TimeUnit.MILLISECONDS))
                    yield assert(r.isPanic)
                    end for
                }
            }
        end if
    }

end MonixTest
