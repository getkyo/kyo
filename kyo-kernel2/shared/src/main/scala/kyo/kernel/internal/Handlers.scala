package kyo.kernel.internal

import kyo.Arrow
import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec

// The stack of entered regions. A value of this type is the stack itself:
// the top cell links to the enclosing region through prev and Empty is the
// empty stack, so entry is one cell allocation, settling is a pointer step,
// and a stateful update replaces one cell while the handler object stays
// the same. Cells are immutable and structurally shared, which is what
// makes captured continuations and residuals hold them safely. The cells
// are generic and constructed from the typed region nodes; the evaluator
// reads them back through its erased patterns
sealed trait Handlers derives CanEqual

object Handlers:

    case object Empty extends Handlers

    // the cell of a stateless region; the handler union makes storing a
    // stateful handler without its state a type error
    final class Node[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        val handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
        val exit: Arrow[Any, Any, Any],
        val prev: Handlers
    ) extends Handlers:
        def withPrev(prev: Handlers): Node[I, O, E, A, S] =
            new Node(handler, exit, prev)
    end Node

    // the cell of a stateful region: state is the region's current state
    final class StateNode[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](
        val handler: Handler.LoopState[I, O, E, A, S, State],
        val exit: Arrow[Any, Any, Any],
        val state: State,
        val prev: Handlers
    ) extends Handlers:
        def withState(state: State): StateNode[I, O, E, A, S, State] =
            new StateNode(handler, exit, state, prev)

        def withPrev(prev: Handlers): StateNode[I, O, E, A, S, State] =
            new StateNode(handler, exit, state, prev)
    end StateNode

    extension (self: Handlers)
        // the innermost cell whose handler answers the tag, or Empty
        def find[E2](tag: Tag[E2]): Handlers =
            @tailrec def loop(l: Handlers): Handlers =
                l match
                    case Empty                          => Empty
                    case l: Node[?, ?, ?, ?, ?]         => if tag <:< l.handler.tag then l else loop(l.prev)
                    case l: StateNode[?, ?, ?, ?, ?, ?] => if tag <:< l.handler.tag then l else loop(l.prev)
            loop(self)
        end find
    end extension

end Handlers
