package kyo.kernel2

import Arrow.internal.*
import java.util.ArrayDeque
import kyo.Frame
import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.tailrec
import kyo.kernel2.internal.Safepoint

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
            def _input        = input

    inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        inline tag: Tag[E],
        inline v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S)
    ): A < S =

        @tailrec def handleLoop(v: A < (E & S)): A < S =
            v.reduce(
                kyo =>
                    val stack = Stack.acquire()
                    val root  = Stack.load(stack)(kyo)
                    val cont  = stack.dumpAndRelease()
                    root match
                        case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                            handleLoop(handle(kyo._input, Chain.unsafeEval(_, cont)))
                        case kyo: Arrow[Any, Any, S] @unchecked =>
                            AndThen(kyo, Arrow.init(r => handleLoop(Chain.unsafeEval(r, cont))))
                        case v =>
                            v.asInstanceOf[A < S]
                    end match
                ,
                v => v
            )
        end handleLoop
        handleLoop(v)
    end handle

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline tag: Tag[E],
        inline v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A < S] < S2
    )(using Safepoint): A < (S & S2) =
        Loop(v: A < (E & S)) { v =>
            v.reduce(
                kyo =>
                    val stack = Stack.acquire()
                    val root  = Stack.load(stack)(kyo)
                    val cont  = stack.dumpAndRelease()
                    root match
                        case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                            handle(kyo._input, Chain.unsafeEval(_, cont))
                        case kyo: Arrow[Any, Any, S] @unchecked =>
                            AndThen(kyo, Arrow.init(r => Loop.continue(Chain.unsafeEval(r, cont))))
                        case v =>
                            Loop.done(v.asInstanceOf[A < S])
                    end match
                ,
                value => Loop.done(value: A < S)
            )
        }.flatten
    end handleLoop

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline tag: Tag[E],
        inline v: A < (E & S),
        inline state: State
    )(
        inline handle: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome2[A < (E & S), State, A < S] < S2
    )(using Safepoint): A < (S & S2) =
        Loop(v, state) { (v, state) =>
            v.reduce(
                kyo =>
                    Safepoint.useBuffer[Arrow[Any, Any, Any]] { buffer =>
                        val root = flatten(kyo, buffer)
                        val cont = IArray.unsafeFromArray(buffer.toArray(Chain.emptyArray))
                        root match
                            case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                                handle(kyo._input, state, Chain.unsafeEval(_, cont))
                            case kyo: Arrow[Any, Any, S] @unchecked =>
                                AndThen(kyo, Arrow.init(r => Loop.continue(Chain.unsafeEval(r, cont), state)))
                            case v =>
                                Loop.done(v.asInstanceOf[A < S])
                        end match
                    }
                ,
                value => Loop.done(value: A < S)
            )
        }.flatten
    end handleLoop

    @tailrec private def flatten(v: Any < Any, buffer: ArrayDeque[Arrow[Any, Any, Any]]): Any < Any =
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
