package kyo.test

import kyo.*
// Assumed definitions for required types in the kyo-test module:
// - TestAnnotationMap: a map-like structure for test annotations
// - TestResult: a type representing the result of an assertion
// - Cause: a type representing error causes, with methods `fail`, `die`, and `map`

sealed abstract class TestFailure[+E]:
    self =>

    /** Retrieves the annotations associated with this test failure. */
    def annotations: TestAnnotationMap

    /** Annotates this test failure with the specified test annotations. */
    def annotated(annotations: TestAnnotationMap): TestFailure[E] = self match
        case a: TestFailure.Assertion  => TestFailure.Assertion(a.result, this.annotations ++ annotations)
        case r: TestFailure.Runtime[E] => TestFailure.Runtime(r.cause, this.annotations ++ annotations)

    /** Transforms the error type of this test failure with the specified function. */
    def map[E2](f: E => E2): TestFailure[E2] = self match
        case a: TestFailure.Assertion  => TestFailure.Assertion(a.result, a.annotations)
        case r: TestFailure.Runtime[E] => TestFailure.Runtime(r.cause.map(f).asInstanceOf[Result.Error[E2]], r.annotations)
end TestFailure

object TestFailure:
    final case class Assertion(result: TestResult, annotations: TestAnnotationMap = TestAnnotationMap.empty)
        extends TestFailure[Nothing]

    final case class Runtime[+E](cause: Result.Error[E], annotations: TestAnnotationMap = TestAnnotationMap.empty)
        extends TestFailure[E]

    /** Constructs an assertion failure with the specified result. */
    def assertion(result: TestResult): TestFailure[Nothing] =
        Assertion(result, TestAnnotationMap.empty)

    /** Constructs a runtime failure that dies with the specified `Throwable`. */
    def die(t: Throwable): TestFailure[Throwable] =
        failCause(Result.Panic(t))

    /** Constructs a runtime failure that fails with the specified error. */
    def fail[E](e: E): TestFailure[E] =
        failCause(Result.Failure(e))

    /** Constructs a runtime failure with the specified cause. */
    def failCause[E](cause: Result.Error[E]): TestFailure[E] =
        Runtime(cause, TestAnnotationMap.empty)
end TestFailure
