package kyo.test.poly
// Converted from ZIO test to Kyo test. (No ZIO-specific code present)
import kyo.Frame
import kyo.test.Gen

/** `GenFractionalPoly` provides evidence that instances of `Gen[T]` and `Fractional[T]` exist for some concrete but unknown type `T`.
  */
trait GenFractionalPoly extends GenNumericPoly:
    override val numT: Fractional[T]

object GenFractionalPoly:

    /** Constructs an instance of `GenFractionalPoly` using the specified `Gen` and `Fractional` instances, existentially hiding the
      * underlying type.
      */
    def apply[A](gen: Gen[Any, A], num: Fractional[A]): GenFractionalPoly =
        new GenFractionalPoly:
            type T = A
            val genT = gen
            val numT = num

    /** Provides evidence that instances of `Gen` and `Fractional` exist for doubles.
      */
    def double(implicit trace: Frame): GenFractionalPoly =
        GenFractionalPoly(Gen.double, Numeric.DoubleIsFractional)

    /** Provides evidence that instances of `Gen` and `Fractional` exist for floats.
      */
    def float(implicit trace: Frame): GenFractionalPoly =
        GenFractionalPoly(Gen.float, Numeric.FloatIsFractional)

    /** A generator of polymorphic values constrainted to have a `Fractional` instance.
      */
    def genFractionalPoly(implicit trace: Frame): Gen[Any, GenFractionalPoly] =
        Gen.elements(double, float)
end GenFractionalPoly
