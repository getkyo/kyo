package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import scala.annotation.nowarn

/** Backed by a `com.twitter.util.Future[A]` wired to a `Promise[A]` with an interrupt handler.
  *
  * `init` wraps the body's result in a `Promise[A]` with `setInterruptHandler`: when the promise is raised with an exception (e.g. by
  * `race`'s loser-cancel or parent-fiber propagation), the handler calls `body.raise` so the inner computation receives the signal and
  * flips the result promise to `Throw(t)` so the fiber resolves as interrupted. `body.respond` is also wired to complete the result promise
  * normally on success/failure.
  */
opaque type CFiber[+A] = Future[A]

object CFiber:

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

    inline def lift[A](inline u: Future[A]): CFiber[A] = u

    extension [A](self: CFiber[A])

        inline def lower: Future[A] = self

        inline def get: CIO[A] =
            CIO.lift(self)

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
