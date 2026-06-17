package kyo.internal

import java.util.concurrent.CompletionStage
import kyo.*

private[kyo] class AsyncPlatformSpecific:

    def fromFuture[A](cs: CompletionStage[A])(using Frame): A < Async =
        fromCompletionStage(cs)

    def fromCompletionStage[A](cs: CompletionStage[A])(using Frame): A < Async =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[A, Any]()
            cs.whenComplete { (success, error) =>
                if error == null then p.completeDiscard(Result.succeed(success))
                else p.completeDiscard(Result.panic(error))
            }
            p.safe.get
        }
end AsyncPlatformSpecific
