package kyo.kernel.internal

import kyo.<
import scala.annotation.static

private[kyo] class Nested[+A](val value: A)

private[kyo] object Nested:

    @static def lift[A, S](v: A): A < S =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v).asInstanceOf[A < S]
            case _                          => v.asInstanceOf[A < S]

end Nested
