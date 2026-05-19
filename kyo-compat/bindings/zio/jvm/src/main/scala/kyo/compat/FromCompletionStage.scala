package kyo.compat

import java.util.concurrent.CompletionStage
import zio.*

/** JVM-only. Wraps `ZIO.fromCompletionStage` (returns `Task[A]` = `ZIO[Any, Throwable, A]`, the CIO carrier). */
object CompatFromCompletionStage:

    inline def fromCompletionStage[A](inline cs: CompletionStage[A])(using inline trace: Trace): CIO[A] =
        CIO.lift(ZIO.fromCompletionStage(cs))

end CompatFromCompletionStage

extension (inline c: CIO.type)
    inline def fromCompletionStage[A](inline cs: CompletionStage[A])(
        using inline trace: Trace
    ): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
