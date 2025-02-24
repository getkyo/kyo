package kyo.test.poly

import kyo.Frame
import kyo.test.Gen

/** `GenNumericPoly` provides evidence that instances of `Gen[T]` and `Numeric[T]` exist for some concrete but unknown type `T`.
  */
trait GenNumericPoly extends GenOrderingPoly:
    val numT: Numeric[T]
    final override val ordT: Ordering[T] = numT

object GenNumericPoly:

    /** Constructs an instance of `GenIntegralPoly` using the specified `Gen` and `Numeric` instances, existentially hiding the underlying
      * type.
      */
    def apply[A](gen: Gen[Any, A], num: Numeric[A]): GenNumericPoly =
        new GenNumericPoly:
            type T = A
            val genT = gen
            val numT = num

    /** Provides evidence that instances of `Gen` and `Numeric` exist for bytes.
      */
    def byte(implicit trace: Frame): GenNumericPoly =
        GenIntegralPoly.byte

    /** Provides evidence that instances of `Gen` and `Numeric` exist for characters.
      */
    def char(implicit trace: Frame): GenNumericPoly =
        GenIntegralPoly.char

    /** Provides evidence that instances of `Gen` and `Numeric` exist for doubles.
      */
    def double(implicit trace: Frame): GenNumericPoly =
        GenFractionalPoly.double

    /** Provides evidence that instances of `Gen` and `Numeric` exist for floats.
      */
    def float(implicit trace: Frame): GenNumericPoly =
        GenFractionalPoly.float

    /** A generator of polymorphic values constrainted to have a `Numeric` instance.
      */
    def genNumericPoly(implicit trace: Frame): Gen[Any, GenNumericPoly] =
        Gen.elements(
            byte,
            char,
            double,
            float,
            int,
            long,
            short
        )

    /** Provides evidence that instances of `Gen` and `Numeric` exist for integers.
      */
    def int(implicit trace: Frame): GenNumericPoly =
        GenIntegralPoly.int

    /** Provides evidence that instances of `Gen` and `Numeric` exist for longs.
      */
    def long(implicit trace: Frame): GenNumericPoly =
        GenIntegralPoly.long

    /** Provides evidence that instances of `Gen` and `Numeric` exist for shorts.
      */
    def short(implicit trace: Frame): GenNumericPoly =
        GenIntegralPoly.long
end GenNumericPoly
