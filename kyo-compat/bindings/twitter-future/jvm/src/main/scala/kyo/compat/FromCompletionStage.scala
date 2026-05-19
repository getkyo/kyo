package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import java.util.concurrent.CompletionStage

/** JVM-only. Bridges via Promise + CompletionStage.whenComplete. Cancellation does NOT propagate back to the source CompletionStage. */
object CompatFromCompletionStage:

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
    inline def fromCompletionStage[A](inline cs: CompletionStage[A]): CIO[A] =
        CompatFromCompletionStage.fromCompletionStage(cs)
end extension
