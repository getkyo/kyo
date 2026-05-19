package kyo.compat

import kyo.compat.internal.LocalCtx
import scala.annotation.publicInBinary
import scala.concurrent.Future

/** The `CLocal` instance's own object reference is the lookup key in the immutable `LocalCtx` map threaded through every `CIO` carrier.
  * `let` produces a child `LocalCtx` with the override and runs `c` under it; continuations within `c` thread the child ctx and see the
  * value. Outside the `let` scope the value reverts to `default`. No EC dependency, no thread-hop loss.
  */
final class CLocal[A] @publicInBinary private[compat] (val default: A):

    inline def lower: CLocal[A] = this

    inline def get: CIO[A] =
        CIO.deferLift(Future.successful(summon[LocalCtx].get(this, default)))

    inline def let[B](inline value: A)(inline c: CIO[B]): CIO[B] =
        CIO.deferLift(c.lower(using summon[LocalCtx].updated(this, value)))

    inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
        CIO.deferLift {
            val ctx = summon[LocalCtx]
            c.lower(using ctx.updated(this, f(ctx.get(this, default))))
        }

end CLocal

object CLocal:

    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.defer(new CLocal[A](default))

    inline def lift[A](inline u: CLocal[A]): CLocal[A] = u

end CLocal
