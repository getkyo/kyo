package kyo2.kernel

import kyo.Tag
import kyo2.Maybe
import kyo2.bug

opaque type Context = Map[Tag[Any], AnyRef]

object Context:
    val empty: Context = Map.empty

    extension (context: Context)
        def isEmpty = context eq Map.empty

        private[kernel] def get[A, E <: ContextEffect[A]](tag: Tag[E]): Maybe[A] =
            if !context.contains(tag.erased) then Maybe.empty
            else Maybe(context(tag.erased).asInstanceOf[A])

        private[kernel] def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            context.updated(tag.erased, value.asInstanceOf[AnyRef])
    end extension
end Context
