package kyo.test

import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.test.TestAspectAtLeastR
import kyo.test.TestAspectPoly
import scala.collection.immutable.SortedSet

/** A `TestAspect` is an aspect that can be weaved into specs. You can think of an aspect as a polymorphic function, capable of transforming
  * one test into another, possibly enlarging the environment or error type.
  */
abstract class TestAspect[+LowerR, -UpperR, +LowerE, -UpperE]:
    self =>

    def some[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: Spec[R, E])(using trace: Trace): Spec[R, E]

    final def apply[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
        all(spec)

    final def all[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
        some(spec)

    final def >>>[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
        that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
    ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
        new TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]:
            def some[R >: LowerR1 <: UpperR1, E >: LowerE1 <: UpperE1](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                that.some(self.some(spec))

    final def @@[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
        that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
    ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
        self >>> that

    final def andThen[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
        that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
    ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
        self >>> that
end TestAspect

object TestAspect extends TimeoutVariants:

    type CheckAspect = KyoAspect[Nothing, Any, Nothing, Any, TestResult, TestResult] // Assuming a KyoAspect exists

    /** An aspect that returns the tests unchanged */
    val identity: TestAspectPoly =
        new TestAspectPoly:
            def some[R, E](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                spec

    /** An aspect that marks tests as ignored. */
    val ignore: TestAspectPoly =
        new TestAspectPoly:
            def some[R, E](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                spec.when(false)

    /** Constructs an aspect that runs the specified effect after every test. */
    def after[R0, E0](effect: Any < Env[R0] & Abort[E0]): TestAspect[Nothing, R0, E0, Any] =
        new TestAspect.PerTest[Nothing, R0, E0, Any]:
            def perTest[R <: R0, E >: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                test.attempt.flatMap {
                    case Right(result) =>
                        effect.catchAllCause(cause => Abort.fail(TestFailure.Runtime(cause))) *> result
                    case Left(err) =>
                        effect.catchAllCause(cause => Abort.fail(TestFailure.Runtime(cause))) *> Abort.fail(err)
                }

    /** Constructs an aspect that runs the specified effect after all tests. */
    def afterAll[R0](effect: Any < Env[R0] & Abort[Nothing]): TestAspect[Nothing, R0, Nothing, Any] =
        aroundAll(unit, effect)

    /** Constructs an aspect that runs the specified effect after all tests if there is at least one failure. */
    def afterAllFailure[R0](f: Any < Env[R0] & Abort[Nothing]): TestAspect[Nothing, R0, Nothing, Any] =
        new TestAspect[Nothing, R0, Nothing, Any]:
            def some[R <: R0, E](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                Spec.scoped[R](
                    AtomicRef.init(false).flatMap { failure =>
                        Kyo.acquireRelease(unit)(_ => f.whenKyo(failure.get)) *>
                            afterFailure(failure.set(true))(spec)
                    }
                )

    /** Constructs an aspect that runs the specified effect after all tests if there are no failures. */
    def afterAllSuccess[R0](f: Any < Env[R0] & Abort[Nothing]): TestAspect[Nothing, R0, Nothing, Any] =
        new TestAspect[Nothing, R0, Nothing, Any]:
            def some[R <: R0, E](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                Spec.scoped[R](
                    AtomicRef.init(true).flatMap { success =>
                        Kyo.acquireRelease(unit)(_ => f.whenKyo(success.get)) *>
                            afterFailure(success.set(false))(spec)
                    }
                )

    /** Constructs an aspect that runs the specified effect after every failed test. */
    def afterFailure[R0, E0](effect: Any < Env[R0] & Abort[E0]): TestAspect[Nothing, R0, E0, Any] =
        new TestAspect.PerTest[Nothing, R0, E0, Any]:
            def perTest[R <: R0, E >: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                test.tapErrorCause(_ => effect.catchAllCause(cause => Abort.fail(TestFailure.Runtime(cause))))

    /** Constructs an aspect that runs the specified effect after every successful test. */
    def afterSuccess[R0, E0](effect: Any < Env[R0] & Abort[E0]): TestAspect[Nothing, R0, E0, Any] =
        new TestAspect.PerTest[Nothing, R0, E0, Any]:
            def perTest[R <: R0, E >: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                test.tap(_ => effect.catchAllCause(cause => Abort.fail(TestFailure.Runtime(cause))))

    /** Annotates tests with the specified test annotation. */
    def annotate[V](key: TestAnnotation[V], value: V): TestAspectPoly =
        new TestAspectPoly:
            def some[R, E](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                spec.annotate(key, value)

    /** Constructs an aspect that evaluates every test between two effects, `before` and `after`, where the result of `before` can be used
      * in `after`.
      */
    def aroundWith[R0, E0, A0](before: A0 < Env[R0] & Abort[E0])(after: A0 => Any < Env[R0] & Abort[Nothing])
        : TestAspect[Nothing, R0, E0, Any] =
        new TestAspect.PerTest[Nothing, R0, E0, Any]:
            def perTest[R <: R0, E >: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                Kyo.acquireReleaseWith(before.catchAllCause(cause => Abort.fail(TestFailure.Runtime(cause))))(after)(_ => test)

    /** A less powerful variant of `around` where the result of `before` is not required by after. */
    def around[R0, E0](before: Any < Env[R0] & Abort[E0], after: Any < Env[R0] & Abort[Nothing]): TestAspect[Nothing, R0, E0, Any] =
        aroundWith(before)(_ => after)

    /** Constructs an aspect that evaluates all tests between two effects, `before` and `after`, where the result of `before` can be used in
      * `after`.
      */
    def aroundAllWith[R0, E0, A0](before: A0 < Env[R0] & Abort[E0])(after: A0 => Any < Env[R0] & Abort[Nothing])
        : TestAspect[Nothing, R0, E0, Any] =
        new TestAspect[Nothing, R0, E0, Any]:
            def some[R <: R0, E >: E0](spec: Spec[R, E])(using trace: Trace): Spec[R, E] =
                Spec.scoped[R](
                    Kyo.acquireRelease(before)(after).mapError(TestFailure.fail).as(spec)
                )

    /** A less powerful variant of `aroundAll` where the result of `before` is not required by `after`. */
    def aroundAll[R0, E0](before: Any < Env[R0] & Abort[E0], after: Any < Env[R0] & Abort[Nothing]): TestAspect[Nothing, R0, E0, Any] =
        aroundAllWith(before)(_ => after)

    /** Constructs an aspect that evaluates every test inside the context of the scoped function. */
    def aroundTest[R0, E0](scoped: TestSuccess => Any < Env[R0] & Abort[TestFailure[E0]]): TestAspect[Nothing, R0, E0, Any] =
        new TestAspect.PerTest[Nothing, R0, E0, Any]:
            def perTest[R <: R0, E >: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                Kyo.scoped(scoped).flatMap(f => test.flatMap(f))

    /** Constructs a simple monomorphic aspect that only works with the specified environment and error type. */
    def aspect[R0, E0](f: TestSuccess < Env[R0] & Abort[TestFailure[E0]] => TestSuccess < Env[R0] & Abort[TestFailure[E0]])
        : TestAspect[R0, R0, E0, E0] =
        new TestAspect.PerTest[R0, R0, E0, E0]:
            def perTest[R >: R0 <: R0, E >: E0 <: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                f(test)

    /** Constructs an aspect that runs the specified effect before every test. */
    def before[R0, E0](effect: Any < Env[R0] & Abort[E0]): TestAspect[Nothing, R0, E0, Any] =
        new TestAspect.PerTest[Nothing, R0, E0, Any]:
            def perTest[R <: R0, E >: E0](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                effect.catchAllCause(cause => Abort.fail(TestFailure.failCause(cause))) *> test

    /** Constructs an aspect that runs the specified effect a single time before all tests. */
    def beforeAll[R0, E0](effect: Any < Env[R0] & Abort[E0]): TestAspect[Nothing, R0, E0, Any] =
        aroundAll(effect, unit)

    /** An aspect that runs each test on the blocking threadpool. Useful for tests that contain blocking code */
    val blocking: TestAspectPoly =
        new PerTest.Poly:
            def perTest[R, E](test: TestSuccess < Env[R] & Abort[TestFailure[E]])(using
                trace: Trace
            ): TestSuccess < Env[R] & Abort[TestFailure[E]] =
                Kyo.blocking(test)

    /** An aspect that applies the provided kyo aspect to each sample of all checks in the test. (Implementation for checksKyo is partially
      * shown)
      */
    def checks(aspect: CheckAspect): TestAspectPoly =
        checksKyo(Kyo.pure(aspect)(using Trace.empty))

    def checksKyo[R, E](makeAspect: Any < Env[R] & Abort[E]): TestAspect[Nothing, R, E, Any] =
        new TestAspect[Nothing, R, E, Any]:
            def some[R1 <: R, E1 >: E](spec: Spec[R1, E1])(using trace: Trace): Spec[R1, E1] =
                spec.transform {
                    case Spec.TestCase(oldTest, annotations) =>
                        // Transformation logic for test cases with the provided aspect
                        Spec.TestCase(oldTest, annotations)
                    case other => other
                }
end TestAspect
