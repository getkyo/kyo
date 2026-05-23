package kyo.compat

import cats.effect.IO
import java.util.concurrent.CompletionStage

/** JVM-only. Lifts a `CompletionStage[A]` into `CIO[A]` via `IO.fromCompletionStage`. Failures surface through the IO error channel. CIO
  * interruption propagates to the source via `cf.cancel(true)`.
  */
object CompatFromCompletionStage:

    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; CIO interruption propagates to `cs` via `cf.cancel(true)`. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.lift(IO.fromCompletionStage(IO.delay(cs)))

end CompatFromCompletionStage

extension (inline c: CIO.type)
    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; CIO interruption propagates to `cs` via `cf.cancel(true)`. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
