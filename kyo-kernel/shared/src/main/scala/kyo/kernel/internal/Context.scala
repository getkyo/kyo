package kyo.kernel.internal

import kyo.Maybe
import kyo.Tag
import kyo.bug
import kyo.kernel.ContextEffect
import scala.annotation.tailrec

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
