package kyo.scheduler

import kyo.*
import org.scalatest.compatible.Assertion
import scala.annotation.tailrec
import scala.concurrent.duration.*

class IOPromiseTest extends Test:

    "complete" - {
        "success" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.complete(Result.success(1)))
            assert(p.done())
            assert(p.block(timeout.toMillis) == Result.success(1))
        }

        "failure" in {
            val ex = new Exception("Test exception")
            val p  = new IOPromise[Exception, Int]()
            assert(p.complete(Result.fail(ex)))
            assert(p.done())
            assert(p.block(timeout.toMillis) == Result.fail(ex))
        }

        "complete twice" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.complete(Result.success(1)))
            assert(!p.complete(Result.success(2)))
            assert(p.done())
            assert(p.block(timeout.toMillis) == Result.success(1))
        }

        "complete with null value" in {
            val p = new IOPromise[Nothing, String]()
            assert(p.complete(Result.success(null)))
            assert(p.done())
            assert(p.block(timeout.toMillis) == Result.success(null))
        }

        "complete with exception" in {
            val p  = new IOPromise[Throwable, Int]()
            val ex = new RuntimeException("Test")
            assert(p.complete(Result.fail(ex)))
            assert(p.done())
            assert(p.block(timeout.toMillis) == Result.fail(ex))
        }
    }

    "completeUnit" - {
        "success" in {
            val p = new IOPromise[Nothing, Int]()
            p.completeUnit(Result.success(1))
            assert(p.block(timeout.toMillis) == Result.success(1))
        }

        "completeUnit with failure" in {
            val p  = new IOPromise[Exception, Int]()
            val ex = new Exception("Test exception")
            p.completeUnit(Result.fail(ex))
            assert(p.block(timeout.toMillis) == Result.fail(ex))
        }
    }

    "become" - {
        "success" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            assert(p2.complete(Result.success(42)))
            assert(p1.become(p2))
            assert(p1.done())
            assert(p1.block(timeout.toMillis) == Result.success(42))
        }

        "failure" in {
            val ex = new Exception("Test exception")
            val p1 = new IOPromise[Exception, Int]()
            val p2 = new IOPromise[Exception, Int]()
            assert(p2.complete(Result.fail(ex)))
            assert(p1.become(p2))
            assert(p1.done())
            assert(p1.block(timeout.toMillis) == Result.fail(ex))
        }

        "already completed" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            assert(p1.complete(Result.success(42)))
            assert(p2.complete(Result.success(99)))
            assert(!p1.become(p2))
            assert(p1.block(timeout.toMillis) == Result.success(42))
        }

        "become with incomplete promise" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            assert(p1.become(p2))
            p2.complete(Result.success(42))
            assert(p1.done())
            assert(p1.block(timeout.toMillis) == Result.success(42))
        }

        "become with chain of promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()
            p1.become(p2)
            p2.become(p3)
            p3.complete(Result.success(42))
            val v = p1.block(timeout.toMillis)
            assert(v == Result.success(42))
        }
    }

    "becomeUnit" - {
        "success" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p2.complete(Result.success(42))
            p1.becomeUnit(p2)
            val v = p1.block(timeout.toMillis)
            assert(v == Result.success(42))
        }

        "becomeUnit with incomplete promise" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p1.becomeUnit(p2)
            p2.complete(Result.success(42))
            val v = p1.block(timeout.toMillis)
            assert(v == Result.success(42))
        }
    }

    "interrupt" - {
        "interrupt" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p.block(timeout.toMillis).isPanic)
        }

        "interrupt completed promise" in {
            val p = new IOPromise[Nothing, Int]()
            p.complete(Result.success(42))
            assert(!p.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p.block(timeout.toMillis) == Result.success(42))
        }

        "interrupt chain of promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()
            p1.become(p2)
            p2.become(p3)
            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p3.block(timeout.toMillis).isPanic)
        }
    }

    "onResult" - {
        "onResult" in {
            val p      = new IOPromise[Nothing, Int]()
            var called = false
            p.onResult(_ => called = true)
            p.complete(Result.success(42))
            assert(called)
        }

        "onResult with multiple callbacks" in {
            val p       = new IOPromise[Nothing, Int]()
            var called1 = false
            var called2 = false
            p.onResult(_ => called1 = true)
            p.onResult(_ => called2 = true)
            p.complete(Result.success(42))
            assert(called1 && called2)
        }

        "onResult with exception in callback" in {
            val p      = new IOPromise[Nothing, Int]()
            var called = false
            p.onResult(_ => throw new RuntimeException("Test"))
            p.onResult(_ => called = true)
            p.complete(Result.success(42))
            assert(called)
        }
    }

    "block" - {
        "immediate completion" in {
            val p = new IOPromise[Nothing, Int]()
            p.complete(Result.success(42))
            val result = p.block(timeout.toMillis)
            assert(result == Result.success(42))
        }

        "timeout" in runJVM {
            val p      = new IOPromise[Nothing, Int]()
            val result = p.block(java.lang.System.currentTimeMillis() + 10)
            assert(result.isFail)
        }

        "block with very short timeout" in runJVM {
            val p      = new IOPromise[Nothing, Int]()
            val result = p.block(java.lang.System.currentTimeMillis() + 1)
            assert(result.isFail)
        }
    }

    "stack safety" - {
        "deeply nested become calls" in {
            def createNestedPromises(depth: Int): IOPromise[Nothing, Int] =
                @tailrec def loop(currentDepth: Int, promise: IOPromise[Nothing, Int]): IOPromise[Nothing, Int] =
                    if currentDepth == 0 then
                        promise.complete(Result.success(42))
                        promise
                    else
                        val newPromise = new IOPromise[Nothing, Int]()
                        promise.become(newPromise)
                        loop(currentDepth - 1, newPromise)
                loop(depth, new IOPromise[Nothing, Int]())
            end createNestedPromises

            val deeplyNested = createNestedPromises(10000)
            assert(deeplyNested.block(timeout.toMillis) == Result.success(42))
        }

        "long chain of onResult callbacks" in {
            val p     = new IOPromise[Nothing, Int]()
            var count = 0
            def addCallback(remaining: Int): Unit =
                if remaining > 0 then
                    p.onResult(_ => count += 1)
                    addCallback(remaining - 1)
            addCallback(10000)
            p.complete(Result.success(42))
            assert(count == 10000)
        }
    }

    "interrupts" - {
        "interrupt linked promise via interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))

            assert(p1.block(timeout.toMillis).isPanic)
            assert(p2.block(timeout.toMillis).isPanic)
        }

        "interrupt linked chain promises via interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p2.interrupts(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))

            assert(p1.block(timeout.toMillis).isPanic)
            assert(p2.block(timeout.toMillis).isPanic)
            assert(p3.block(timeout.toMillis).isPanic)
        }

        "interrupt multiple promises via single interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p1.interrupts(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))

            assert(p1.block(timeout.toMillis).isPanic)
            assert(p2.block(timeout.toMillis).isPanic)
            assert(p3.block(timeout.toMillis).isPanic)
        }

        "ensure interruptions do not propagate without linking" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))
            assert(p1.block(timeout.toMillis).isPanic)
            assert(!p2.done())
        }
    }

end IOPromiseTest
