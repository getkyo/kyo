package kyo.test

import kyo.*

trait GenKyo:

    // /** A generator of `Cause` values
    //   */
    // final def causes[R, E](e: Gen[R, E], t: Gen[R, Throwable])(using trace: Trace): Gen[R, Cause[E]] =
    //     val fiberId           = (Gen.int zip Gen.int zip Gen.const(Trace.empty)).map { case ((a, b), c) => FiberId(a, b, c) }
    //     val zTraceElement     = Gen.string.map(_.asInstanceOf[Trace])
    //     val zTrace            = fiberId.zipWith(Gen.chunkOf(zTraceElement))(StackTrace(_, _))
    //     val failure           = e.zipWith(zTrace)(Cause.fail(_, _))
    //     val die               = t.zipWith(zTrace)(Cause.panic(_, _))
    //     val empty             = Gen.const(Cause.empty)
    //     val interrupt         = fiberId.zipWith(zTrace)(Cause.interrupt(_, _))
    //     def stackless(n: Int) = Gen.suspend(causesN(n - 1).flatMap(c => Gen.elements(Cause.stack(c), Cause.stackless(c))))
    //     def sequential(n: Int) = Gen.suspend {
    //         for
    //             i <- Gen.int(1, n - 1)
    //             l <- causesN(i)
    //             r <- causesN(n - i)
    //         yield Cause.Then(l, r)
    //     }
    //     def parallel(n: Int) = Gen.suspend {
    //         for
    //             i <- Gen.int(1, n - 1)
    //             l <- causesN(i)
    //             r <- causesN(n - i)
    //         yield Cause.Both(l, r)
    //     }
    //     def causesN(n: Int): Gen[R, Cause[E]] = Gen.suspend {
    //         if n == 1 then Gen.oneOf(empty, failure, die, interrupt)
    //         else if n == 2 then stackless(n)
    //         else Gen.oneOf(stackless(n), sequential(n), parallel(n))
    //     }
    //     Gen.small(causesN, 1)
    // end causes

    // /** A generator of effects that are the result of chaining the specified effect with itself a random number of times.
    //   */
    // final def chained[S, A](gen: Gen[S, A])(using trace: Trace): Gen[S, A] =
    //     Gen.small(chainedN(_)(gen))

    // /** A generator of effects that are the result of chaining the specified effect with itself a given number of times.
    //   */
    // final def chainedN[S, A](n: Int)(gen: Gen[S, A])(using trace: Trace): Gen[S, A] =
    //     Gen.listOfN(n min 1)(gen).map(_.reduce(_ *> _))

    // /** A generator of effects that are the result of applying concurrency combinators to the specified effect that are guaranteed not to
    //   * change its value.
    //   */
    // final def concurrent[S, A](gen: Gen[S, A])(using trace: Trace): Gen[Any, A] =
    //     Gen.const(gen.race(Kyo.never))

    /** A generator of effects that have died with a `Throwable`.
      */
    final def died[R](gen: Gen[R, Throwable])(using trace: Trace): Gen[R, Nothing < Abort[Nothing]] =
        gen.map(Abort.panic(_))

    /** A generator of effects that have failed with an error.
      */
    final def failures[S, E](gen: Gen[S, E])(using trace: Trace): Gen[S, Nothing < Abort[E]] =
        gen.map(Abort.fail(_))

    // /** A generator of effects that are the result of applying parallelism combinators to the specified effect that are guaranteed not to
    //   * change its value.
    //   */
    // final def parallel[E, A](gen: Gen[Async & Abort[E], A])(using trace: Trace): Gen[Any, A] =
    //     successes(Gen.unit).map(u => u.zipRightPar(gen))

    /** A generator of successful effects.
      */
    final def successes[S, A](gen: Gen[S, A])(using trace: Trace): Gen[S, A] =
        gen.map(identity)

end GenKyo
