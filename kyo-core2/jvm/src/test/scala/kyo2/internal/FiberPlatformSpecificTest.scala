package kyo2.internal

import java.util.concurrent.*
import kyo2.*

class FiberPlatformSpecificTest extends Test:

    "completionStage to Fiber" in runJVM {
        val cf = new CompletableFuture[Int]()
        cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
        val res = Fiber.fromCompletionStage(cf)
        res.map(v => assert(v == 42))
    }
end FiberPlatformSpecificTest
