package kyo.prototype

import kyo.Tag

opaque type Context = Map[Tag[Any], AnyRef]

object Context:

    val empty: Context = Map.empty

    extension (self: Context)
        private[prototype] def contains[E <: ContextEffect[?]](tag: Tag[E]): Boolean =
            self.contains(tag.erased)

        private[prototype] def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], default: => B): B =
            self.get(tag.erased) match
                case Some(value) => value.asInstanceOf[B]
                case None        => default

        private[prototype] def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            self.updated(tag.erased, value.asInstanceOf[AnyRef])
    end extension
end Context
