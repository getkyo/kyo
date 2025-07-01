package kyo.scheduler

import kyo.*
import org.scalatest.compatible.Assertion
import scala.annotation.tailrec

class SyncPromiseTest extends Test:

    def deadline(after: Duration = timeout) =
        import AllowUnsafe.embrace.danger
        Clock.live.unsafe.deadline(after)

    "complete" - {
        "success" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.complete(Result.succeed(1)))
            assert(p.done())
            assert(p.block(deadline()) == Result.succeed(1))
        }

        "failure" in {
            val ex = new Exception("Test exception")
            val p  = new IOPromise[Exception, Int]()
            assert(p.complete(Result.fail(ex)))
            assert(p.done())
            assert(p.block(deadline()) == Result.fail(ex))
        }

        "complete twice" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.complete(Result.succeed(1)))
            assert(!p.complete(Result.succeed(2)))
            assert(p.done())
            assert(p.block(deadline()) == Result.succeed(1))
        }

        "complete with null value" in {
            val p = new IOPromise[Nothing, String]()
            assert(p.complete(Result.succeed(null)))
            assert(p.done())
            assert(p.block(deadline()) == Result.succeed(null))
        }

        "complete with exception" in {
            val p  = new IOPromise[Throwable, Int]()
            val ex = new RuntimeException("Test")
            assert(p.complete(Result.fail(ex)))
            assert(p.done())
            assert(p.block(deadline()) == Result.fail(ex))
        }

        "wrong type" in {
            val p = new IOPromise[String, Int]()
            typeCheckFailure("p.complete(Result.unit)")("Required: kyo.Result[String, Int]")
            typeCheckFailure("p.complete(Result.fail(1))")("Required: String")
        }
    }

    "completeDiscard" - {
        "success" in {
            val p = new IOPromise[Nothing, Int]()
            p.completeDiscard(Result.succeed(1))
            assert(p.block(deadline()) == Result.succeed(1))
        }

        "completeDiscard with failure" in {
            val p  = new IOPromise[Exception, Int]()
            val ex = new Exception("Test exception")
            p.completeDiscard(Result.fail(ex))
            assert(p.block(deadline()) == Result.fail(ex))
        }
    }

    "become" - {
        "success" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            assert(p2.complete(Result.succeed(42)))
            assert(p1.become(p2))
            assert(p1.done())
            assert(p1.block(deadline()) == Result.succeed(42))
        }

        "failure" in {
            val ex = new Exception("Test exception")
            val p1 = new IOPromise[Exception, Int]()
            val p2 = new IOPromise[Exception, Int]()
            assert(p2.complete(Result.fail(ex)))
            assert(p1.become(p2))
            assert(p1.done())
            assert(p1.block(deadline()) == Result.fail(ex))
        }

        "already completed" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            assert(p1.complete(Result.succeed(42)))
            assert(p2.complete(Result.succeed(99)))
            assert(!p1.become(p2))
            assert(p1.block(deadline()) == Result.succeed(42))
        }

        "become with incomplete promise" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            assert(p1.become(p2))
            p2.complete(Result.succeed(42))
            assert(p1.done())
            assert(p1.block(deadline()) == Result.succeed(42))
        }

        "become with chain of promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()
            p1.become(p2)
            p2.become(p3)
            p3.complete(Result.succeed(42))
            val v = p1.block(deadline())
            assert(v == Result.succeed(42))
        }
    }

    "becomeDiscard" - {
        "success" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p2.complete(Result.succeed(42))
            p1.becomeDiscard(p2)
            val v = p1.block(deadline())
            assert(v == Result.succeed(42))
        }

        "becomeDiscard with incomplete promise" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p1.becomeDiscard(p2)
            p2.complete(Result.succeed(42))
            val v = p1.block(deadline())
            assert(v == Result.succeed(42))
        }
    }

    "interrupt" - {
        "interrupt" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p.block(deadline()).isPanic)
        }

        "interrupt completed promise" in {
            val p = new IOPromise[Nothing, Int]()
            p.complete(Result.succeed(42))
            assert(!p.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p.block(deadline()) == Result.succeed(42))
        }

        "interrupt chain of promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()
            p1.become(p2)
            p2.become(p3)
            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p3.block(deadline()).isPanic)
        }

        "interruptDiscard" in {
            val p = new IOPromise[Nothing, Int]()
            p.interruptDiscard(Result.Panic(new Exception("Interrupted")))
            assert(p.block(deadline()).isPanic)
        }
    }

    "onComplete" - {
        "onComplete" in {
            val p      = new IOPromise[Nothing, Int]()
            var called = false
            p.onComplete(_ => called = true)
            p.complete(Result.succeed(42))
            assert(called)
        }

        "onComplete with multiple callbacks" in {
            val p       = new IOPromise[Nothing, Int]()
            var called1 = false
            var called2 = false
            p.onComplete(_ => called1 = true)
            p.onComplete(_ => called2 = true)
            p.complete(Result.succeed(42))
            assert(called1 && called2)
        }

        "onComplete with exception in callback" in {
            val p      = new IOPromise[Nothing, Int]()
            var called = false
            p.onComplete(_ => throw new RuntimeException("Test"))
            p.onComplete(_ => called = true)
            p.complete(Result.succeed(42))
            assert(called)
        }
    }

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
    }

    "stack safety" - {
        "deeply nested become calls" in {
            def createNestedPromises(depth: Int): IOPromise[Nothing, Int] =
                @tailrec def loop(currentDepth: Int, promise: IOPromise[Nothing, Int]): IOPromise[Nothing, Int] =
                    if currentDepth == 0 then
                        promise.complete(Result.succeed(42))
                        promise
                    else
                        val newPromise = new IOPromise[Nothing, Int]()
                        promise.become(newPromise)
                        loop(currentDepth - 1, newPromise)
                loop(depth, new IOPromise[Nothing, Int]())
            end createNestedPromises

            val deeplyNested = createNestedPromises(10000)
            assert(deeplyNested.block(deadline()) == Result.succeed(42))
        }

        "long chain of onComplete callbacks" in {
            val p     = new IOPromise[Nothing, Int]()
            var count = 0
            def addCallback(remaining: Int): Unit =
                if remaining > 0 then
                    p.onComplete(_ => count += 1)
                    addCallback(remaining - 1)
            addCallback(10000)
            p.complete(Result.succeed(42))
            assert(count == 10000)
        }
    }

    "interrupts" - {
        "interrupt linked promise via interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))

            assert(p1.block(deadline()).isPanic)
            assert(p2.block(deadline()).isPanic)
        }

        "interrupt linked chain promises via interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p2.interrupts(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))

            assert(p1.block(deadline()).isPanic)
            assert(p2.block(deadline()).isPanic)
            assert(p3.block(deadline()).isPanic)
        }

        "interrupt multiple promises via single interrupts" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p1.interrupts(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))

            assert(p1.block(deadline()).isPanic)
            assert(p2.block(deadline()).isPanic)
            assert(p3.block(deadline()).isPanic)
        }

        "ensure interruptions do not propagate without linking" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted p1"))))
            assert(p1.block(deadline()).isPanic)
            assert(!p2.done())
        }
    }

    "mask" - {
        "doesn't propagate interrupts to parent" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()

            var originalCompleted                         = false
            var maskedResult: Maybe[Result[Nothing, Int]] = Absent

            original.onComplete(_ => originalCompleted = true)
            masked.onComplete(r => maskedResult = Maybe(r))

            assert(!masked.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(maskedResult.isEmpty)
            assert(!originalCompleted)
        }

        "completes when original completes" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()

            var maskedResult: Maybe[Result[Nothing, Int]] = Absent
            masked.onComplete(r => maskedResult = Maybe(r))

            original.complete(Result.succeed(42))
            assert(maskedResult.contains(Result.succeed(42)))
        }

        "propagates failure" in {
            val original = new IOPromise[Exception, Int]()
            val masked   = original.mask()

            var maskedResult: Maybe[Result[Exception, Int]] = Absent
            masked.onComplete(r => maskedResult = Maybe(r))

            val ex = new Exception("Test exception")
            original.complete(Result.fail(ex))
            assert(maskedResult.contains(Result.fail(ex)))
        }

        "allows completion of masked promise" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()

            var originalResult: Maybe[Result[Nothing, Int]] = Absent
            var maskedResult: Maybe[Result[Nothing, Int]]   = Absent

            original.onComplete(r => originalResult = Maybe(r))
            masked.onComplete(r => maskedResult = Maybe(r))

            masked.complete(Result.succeed(99))
            assert(maskedResult.contains(Result.succeed(99)))
            assert(originalResult.isEmpty)
        }

        "chained masks" in {
            val original = new IOPromise[Nothing, Int]()
            val masked1  = original.mask()
            val masked2  = masked1.mask()

            var originalCompleted                          = false
            var masked1Completed                           = false
            var masked2Result: Maybe[Result[Nothing, Int]] = Absent

            original.onComplete(_ => originalCompleted = true)
            masked1.onComplete(_ => masked1Completed = true)
            masked2.onComplete(r => masked2Result = Maybe(r))

            assert(!masked2.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(masked2Result.isEmpty)
            assert(!masked1Completed)
            assert(!originalCompleted)

            original.complete(Result.succeed(42))
            assert(masked1Completed)
        }

        "mask after completion" in {
            val original = new IOPromise[Nothing, Int]()
            original.complete(Result.succeed(42))

            val masked                                    = original.mask()
            var maskedResult: Maybe[Result[Nothing, Int]] = Absent
            masked.onComplete(r => maskedResult = Maybe(r))

            assert(maskedResult.contains(Result.succeed(42)))
        }

        "interrupt original completes masked" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()

            var originalResult: Maybe[Result[Nothing, Int]] = Absent
            var maskedResult: Maybe[Result[Nothing, Int]]   = Absent

            original.onComplete(r => originalResult = Maybe(r))
            masked.onComplete(r => maskedResult = Maybe(r))

            val panic = Result.Panic(new Exception("Interrupted"))
            assert(original.interrupt(panic))
            assert(originalResult == Maybe(panic))
            assert(maskedResult == Maybe(panic))
        }

        "chained masks with interrupt" in {
            val original = new IOPromise[Nothing, Int]()
            val masked1  = original.mask()
            val masked2  = masked1.mask()

            var originalResult: Maybe[Result[Nothing, Int]] = Absent
            var masked1Result: Maybe[Result[Nothing, Int]]  = Absent
            var masked2Result: Maybe[Result[Nothing, Int]]  = Absent

            original.onComplete(r => originalResult = Maybe(r))
            masked1.onComplete(r => masked1Result = Maybe(r))
            masked2.onComplete(r => masked2Result = Maybe(r))

            assert(!masked2.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(masked2Result.isEmpty)
            assert(masked1Result.isEmpty)
            assert(originalResult.isEmpty)

            original.complete(Result.succeed(42))

            assert(originalResult.contains(Result.succeed(42)))
            assert(masked1Result.contains(Result.succeed(42)))
            assert(masked2Result.contains(Result.succeed(42)))
        }

        "mask interaction with become" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()
            val other    = new IOPromise[Nothing, Int]()

            var originalResult: Maybe[Result[Nothing, Int]] = Absent
            var maskedResult: Maybe[Result[Nothing, Int]]   = Absent
            var otherResult: Maybe[Result[Nothing, Int]]    = Absent

            original.onComplete(r => originalResult = Maybe(r))
            masked.onComplete(r => maskedResult = Maybe(r))
            other.onComplete(r => otherResult = Maybe(r))

            assert(masked.become(other))

            other.complete(Result.succeed(99))

            assert(originalResult.isEmpty)
            assert(maskedResult.contains(Result.succeed(99)))
            assert(otherResult.contains(Result.succeed(99)))

            original.complete(Result.succeed(42))

            assert(originalResult.contains(Result.succeed(42)))
            assert(maskedResult.contains(Result.succeed(99)))
            assert(otherResult.contains(Result.succeed(99)))
        }

        "mask with interrupts" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()
            val other    = new IOPromise[Nothing, Int]()

            masked.interrupts(other)

            assert(!masked.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(!masked.done())
            assert(!other.done())
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
            p.complete(Result.succeed(42))
            assert(!interrupted)
        }

        "onInterrupt with mask" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()

            var originalInterrupted = false
            var maskedInterrupted   = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked.onInterrupt(_ => maskedInterrupted = true)

            assert(!masked.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(!maskedInterrupted)
            assert(!originalInterrupted)
        }

        "onInterrupt with chained masks" in {
            val original = new IOPromise[Nothing, Int]()
            val masked1  = original.mask()
            val masked2  = masked1.mask()

            var originalInterrupted = false
            var masked1Interrupted  = false
            var masked2Interrupted  = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked1.onInterrupt(_ => masked1Interrupted = true)
            masked2.onInterrupt(_ => masked2Interrupted = true)

            assert(!masked2.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(!masked2Interrupted)
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
            val masked   = original.mask()
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
            val masked   = original.mask()
            val other    = new IOPromise[Nothing, Int]()

            var originalInterrupted = false
            var maskedInterrupted   = false
            var otherInterrupted    = false

            original.onInterrupt(_ => originalInterrupted = true)
            masked.onInterrupt(_ => maskedInterrupted = true)
            other.onInterrupt(_ => otherInterrupted = true)

            masked.interrupts(other)

            assert(!masked.interrupt(Result.Panic(new Exception("Interrupted"))))

            assert(!originalInterrupted)
            assert(!maskedInterrupted)
            assert(!otherInterrupted)
        }
    }

    "edge cases" - {
        "completing a promise during onComplete callback" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                require(p2.complete(Result.succeed(42)))
            }

            p1.complete(Result.succeed(1))
            assert(p2.block(deadline()) == Result.succeed(42))
        }

        "interrupting a promise during onComplete callback" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                require(p2.interrupt(Result.Panic(new Exception("Interrupted during callback"))))
            }

            p1.complete(Result.succeed(1))
            assert(p2.block(deadline()).isPanic)
        }

        "becoming another promise during onComplete callback" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                require(p2.become(p3))
            }

            p1.complete(Result.succeed(1))
            p3.complete(Result.succeed(42))
            assert(p2.block(deadline()) == Result.succeed(42))
        }

        "complex chaining with interrupts and masks" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = p1.mask()
            val p3 = new IOPromise[Nothing, Int]()
            val p4 = p3.mask()

            p2.become(p4)
            p1.interrupts(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p1.block(deadline()).isPanic)
            assert(p2.block(deadline()).isPanic)
            assert(p3.block(deadline()).isPanic)
            assert(p4.block(deadline()).isPanic)
        }

        "nested onComplete callbacks" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.onComplete { _ =>
                p2.onComplete { _ =>
                    require(p3.complete(Result.succeed(42)))
                }
                require(p2.complete(Result.succeed(2)))
            }

            p1.complete(Result.succeed(1))
            assert(p3.block(deadline()) == Result.succeed(42))
        }

        "completing a promise with a failed result during onInterrupt" in {
            val p1 = new IOPromise[Exception, Int]()
            val p2 = new IOPromise[Exception, Int]()

            p1.onInterrupt { _ =>
                require(p2.complete(Result.fail(new Exception("Failed during interrupt"))))
            }

            p1.interrupt(Result.Panic(new Exception("Interrupted")))
            assert(p2.block(deadline()).isFailure)
        }
    }

    "exception handling" - {
        val ex = new RuntimeException("test exception")

        "multiple callbacks with exceptions" in {
            val p                     = new IOPromise[Nothing, Int]()
            var firstCallbackExecuted = false
            var lastCallbackExecuted  = false

            p.onComplete(_ => firstCallbackExecuted = true)
            p.onComplete(_ => throw ex)
            p.onComplete(_ => throw ex)
            p.onComplete(_ => lastCallbackExecuted = true)

            p.complete(Result.succeed(42))
            assert(firstCallbackExecuted)
            assert(lastCallbackExecuted)
        }

        "exceptions in onInterrupt callbacks" in {
            val p                     = new IOPromise[Nothing, Int]()
            var firstCallbackExecuted = false
            var lastCallbackExecuted  = false

            p.onComplete(_ => firstCallbackExecuted = true)
            p.onInterrupt(_ => throw ex)
            p.onInterrupt(_ => lastCallbackExecuted = true)

            p.interrupt(Result.Panic(new Exception("Test interrupt")))
            assert(firstCallbackExecuted)
            assert(lastCallbackExecuted)
        }

        "nested callbacks with exceptions" in {
            val p1                    = new IOPromise[Nothing, Int]()
            val p2                    = new IOPromise[Nothing, Int]()
            var innerCallbackExecuted = false

            p1.onComplete { _ =>
                throw ex
                discard(p2.complete(Result.succeed(42)))
            }

            p2.onComplete(_ => innerCallbackExecuted = true)

            p1.complete(Result.succeed(1))
            assert(!innerCallbackExecuted)

            p2.complete(Result.succeed(42))
            assert(innerCallbackExecuted)
        }

        "exceptions during promise chaining" in {
            val p1             = new IOPromise[Nothing, Int]()
            val p2             = new IOPromise[Nothing, Int]()
            var chainCompleted = false

            p1.onComplete { _ =>
                p2.onComplete(_ => throw new RuntimeException("test exception"))
                p2.onComplete(_ => chainCompleted = true)
            }

            p1.complete(Result.succeed(1))
            p2.complete(Result.succeed(2))

            assert(chainCompleted)
        }

        "exceptions with masked promises" in {
            val original               = new IOPromise[Nothing, Int]()
            val masked                 = original.mask()
            var maskedCallbackExecuted = false

            masked.onComplete(_ => throw ex)
            masked.onComplete(_ => maskedCallbackExecuted = true)

            original.complete(Result.succeed(42))
            assert(maskedCallbackExecuted)
        }
    }

    "removeInterrupt" - {
        "basic removeInterrupt" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p1.removeInterrupt(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p1.block(deadline()).isPanic)
            assert(!p2.done())
        }

        "removeInterrupt with multiple linked promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p1.interrupts(p3)
            p1.removeInterrupt(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p1.block(deadline()).isPanic)
            assert(!p2.done())
            assert(p3.block(deadline()).isPanic)
        }

        "removeInterrupt with chain of promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p2.interrupts(p3)
            p1.removeInterrupt(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p1.block(deadline()).isPanic)
            assert(!p2.done())
            assert(!p3.done())
        }

        "removeInterrupt with non-linked promise" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p1.removeInterrupt(p3)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p1.block(deadline()).isPanic)
            assert(p2.block(deadline()).isPanic)
        }

        "removeInterrupt after completion" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.interrupts(p2)
            p1.complete(Result.succeed(42))
            p1.removeInterrupt(p2)

            assert(p1.block(deadline()) == Result.succeed(42))
            assert(!p2.done())
        }

        "removeInterrupt with become" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            val p3 = new IOPromise[Nothing, Int]()

            p1.interrupts(p3)
            p1.become(p2)
            p1.removeInterrupt(p3)

            assert(p2.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(p2.block(deadline()).isPanic)
            assert(p3.done())
        }

        "removeInterrupt with mask" in {
            val original = new IOPromise[Nothing, Int]()
            val masked   = original.mask()
            val other    = new IOPromise[Nothing, Int]()

            masked.interrupts(other)
            masked.removeInterrupt(other)

            assert(!masked.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(!other.done())
        }

        "removeInterrupt preserves other callbacks" in {
            val p1               = new IOPromise[Nothing, Int]()
            val p2               = new IOPromise[Nothing, Int]()
            var callbackExecuted = false

            p1.interrupts(p2)
            p1.onComplete(_ => callbackExecuted = true)
            p1.removeInterrupt(p2)

            p1.complete(Result.succeed(42))
            assert(callbackExecuted)
            assert(!p2.done())
        }

        "removeInterrupt with onInterrupt callbacks" in {
            val p1                        = new IOPromise[Nothing, Int]()
            val p2                        = new IOPromise[Nothing, Int]()
            var interruptCallbackExecuted = false

            p1.interrupts(p2)
            p1.onInterrupt(_ => interruptCallbackExecuted = true)
            p1.removeInterrupt(p2)

            assert(p1.interrupt(Result.Panic(new Exception("Interrupted"))))
            assert(interruptCallbackExecuted)
            assert(!p2.done())
        }
    }

    "poll" - {
        "returns Absent for pending promise" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.poll().isEmpty)
        }

        "returns Present with result for completed promise" in {
            val p = new IOPromise[Nothing, Int]()
            p.complete(Result.succeed(42))
            assert(p.poll() == Maybe(Result.succeed(42)))
        }

        "returns Present with failure for failed promise" in {
            val ex = new Exception("Test exception")
            val p  = new IOPromise[Exception, Int]()
            p.complete(Result.fail(ex))
            assert(p.poll() == Maybe(Result.fail(ex)))
        }

        "returns Present with panic for interrupted promise" in {
            val p     = new IOPromise[Nothing, Int]()
            val panic = Result.Panic(new Exception("Interrupted"))
            p.interrupt(panic)
            assert(p.poll() == Maybe(panic))
        }

        "returns Absent for linked promise that is pending" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p1.become(p2)
            assert(p1.poll().isEmpty)
        }

        "returns Present for linked promise that is completed" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p1.become(p2)
            p2.complete(Result.succeed(42))
            assert(p1.poll() == Maybe(Result.succeed(42)))
        }
    }

    "waiters" - {
        "returns 0 for new promise" in {
            val p = new IOPromise[Nothing, Int]()
            assert(p.waiters() == 0)
        }

        "returns 1 after adding onComplete callback" in {
            val p = new IOPromise[Nothing, Int]()
            p.onComplete(_ => ())
            assert(p.waiters() == 1)
        }

        "returns 1 after adding onInterrupt callback" in {
            val p = new IOPromise[Nothing, Int]()
            p.onInterrupt(_ => ())
            assert(p.waiters() == 1)
        }

        "returns 1 after adding interrupts link" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()
            p1.interrupts(p2)
            assert(p1.waiters() == 1)
        }

        "returns correct count after multiple callbacks" in {
            val p = new IOPromise[Nothing, Int]()
            p.onComplete(_ => ())
            p.onComplete(_ => ())
            p.onInterrupt(_ => ())
            assert(p.waiters() == 3)
        }

        "returns 0 after completion" in {
            val p = new IOPromise[Nothing, Int]()
            p.onComplete(_ => ())
            p.onComplete(_ => ())
            p.complete(Result.succeed(42))
            assert(p.waiters() == 0)
        }

        "returns correct count for linked promises" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.onComplete(_ => ())
            p2.onComplete(_ => ())
            p2.onComplete(_ => ())

            p1.become(p2)

            assert(p1.waiters() == 3)
        }

        "returns 0 for linked promises after completion" in {
            val p1 = new IOPromise[Nothing, Int]()
            val p2 = new IOPromise[Nothing, Int]()

            p1.onComplete(_ => ())
            p2.onComplete(_ => ())

            p1.become(p2)
            p2.complete(Result.succeed(42))

            assert(p1.waiters() == 0)
        }
    }

end SyncPromiseTest
