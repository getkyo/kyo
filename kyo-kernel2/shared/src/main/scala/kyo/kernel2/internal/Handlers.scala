package kyo.kernel2.internal

import kyo.Tag

/** The handlers in scope at an execution point, threaded as a parameter beside [[Context]].
  *
  * Like the context, the collection is never stored in a node: it exists only in flight. Entering a handled scope extends it, leaving
  * restores the previous instance, and resumptions re-derive it by re-entering the scopes carried in the continuation. An operation
  * whose handler answers in place (the fun format) resolves against this collection at the operation's own execution point and is
  * applied locally, with no continuation built.
  *
  * One final array-backed class: innermost-last linear scan with reference-first tag comparison, so the lookup site stays monomorphic
  * and hits are allocation-free.
  */
final class Handlers private (tags: Array[AnyRef], entries: Array[AnyRef]):

    private[kyo] def set(tag: Tag[Any], entry: AnyRef): Handlers =
        val n  = tags.length
        val t2 = java.util.Arrays.copyOf(tags, n + 1)
        val e2 = java.util.Arrays.copyOf(entries, n + 1)
        t2(n) = tag.asInstanceOf[AnyRef]
        e2(n) = entry
        new Handlers(t2, e2)
    end set

    /** The innermost entry for the tag, or null. Null-based rather than Maybe: this sits on the operation dispatch path. */
    private[kyo] def resolve(tag: Tag[Any]): AnyRef | Null =
        val key = tag.asInstanceOf[AnyRef]
        var i   = tags.length - 1 // innermost wins
        while i >= 0 do
            val t = tags(i)
            if (t eq key) || t.equals(key) then return entries(i)
            i -= 1
        end while
        null
    end resolve

    private[kyo] def isEmpty: Boolean = tags.length == 0

end Handlers

object Handlers:
    val empty = new Handlers(new Array[AnyRef](0), new Array[AnyRef](0))
