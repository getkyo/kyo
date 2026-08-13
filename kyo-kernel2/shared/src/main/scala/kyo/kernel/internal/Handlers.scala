package kyo.kernel.internal

import kyo.Arrow
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.`<`.fromKyo
import scala.annotation.tailrec

/** The spine: the regions an evaluation has entered, innermost first.
  *
  * One immutable cell per region, linked through `prev`, with `Empty` as the empty stack. Which kinds of cell exist is this file's own
  * business. A walk outside it reads the four values every region has, and the operation dispatch reads `handler`, whose kinds are the
  * vocabulary the handling variants already publish, so a new kind of region is added here and answered there rather than enumerated at
  * every walk.
  */
sealed abstract class Handlers private[Handlers] (
    private[kernel] val tag: Tag[Nothing],
    private[kernel] val handler: Handler[[X] =>> Any, [X] =>> Any, Nothing, Any, Any],
    private[kernel] val exit: Arrow[Any, Any, Any],
    private[kernel] val prev: Handlers
) derives CanEqual:

    // the state pair belongs to the stateful region alone: the dispatch reads it
    // under the LoopState arm, where the cell is always that one, so every other
    // region inherits the refusal instead of carrying a value it cannot have
    private[kernel] def state: Any =
        throw new IllegalStateException(s"region without state: $this")

    private[kernel] def withState(state: Any): Handlers =
        throw new IllegalStateException(s"region without state: $this")

    /** The same region one cell further down, for the path copy a state update makes. */
    private[Handlers] def withPrev(prev: Handlers): Handlers

    /** The region node this cell reconstitutes into, wrapped around a value standing at this cell's position. */
    private[kernel] def rebuilt(value: Any < Nothing): Any < Nothing

end Handlers

object Handlers:

    /** The empty stack. Its four values are never read: every walk stops here by identity, and the two copies it cannot make are refused.
      */
    case object Empty extends Handlers(null.asInstanceOf[Tag[Nothing]], null, null, null):
        private[Handlers] def withPrev(prev: Handlers): Handlers =
            throw new IllegalStateException("empty spine")
        private[kernel] def rebuilt(value: Any < Nothing): Any < Nothing =
            throw new IllegalStateException("empty spine")
    end Empty

    // the cell kinds, one per handler kind, differing only where the handler
    // kinds do: the stateful region carries the state its clause advances
    final private class Node(
        handler: Handler.Cont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] |
            Handler.Loop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any],
        exit: Arrow[Any, Any, Any],
        prev: Handlers
    ) extends Handlers(handler.tag, handler, exit, prev):
        private[Handlers] def withPrev(prev: Handlers): Handlers =
            new Node(contOrLoop, exit, prev)
        private[kernel] def rebuilt(value: Any < Nothing): Any < Nothing =
            new RebuiltNode(value, this)
        // the handler is kept once, on the cell, and read back at the kind the
        // region was built with: the field carries every kind, so its own kind
        // is what the cell knows and the type system does not
        private[Handlers] def contOrLoop =
            handler.asInstanceOf[
                Handler.Cont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] |
                    Handler.Loop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any]
            ]
    end Node

    final private class StateNode(
        handler: Handler.LoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any],
        exit: Arrow[Any, Any, Any],
        override val state: Any,
        prev: Handlers
    ) extends Handlers(handler.tag, handler, exit, prev):
        override private[kernel] def withState(state: Any): Handlers =
            new StateNode(loopState, exit, state, prev)
        private[Handlers] def withPrev(prev: Handlers): Handlers =
            new StateNode(loopState, exit, state, prev)
        private[kernel] def rebuilt(value: Any < Nothing): Any < Nothing =
            new RebuiltStateNode(value, this)
        private[Handlers] def loopState =
            handler.asInstanceOf[Handler.LoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]]
    end StateNode

    final private class FirstNode(
        handler: Handler.First[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any],
        exit: Arrow[Any, Any, Any],
        prev: Handlers
    ) extends Handlers(handler.tag, handler, exit, prev):
        private[Handlers] def withPrev(prev: Handlers): Handlers =
            new FirstNode(first, exit, prev)
        private[kernel] def rebuilt(value: Any < Nothing): Any < Nothing =
            new RebuiltFirstNode(value, this)
        private[Handlers] def first =
            handler.asInstanceOf[Handler.First[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]]
    end FirstNode

    // a region layer rebuilt from an existing cell: as a value it is the regular
    // region node; when it is pushed back at the position it was built from, the
    // cell re-enters the stack with no new allocation
    final private class RebuiltNode(
        val value: Any < Nothing,
        val cell: Node
    ) extends Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]:
        def handler = cell.contOrLoop
        def exit    = cell.exit
    end RebuiltNode

    final private class RebuiltStateNode(
        val value: Any < Nothing,
        val cell: StateNode
    ) extends Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any]:
        def handler = cell.loopState
        def exit    = cell.exit
        def state   = cell.state
    end RebuiltStateNode

    final private class RebuiltFirstNode(
        val value: Any < Nothing,
        val cell: FirstNode
    ) extends Kyo.HandledFirst[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any]:
        def handler = cell.first
        def exit    = cell.exit
    end RebuiltFirstNode

    extension (self: Handlers)

        /** The innermost region an operation of this tag resolves to, or `Empty` when none does.
          *
          * The tag rides the cell rather than the handler: a handle site mints a fresh anonymous handler class, so reading the tag through
          * it makes this walk, one call per answered operation, dispatch on a new receiver type per site.
          */
        def find[E](tag: Tag[E]): Handlers =
            @tailrec def loop(l: Handlers): Handlers =
                if l eq Empty then Empty
                else if tag <:< l.tag then l
                else loop(l.prev)
            loop(self)
        end find

        /** Enters a region: the cell it was built from when the layer lands where it was built, a fresh cell otherwise. */
        def push(kyo: Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]): Handlers =
            kyo match
                case kyo: RebuiltNode if kyo.cell.prev eq self => kyo.cell
                case _                                         => new Node(kyo.handler, kyo.exit, self)

        def push(kyo: Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any]): Handlers =
            kyo match
                case kyo: RebuiltStateNode if kyo.cell.prev eq self => kyo.cell
                case _                                              => new StateNode(kyo.handler, kyo.exit, kyo.state, self)

        def push(kyo: Kyo.HandledFirst[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any]): Handlers =
            kyo match
                case kyo: RebuiltFirstNode if kyo.cell.prev eq self => kyo.cell
                case _                                              => new FirstNode(kyo.handler, kyo.exit, self)

        /** Swaps one cell, which a state update does. The cells above it are path-copied iteratively, so a pathological depth never reaches
          * the Java stack.
          */
        def replace(cell: Handlers, updated: Handlers): Handlers =
            if self eq cell then updated
            else
                @tailrec def count(l: Handlers, n: Int): Int =
                    if (l eq cell) || (l eq Empty) then n
                    else count(l.prev, n + 1)
                val n     = count(self, 0)
                val cells = new Array[Handlers](n)
                @tailrec def fill(l: Handlers, i: Int): Unit =
                    if i < n then
                        cells(i) = l
                        fill(l.prev, i + 1)
                fill(self, 0)
                @tailrec def build(i: Int, acc: Handlers): Handlers =
                    if i < 0 then acc
                    else build(i - 1, cells(i).withPrev(acc))
                build(n - 1, updated)
            end if
        end replace
    end extension

end Handlers
