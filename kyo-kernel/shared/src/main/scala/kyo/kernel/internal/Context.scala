package kyo.kernel.internal

import Context.internal.*
import kyo.Flat
import kyo.Tag
import kyo.bug
import kyo.kernel.*

private[kyo] opaque type Context = Map[Tag[Any], AnyRef]

private[kyo] object Context:
    inline given Flat[Context] = Flat.unsafe.bypass

    val empty: Context = Map.empty

    extension (self: Context)
        def isEmpty = self eq empty

        def contains[E <: (ContextEffect[?] | IsolationFlag)](tag: Tag[E]): Boolean =
            self.contains(tag.erased)

        def inherit: Context =
            if !contains(Tag[IsolationFlag]) then self
            else
                self.filterNot { (k, _) =>
                    k <:< Tag[IsolationFlag] || k <:< Tag[ContextEffect.Isolated]
                }

        inline def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
            if !contains(tag) then default
            else self(tag.erased).asInstanceOf[B]

        private[kyo] def get[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            getOrElse(tag, bug(s"Missing value for context effect '${tag}'. Values: $self"))

        private[kernel] def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            val newContext = self.updated(tag.erased, value.asInstanceOf[AnyRef])
            if tag <:< Tag[ContextEffect.Isolated] then
                newContext.updated(Tag[IsolationFlag].erased, IsolationFlag)
            else
                newContext
            end if
        end set
    end extension

    object internal:
        class IsolationFlag
        object IsolationFlag extends IsolationFlag
end Context
