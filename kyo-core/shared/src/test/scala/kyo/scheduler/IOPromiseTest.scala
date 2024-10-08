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

    "completeDiscard" - {
        "success" in {
            val p = new IOPromise[Nothing, Int]()
            p.completeDiscard(Result.success(1))
            assert(p.block(timeout.toMillis) == Result.success(1))
        }

        "completeDiscard with failure" in {
            val p  = new IOPromise[Exception, Int]()
            val ex = new Exception("Test exception")
            p.completeDiscard(Result.fail(ex))
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

    "becomeDiscard" - {
        "success" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p2.complete(Result.success(42))
            p1.becomeDiscard(p2)
            val v = p1.block(timeout.toMillis)
            assert(v == Result.success(42))
        }

        "becomeDiscard with incomplete promise" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p1.becomeDiscard(p2)
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

    "onComplete" - {
        "onComplete" in {
            val p      = new IOPromise[Nothing, Int]()
            var called = false
            p.onComplete(_ => called = true)
            p.complete(Result.success(42))
            assert(called)
        }

        "onComplete with multiple callbacks" in {
            val p       = new IOPromise[Nothing, Int]()
            var called1 = false
            var called2 = false
            p.onComplete(_ => called1 = true)
            p.onComplete(_ => called2 = true)
            p.complete(Result.success(42))
            assert(called1 && called2)
        }

        "onComplete with exception in callback" in {
            val p      = new IOPromise[Nothing, Int]()
            var called = false
            p.onComplete(_ => throw new RuntimeException("Test"))
            p.onComplete(_ => called = true)
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

        "long chain of onComplete callbacks" in {
            val p     = new IOPromise[Nothing, Int]()
            var count = 0
            def addCallback(remaining: Int): Unit =
                if remaining > 0 then
                    p.onComplete(_ => count += 1)
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

    "mask" - {
        "doesn't propagate interrupts to parent" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask

            var originalCompleted                         = false
            var maskedResult: Maybe[Result[Nothing, Int]] = Maybe.Empty

            original.onComplete(_ => originalCompleted = true)
            masked.onComplete(r => maskedResult = Maybe(r))

            assert(masked.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(maskedResult.exists(_.isPanic))
            assert(!originalCompleted)
        }

        "completes when original completes" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask

            var maskedResult: Maybe[Result[Nothing, Int]] = Maybe.Empty
            masked.onComplete(r => maskedResult = Maybe(r))

            original.complete(Result.success(42))
            assert(maskedResult.contains(Result.success(42)))
        }

        "propagates failure" in {
            val original = new IOPromise[Exception, Int]()
            val masked   = original.mask

            var maskedResult: Maybe[Result[Exception, Int]] = Maybe.Empty
            masked.onComplete(r => maskedResult = Maybe(r))

            val ex = new Exception("Test exception")
            original.complete(Result.fail(ex))
            assert(maskedResult.contains(Result.fail(ex)))
        }

        "allows completion of masked promise" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask

            var originalResult: Maybe[Result[Nothing, Int]] = Maybe.Empty
            var maskedResult: Maybe[Result[Nothing, Int]]   = Maybe.Empty

            original.onComplete(r => originalResult = Maybe(r))
            masked.onComplete(r => maskedResult = Maybe(r))

            masked.complete(Result.success(99))
            assert(maskedResult.contains(Result.success(99)))
            assert(originalResult.isEmpty)
        }

        "chained masks" in {
            val original = new IOPromise[Nothing, Int]()
            val masked1  = original.mask
            val masked2  = masked1.mask

            var originalCompleted                          = false
            var masked1Completed                           = false
            var masked2Result: Maybe[Result[Nothing, Int]] = Maybe.Empty

            original.onComplete(_ => originalCompleted = true)
            masked1.onComplete(_ => masked1Completed = true)
            masked2.onComplete(r => masked2Result = Maybe(r))

            assert(masked2.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(masked2Result.exists(_.isPanic))
            assert(!masked1Completed)
            assert(!originalCompleted)

            original.complete(Result.success(42))
            assert(masked1Completed)
        }

        "mask after completion" in {
            val original = new IOPromise[Nothing, Int]()
            original.complete(Result.success(42))

            val masked                                    = original.mask
            var maskedResult: Maybe[Result[Nothing, Int]] = Maybe.Empty
            masked.onComplete(r => maskedResult = Maybe(r))

            assert(maskedResult.contains(Result.success(42)))
        }

        "interrupt original completes masked" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask

            var originalResult: Maybe[Result[Nothing, Int]] = Maybe.Empty
            var maskedResult: Maybe[Result[Nothing, Int]]   = Maybe.Empty

            original.onComplete(r => originalResult = Maybe(r))
            masked.onComplete(r => maskedResult = Maybe(r))

            val panic = Result.Panic(new Exception("Interrupted"))
            assert(original.interrupt(panic))
            assert(originalResult == Maybe(panic))
            assert(maskedResult == Maybe(panic))
        }

        "chained masks with interrupt" in {
            val original = new IOPromise[Nothing, Int]()
            val masked1  = original.mask
            val masked2  = masked1.mask

            var originalResult: Maybe[Result[Nothing, Int]] = Maybe.Empty
            var masked1Result: Maybe[Result[Nothing, Int]]  = Maybe.Empty
            var masked2Result: Maybe[Result[Nothing, Int]]  = Maybe.Empty

            original.onComplete(r => originalResult = Maybe(r))
            masked1.onComplete(r => masked1Result = Maybe(r))
            masked2.onComplete(r => masked2Result = Maybe(r))

            assert(masked2.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(masked2Result.exists(_.isPanic))
            assert(masked1Result.isEmpty)
            assert(originalResult.isEmpty)

            original.complete(Result.success(42))

            assert(originalResult.contains(Result.success(42)))
            assert(masked1Result.contains(Result.success(42)))
            assert(masked2Result.exists(_.isPanic))
        }

        "mask interaction with become" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask
            val other    = new IOPromise[Nothing, Int]()

            var originalResult: Maybe[Result[Nothing, Int]] = Maybe.Empty
            var maskedResult: Maybe[Result[Nothing, Int]]   = Maybe.Empty
            var otherResult: Maybe[Result[Nothing, Int]]    = Maybe.Empty

            original.onComplete(r => originalResult = Maybe(r))
            masked.onComplete(r => maskedResult = Maybe(r))
            other.onComplete(r => otherResult = Maybe(r))

            assert(masked.become(other))

            other.complete(Result.success(99))

            assert(originalResult.isEmpty)
            assert(maskedResult.contains(Result.success(99)))
            assert(otherResult.contains(Result.success(99)))

            original.complete(Result.success(42))

            assert(originalResult.contains(Result.success(42)))
            assert(maskedResult.contains(Result.success(99)))
            assert(otherResult.contains(Result.success(99)))
        }

        "mask with interrupts" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask
            val other    = new IOPromise[Nothing, Int]()

            masked.interrupts(other)

            assert(masked.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(masked.block(timeout.toMillis).isPanic)
            assert(other.block(timeout.toMillis).isPanic)
            assert(!original.done())
        }
    }

    "onInterrupt" - {
        "basic onInterrupt" in {
            val p           = new IOPromise[Nothing, Int]()
            var interrupted = false
            p.onInterrupt(_ => interrupted = true)
            assert(p.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(interrupted)
        }

        "multiple onInterrupt callbacks" in {
            val p     = new IOPromise[Nothing, Int]()
            var count = 0
            p.onInterrupt(_ => count += 1)
            p.onInterrupt(_ => count += 1)
            p.onInterrupt(_ => count += 1)
            assert(p.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(count == 3)
        }

        "onInterrupt not called on normal completion" in {
            val p           = new IOPromise[Nothing, Int]()
            var interrupted = false
            p.onInterrupt(_ => interrupted = true)
            p.complete(Result.success(42))
            assert(!interrupted)
        }

        "onInterrupt with mask" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask

            var originalInterrupted = false
            var maskedInterrupted   = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked.onInterrupt(_ => maskedInterrupted = true)

            assert(masked.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(maskedInterrupted)
            assert(!originalInterrupted)
        }

        "onInterrupt with chained masks" in {
            val original = new IOPromise[Nothing, Int]()
            val masked1  = original.mask
            val masked2  = masked1.mask

            var originalInterrupted = false
            var masked1Interrupted  = false
            var masked2Interrupted  = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked1.onInterrupt(_ => masked1Interrupted = true)
            masked2.onInterrupt(_ => masked2Interrupted = true)

            assert(masked2.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(masked2Interrupted)
            assert(!masked1Interrupted)
            assert(!originalInterrupted)
        }

        "onInterrupt with become" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            var p1Interrupted = false
            var p2Interrupted = false

            p1.onInterrupt(_ => p1Interrupted = true)
            p2.onInterrupt(_ => p2Interrupted = true)

            assert(p1.become(p2))
            assert(p2.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(p1Interrupted)
            assert(p2Interrupted)
        }

        "onInterrupt with mask and become" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask
            val other    = new IOPromise[Nothing, Int]()

            var originalInterrupted = false
            var maskedInterrupted   = false
            var otherInterrupted    = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked.onInterrupt(_ => maskedInterrupted = true)
            other.onInterrupt(_ => otherInterrupted = true)

            assert(masked.become(other))
            assert(other.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(!originalInterrupted)
            assert(maskedInterrupted)
            assert(otherInterrupted)
        }

        "onInterrupt with interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            var p1Interrupted = false
            var p2Interrupted = false

            p1.onInterrupt(_ => p1Interrupted = true)
            p2.onInterrupt(_ => p2Interrupted = true)

            p1.interrupts(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(p1Interrupted)
            assert(p2Interrupted)
        }

        "onInterrupt with mask and interrupts" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask
            val other    = new IOPromise[Nothing, Int]()

            var originalInterrupted = false
            var maskedInterrupted   = false
            var otherInterrupted    = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked.onInterrupt(_ => maskedInterrupted = true)
            other.onInterrupt(_ => otherInterrupted = true)

            masked.interrupts(other)

            assert(masked.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(!originalInterrupted)
            assert(maskedInterrupted)
            assert(otherInterrupted)
        }
    }

    "edge cases" - {
        "completing a promise during onComplete callback" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                require(p2.complete(Result.success(42)))
            }

            p1.complete(Result.success(1))
            assert(p2.block(timeout.toMillis) == Result.success(42))
        }

        "interrupting a promise during onComplete callback" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                require(p2.interrupt(Result.Panic(new Exception("Interrupted during callback"))))
            }

            p1.complete(Result.success(1))
            assert(p2.block(timeout.toMillis).isPanic)
        }

        "becoming another promise during onComplete callback" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                require(p2.become(p3))
            }

            p1.complete(Result.success(1))
            p3.complete(Result.success(42))
            assert(p2.block(timeout.toMillis) == Result.success(42))
        }

        "complex chaining with interrupts and masks" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = p1.mask
            val p3 = new IOPromise[Nothing, Int]()
            val p4 = p3.mask

            p2.become(p4)
            p1.interrupts(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p1.block(timeout.toMillis).isPanic)
            assert(p2.block(timeout.toMillis).isPanic)
            assert(p3.block(timeout.toMillis).isPanic)
            assert(p4.block(timeout.toMillis).isPanic)
        }

        "nested onComplete callbacks" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                p2.onComplete { _ =>
                    require(p3.complete(Result.success(42)))
                }
                require(p2.complete(Result.success(2)))
            }

            p1.complete(Result.success(1))
            assert(p3.block(timeout.toMillis) == Result.success(42))
        }

        "completing a promise with a failed result during onInterrupt" in {
            val p1 = new IOPromise[Exception, Int]()
            val p2 = new IOPromise[Exception, Int]()

            p1.onInterrupt { _ =>
                require(p2.complete(Result.fail(new Exception("Failed during interrupt"))))
            }

            p1.interrupt(Result.Panic(new Exception("Interrupted")))
            assert(p2.block(timeout.toMillis).isFail)
        }
    }

end IOPromiseTest
