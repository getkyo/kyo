package kyo2

import kernel.*
import kyo.Tag
import scala.collection.immutable.Queue

sealed trait Coroutine[+S] extends Effect[Const[Coroutine.Op[S]], Const[Unit]]

object Coroutine:

    sealed trait Op[-S] derives CanEqual
    object Op:
        case object Pause extends Op[Any]
        abstract class Spawn[-S] extends Op[S]:
            def apply(): Unit < (Coroutine[S] & S)
    end Op

    private def erasedTag[S] = Tag[Coroutine[Any]].asInstanceOf[Tag[Coroutine[S]]]

    inline def pause: Unit < Coroutine[Any] =
        Effect.suspend[Any](erasedTag[Any], Op.Pause)

    inline def spawn[A, S](inline v: => Unit < (Coroutine[S] & S)): Unit < Coroutine[S] =
        Effect.suspend[Any](erasedTag[S], (() => v): Op.Spawn[S])

    def run[A, S](v: A < (Coroutine[S] & S)): A < S =
        Loop.transform(Queue(v.map(Maybe(_))), Maybe.empty[A]) { (state, result) =>
            if state.isEmpty then Loop.done(result.get)
            else
                val (task, rest) = state.dequeue
                Effect.handle.state(erasedTag[S], rest, task)(
                    handle = [C] =>
                        (input, state, cont) =>
                            input match
                                case Op.Pause =>
                                    (state.enqueue(cont(())), Maybe.empty)
                                case input: Op.Spawn[S] =>
                                    (state.enqueue(input().andThen(Maybe.empty[A])), cont(()))
                            end match
                    ,
                    done = (state, result2) =>
                        Loop.continue(state, result.orElse(result2))
                )
            end if
        }
    end run

end Coroutine
