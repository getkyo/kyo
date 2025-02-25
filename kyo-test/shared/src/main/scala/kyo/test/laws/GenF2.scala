package kyo.test.laws

import kyo.*
import kyo.test.FunctionVariants
import kyo.test.Gen
import kyo.test.Trace

/** A `GenF` knows how to construct a generator of `F[A,B]` values given a generator of `A` and generator of `B` values. For example, a
  * `GenF2` of `Function1` values knows how to generate functions A => B with elements given a generator of elements of that type `B`.
  */
trait GenF2[-R, F[_, _]]:

    /** Construct a generator of `F[A,B]` values given a generator of `B` values.
      */
    def apply[R1 <: R, A, B](gen: Gen[R1, B])(implicit trace: Trace): Gen[R1, F[A, B]]
end GenF2

object GenF2 extends FunctionVariants:

    /** A generator of `Function1` A => B values.
      */
    val function1: GenF2[Any, Function1] =
        new GenF2[Any, Function1]:

            override def apply[R1, A, B](gen: Gen[R1, B])(implicit
                trace: Trace
            ): Gen[R1, Function1[A, B]] =
                function[R1, A, B](gen)
end GenF2
