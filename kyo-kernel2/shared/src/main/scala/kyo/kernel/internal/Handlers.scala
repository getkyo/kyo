package kyo.kernel.internal

import kyo.Arrow
import kyo.Tag
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec

// TODO can we encapsulate so the internal representaiotn is easier to evolve later?
sealed trait Handlers derives CanEqual

object Handlers:

    case object Empty extends Handlers

    final class Node[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        val handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
        val exit: Arrow[Any, Any, Any],
        val prev: Handlers
    ) extends Handlers:
        def withPrev(prev: Handlers): Node[I, O, E, A, S] =
            new Node(handler, exit, prev)
    end Node

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
