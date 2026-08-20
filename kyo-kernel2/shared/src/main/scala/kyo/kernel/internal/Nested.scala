package kyo.kernel.internal

import kyo.<
import scala.annotation.static

class Nested[+A](val value: A)

object Nested:

    @static def lift[A, S](v: A): A < S =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v).asInstanceOf[A < S]
            case _                          => v.asInstanceOf[A < S]

end Nested
