package kyo.kernel3

import <.internal.*
import Arrow.internal.*
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.bug
import kyo.kernel.internal.WeakFlat
import kyo.kernel3.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.compiletime.erasedValue
import scala.language.implicitConversions
import scala.util.NotGiven

trait ArrowEffect[I[_], O[_]] extends Effect

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[I[_], O[_], E <: ArrowEffect[I, O], A](
        inline tag: Tag[E],
        inline input: I[A]
    ): O[A] < E =
        new SuspendIdentity[I, O, E, A]:
            def _tag   = tag
            def _input = input
        end new
    end suspend

    @nowarn("msg=anonymous")
    inline def suspendWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline tag: Tag[E],
        inline input: I[A]
    )(
        inline f: O[A] => B < S
    )(using inline frame: Frame): B < (E & S) =
        new Suspend[I, O, E, A, B, E & S]:
            def _tag   = tag
            def _input = input
            def cont   = Arrow.init(f)
        end new
    end suspendWith

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline tag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => Loop.Outcome[A < (E & S), A] < S2
    )(using inline frame: Frame, safepoint: Safepoint): A < (S & S2) =
        val arrow =
            Arrow.loop[Loop.Outcome[A < (E & S), A], A, S & S2] { (self, outcome) =>
                outcome.reduce(
                    done = identity,
                    continue =
                        case kyo: Suspend[?, ?, ?, ?, A, E & S] @unchecked =>
                            if kyo._tag =:= tag then
                                val sus = kyo.asInstanceOf[Suspend[I, O, E, Any, A, E & S]]
                                self(handle(sus._input, sus.cont))
                            else
                                kyo.updateCont(cont => Arrow.init(v => self(Loop.continue(cont(v)))))
                        case kyo =>
                            kyo.unsafeUnwrap
                )
            }
        arrow(Loop.continue(v))
    end handleLoop

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], State, A, B, S, S2](
        inline tag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        handle: [C] => (State, I[C], Arrow[O[C], A, E & S]) => Loop.Outcome2[State, A < (E & S), B] < S2,
        done: (State, A) => B = (_: State, v: A) => v
    )(
        using
        inline frame: Frame,
        safepoint: Safepoint
    ): B < (S & S2) =
        val arrow =
            Arrow.loop[Loop.Outcome2[State, A < (E & S), B], B, S & S2] { (self, outcome) =>
                outcome.reduce(
                    done = identity,
                    continue = (state, v) =>
                        v match
                            case kyo: Suspend[?, ?, ?, ?, A, E & S] @unchecked =>
                                if kyo._tag =:= tag then
                                    val sus = kyo.asInstanceOf[Suspend[I, O, E, Any, A, E & S]]
                                    self(handle(state, sus._input, sus.cont))
                                else
                                    kyo.updateCont(cont => Arrow.init(v => self(Loop.continue(state, cont(v)))))
                            case kyo =>
                                done(state, kyo.unsafeUnwrap)
                )
            }
        arrow(Loop.continue(state, v))
    end handleLoop
end ArrowEffect
