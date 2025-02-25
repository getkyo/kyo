package kyo.test

import Spec.*
import kyo.*
import kyo.test.*

/** A `Spec[R, E]` is the backbone of Kyo Test. Every spec is either a suite, which contains other specs, or a test. All specs require an
  * environment of type `R` and may potentially fail with an error of type `E`.
  */
final case class Spec[-R, +E](caseValue: SpecCase[R, E, Spec[R, E]]) extends KyoSpecVersionSpecific[R]:
    self =>

    /** Combines this spec with the specified spec. */
    def +[R1 <: R, E1 >: E](that: Spec[R1, E1]): Spec[R1, E1] =
        (self.caseValue, that.caseValue) match
            case (MultipleCase(selfSpecs), MultipleCase(thatSpecs)) => Spec.multiple(selfSpecs ++ thatSpecs)
            case (MultipleCase(selfSpecs), _)                       => Spec.multiple(selfSpecs :+ that)
            case (_, MultipleCase(thatSpecs))                       => Spec.multiple(self +: thatSpecs)
            case _                                                  => Spec.multiple(Chunk(self, that))

    /** Syntax for adding aspects. */
    final def @@[R0 <: R, R1 <: R, E0 >: E, E1 >: E0](aspect: TestAspect[R0, R1, E0, E1])(implicit trace: Trace): Spec[R1, E0] =
        aspect(self)

    /** Annotates each test in this spec with the specified test annotation. */
    final def annotate[V](key: TestAnnotation[V], value: V)(implicit trace: Trace): Spec[R, E] =
        transform {
            case TestCase(test, annotations) => TestCase(test, annotations.annotate(key, value))
            case other                       => other
        }

    /** Returns a new spec with the annotation map at each node. */
    final def annotated(implicit trace: Trace): Spec[R, E] =
        transform {
            case ExecCase(exec, spec)        => ExecCase(exec, spec)
            case LabeledCase(label, spec)    => LabeledCase(label, spec)
            case ScopedCase(scoped)          => ScopedCase(scoped)
            case MultipleCase(specs)         => MultipleCase(specs)
            case TestCase(test, annotations) => TestCase(Annotations.withAnnotation(test), annotations)
        }

    /** Returns an effect that models execution of this spec. */
    final def execute(defExec: ExecutionStrategy)(implicit trace: Trace): Spec[Any, E] < IO =
        // Assuming a Kyo equivalent of environmentWithKyo and foreachExec exists
        Env.provideEnvironment(self).foreachExec(defExec)(
            cause => Exit.failCause(cause),
            success => Kyo.pure(success)
        )

    /** Returns a new spec with only those tests with annotations satisfying the specified predicate. */
    final def filterAnnotations[V](key: TestAnnotation[V])(f: V => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
        caseValue match
            case ExecCase(exec, spec)     => spec.filterAnnotations(key)(f).map(s => Spec.exec(exec, s))
            case LabeledCase(label, spec) => spec.filterAnnotations(key)(f).map(s => Spec.labeled(label, s))
            case ScopedCase(scoped)       => Some(Spec.scoped(scoped.map(_.filterAnnotations(key)(f).getOrElse(Spec.empty))))
            case MultipleCase(specs) =>
                val filtered = specs.flatMap(_.filterAnnotations(key)(f))
                if filtered.isEmpty then None else Some(Spec.multiple(filtered))
            case TestCase(test, annotations) =>
                if f(annotations.get(key)) then Some(Spec.test(test, annotations)) else None

    /** Returns a new spec with only those suites and tests satisfying the specified predicate. */
    final def filterLabels(f: String => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
        caseValue match
            case ExecCase(exec, spec) => spec.filterLabels(f).map(s => Spec.exec(exec, s))
            case LabeledCase(label, spec) =>
                if f(label) then Some(Spec.labeled(label, spec))
                else spec.filterLabels(f).map(s => Spec.labeled(label, s))
            case ScopedCase(scoped) => Some(Spec.scoped(scoped.map(_.filterLabels(f).getOrElse(Spec.empty))))
            case MultipleCase(specs) =>
                val filtered = specs.flatMap(_.filterLabels(f))
                if filtered.isEmpty then None else Some(Spec.multiple(filtered))
            case TestCase(_, _) => None

    /** Returns a new spec with only those tests with tags satisfying the specified predicate. */
    final def filterTags(f: String => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
        filterAnnotations(TestAnnotation.tagged)(_.exists(f))

    /** Returns a new spec with only those tests except for the ones with tags satisfying the predicate. */
    final def filterNotTags(f: String => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
        filterAnnotations(TestAnnotation.tagged)(t => !t.exists(f))

    /** Effectfully folds over all nodes according to the execution strategy of suites. */
    final def foldScoped[R1 <: R, E1, Z](defExec: ExecutionStrategy)(f: SpecCase[R, E, Z] => Z < (Env[R1 with Scope] & Abort[E1]))(implicit
        trace: Frame
    ): Z < (Env[R1 with Scope] & Abort[E1]) =
        caseValue match
            case ExecCase(exec, spec)     => spec.foldScoped[R1, E1, Z](exec)(f).flatMap(z => f(ExecCase(exec, z)))
            case LabeledCase(label, spec) => spec.foldScoped[R1, E1, Z](defExec)(f).flatMap(z => f(LabeledCase(label, z)))
            case ScopedCase(scoped) =>
                scoped.foldCause(
                    c => f(ScopedCase(Exit.failCause(c))),
                    spec => spec.foldScoped[R1, E1, Z](defExec)(f).flatMap(z => f(ScopedCase(Kyo.pure(z))))
                )
            case MultipleCase(specs) =>
                Kyo.foreachExec(specs)(defExec)(spec => Scope.scoped(spec.foldScoped[R1, E1, Z](defExec)(f)))
                    .flatMap(zs => f(MultipleCase(zs)))
            case t @ TestCase(_, _) => f(t)

    /** Iterates over the spec with the specified default execution strategy, transforming every test with the provided function. */
    final def foreachExec[R1 <: R, E1](defExec: ExecutionStrategy)(
        failure: Cause[TestFailure[E]] => (TestFailure[E1] < IO),
        success: TestSuccess => (TestSuccess < IO)
    )(implicit trace: Trace): Spec[R1, E1] < IO =
        foldScoped[R1, Nothing, Spec[R1, E1]](defExec) {
            case ExecCase(exec, spec)     => Kyo.pure(Spec.exec(exec, spec))
            case LabeledCase(label, spec) => Kyo.pure(Spec.labeled(label, spec))
            case ScopedCase(scoped) =>
                scoped.foldCause(
                    c => Spec.test(failure(c), TestAnnotationMap.empty),
                    t => Spec.scoped(Kyo.pure(t))
                )
            case MultipleCase(specs) => Kyo.pure(Spec.multiple(specs))
            case TestCase(test, annotations) =>
                test.foldCause(
                    e => Spec.test(failure(e), annotations),
                    t => Spec.test(success(t).mapError(TestFailure.fail), annotations)
                )
        }

    final def foreach[R1 <: R, E1](
        failure: Cause[TestFailure[E]] => (TestFailure[E1] < IO),
        success: TestSuccess => (TestSuccess < IO)
    )(implicit trace: Trace): Spec[R1, E1] < IO =
        foreachExec(ExecutionStrategy.Sequential)(failure, success)

    final def foreachPar[R1 <: R, E1](
        failure: Cause[TestFailure[E]] => (TestFailure[E1] < IO),
        success: TestSuccess => (TestSuccess < IO)
    )(implicit trace: Trace): Spec[R1, E1] < IO =
        foreachExec(ExecutionStrategy.Parallel)(failure, success)

    final def foreachParN[R1 <: R, E1](n: Int)(
        failure: Cause[TestFailure[E]] => (TestFailure[E1] < IO),
        success: TestSuccess => (TestSuccess < IO)
    )(implicit trace: Trace): Spec[R1, E1] < IO =
        foreachExec(ExecutionStrategy.ParallelN(n))(failure, success)

    final def mapError[E1](f: E => E1)(implicit ev: CanFail[E], trace: Trace): Spec[R, E1] =
        transform {
            case ExecCase(exec, spec)        => ExecCase(exec, spec)
            case LabeledCase(label, spec)    => LabeledCase(label, spec)
            case ScopedCase(scoped)          => ScopedCase(scoped.mapError(_.map(f)))
            case MultipleCase(specs)         => MultipleCase(specs)
            case TestCase(test, annotations) => TestCase(test.mapError(_.map(f)), annotations)
        }

    final def mapLabel(f: String => String)(implicit trace: Trace): Spec[R, E] =
        transform {
            case ExecCase(exec, spec)        => ExecCase(exec, spec)
            case LabeledCase(label, spec)    => LabeledCase(f(label), spec)
            case ScopedCase(scoped)          => ScopedCase(scoped)
            case MultipleCase(specs)         => MultipleCase(specs)
            case TestCase(test, annotations) => TestCase(test, annotations)
        }

    @deprecated("use provideLayer", "2.0.2")
    def provideCustomLayer[E1 >: E, R1](layer: Layer[TestEnvironment, E1, R1])(implicit
        ev: TestEnvironment with R1 <:< R,
        tagged: Tag[R1],
        trace: Trace
    ): Spec[TestEnvironment, E1] =
        provideSomeLayer(layer)

    // Additional conversion methods would be added here
end Spec

object Spec:
    // Definitions for Spec cases
    sealed trait SpecCase[-R, +E, +A]
    final case class ExecCase[R, E, A](exec: ExecutionStrategy, spec: A)                      extends SpecCase[R, E, A]
    final case class LabeledCase[R, E, A](label: String, spec: A)                             extends SpecCase[R, E, A]
    final case class ScopedCase[R, E, A](scoped: Kyo[Spec[R, E]])                             extends SpecCase[R, E, Spec[R, E]]
    final case class MultipleCase[R, E, A](specs: Chunk[A])                                   extends SpecCase[R, E, A]
    final case class TestCase[R, E](test: TestRunnable[R, E], annotations: TestAnnotationMap) extends SpecCase[R, E, Nothing]

    def multiple[R, E](specs: Chunk[Spec[R, E]]): Spec[R, E]                              = Spec(MultipleCase(specs))
    def exec[R, E](exec: ExecutionStrategy, spec: Spec[R, E]): Spec[R, E]                 = Spec(ExecCase(exec, spec))
    def labeled[R, E](label: String, spec: Spec[R, E]): Spec[R, E]                        = Spec(LabeledCase(label, spec))
    def scoped[R, E](scoped: E < Env[Spec[R] & Abort[E]])(implicit trace: Trace): Spec[R] = Spec(ScopedCase(scoped))
    def test[R, E](test: TestRunnable[R, E], annotations: TestAnnotationMap): Spec[R, E]  = Spec(TestCase(test, annotations))
    def empty[R, E]: Spec[R, E]                                                           = multiple(Chunk.empty)
end Spec
