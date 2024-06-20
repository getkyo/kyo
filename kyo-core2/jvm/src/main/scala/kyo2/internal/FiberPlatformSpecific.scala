package kyo2.internal

import java.util.concurrent.CompletionStage
import kyo2.*
import kyo2.scheduler.IOPromise
import kyo2.scheduler.IOTask

trait FiberPlatformSpecific:
    def fromCompletionStage[T](cs: CompletionStage[T]): T < Async =
        fromCompletionStageFiber(cs).map(_.get)

    def fromCompletionStageFiber[T](cs: CompletionStage[T]): Fiber[Nothing, T] < IO =
        IO {
            val p = new IOPromise[Nothing, T]()
            cs.whenComplete { (success, error) =>
                if error == null then p.completeUnit(Result.success(success))
                else p.completeUnit(Result.panic(error))
            }
            Fiber.unsafe(p)
        }
end FiberPlatformSpecific
