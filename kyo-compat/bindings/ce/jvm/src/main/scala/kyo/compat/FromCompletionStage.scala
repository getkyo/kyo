package kyo.compat

import cats.effect.IO
import java.util.concurrent.CompletionStage

/** JVM-only. Delegates to `IO.fromCompletionStage`, which propagates CIO interruption to the source via `cf.cancel(true)`. */
object CompatFromCompletionStage:

    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.lift(IO.fromCompletionStage(IO.delay(cs)))

end CompatFromCompletionStage

extension (inline c: CIO.type)
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
