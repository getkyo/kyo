package kyo.scheduler

import kyo.*
import org.scalatest.compatible.Assertion
import scala.annotation.tailrec

class IOPromiseBlockingTest extends Test:

    def deadline(after: Duration = timeout) =
        import AllowUnsafe.embrace.danger
        Clock.live.unsafe.deadline(after)

    "block" - {
        "immediate completion" in {
            val p = new IOPromise[Nothing, Int]()
            p.complete(Result.succeed(42))
            val result = p.block(deadline())
            assert(result == Result.succeed(42))
        }

        "timeout" in runNotJS {
            val p      = new IOPromise[Nothing, Int]()
            val result = p.block(deadline(10.millis))
            assert(result.isFailure)
        }

        "block with very short timeout" in runNotJS {
            val p      = new IOPromise[Nothing, Int]()
            val result = p.block(deadline(10.millis))
            assert(result.isFailure)
        }

        def threadInterruption[E, A](promise: IOPromise[E, A])(assertion: Result[E | Timeout, A] => Assertion) =
            @volatile var threadStarted = false
            val thread = new Thread:
                override def run(): Unit =
                    threadStarted = true
                    discard(promise.block(deadline(Duration.Infinity)))
                end run
            thread.start()

            // wait for parking
            while !threadStarted do Thread.sleep(10)
            Thread.sleep(10)

            thread.interrupt()
            thread.join(200)

            val result = promise.block(deadline())
            assertion(result)
        end threadInterruption

        "thread interruption" - {
            "uncompleted" in {
                threadInterruption(new IOPromise[Nothing, Int]()) { result =>
                    assert(result.isPanic)
                }
            }
            "linked" in runNotJS {
                val p = new IOPromise[Nothing, Int]()
                p.becomeDiscard(new IOPromise[Nothing, Int]())
                threadInterruption(p) { result =>
                    assert(result.isPanic)
                }
            }
        }
    }

end IOPromiseBlockingTest
