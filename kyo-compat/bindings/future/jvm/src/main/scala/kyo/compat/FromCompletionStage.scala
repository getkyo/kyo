package kyo.compat

import java.util.concurrent.CompletionStage
import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters.CompletionStageOps

/** JVM-only. Lifts a `CompletionStage[A]` into `CIO[A]` via `CompletionStageOps.asScala`, observing the source's eventual completion. The
  * CIO surface does not expose cancellation, so the source `CompletionStage` is not cancelled by the consumer.
  */
object CompatFromCompletionStage:

    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.deferLift(cs.asScala)

end CompatFromCompletionStage

extension (inline c: CIO.type)
    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
