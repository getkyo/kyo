package kyo.proto.kernel.internal

import kyo.Tag
import kyo.TypeMap
import kyo.internal.NotIntersection
import kyo.proto.kernel.ContextEffect

opaque type Context = TypeMap[Any]

object Context:

    val empty: Context = TypeMap.empty

    extension (self: Context)
        inline def contains[E](tag: Tag[E]): Boolean =
            self <:< tag

        inline def apply[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            // Unsafe: erasure at the storage boundary, the map holds the value untyped
            self.get[Any](using tag.erased, NotIntersection.singleton).asInstanceOf[A]

        inline def update[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            self.add[Any](value)(using tag.erased)
    end extension
end Context
