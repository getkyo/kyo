package kyoTest

import java.util.concurrent.*
import kyo.*
import kyo.Fibers

class completionStageTest extends KyoTest:
    "completionStage to Fiber" in runJVM {
        val cf = new CompletableFuture[Int]()
        cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
        val res = Fibers.fromCompletionStage(cf)
        res.map(v => assert(v == 42))
    }
end completionStageTest
