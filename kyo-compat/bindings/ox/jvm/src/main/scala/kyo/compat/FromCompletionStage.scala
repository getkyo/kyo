package kyo.compat

import java.util.concurrent.CompletionStage

/** JVM-only. Cancellation does NOT propagate back to the source CompletionStage. */
object CompatFromCompletionStage:

    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.deferLift {
            val cf = cs.toCompletableFuture
            try cf.get()
            catch
                case ee: java.util.concurrent.ExecutionException =>
                    val cause = ee.getCause
                    if cause ne null then throw cause else throw ee
            end try
        }

end CompatFromCompletionStage

extension (inline c: CIO.type)
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
