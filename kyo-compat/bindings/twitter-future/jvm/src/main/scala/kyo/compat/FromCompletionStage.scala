package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import java.util.concurrent.CompletionStage

/** JVM-only bridge from `java.util.concurrent.CompletionStage[A]` to `CIO[A]`. Twitter Future has no native `CompletionStage` adapter, so
  * the bridge is hand-rolled: a fresh `com.twitter.util.Promise[A]` is wired through `CompletionStage.whenComplete`, unwrapping
  * `CompletionException` causes to expose the underlying throwable. Cancellation does not propagate back to the source `CompletionStage`.
  */
object CompatFromCompletionStage:

    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion; `CompletionException` is unwrapped to its cause. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CIO.deferLift {
            val p = new Promise[A]()
            cs.whenComplete { (result: A, err: Throwable) =>
                if err != null then
                    val cause =
                        if err.isInstanceOf[java.util.concurrent.CompletionException] && err.getCause != null
                        then err.getCause
                        else err
                    val _ = p.updateIfEmpty(Throw(cause))
                else
                    val _ = p.updateIfEmpty(Return(result))
            }
            p
        }

end CompatFromCompletionStage

extension (inline c: CIO.type)
    /** Lifts `cs` into a `CIO[A]` that observes its eventual completion. */
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
