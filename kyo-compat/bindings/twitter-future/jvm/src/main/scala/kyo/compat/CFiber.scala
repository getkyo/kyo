package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import scala.annotation.nowarn

/** Underlying carrier is `com.twitter.util.Future[A]` wired to a `com.twitter.util.Promise[A]` with an `setInterruptHandler`. There is no
  * `Frame` / `Trace` to propagate. `lift` and `lower` are identity since the carrier is already a native Twitter Future. `init` wires
  * `body.respond` to complete the result promise on success or failure, and `setInterruptHandler` so that when the result promise is raised
  * (e.g. by `race`'s loser-cancel or parent-fiber propagation), the handler calls `body.raise` to forward the signal and flips the result
  * promise to `Throw(t)` so the fiber resolves as interrupted. `onComplete` adapts Twitter's `Try` (`Return` / `Throw`) into a
  * `scala.util.Try[A]` for the callback.
  */
opaque type CFiber[+A] = Future[A]

object CFiber:

    /** Forks `c` as a new fiber wired with an interrupt handler that raises the inner body and flips the result to `Throw(t)`. */
    @nowarn("msg=anonymous")
    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.defer {
            val result = new Promise[A]()
            val body   = c.lower()
            val _ = body.respond { r =>
                val _ = result.updateIfEmpty(r)
            }
            result.setInterruptHandler { case t =>
                body.raise(t)
                val _ = result.updateIfEmpty(Throw(t))
            }
            result
        }

    /** Wraps a native `com.twitter.util.Future` as a `CFiber`. Identity on the carrier. */
    inline def lift[A](inline u: Future[A]): CFiber[A] = u

    extension [A](self: CFiber[A])

        /** Unwraps to the native `com.twitter.util.Future`. Identity on the carrier. */
        inline def lower: Future[A] = self

        /** Joins the fiber and returns its result. */
        inline def get: CIO[A] =
            CIO.lift(self)

        /** Registers `cb` to fire when the fiber completes; success and failure are reified as `scala.util.Try`. */
        inline def onComplete(inline cb: scala.util.Try[A] => CIO[Unit]): CIO[Unit] =
            CIO.defer {
                val _ = self.respond { r =>
                    val t: scala.util.Try[A] = r match
                        case Return(a) => scala.util.Success(a)
                        case Throw(e)  => scala.util.Failure(e)
                    val _ = cb(t).lower()
                }
            }
        end onComplete

    end extension

end CFiber
