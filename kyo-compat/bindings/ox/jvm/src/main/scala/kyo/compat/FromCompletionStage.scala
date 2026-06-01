package kyo.compat

import java.util.concurrent.CompletionStage

/** JVM-only. Lifts a `CompletionStage[A]` into `CIO[A]` by blocking the calling thread on `cf.get()` and unwrapping `ExecutionException`.
  * Cancellation does NOT propagate back to the source `CompletionStage`.
  */
object CompatFromCompletionStage:

    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion (blocks the calling thread); cancellation does not propagate back
      * to `cs`.
      */
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
    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion (blocks the calling thread); cancellation does not propagate back
      * to `cs`.
      */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
