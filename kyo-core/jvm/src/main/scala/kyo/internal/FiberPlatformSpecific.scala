package kyo.internal

import java.util.concurrent.CompletionStage
import kyo.*
import kyo.scheduler.IOPromise
import kyo.scheduler.IOTask

trait FiberPlatformSpecific:
    def fromCompletionStage[A](cs: CompletionStage[A]): A < Async =
        fromCompletionStageFiber(cs).map(_.get)

    def fromCompletionStageFiber[A](cs: CompletionStage[A]): Fiber[Nothing, A] < IO =
        IO {
            val p = new IOPromise[Nothing, A]()
            cs.whenComplete { (success, error) =>
                if error == null then p.completeUnit(Result.success(success))
                else p.completeUnit(Result.panic(error))
            }
            Fiber.initUnsafe(p)
        }
end FiberPlatformSpecific
