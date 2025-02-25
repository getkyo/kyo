package kyo.test

import kyo.*
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
                Kyo.pure(input).asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
end CheckConstructor

trait CheckConstructorLowPriority1 extends CheckConstructorLowPriority2:

    implicit def AssertKyoConstructor[R, R1, E, A <: TestResult]
        : CheckConstructor.WithOut[R, A < R1 & Abort[E], R & R1, E] =
        new CheckConstructor[R, A < R1 & Abort[E]]:
            type OutEnvironment = R & R1
            type OutError       = E
            def apply(input: => A < R1 & Abort[E]): TestResult < Env[OutEnvironment] & Abort[OutError] =
                input.asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
end CheckConstructorLowPriority1

trait CheckConstructorLowPriority2 extends CheckConstructorLowPriority3:

    // implicit def AssertZSTMConstructor[R, R1, E, A <: TestResult]
    //     : CheckConstructor.WithOut[R, TRef[A], R & R1, E] =
    //     new CheckConstructor[R, TRef[A]]:
    //         type OutEnvironment = R & R1
    //         type OutError       = E
    //         def apply(input: => TRef[A]): TestResult < Env[OutEnvironment] & Abort[OutError] =
    //             Kyos.get(input.use(_)).asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
end CheckConstructorLowPriority2

trait CheckConstructorLowPriority3:

    implicit def AssertEitherConstructor[R, E, A <: TestResult]: CheckConstructor.WithOut[R, Either[E, A], R, E] =
        new CheckConstructor[R, Either[E, A]]:
            type OutEnvironment = R
            type OutError       = E
            def apply(input: => Either[E, A]): TestResult < Env[OutEnvironment] & Abort[OutError] =
                input match
                    case Right(a) => Kyo.pure(a).asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
                    case Left(e)  => Abort.fail(e.asInstanceOf[OutError]).asInstanceOf[TestResult < Env[OutEnvironment] & Abort[OutError]]
end CheckConstructorLowPriority3
