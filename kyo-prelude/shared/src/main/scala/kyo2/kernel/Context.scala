package kyo2.kernel

import kyo.Tag
import kyo2.bug

opaque type Context = Map[Tag[Any], AnyRef]

object Context:
    val empty: Context = Map.empty

    extension (context: Context)
        inline def isEmpty = context eq empty

        inline def contains[A, E <: ContextEffect[A]](tag: Tag[E]): Boolean =
            context.contains(tag.erased)

        inline def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
            if !contains(tag) then default
            else context(tag.erased).asInstanceOf[B]

        private[kernel] inline def get[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            getOrElse(tag, bug(s"Missing value for context effect '${tag}'. Values: $context"))

        private[kernel] inline def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            context.updated(tag.erased, value.asInstanceOf[AnyRef])
    end extension
end Context
