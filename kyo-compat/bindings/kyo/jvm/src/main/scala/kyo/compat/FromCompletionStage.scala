package kyo.compat

import java.util.concurrent.CompletionStage
import kyo.*

/** JVM-only. Lifts a `CompletionStage[A]` into `CIO[A]`. Failures surface as `Abort[Throwable]`. Cancellation does NOT propagate back. */
object CompatFromCompletionStage:

    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; cancellation does not propagate back to `cs`. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A])(using inline frame: Frame): CIO[A] =
        CIO.lift(Async.fromCompletionStage(cs))

end CompatFromCompletionStage

extension (inline c: CIO.type)
    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; cancellation does not propagate back to `cs`. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A])(
        using inline frame: Frame
    ): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
