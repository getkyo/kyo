package kyo.kernel.internal

import kyo.Maybe
import kyo.Tag
import kyo.bug
import kyo.kernel.*
import scala.annotation.tailrec

/** Storage for effect values used by ContextEffect.
  *
  * Context maintains a type-safe mapping between effect tags and their values. It provides the underlying storage mechanism that allows
  * ContextEffect to request, store, and retrieve values. Bindings are kept in region order: a region binds on top and unbinds on exit, and
  * a read walks outward from the innermost binding, so an inner region shadows an outer one with the same tag.
  *
  * This structure decides only what a read sees on one fiber. What crosses an async boundary is decided per binding, by each
  * `ContextHandler`'s fork and join.
  */
sealed abstract private[kernel] class Context:

    final def bind[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
        Context.Bound(tag.erased, value, this)

    /** Binds `tag` to the marker a masking region leaves behind, shadowing whatever is bound outside it.
      *
      * A read that finds the marker is dispatched to the region that left it instead of being answered from the
      * context, the same route an arrow operation takes, so one mask covers both kinds of effect.
      */
    final def mask[E <: Effect](tag: Tag[E]): Context =
        Context.Bound(tag.erased, Context.Masked, this)

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

    /** What [[Context.mask]] binds. Compared by reference at the read site; never handed to user code. */
    private[kernel] object Masked

    private object Empty extends Context:
        def unbind: Context = bug("unbind on an empty context")

    final private class Bound(val tag: Tag[Any], val value: Any, val next: Context) extends Context:
        def unbind: Context = next

end Context
