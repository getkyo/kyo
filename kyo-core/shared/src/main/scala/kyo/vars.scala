package kyo

import Vars.*
import kyo.core.*

class Vars[-V](private val tag: Tag[Any]) extends Effect[[T] =>> State[V, T], Vars[V]]:
    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
        other match
            case other: Vars[?] =>
                other.tag == tag
            case _ =>
                false
end Vars

object Vars:

    opaque type State[-V, +T] = T | Op[V, T]

    sealed private trait Op[-V, +T]
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

    def run[T, S](v: T < (Vars[Nothing] & S))(using Flat[T < (Vars[Nothing] & S)]): T < S =
        v.asInstanceOf[T < S]

    def let[V: Tag, T: Flat, S, V1, V2](init: V)(v: T < (Vars[V1] & S))(
        using V1 => V | V2
    ): T < (Vars[V2] & S) =
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
            .handle[T, Vars[V2] & S, Any](v.asInstanceOf[T < (Vars[V] & Vars[V2] & S)])
            .map {
                case Get    => curr.asInstanceOf[T]
                case Set(v) => ().asInstanceOf[T]
                case v      => v.asInstanceOf[T]
            }
    end let
end Vars
