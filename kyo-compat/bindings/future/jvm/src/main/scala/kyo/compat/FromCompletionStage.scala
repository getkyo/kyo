package kyo.compat

import java.util.concurrent.CompletionStage
import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters.CompletionStageOps

/** JVM-only. Wraps a `CompletionStage` into a `CIO` via `CompletionStageOps.asScala`. */
object CompatFromCompletionStage:

    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.deferLift(cs.asScala)

end CompatFromCompletionStage

extension (inline c: CIO.type)
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
