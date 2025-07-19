package kyo.internal

import java.util.concurrent.*
import kyo.*

class AsyncPlatformSpecificTest extends Test:

    "success" - {
        "stage" in run {
            val cf = new CompletableFuture[Int]()
            cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
            val res = Async.fromCompletionStage(cf)
            res.map(v => assert(v == 42))
        }

        "future" in run {
            val cf = new CompletableFuture[Int]()
            cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
            val res = Async.fromFuture(cf)
            res.map(v => assert(v == 42))
        }
    }

    "exception" - {
        "stage" in run {
            val cf            = new CompletableFuture[Int]()
            val testException = new RuntimeException("Test exception")
            cf.completeExceptionally(testException)
            val res = Async.fromCompletionStage(cf)
            Abort.run(res).map {
                case Result.Panic(ex) => assert(ex == testException)
                case _                => fail("Expected panic but got success")
            }
        }

        "future" in run {
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
end AsyncPlatformSpecificTest
