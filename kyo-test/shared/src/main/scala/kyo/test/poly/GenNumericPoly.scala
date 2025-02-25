package kyo.test.poly

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.Gen
import zio.Trace

/**
 * `GenNumericPoly` provides evidence that instances of `Gen[T]` and
 * `Numeric[T]` exist for some concrete but unknown type `T`.
 */
trait GenNumericPoly extends GenOrderingPoly {
  val numT: Numeric[T]
  override final val ordT: Ordering[T] = numT
}

object GenNumericPoly {

  /**
   * Constructs an instance of `GenIntegralPoly` using the specified `Gen` and
   * `Numeric` instances, existentially hiding the underlying type.
   */
  def apply[A](gen: Gen[Any, A], num: Numeric[A]): GenNumericPoly =
    new GenNumericPoly {
      type T = A
      val genT = gen
      val numT = num
    }

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for bytes.
   */
  def byte(implicit trace: Trace): GenNumericPoly =
    GenIntegralPoly.byte

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for
   * characters.
   */
  def char(implicit trace: Trace): GenNumericPoly =
    GenIntegralPoly.char

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for doubles.
   */
  def double(implicit trace: Trace): GenNumericPoly =
    GenFractionalPoly.double

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for floats.
   */
  def float(implicit trace: Trace): GenNumericPoly =
    GenFractionalPoly.float

  /**
   * A generator of polymorphic values constrainted to have a `Numeric`
   * instance.
   */
  def genNumericPoly(implicit trace: Trace): Gen[Any, GenNumericPoly] =
    Gen.elements(
      byte,
      char,
      double,
      float,
      int,
      long,
      short
    )

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for integers.
   */
  def int(implicit trace: Trace): GenNumericPoly =
    GenIntegralPoly.int

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for longs.
   */
  def long(implicit trace: Trace): GenNumericPoly =
    GenIntegralPoly.long

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for shorts.
   */
  def short(implicit trace: Trace): GenNumericPoly =
    GenIntegralPoly.long
}
