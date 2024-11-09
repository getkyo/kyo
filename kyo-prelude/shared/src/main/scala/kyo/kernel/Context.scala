package kyo.kernel

import Context.internal.*
import kyo.Flat
import kyo.Tag
import kyo.bug

opaque type Context = Map[Tag[Any] | IsolationFlag, AnyRef]

object Context:
    inline given Flat[Context] = Flat.unsafe.bypass

    val empty: Context = Map.empty

    extension (self: Context)
        def isEmpty = self eq empty

        def contains[A, E <: ContextEffect[A]](tag: Tag[E]): Boolean =
            self.contains(tag.erased)

        def inherit: Context =
            if !self.contains(IsolationFlag) then self
            else
                self.filter { (k, _) =>
                    !IsolationFlag.equals(k) &&
                    !(k.asInstanceOf[Tag[Any]] <:< Tag[ContextEffect.Isolated])
                }

        inline def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
            if !contains(tag) then default
            else self(tag.erased).asInstanceOf[B]

        private[kyo] def get[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            getOrElse(tag, bug(s"Missing value for context effect '${tag}'. Values: $self"))

        private[kernel] def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            val newContext = self.updated(tag.erased, value.asInstanceOf[AnyRef])
            if tag <:< Tag[ContextEffect.Isolated] then
                newContext.updated(IsolationFlag, IsolationFlag)
            else
                newContext
            end if
        end set
    end extension

    private[kyo] object internal:
        class IsolationFlag
        object IsolationFlag extends IsolationFlag
end Context
