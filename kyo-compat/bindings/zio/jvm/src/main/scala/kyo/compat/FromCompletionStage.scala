package kyo.compat

import java.util.concurrent.CompletionStage
import zio.*

/** JVM-only. Lifts a `CompletionStage[A]` into `CIO[A]`. Failures surface in the typed `Throwable` error channel. Cancellation does NOT
  * propagate back.
  */
object CompatFromCompletionStage:

    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; cancellation does not propagate back to `cs`. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A])(using inline trace: Trace): CIO[A] =
        CIO.lift(ZIO.fromCompletionStage(cs))

end CompatFromCompletionStage

extension (inline c: CIO.type)
    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; cancellation does not propagate back to `cs`. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A])(
        using inline trace: Trace
    ): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
