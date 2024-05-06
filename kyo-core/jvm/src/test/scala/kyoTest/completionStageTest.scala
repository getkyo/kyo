package kyoTest

import java.util.concurrent.*
import kyo.*
import scala.util.*

class completionStageTest extends KyoTest:
    "completionStage" - {
        "success to Fiber" in runJVM {
            val cf = new CompletableFuture[Int]()
            cf.completeOnTimeout(42, 1, TimeUnit.MICROSECONDS)
            val res = Fibers.fromCompletionStage(cf)
            res.map(v => assert(v == 42))
        }

        "failure to Fiber" in runJVM {
            val cf  = new CompletableFuture[Int]()
            val err = new RuntimeException("error")
            cf.completeExceptionally(err)
            val res   = Fibers.fromCompletionStage(cf)
            val fiber = Fibers.run(res)
            fiber.map(_.getTry.map {
                case Failure(e) => assert(e == err)
                case Success(_) => assert(false)
            })
        }

        "locals propagate" in runJVM {
            val cf = new CompletableFuture[Int]()
            cf.complete(42)
            val myLocal = Locals.init(99)
            myLocal.let(100) {
                val fiberValue = Fibers.fromCompletionStage(cf)
                val localValue = myLocal.get
                for
                    fv <- fiberValue
                    lv <- localValue
                yield assert(fv == 42 && lv == 100)
                end for
            }
        }
    }
end completionStageTest
