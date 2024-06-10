package kyo

import kyo.core.*
import kyo.internal.Trace

sealed trait Vars[V] extends Effect[Vars.Input[V, *], Id]

object Vars:

    sealed trait Input[V, X]
    private object Input:
        case class Get[V]()             extends Input[V, V]
        case class Set[V](value: V)     extends Input[V, Unit]
        case class Update[V](f: V => V) extends Input[V, Unit]
        val _get   = Get[Any]()
        def get[V] = _get.asInstanceOf[Get[V]]
    end Input

    import Input.*

    inline def get[V](using inline tag: Tag[Vars[V]]): V < Vars[V] =
        suspend[V](tag, Input.get[V])

    class UseDsl[V](ign: Unit) extends AnyVal:
        inline def apply[T, S](inline f: V => T < S)(
            using inline tag: Tag[Vars[V]]
        ): T < (Vars[V] & S) =
            suspend[V](tag, Get(), f)
    end UseDsl

    inline def use[V]: UseDsl[V] = UseDsl(())

    inline def set[V](inline value: V)(using inline tag: Tag[Vars[V]]): Unit < Vars[V] =
        suspend(tag, Set(value))

    inline def update[V](inline f: V => V)(using inline tag: Tag[Vars[V]]): Unit < Vars[V] =
        suspend(tag, Update(f))

    case class RunDsl[V](ign: Unit) extends AnyVal:
        def apply[T, S](st: V)(v: T < (Vars[V] & S))(using tag: Tag[Vars[V]], t: Trace): T < S =
            handle.state(tag, st, v) {
                [C] =>
                    (input, state, cont) =>
                        input match
                            case Get() =>
                                (state, cont(state))
                            case Set(value) =>
                                (value, cont(()))
                            case Update(f) =>
                                (f(state), cont(()))
            }
    end RunDsl

    inline def run[V >: Nothing]: RunDsl[V] = RunDsl(())

end Vars
