package kyo

import Vars.*
import kyo.core.*

case class Vars[V](private val tag: Tag[Any])
    extends Effect[[T] =>> State[V, T], Vars[V]]

object Vars:

    opaque type State[V, +T] = T | Op[V, T]

    sealed private trait Op[V, +T]
    private case object Get         extends Op[Any, Any]
    private case class Set[V](v: V) extends Op[V, Unit]

    private def vars[T](using t: Tag[T]): Vars[T] =
        Vars(t.asInstanceOf[Tag[Any]])

    def get[V: Tag]: V < Vars[V] =
        vars[V].suspend(Get.asInstanceOf[Op[V, V]])

    def set[V: Tag](v: V): Unit < Vars[V] =
        vars[V].suspend(Set[V](v))

    def update[V: Tag](f: V => V): Unit < Vars[V] =
        get[V].map(f).map(set[V])

    def run[V: Tag, T: Flat, S](init: V)(v: T < (Vars[V] & S)): T < S =
        var curr = init
        given Handler[[T] =>> State[V, T], Vars[V], Any] with
            def pure[T: Flat](v: T) = v
            def apply[T, U: Flat, S2](m: State[V, T], f: T => U < (Vars[V] & S2)) =
                m match
                    case Get =>
                        f(curr.asInstanceOf[T])
                    case Set(v) =>
                        curr = v.asInstanceOf[V]
                        f(().asInstanceOf[T])
                    case v =>
                        f(v.asInstanceOf[T])
                end match
            end apply
        end given

        vars[V]
            .handle[T, S, Any](v)
            .map {
                case Get    => curr.asInstanceOf[T]
                case Set(v) => ().asInstanceOf[T]
                case v      => v.asInstanceOf[T]
            }
    end run
end Vars
