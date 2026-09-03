package kyo.kernel.internal

import kyo.Maybe
import kyo.Tag
import kyo.bug
import kyo.kernel.ContextEffect
import scala.annotation.tailrec

/** Storage for effect values used by ContextEffect.
  *
  * Context maintains a type-safe mapping between effect tags and their values. It provides the underlying storage mechanism that allows
  * ContextEffect to request, store, and retrieve values. Bindings are kept in region order: entering a context region binds on top, leaving
  * it unbinds the top, and a read walks from the innermost binding outward, so an inner region shadows an outer one with the same tag.
  */
// Diverges from main: main's Context is an opaque Map[Tag[Any], AnyRef] with a NoninheritableFlag
// entry that `inherit` filters on at async boundaries. Here it is the stack's context regions in
// order, and what crosses a boundary is decided by each ContextHandler's fork and join (D4).
sealed abstract private[kernel] class Context:

    final def bind[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
        Context.Bound(tag.erased, value, this)

    final def get[A, E <: ContextEffect[A]](tag: Tag[E]): Maybe[A] =
        val te = tag.erased
        @tailrec def loop(c: Context): Maybe[A] =
            c match
                case b: Context.Bound => if b.tag <:< te then Maybe(b.value.asInstanceOf[A]) else loop(b.next)
                case _                => Maybe.empty
        loop(this)
    end get

    def unbind: Context

end Context

private[kernel] object Context:

    val empty: Context = Empty

    private object Empty extends Context:
        def unbind: Context = bug("unbind on an empty context")

    final private class Bound(val tag: Tag[Any], val value: Any, val next: Context) extends Context:
        def unbind: Context = next

end Context
