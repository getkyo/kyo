package kyo.proto.kernel.internal

import kyo.Tag
import kyo.TypeMap
import kyo.internal.NotIntersection
import kyo.proto.kernel.ContextEffect

// Public as a name because the context suspension's protocol mentions it (`answers` and `update`
// on a node that is public in the binary); every operation stays private to the kernel, so outside
// it a Context is an opaque token nothing can be done with
opaque type Context = TypeMap[Any]

object Context:

    private[kernel] val empty: Context = TypeMap.empty

    extension (self: Context)
        private[kernel] inline def contains[E](tag: Tag[E]): Boolean =
            self <:< tag

        private[kernel] inline def apply[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            // Unsafe: erasure at the storage boundary, the map holds the value untyped
            self.get[Any](using tag.erased, NotIntersection.singleton).asInstanceOf[A]

        private[kernel] inline def update[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            self.add[Any](value)(using tag.erased)

        // the erased spellings the eval's exit law needs: a region exit keeps the interior's
        // updates and reverts only the exiting region's own binding, whose tag arrives erased
        // from the stack entry
        private[kernel] inline def updateErased[E](tag: Tag[E], value: Any): Context =
            self.add[Any](value)(using tag.erased)

        private[kernel] inline def remove[E](tag: Tag[E]): Context =
            TypeMap.removeExact(self, tag.erased)
    end extension
end Context
