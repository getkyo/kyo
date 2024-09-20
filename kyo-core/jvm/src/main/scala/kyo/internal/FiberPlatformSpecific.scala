package kyo.internal

import java.util.concurrent.CompletionStage
import kyo.*
import kyo.scheduler.IOPromise

trait FiberPlatformSpecific:
    def fromCompletionStage[A](cs: CompletionStage[A])(using Frame): A < Async =
        fromCompletionStageFiber(cs).map(_.get)

    def fromCompletionStageFiber[A](cs: CompletionStage[A])(using Frame): Fiber[Nothing, A] < IO =
        IO {
            val p = new IOPromise[Nothing, A]()
            cs.whenComplete { (success, error) =>
                if error == null then p.completeUnit(Result.succeed(success))
                else p.completeUnit(Result.panic(error))
            }
            Fiber.initUnsafe(p)
        }
end FiberPlatformSpecific
