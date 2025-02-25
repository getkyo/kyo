package kyo.test

import kyo.*

trait GenKyo:

    final def causes[R, E](e: Gen[R, E], t: Gen[R, Throwable])(using trace: Trace): Gen[R, Cause[E]] =
        val fiberId           = (Gen.int zip Gen.int zip Gen.const(Trace.empty)).map { case ((a, b), c) => FiberId(a, b, c) }
        val zTraceElement     = Gen.string.map(_.asInstanceOf[Trace])
        val zTrace            = fiberId.zipWith(Gen.chunkOf(zTraceElement))(StackTrace(_, _))
        val failure           = e.zipWith(zTrace)(Cause.fail(_, _))
        val die               = t.zipWith(zTrace)(Cause.panic(_, _))
        val empty             = Gen.const(Cause.empty)
        val interrupt         = fiberId.zipWith(zTrace)(Cause.interrupt(_, _))
        def stackless(n: Int) = Gen.suspend(causesN(n - 1).flatMap(c => Gen.elements(Cause.stack(c), Cause.stackless(c))))
        def sequential(n: Int) = Gen.suspend {
            for
                i <- Gen.int(1, n - 1)
                l <- causesN(i)
                r <- causesN(n - i)
            yield Cause.Then(l, r)
        }
        def parallel(n: Int) = Gen.suspend {
            for
                i <- Gen.int(1, n - 1)
                l <- causesN(i)
                r <- causesN(n - i)
            yield Cause.Both(l, r)
        }
        def causesN(n: Int): Gen[R, Cause[E]] = Gen.suspend {
            if n == 1 then Gen.oneOf(empty, failure, die, interrupt)
            else if n == 2 then stackless(n)
            else Gen.oneOf(stackless(n), sequential(n), parallel(n))
        }
        Gen.small(causesN, 1)
    end causes

    final def chained[R, Env, E, A](gen: Gen[R, A < Env & Abort[E]])(using trace: Trace): Gen[R, A < Env & Abort[E]] =
        Gen.small(chainedN(_)(gen))

    final def chainedN[R, Env, E, A](n: Int)(zio: Gen[R, A < Env & Abort[E]])(using trace: Trace): Gen[R, A < Env & Abort[E]] =
        Gen.listOfN(n min 1)(zio).map(_.reduce(_ *> _))

    final def concurrent[R, E, A](zio: A < R & Abort[E])(using trace: Trace): Gen[Any, A < R & Abort[E]] =
        Gen.const(zio.race(Kyo.never))

    final def died[R](gen: Gen[R, Throwable])(using trace: Trace): Gen[R, Nothing < Any] =
        gen.map(Abort.panic(_))

    final def failures[R, E](gen: Gen[R, E])(using trace: Trace): Gen[R, Nothing < Any & Abort[E]] =
        gen.map(Abort.fail(_))

    final def parallel[R, E, A](zio: A < R & Abort[E])(using trace: Trace): Gen[Any, A < R & Abort[E]] =
        successes(Gen.unit).map(u => u &> zio)

    final def successes[R, A](gen: Gen[R, A])(using trace: Trace): Gen[R, A < Any] =
        gen.map(identity)

end GenKyo
