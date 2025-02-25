package kyo.test

import zio.{Semaphore, ZIO, Trace}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.ZStream.BufferedPull

trait FunctionVariants {

  /**
   * Constructs a generator of functions from `A` to `B` given a generator of
   * `B` values. Two `A` values will be considered to be equal, and thus will be
   * guaranteed to generate the same `B` value, if they have the same
   * `hashCode`.
   */
  final def function[R, A, B](gen: Gen[R, B])(implicit trace: Trace): Gen[R, A => B] =
    functionWith(gen)(_.hashCode)

  /**
   * A version of `function` that generates functions that accept two
   * parameters.
   */
  final def function2[R, A, B, C](gen: Gen[R, C])(implicit trace: Trace): Gen[R, (A, B) => C] =
    function[R, (A, B), C](gen).map(Function.untupled[A, B, C])

  /**
   * A version of `function` that generates functions that accept three
   * parameters.
   */
  final def function3[R, A, B, C, D](gen: Gen[R, D])(implicit trace: Trace): Gen[R, (A, B, C) => D] =
    function[R, (A, B, C), D](gen).map(Function.untupled[A, B, C, D])

  /**
   * A version of `function` that generates functions that accept four
   * parameters.
   */
  final def function4[R, A, B, C, D, E](gen: Gen[R, E])(implicit trace: Trace): Gen[R, (A, B, C, D) => E] =
    function[R, (A, B, C, D), E](gen).map(Function.untupled[A, B, C, D, E])

  /**
   * Constructs a generator of functions from `A` to `B` given a generator of
   * `B` values and a hashing function for `A` values. Two `A` values will be
   * considered to be equal, and thus will be guaranteed to generate the same
   * `B` value, if they have have the same hash. This is useful when `A` does
   * not implement `hashCode` in a way that is consistent with equality.
   */
  final def functionWith[R, A, B](gen: Gen[R, B])(hash: A => Int)(implicit trace: Trace): Gen[R, A => B] =
    Gen.fromZIO {
      ZIO.scoped[R] {
        gen.sample.forever.toPull.flatMap { pull =>
          for {
            lock    <- Semaphore.make(1)
            bufPull <- BufferedPull.make[R, Nothing, Sample[R, B]](pull)
            fun     <- Fun.makeHash((_: A) => lock.withPermit(bufPull.pullElement).unsome.map(_.get.value))(hash)
          } yield fun
        }
      }
    }

  /**
   * A version of `functionWith` that generates functions that accept two
   * parameters.
   */
  final def functionWith2[R, A, B, C](gen: Gen[R, C])(hash: (A, B) => Int)(implicit
    trace: Trace
  ): Gen[R, (A, B) => C] =
    functionWith[R, (A, B), C](gen)(hash.tupled).map(Function.untupled[A, B, C])

  /**
   * A version of `functionWith` that generates functions that accept three
   * parameters.
   */
  final def functionWith3[R, A, B, C, D](gen: Gen[R, D])(hash: (A, B, C) => Int)(implicit
    trace: Trace
  ): Gen[R, (A, B, C) => D] =
    functionWith[R, (A, B, C), D](gen)(hash.tupled).map(Function.untupled[A, B, C, D])

  /**
   * A version of `functionWith` that generates functions that accept four
   * parameters.
   */
  final def functionWith4[R, A, B, C, D, E](gen: Gen[R, E])(hash: (A, B, C, D) => Int)(implicit
    trace: Trace
  ): Gen[R, (A, B, C, D) => E] =
    functionWith[R, (A, B, C, D), E](gen)(hash.tupled).map(Function.untupled[A, B, C, D, E])
}
