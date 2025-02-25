package kyo.test

import kyo.*
import kyo.test.Gen
import kyo.test.Trace

trait FunctionVariants:

    // /** Constructs a generator of functions from A to B given a generator of B values. Two A values will be considered equal (if they have
    //   * the same hashCode).
    //   */
    // final def function[R, A, B](gen: Gen[R, B])(using trace: Trace): Gen[R, A => B] =
    //     functionWith(gen)(_.hashCode)

    // /** A version of function that generates functions that accept two parameters.
    //   */
    // final def function2[R, A, B, C](gen: Gen[R, C])(using trace: Trace): Gen[R, (A, B) => C] =
    //     function[R, (A, B), C](gen).map(Function.untupled[A, B, C])

    // /** A version of function that generates functions that accept three parameters.
    //   */
    // final def function3[R, A, B, C, D](gen: Gen[R, D])(using trace: Trace): Gen[R, (A, B, C) => D] =
    //     function[R, (A, B, C), D](gen).map(Function.untupled[A, B, C, D])

    // /** A version of function that generates functions that accept four parameters.
    //   */
    // final def function4[R, A, B, C, D, E](gen: Gen[R, E])(using trace: Trace): Gen[R, (A, B, C, D) => E] =
    //     function[R, (A, B, C, D), E](gen).map(Function.untupled[A, B, C, D, E])

    // /** Constructs a generator of functions from A to B given a generator of B values and a hashing function for A values. This ensures that
    //   * two A values with the same hash are mapped to the same B value.
    //   */
    // final def functionWith[R, A, B](gen: Gen[R, B])(hash: A => Int)(using trace: Trace): Gen[R, A => B] =
    //     Gen.fromKyo {
    //         Kyo.scoped {
    //             gen.sample.forever.toPull.flatMap { pull =>
    //                 for
    //                     lock    <- Semaphore.make(1)
    //                     bufPull <- BufferedPull.make(pull)
    //                     fun     <- Fun.makeHash((a: A) => lock.withPermit(bufPull.pullElement).unsome.map(_.get.value))(hash)
    //                 yield fun
    //             }
    //         }
    //     }

    // /** A version of functionWith that generates functions that accept two parameters.
    //   */
    // final def functionWith2[R, A, B, C](gen: Gen[R, C])(hash: (A, B) => Int)(using trace: Trace): Gen[R, (A, B) => C] =
    //     functionWith[R, (A, B), C](gen)(hash.tupled).map(Function.untupled[A, B, C])

    // /** A version of functionWith that generates functions that accept three parameters.
    //   */
    // final def functionWith3[R, A, B, C, D](gen: Gen[R, D])(hash: (A, B, C) => Int)(using trace: Trace): Gen[R, (A, B, C) => D] =
    //     functionWith[R, (A, B, C), D](gen)(hash.tupled).map(Function.untupled[A, B, C, D])

    // /** A version of functionWith that generates functions that accept four parameters.
    //   */
    // final def functionWith4[R, A, B, C, D, E](gen: Gen[R, E])(hash: (A, B, C, D) => Int)(using trace: Trace): Gen[R, (A, B, C, D) => E] =
    //     functionWith[R, (A, B, C, D), E](gen)(hash.tupled).map(Function.untupled[A, B, C, D, E])
end FunctionVariants
