package kyo.compat

import kyo.compat.internal.LocalCtx
import scala.concurrent.Future

/** Custom `final class` backing the Future binding's fiber-local — the Future ecosystem has no native fiber-local, so this binding uses
  * identity-keyed lookup in the immutable `LocalCtx` map threaded through every `CIO` carrier. The Future ecosystem has no `Frame` /
  * `Trace` to propagate. `lift` and `lower` are identity on this `final class`. The `CLocal` instance's own object reference is the lookup
  * key in the `LocalCtx` map; `let` produces a child `LocalCtx` with the override and runs `c` under it; continuations within `c` thread
  * the child ctx and see the value. Outside the `let` scope the value reverts to `default`. No EC dependency, no thread-hop loss.
  */
final class CLocal[A](val default: A):

    /** Returns this local. Identity on the carrier. */
    inline def lower: CLocal[A] = this

    /** Reads the current `LocalCtx`'s value for this local; returns `default` when unset. */
    inline def get: CIO[A] =
        CIO.deferLift(Future.successful(summon[LocalCtx].get(this, default)))

    /** Installs `value` for the duration of `c` by threading a child `LocalCtx`; reverts when `c` completes. */
    inline def let[B](inline value: A)(inline c: CIO[B]): CIO[B] =
        CIO.deferLift(c.lower(using summon[LocalCtx].updated(this, value)))

    /** Reads the current value, applies `f`, installs the result for the duration of `c` by threading a child `LocalCtx`; reverts when `c`
      * completes.
      */
    inline def update[B](inline f: A => A)(inline c: CIO[B]): CIO[B] =
        CIO.deferLift {
            val ctx = summon[LocalCtx]
            c.lower(using ctx.updated(this, f(ctx.get(this, default))))
        }

end CLocal

object CLocal:

    /** Allocates a fresh fiber-local seeded with `default`. */
    inline def init[A](inline default: A): CIO[CLocal[A]] =
        CIO.defer(new CLocal[A](default))

    /** Wraps a native `CLocal` as a `CLocal`. Identity on the carrier. */
    inline def lift[A](inline u: CLocal[A]): CLocal[A] = u

end CLocal
