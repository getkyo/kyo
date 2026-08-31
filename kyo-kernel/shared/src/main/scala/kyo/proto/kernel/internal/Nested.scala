package kyo.proto.kernel.internal

import kyo.proto.Arrow
import kyo.proto.kernel.<
import scala.annotation.publicInBinary

private[kyo] class Nested[+A](val value: A)

@publicInBinary private[kyo] object Nested:

    def unnest[A](v: Any): A =
        v match
            case v: Nested[A] @unchecked => v.value
            case v                       => v.asInstanceOf[A]

    def nest[A, S](v: A): A < S =
        v match

            case v: (Pending[?, ?] | Nested[?]) => Nested(v).asInstanceOf[A < S]
            case _                              => v.asInstanceOf[A < S]

end Nested
