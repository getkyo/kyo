package kyo.internal

import java.util.concurrent.CompletionStage
import kyo.*

trait FiberPlatformSpecific:
    def fromCompletionStage[A](cs: CompletionStage[A])(using Frame): A < Async =
        fromCompletionStageFiber(cs).map(_.get)

    def fromCompletionStageFiber[A](cs: CompletionStage[A])(using Frame): Fiber[Nothing, A] < IO =
        IO.Unsafe {
            val p = Promise.Unsafe.init[Nothing, A]()
            cs.whenComplete { (success, error) =>
                if error == null then p.completeDiscard(Result.succeed(success))
                else p.completeDiscard(Result.panic(error))
            }
            p.safe
        }
end FiberPlatformSpecific
