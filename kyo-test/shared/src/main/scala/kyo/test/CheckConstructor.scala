package kyo.test

import kyo.*
import kyo.ZIO
import kyo.ZIOs
import kyo.stm.ZSTM
import scala.util.Either

trait CheckConstructor[Environment, In]:
    type OutEnvironment <: Environment
    type OutError
    def apply(input: => In): TestResult < Env[OutEnvironment] & Abort[OutError]
end CheckConstructor

object CheckConstructor extends CheckConstructorLowPriority1:

    type WithOut[Environment, In, OutEnvironment0, OutError0] =
        CheckConstructor[Environment, In] {
            type OutEnvironment = OutEnvironment0
            type OutError       = OutError0
        }

    implicit def AssertConstructor[R, A <: TestResult]: CheckConstructor.WithOut[R, A, R, Nothing] =
        new CheckConstructor[R, A]:
            type OutEnvironment = R
            type OutError       = Nothing
            def apply(input: => A): TestResult < Env[OutEnvironment] & Abort[OutError] =
                input.asInstanceOf[TestResult]
end CheckConstructor

trait CheckConstructorLowPriority1 extends CheckConstructorLowPriority2:

    implicit def AssertZIOConstructor[R, R1, E, A <: TestResult]
        : CheckConstructor.WithOut[R, ZIO[R1, E, A], R with R1, E] =
        new CheckConstructor[R, ZIO[R1, E, A]]:
            type OutEnvironment = R with R1
            type OutError       = E
            def apply(input: => ZIO[R1, E, A]): TestResult < Env[OutEnvironment] & Abort[OutError] =
                ZIOs.get(input).asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
end CheckConstructorLowPriority1

trait CheckConstructorLowPriority2 extends CheckConstructorLowPriority3:

    implicit def AssertZSTMConstructor[R, R1, E, A <: TestResult]
        : CheckConstructor.WithOut[R, ZSTM[R1, E, A], R with R1, E] =
        new CheckConstructor[R, ZSTM[R1, E, A]]:
            type OutEnvironment = R with R1
            type OutError       = E
            def apply(input: => ZSTM[R1, E, A]): TestResult < Env[OutEnvironment] & Abort[OutError] =
                ZIOs.get(input.commit).asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
end CheckConstructorLowPriority2

trait CheckConstructorLowPriority3:

    implicit def AssertEitherConstructor[R, E, A <: TestResult]: CheckConstructor.WithOut[R, Either[E, A], R, E] =
        new CheckConstructor[R, Either[E, A]]:
            type OutEnvironment = R
            type OutError       = E
            def apply(input: => Either[E, A]): TestResult < Env[OutEnvironment] & Abort[OutError] =
                input match
                    case Right(a) => Kyo.pure(a)
                    case Left(e)  => Abort.fail(e.asInstanceOf[OutError]) // Unsafe cast, but should work
end CheckConstructorLowPriority3
