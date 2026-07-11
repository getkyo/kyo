package kyo.internal

import java.util.concurrent.*
import kyo.*

class AsyncPlatformSpecificTest extends kyo.test.Test[Any]:

    "success" - {
        "stage" in {
            val cf = new CompletableFuture[Int]()
            cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
            val res = Async.fromCompletionStage(cf)
            res.map(v => assert(v == 42))
        }

        "future" in {
            val cf = new CompletableFuture[Int]()
            cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
            val res = Async.fromFuture(cf)
            res.map(v => assert(v == 42))
        }
    }

    "exception" - {
        "stage" in {
            val cf            = new CompletableFuture[Int]()
            val testException = new RuntimeException("Test exception")
            cf.completeExceptionally(testException)
            val res = Async.fromCompletionStage(cf)
            Abort.run(res).map {
                case Result.Panic(ex) => assert(ex == testException)
                case _                => fail("Expected panic but got success")
            }
        }

        "future" in {
            val cf            = new CompletableFuture[Int]()
            val testException = new RuntimeException("Test exception")
            cf.completeExceptionally(testException)
            val res = Async.fromFuture(cf)
            Abort.run(res).map {
                case Result.Panic(ex) => assert(ex == testException)
                case _                => fail("Expected panic but got success")
            }
        }
    }

    "fromCompletableFuture" - {
        "completes with the future's value" in {
            val cf = CompletableFuture.completedFuture(42)
            Async.fromCompletableFuture(cf).map(v => assert(v == 42))
        }

        "surfaces an exceptional completion as Abort[Throwable], not a panic" in {
            val cf = new CompletableFuture[Int]()
            cf.completeExceptionally(new RuntimeException("boom"))
            Abort.run[Throwable](Async.fromCompletableFuture(cf)).map {
                case Result.Failure(ex) => assert(ex.getMessage == "boom")
                case other              => fail(s"Expected Abort failure with message 'boom', got: $other")
            }
        }

        "cancels the underlying future when the calling fiber is interrupted" in {
            val cf = new CompletableFuture[Int]() // never completes on its own
            for
                fiber <- Fiber.initUnscoped(Async.fromCompletableFuture(cf))
                _     <- Async.sleep(50.millis) // let the fiber suspend so the cancel finalizer is registered
                _     <- fiber.interrupt
                cancelled <- Loop.indexed { attempt =>
                    if cf.isCancelled then Loop.done(true)
                    else if attempt >= 200 then Loop.done(false)
                    else Async.sleep(10.millis).andThen(Loop.continue)
                }
            yield assert(cancelled, "interrupting the fiber should cancel the underlying CompletableFuture")
            end for
        }
    }
end AsyncPlatformSpecificTest
