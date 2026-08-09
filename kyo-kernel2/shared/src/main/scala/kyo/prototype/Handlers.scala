package kyo.prototype

import kyo.Tag

opaque type Handlers = Map[Tag[Any], Any]

object Handlers:

    val empty: Handlers = Map.empty

    extension (self: Handlers)
        private[prototype] def isEmpty: Boolean = self.isEmpty

        private[prototype] def contains(tag: Tag[Any]): Boolean =
            self.contains(tag)

        private[prototype] def get(tag: Tag[Any]): Option[Any] =
            self.get(tag)

        private[prototype] def add(tag: Tag[Any], handler: Any): Handlers =
            self.updated(tag, handler)
    end extension
end Handlers
