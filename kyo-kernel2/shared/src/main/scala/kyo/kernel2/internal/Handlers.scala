package kyo.kernel2.internal

import kyo.Chunk
import kyo.Tag

/** The fun-format handlers in scope at an execution point, threaded as a parameter beside [[Context]] and maintained the same way:
  * never stored in a node, extended when a handle loop enters its computation, restored on exit because it is a call parameter.
  *
  * Only fun-format handlers are carried: an operation of one is answered locally at the point it surfaces, with no continuation built.
  * Every other format has to receive the built-up continuation, so its operations travel to the handler structurally and the parameter
  * has nothing to offer them.
  */
opaque type Handlers = Chunk[Handlers.Entry]

object Handlers:

    val empty: Handlers = Chunk.empty

    /** One in-scope fun-format handler, with the parameters captured where it was installed: its handle function runs at the handler's
      * scope, not the operation's, so reads inside it resolve against the bindings outside the handler.
      */
    final private[kyo] class Entry(
        val handler: Handler.Resume[?, ?, ?, ?, ?, ?],
        val entryContext: Context,
        val entryHandlers: Handlers
    )

    extension (self: Handlers)

        private[kyo] def add(entry: Entry): Handlers =
            self.append(entry)

        /** The innermost entry whose handler matches the suspension's tag, or null. Null-based rather than Maybe: this sits on the
          * operation dispatch path. The predicate is the dispatch predicate: reference-first, then the handle loops' subtype match.
          */
        private[kyo] def resolve(tag: Tag[Any]): Entry =
            var i = self.length - 1 // innermost wins
            while i >= 0 do
                val entry = self(i)
                val t     = entry.handler.erasedTag
                if (t.asInstanceOf[AnyRef] eq tag.asInstanceOf[AnyRef]) || t <:< tag then return entry
                i -= 1
            end while
            null
        end resolve

        private[kyo] def isEmpty: Boolean = self.length == 0

    end extension

end Handlers
