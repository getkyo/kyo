package kyo.test

import kyo.*
import scala.collection.immutable.SortedSet

/** The `Annotations` trait provides access to an annotation map that tests can add arbitrary annotations to. Each annotation consists of a
  * string identifier, an initial value, and a function for combining two values. Annotations form monoids and you can think of
  * `Annotations` as a more structured logging service or as a super polymorphic version of the writer monad effect.
  */
trait Annotations extends Serializable:
    def annotate[V](key: TestAnnotation[V], value: V)(implicit frame: Frame): Unit < Env[Annotations]
    def get[V](key: TestAnnotation[V])(implicit frame: Frame): V < Env[Annotations]
    def withAnnotation[R, E](effect: TestSuccess < (Env[R] & Abort[TestFailure[E]] & IO))(implicit
        frame: Frame
    ): TestSuccess < (Env[R] & Abort[E] & IO)
    def supervisedFibers(implicit frame: Frame): SortedSet[Fiber[Any, Any]] < Env[Annotations]
    private[test] def unsafe: UnsafeAPI

    private[test] trait UnsafeAPI:
        def annotate[V](key: TestAnnotation[V], value: V): Unit
end Annotations

object Annotations:
    val tag: Tag[Annotations] = Tag[Annotations]
    final case class Test(ref: AtomicRef[TestAnnotationMap]) extends Annotations:
        def annotate[V](key: TestAnnotation[V], value: V)(implicit frame: Frame): Unit < IO =
            ref.update(_.annotate(key, value))

        def get[V](key: TestAnnotation[V])(implicit frame: Frame): V < IO =
            ref.get.map(_.get[V](key))

        def withAnnotation[R, E](effect: TestSuccess < (Env[R] & Abort[TestFailure[E]] & IO))(implicit
            frame: Frame
        ): TestSuccess < (Env[R] & Abort[TestFailure[E]] & IO) =
            effect.foldAbort(
                onSuccess = a => ref.get.map(a.annotated),
                onFail = e => ref.get.map(e.annotated).flatMap(Abort.fail)
            )

        def supervisedFibers(implicit frame: Frame): SortedSet[Fiber[Any, Any]] < IO =
            Fiber.current.map { fiberId =>
                get(TestAnnotation.fibers).map {
                    case Left(_) => SortedSet.empty[Fiber[Any, Any]]
                    case Right(refs) =>
                        refs.foldLeft(SortedSet.empty[Fiber[Any, Any]]) { (set, ref) =>
                            set ++ ref.get
                        }.filter(_.id != fiberId)
                }
            }

        private[test] def unsafe: UnsafeAPI =
            new UnsafeAPI:
                def annotate[V](key: TestAnnotation[V], value: V)(implicit frame: Frame, allow: AllowUnsafe): Unit =
                    ref.unsafe.update(_.annotate(key, value))
    end Test

    def annotate[V](key: TestAnnotation[V], value: V)(implicit frame: Frame): Unit < Env[Annotations] =
        Env.get[Annotations].map(_.annotate(key, value))

    def get[V](key: TestAnnotation[V])(implicit frame: Frame): V < Env[Annotations] =
        Env.get[Annotations].map(_.get(key))

    def supervisedFibers(implicit frame: Frame): SortedSet[Fiber[Any, Any]] < Env[Annotations] =
        Env.get[Annotations].map(_.supervisedFibers)

    val live: Layer[Annotations, IO] =
        Layer(
            for
                ref <- AtomicRef.init(TestAnnotationMap.empty)
                annotations = Test(ref)
            yield annotations
        )
end Annotations
