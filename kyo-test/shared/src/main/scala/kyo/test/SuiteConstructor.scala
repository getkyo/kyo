package kyo.test

import kyo.*

// SuiteConstructor is responsible for converting various types of specs into a unified Spec using Kyo effects.
trait SuiteConstructor[In]:
    type OutEnvironment
    type OutError
    def apply(spec: In)(implicit trace: Trace): Spec[OutEnvironment, OutError]
end SuiteConstructor

object SuiteConstructor extends SuiteConstructorLowPriority1:

    type WithOut[In, OutEnvironment0, OutError0] =
        SuiteConstructor[In] {
            type OutEnvironment = OutEnvironment0
            type OutError       = OutError0
        }

    implicit val NothingConstructor: SuiteConstructor.WithOut[Nothing, Any, Nothing] =
        new SuiteConstructor[Nothing]:
            type OutEnvironment = Any
            type OutError       = Nothing
            def apply(spec: Nothing)(implicit trace: Trace): Spec[Any, Nothing] =
                Spec.multiple(Chunk.empty)
end SuiteConstructor

trait SuiteConstructorLowPriority1 extends SuiteConstructorLowPriority2:

    implicit def SpecConstructor[R, E]: SuiteConstructor.WithOut[Spec[R, E], R, E] =
        new SuiteConstructor[Spec[R, E]]:
            type OutEnvironment = R
            type OutError       = E
            def apply(spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] = spec
end SuiteConstructorLowPriority1

trait SuiteConstructorLowPriority2 extends SuiteConstructorLowPriority3:

    implicit def IterableConstructor[R, E, Collection[+Element] <: Iterable[Element]]
        : SuiteConstructor.WithOut[Collection[Spec[R, E]], R, E] =
        new SuiteConstructor[Collection[Spec[R, E]]]:
            type OutEnvironment = R
            type OutError       = E
            def apply(specs: Collection[Spec[R, E]])(implicit trace: Trace): Spec[R, E] =
                Spec.multiple(Chunk.Indexed.from(specs))
end SuiteConstructorLowPriority2

trait SuiteConstructorLowPriority3 extends SuiteConstructorLowPriority4:

    implicit def KyoConstructor[R, R1, E <: E2, E1 <: E2, E2, Collection[+Element] <: Iterable[Element]]
        : SuiteConstructor.WithOut[Any < Env[R] & Abort[Collection[Spec[R1, E1]]], R & R1, E2] =
        new SuiteConstructor[Any < Env[R] & Abort[Collection[Spec[R1, E1]]]]:
            type OutEnvironment = R & R1
            type OutError       = E2
            def apply(specs: Collection[Spec[R1, E1]] < Env[R] & Abort[E])(implicit trace: Trace): Spec[R & R1, E2] =
                Spec.scoped(specs.foldAbort(
                    onSuccess = specs => Spec.multiple(Chunk.Indexed.from(specs)),
                    onFail = TestFailure.fail,
                    onPanic = TestFailure.die
                ))
end SuiteConstructorLowPriority3

trait SuiteConstructorLowPriority4:

    // implicit def KSTMConstructor[R, R1, E <: E2, E1 <: E2, E2, Collection[+Element] <: Iterable[Element]]
    //     : SuiteConstructor.WithOut[TRef[Collection[Spec[R1, E1]]], R & R1, E2] =
    //     new SuiteConstructor[TRef[Collection[Spec[R1, E1]]]]:
    //         type OutEnvironment = R & R1
    //         type OutError       = E2
    //         def apply(specs: TRef[Collection[Spec[R1, E1]]])(implicit trace: Trace): Spec[R & R1, E2] =
    //             Spec.scoped(specs.foldAbort(onSuccess = specs => Spec.multiple(Chunk.Indexed.from(specs)), onFail = TestFailure.fail, onPanic = TestFailure.die).commit)
end SuiteConstructorLowPriority4
