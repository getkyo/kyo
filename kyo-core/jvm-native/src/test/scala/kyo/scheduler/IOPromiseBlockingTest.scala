package kyo.scheduler

import kyo.*
import scala.annotation.tailrec

class IOPromiseBlockingTest extends kyo.test.Test[Any]:

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

        "timeout".notJs in {
            val p      = new IOPromise[Nothing, Int]()
            val result = p.block(deadline(10.millis))
            assert(result.isFailure)
        }

        "block with very short timeout".notJs in {
            val p      = new IOPromise[Nothing, Int]()
            val result = p.block(deadline(10.millis))
            assert(result.isFailure)
        }

        def threadInterruption[E, A](promise: IOPromise[E, A])(assertion: Result[E | Timeout, A] => Unit) =
            val thread = new Thread:
                override def run(): Unit =
                    discard(promise.block(deadline(Duration.Infinity)))
                end run
            thread.start()

            // block() registers its waiter before parking, so a positive waiter count means the thread reached the blocking
            // call and the interrupt lands on a parked thread.
            while promise.waiters() == 0 do Thread.sleep(1)

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
            "linked".notJs in {
                val p = new IOPromise[Nothing, Int]()
                p.becomeDiscard(new IOPromise[Nothing, Int]())
                threadInterruption(p) { result =>
                    assert(result.isPanic)
                }
            }
        }
    }

end IOPromiseBlockingTest
