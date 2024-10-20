package kyo

import kyo.*
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.compatible.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.Future

class MonixsTest extends AsyncFreeSpec:
    given CanEqual[Throwable, Throwable] = CanEqual.derived

    def runMonix[T](v: Task[T]): Future[T] =
        v.runToFuture

    def runKyo(v: => Assertion < (Abort[Throwable] & Async)): Future[Assertion] =
        Monixs.run(v).runToFuture

    "Monixs" - {
        "get" - {
            "should convert Task to Kyo effect" in runKyo {
                val task = Task.pure(42)
                val kyo  = Monixs.get(task)
                kyo.map(result => assert(result == 42))
            }

            "should handle Task failures" in runKyo {
                val ex   = new Exception("Test exception")
                val task = Task.raiseError(ex)
                val kyo  = Monixs.get(task)
                Abort.run[Throwable](kyo).map {
                    case Result.Fail(e) => assert(e == ex)
                    case _              => fail("Expected Fail result")
                }
            }
        }

        "run" - {
            "should convert Kyo effect to Task" in runMonix {
                val kyo: Int < (Abort[Nothing] & Async) = Async.run(42).map(_.get)
                val task                                = Monixs.run(kyo)
                task.map(result => assert(result == 42))
            }

            "should handle Kyo failures" in runMonix {
                val ex   = new Exception("Test exception")
                val kyo  = Abort.fail[Throwable](ex)
                val task = Monixs.run(kyo)
                task.attempt.map {
                    case Left(e)  => assert(e == ex)
                    case Right(_) => fail("Expected Left result")
                }
            }
        }
    }

end MonixsTest
