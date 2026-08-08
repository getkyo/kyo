package kyo.kernel2.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Tag

/** The handlers in scope at an execution point, threaded as a parameter beside [[Context]] and maintained the same way: never stored
  * in a node, extended when a handle loop enters its computation, restored on exit because it is a call parameter.
  *
  * The parameter exists so execution at a suspension point knows the operation's handler and can dispatch right there: a resume entry
  * answers in place with no continuation captured, a stop entry makes the suspension skip to its handler bare, with nothing stacked
  * on the way, and a shadow entry masks outer entries of its tag for the formats whose operations must travel structurally, so
  * innermost-wins holds across formats.
  */
opaque type Handlers = Chunk[Handlers.Entry]

object Handlers:

    val empty: Handlers = Chunk.empty

    /** One in-scope handler registration, keyed by its effect tag. */
    sealed abstract private[kyo] class Entry(val tag: Tag[Any])

    private[kyo] object Entry:

        /** A fun-format handler with the parameters captured where it was installed: its handle function runs at the handler's
          * scope, not the operation's, so reads inside it resolve against the bindings outside the handler.
          */
        final class Resume(
            val handler: ResumeHandler[?, ?, ?, ?],
            val entryContext: Context,
            val entryHandlers: Handlers
        ) extends Entry(handler.erasedTag)

        /** A stop-format handler: one instance per handle call, reused by every rotate re-entry. It captures no scope because
          * nothing executes at the suspension point; the operation passes through bare and the loop answers on arrival.
          */
        final class Stop(tag0: Tag[Any]) extends Entry(tag0)

        /** A structural-travel marker: one instance per ctl, first, or loop handle call, registered only when an outer entry of
          * the same tag is visible, so the outer entry cannot act inside this handler's region.
          */
        final class Shadow(tag0: Tag[Any]) extends Entry(tag0)

    end Entry

    extension (self: Handlers)

        private[kyo] def add(entry: Entry): Handlers =
            self.append(entry)

        /** The innermost entry whose tag matches the suspension's, or Absent. The predicate is the dispatch predicate:
          * reference-first, then the handle loops' subtype match. This sits on the operation dispatch path.
          */
        private[kyo] def resolve(tag: Tag[Any]): Maybe[Entry] =
            var i = self.length - 1 // innermost wins
            while i >= 0 do
                val entry = self(i)
                val t     = entry.tag
                if (t.asInstanceOf[AnyRef] eq tag.asInstanceOf[AnyRef]) || t <:< tag then return Maybe(entry)
                i -= 1
            end while
            Maybe.Absent
        end resolve

        private[kyo] def isEmpty: Boolean = self.length == 0

    end extension

end Handlers
