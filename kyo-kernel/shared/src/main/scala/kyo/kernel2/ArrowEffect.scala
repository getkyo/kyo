package kyo.kernel2

import Arrow.internal.*
import java.util.ArrayDeque
import kyo.Frame
import kyo.Tag
import kyo.bug
import kyo.kernel2.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec

trait ArrowEffect[I[_], O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[I[_], O[_], E <: ArrowEffect[I, O], A](
        inline tag: Tag[E],
        inline input: I[A]
    )(using inline frame: Frame): O[A] < E =
        new Suspend[I, O, E, A]:
            def _frame: Frame = frame
            def _tag          = tag
            val _input        = input

    // inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](
    //     inline tag: Tag[E],
    //     inline v: A < (E & S)
    // )(
    //     inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S)
    // ): A < S =

    //     @tailrec def handleLoop(v: A < (E & S)): A < S =
    //         v.reduce(
    //             kyo =>
    //                 val stack = Stack.acquire()
    //                 val root  = Stack.load(stack)(kyo)
    //                 val cont  = stack.dumpAndRelease()
    //                 root match
    //                     case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
    //                         handleLoop(handle(kyo._input, Chain.unsafeEval(_, cont)))
    //                     case kyo: Arrow[Any, Any, S] @unchecked =>
    //                         AndThen(kyo, Arrow.init(r => handleLoop(Chain.unsafeEval(r, cont))))
    //                     case v =>
    //                         v.asInstanceOf[A < S]
    //                 end match
    //             ,
    //             v => v
    //         )
    //     end handleLoop
    //     handleLoop(v)
    // end handle

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline tag: Tag[E],
        inline v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A] < S2
    )(using Safepoint): A < (S & S2) =
        Loop(v) {
            _.reduce(
                kyo =>
                    Safepoint.useBuffer[Arrow[Any, Any, Any]] { buffer =>
                        val root = flatten(kyo, buffer)
                        val cont = IArray.unsafeFromArray(buffer.toArray(Chain.emptyArray))
                        root match
                            case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                                handle(kyo._input, Chain.unsafeEval(_, cont))
                            case kyo: Arrow[Any, Any, S] @unchecked =>
                                AndThen(kyo, Arrow.init(r => Loop.continue(Chain.unsafeEval(r, cont))))
                            case _ =>
                                bug(s"Expected a suspension but found: " + root)
                        end match
                    },
                Loop.done(_)
            )
        }
    end handleLoop

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], State, A, B, S, S2](
        inline tag: Tag[E],
        inline v: A < (E & S),
        inline state: State
    )(
        inline handle: [C] => (State, I[C], O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), B] < S2,
        inline done: (State, A) => B = (_: State, v: A) => v
    )(using Safepoint): B < (S & S2) =
        Loop(state, v)((state, v) =>
            v.reduce(
                kyo =>
                    Safepoint.useBuffer[Arrow[Any, Any, Any]] { buffer =>
                        val root = flatten(kyo, buffer)
                        buffer.size() match
                            case 1 =>
                                val cont = buffer.pop().asInstanceOf[Arrow[O[A], A, E & S]]
                                root match
                                    case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                                        handle(state, kyo._input, cont(_))
                                    case kyo: Suspend[I, O, E, A] @unchecked =>
                                        AndThen(
                                            kyo.asInstanceOf[Arrow[Any, Any, Any]],
                                            Arrow.init(r => Loop.continue(state, cont(r.asInstanceOf)))
                                        )
                                    case _ =>
                                        bug(s"Expected a suspension but found: " + root)
                                end match
                            case _ =>
                                val cont = IArray.unsafeFromArray(buffer.toArray(Chain.emptyArray))
                                root match
                                    case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                                        handle(state, kyo._input, Chain.unsafeEval(_, cont))
                                    case kyo: Suspend[I, O, E, A] @unchecked =>
                                        AndThen(
                                            kyo.asInstanceOf[Arrow[Any, Any, Any]],
                                            Arrow.init(r => Loop.continue(state, Chain.unsafeEval(r, cont)))
                                        )
                                    case _ =>
                                        bug(s"Expected a suspension but found: " + root)
                                end match
                        end match
                    },
                value => Loop.done(done(state, value))
            )
        )
    end handleLoop

    @tailrec private def flatten(v: Any, buffer: ArrayDeque[Arrow[Any, Any, Any]]): Any =
        v match
            case Chain(array) =>
                def loop(idx: Int): Unit =
                    if idx < array.length then
                        buffer.push(array(idx))
                        loop(idx + 1)
                loop(1)
                array(0)
            case AndThen(a, b) =>
                buffer.push(b.asInstanceOf[Arrow[Any, Any, Any]])
                flatten(a, buffer)
            case _ =>
                v

end ArrowEffect
