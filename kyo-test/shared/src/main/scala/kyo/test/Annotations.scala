package kyo.test

import kyo.*
import scala.collection.immutable.SortedSet

trait Annotations:
    def annotate[V](key: TestAnnotation[V], value: V): Unit < Env[Annotations]
    def get[V](key: TestAnnotation[V]): V < Env[Annotations]
    def withAnnotation[R, E](effect: TestSuccess < (Env[R] & Abort[E] & IO)): TestSuccess < (Env[R] & Abort[E] & IO)
    def supervisedFibers: SortedSet[Fiber[Any, Any]] < Env[Annotations]
    private[test] def unsafe: UnsafeAPI

    private[test] trait UnsafeAPI:
        def annotate[V](key: TestAnnotation[V], value: V): Unit
end Annotations

object Annotations:
    final case class Test(ref: AtomicRef[TestAnnotationMap]) extends Annotations:
        def annotate[V](key: TestAnnotation[V], value: V): Unit < IO =
            ref.update(_.annotate(key, value))

        def get[V](key: TestAnnotation[V]): V < IO =
            ref.get.map(_.get(key))

        def withAnnotation[R, E](effect: TestSuccess < (Env[R] & Abort[E] & IO)): TestSuccess < (Env[R] & Abort[E] & IO) =
            effect.mapAbort {
                case Abort.Failure(e) => ref.get.map(e.annotated).flatMap(Abort.fail)
                case Abort.Success(a) => ref.get.map(a.annotated)
            }

        def supervisedFibers: SortedSet[Fiber[Any, Any]] < IO =
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
                def annotate[V](key: TestAnnotation[V], value: V): Unit =
                    ref.unsafe.update(_.annotate(key, value))
    end Test

    def annotate[V](key: TestAnnotation[V], value: V): Unit < Env[Annotations] =
        Env.get[Annotations].map(_.annotate(key, value))

    def get[V](key: TestAnnotation[V]): V < Env[Annotations] =
        Env.get[Annotations].map(_.get(key))

    def supervisedFibers: SortedSet[Fiber[Any, Any]] < Env[Annotations] =
        Env.get[Annotations].map(_.supervisedFibers)

    val live: Annotations < IO =
        for
            ref <- AtomicRef.init(TestAnnotationMap.empty)
            annotations = Test(ref)
        yield annotations
end Annotations
