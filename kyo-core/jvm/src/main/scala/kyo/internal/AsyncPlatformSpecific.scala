package kyo.internal

import java.util.concurrent.CompletionStage
import kyo.*

trait AsyncPlatformSpecific:
    def fromCompletionStage[A](cs: CompletionStage[A])(using Frame): A < Async =
        IO.Unsafe {
            val p = Promise.Unsafe.init[Nothing, A]()
            cs.whenComplete { (success, error) =>
                if error == null then p.completeDiscard(Result.succeed(success))
                else p.completeDiscard(Result.panic(error))
            }
            p.safe.get
        }
end AsyncPlatformSpecific
