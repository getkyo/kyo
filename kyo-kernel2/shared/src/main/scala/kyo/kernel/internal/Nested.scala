package kyo.kernel.internal

import kyo.<
import scala.annotation.static

private[kyo] class Nested[+A](val value: A)

// public for the same reason as Safepoint's depth guard: the implicit lift is inline and names it, and an
// accessor here costs the hottest expansion in the kernel. The class stays private[kyo], so the wrapper
// itself is still unnameable outside kyo.
object Nested:

    @static def lift[A, S](v: A): A < S =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v).asInstanceOf[A < S]
            case _                          => v.asInstanceOf[A < S]

end Nested
