package kyo.proto.kernel.internal

import kyo.Tag
import kyo.TypeMap
import kyo.internal.NotIntersection
import kyo.proto.kernel.ContextEffect

opaque type Context = TypeMap[Any]

object Context:

    private[kernel] val empty: Context = TypeMap.empty

    extension (self: Context)
        private[kernel] inline def contains[E](tag: Tag[E]): Boolean =
            self <:< tag

        private[kernel] inline def apply[A, E <: ContextEffect[A]](tag: Tag[E]): A =

            self.get[Any](using tag.erased, NotIntersection.singleton).asInstanceOf[A]

        private[kernel] inline def update[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            self.add[Any](value)(using tag.erased)

        private[kernel] inline def updateErased[E](tag: Tag[E], value: Any): Context =
            self.add[Any](value)(using tag.erased)

        private[kernel] inline def remove[E](tag: Tag[E]): Context =
            TypeMap.removeExact(self, tag.erased)
    end extension
end Context
