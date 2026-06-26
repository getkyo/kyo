package kyo.internal

import java.util.concurrent.CompletableFuture
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

    /** Converts a CompletableFuture to an asynchronous computation, cancelling the future when the
      * calling fiber is interrupted and surfacing an exceptional completion as a typed `Abort[Throwable]`.
      *
      * Unlike [[fromCompletionStage]] (which abandons the stage on interruption and panics on an
      * exceptional completion), a CompletableFuture is cancellable and carries a recoverable failure:
      * interrupting the fiber runs `cancel(true)` on the underlying future, and an exceptional
      * completion aborts with the future's `Throwable` rather than panicking, so the caller can recover it.
      *
      * @param cf
      *   The CompletableFuture to convert
      * @return
      *   An asynchronous computation completing with the future's result, cancelling the future on
      *   interruption and aborting with the future's exception on exceptional completion
      */
    def fromCompletableFuture[A](cf: CompletableFuture[A])(using Frame): A < (Async & Abort[Throwable]) =
        Sync.ensure(Sync.defer(discard(cf.cancel(true)))) {
            Sync.Unsafe.defer {
                val p = Promise.Unsafe.init[A, Abort[Throwable]]()
                cf.whenComplete { (value, error) =>
                    if error == null then p.completeDiscard(Result.succeed(value))
                    else p.completeDiscard(Result.fail(error))
                }
                p.safe.get
            }
        }
end AsyncPlatformSpecific
