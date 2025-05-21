package kyo.internal

import java.util.concurrent.*
import kyo.*

class AsyncPlatformSpecificTest extends Test:

    "completionStage to Fiber" in runJVM {
        val cf = new CompletableFuture[Int]()
        cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
        val res = Async.fromCompletionStage(cf)
        res.map(v => assert(v == 42))
    }

    "fromFuture with CompletionStage" in runJVM {
        val cf = new CompletableFuture[Int]()
        cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
        val res = Async.fromFuture(cf)
        res.map(v => assert(v == 42))
    }
end AsyncPlatformSpecificTest
