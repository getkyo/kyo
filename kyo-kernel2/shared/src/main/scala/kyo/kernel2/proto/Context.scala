package kyo.kernel2.proto

import kyo.Maybe
import kyo.Tag

/** The one threaded environment (design section 5): tag-keyed entries where a [[Context.Binding]] carries a context effect's value and a
  * [[Context.Region]] carries an arrow handler together with the environment at its region's entry.
  *
  * The representation is E2b's: one final array-backed class, innermost-last linear scan, reference-first tag comparison with the
  * structural fallback off the fast path. Entering a region appends; leaving restores the previous instance; nothing mutates.
  *
  * What we call Context today is the inheritable subset: [[inherit]] is the fork boundary, values cross it, interpreters never do.
  */
final class Context private (tags: Array[AnyRef], entries: Array[Context.Entry]):

    private[proto] def set(tag: Tag[Any], entry: Context.Entry): Context =
        val n  = tags.length
        val t2 = java.util.Arrays.copyOf(tags, n + 1)
        val e2 = java.util.Arrays.copyOf(entries, n + 1)
        t2(n) = tag.asInstanceOf[AnyRef]
        e2(n) = entry
        new Context(t2, e2)
    end set

    private[proto] def resolve(tag: Tag[Any]): Maybe[Context.Entry] =
        val key = tag.asInstanceOf[AnyRef]
        var i   = tags.length - 1 // innermost wins
        while i >= 0 do
            val t = tags(i)
            if (t eq key) || t.equals(key) then return Maybe(entries(i))
            i -= 1
        end while
        Maybe.Absent
    end resolve

    /** The fork boundary: bindings cross, handler regions never do. */
    def inherit: Context =
        var out = Context.empty
        var i   = 0
        while i < tags.length do
            entries(i) match
                case b: Context.Binding => out = out.set(tags(i).asInstanceOf[Tag[Any]], b)
                case _                  => ()
            i += 1
        end while
        out
    end inherit

end Context

object Context:

    val empty = new Context(Array.empty, Array.empty)

    sealed trait Entry

    /** A context effect's value, stored under its tag. Recovered at the read site by that tag: the same single documented boundary the
      * real kernel's Context.get carries.
      */
    final class Binding(val value: Any) extends Entry

    /** A live handler region. `entryContext` is the environment at the region's entry; `clauseContext` extends it with the region itself,
      * which is what makes handlers deep (design 2.5): an operation of the region's own effect inside a clause is interpreted by the same
      * handler, while everything else resolves OUTSIDE the region.
      */
    final class Region(val handler: Handler[?, ?, ?, ?, ?, ?], val entryContext: Context) extends Entry:
        lazy val clauseContext: Context = entryContext.set(handler.effectTag.erased, this)

end Context
