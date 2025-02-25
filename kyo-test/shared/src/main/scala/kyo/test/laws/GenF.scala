package kyo.test.laws

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.Gen
import zio.Chunk
import zio.Trace

/**
 * A `GenF` knows how to construct a generator of `F[A]` values given a
 * generator of `A` values for any `A`. For example, a `GenF` of `List` values
 * knows how to generate lists with elements given a generator of elements of
 * that type. You can think of `GenF` as a "recipe" for building generators for
 * parameterized types.
 */
trait GenF[-R, F[_]] {

  /**
   * Construct a generator of `F[A]` values given a generator of `A` values.
   */
  def apply[R1 <: R, A](gen: Gen[R1, A])(implicit trace: Trace): Gen[R1, F[A]]
}

object GenF {

  /**
   * A generator of `Chunk` values.
   */
  val chunk: GenF[Any, Chunk] =
    new GenF[Any, Chunk] {
      def apply[R1, A](gen: Gen[R1, A])(implicit
        trace: Trace
      ): Gen[R1, Chunk[A]] =
        Gen.chunkOf(gen)
    }

  /**
   * A generator of `Either` values.
   */
  def either[R, A](a: Gen[R, A]): GenF[R, ({ type lambda[+x] = Either[A, x] })#lambda] =
    new GenF[R, ({ type lambda[+x] = Either[A, x] })#lambda] {
      def apply[R1 <: R, B](b: Gen[R1, B])(implicit trace: Trace): Gen[R1, Either[A, B]] =
        Gen.either(a, b)
    }

  /**
   * A generator of `List` values.
   */
  val list: GenF[Any, List] =
    new GenF[Any, List] {
      def apply[R1, A](gen: Gen[R1, A])(implicit
        trace: Trace
      ): Gen[R1, List[A]] =
        Gen.listOf(gen)
    }

  /**
   * A generator of `Map` values.
   */
  def map[R, A](a: Gen[R, A]): GenF[R, ({ type lambda[+x] = Map[A, x] })#lambda] =
    new GenF[R, ({ type lambda[+x] = Map[A, x] })#lambda] {
      def apply[R1 <: R, B](b: Gen[R1, B])(implicit trace: Trace): Gen[R1, Map[A, B]] =
        Gen.mapOf(a, b)
    }

  /**
   * A generator of `Option` values.
   */
  val option: GenF[Any, Option] =
    new GenF[Any, Option] {
      def apply[R1, A](gen: Gen[R1, A])(implicit trace: Trace): Gen[R1, Option[A]] =
        Gen.option(gen)
    }

  /**
   * A generator of `Set` values.
   */
  val set: GenF[Any, Set] =
    new GenF[Any, Set] {
      def apply[R1, A](gen: Gen[R1, A])(implicit trace: Trace): Gen[R1, Set[A]] =
        Gen.setOf(gen)
    }

  /**
   * A generator of `Vector` values.
   */
  val vector: GenF[Any, Vector] =
    new GenF[Any, Vector] {
      def apply[R1, A](gen: Gen[R1, A])(implicit
        trace: Trace
      ): Gen[R1, Vector[A]] =
        Gen.vectorOf(gen)
    }
}
