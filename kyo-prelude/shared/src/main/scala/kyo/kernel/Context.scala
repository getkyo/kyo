package kyo.kernel

import kyo.Tag
import kyo.bug

opaque type Context = Map[Tag[Any], AnyRef]

object Context:
    inline given Flat[Context] = Flat.unsafe.bypass

    val empty: Context = Map.empty

    extension (context: Context)
        inline def isEmpty = context eq empty

        inline def contains[A, E <: ContextEffect[A]](tag: Tag[E]): Boolean =
            context.contains(tag.erased)

        inline def inherit: Context =
            context.filterNot(_._1 <:< Tag[ContextEffect.Isolated])

        inline def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
            if !contains(tag) then default
            else context(tag.erased).asInstanceOf[B]

        private[kyo] inline def get[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            getOrElse(tag, bug(s"Missing value for context effect '${tag}'. Values: $context"))

        private[kernel] inline def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            context.updated(tag.erased, value.asInstanceOf[AnyRef])
    end extension
end Context
