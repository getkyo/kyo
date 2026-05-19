package kyo.compat

import cats.effect.IO
import java.util.concurrent.CompletionStage

/** JVM-only. CIO interruption propagates back to the source `CompletionStage` via `cf.cancel(false)` attached as an `onCancel` finalizer.
  */
object CompatFromCompletionStage:

    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.lift {
            IO.delay(cs.toCompletableFuture).flatMap { cf =>
                IO.fromCompletableFuture(IO.pure(cf))
                    .onCancel(IO.delay { val _ = cf.cancel(false) })
            }
        }

end CompatFromCompletionStage

extension (inline c: CIO.type)
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
