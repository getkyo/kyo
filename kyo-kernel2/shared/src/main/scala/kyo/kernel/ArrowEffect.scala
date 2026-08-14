package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import kyo.kernel.internal.*
import scala.annotation.nowarn

abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline v: I[C]
    ): O[C] < E =
        new Kyo.Suspend[I, O, E, C, O[C], E]:
            def tag   = effectTag
            def input = v
            def frame = _frame

    @nowarn("msg=anonymous")
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline v: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =
        new Arrow.Transform[O[C], B, S] with Kyo.Suspend[I, O, E, C, B, E & S]:
            def tag   = effectTag
            def input = v
            def frame = _frame
            def apply[C2, S2](v2: O[C] < S2, next: Arrow[B, C2, S2]): C2 < (S & S2) =
                v2 match
                    case kyo: Kyo[O[C], S2] @unchecked =>
                        kyo.map(this.chain(next))
                    case v2 =>
                        val res  = Kyo.unnest(v2)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.defer(v2, this.chain(next))
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end apply

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Outcome[O[C] < (E & S & S2), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                new Kyo.HandleLoop[I, O, E, A, A, S & S2]:
                    def tag                 = effectTag
                    def value               = v
                    def run[C](input: I[C]) = handle[C](input)
                    def complete(v: A)      = Nested.lift(v)
            case v =>
                v.asInstanceOf[A < (S & S2)]

    @nowarn("msg=anonymous")
    inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                // rows: the node's S is S & S2 and its S2 is E, so run's continuation
                // and result sit at exactly the clause's row. The deep region stays
                // installed while its clause result runs, which is what discharges E:
                // hence the one cast at the node's own row
                val region =
                    new Kyo.HandleCont[I, O, E, A, A, S & S2, E]:
                        def tag   = effectTag
                        def value = v
                        def run[C](input: I[C], cont: O[C] => A < (E & S & S2)) =
                            handle[C](input, cont)
                        def complete(v: A) = Nested.lift(v)
                        override def deep  = true
                region.asInstanceOf[A < (S & S2)]
            case v =>
                v.asInstanceOf[A < (S & S2)]
        end match
    end handle

    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2)
    )(
        inline recover: Throwable => A < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        ArrowEffect.handle[I, O, E, A, S & S2, Any](effectTag, Effect.catching(v)(recover))(
            [C] => (input, cont) => Effect.catching(handle[C](input, cont))(recover)
        )

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], State, O[C] => A < (E & S)) => Outcome2[State, A < (E & S), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop[I, O, E, A, A, S, S2, State](effectTag, state, v)(done = (_, v) => v, handle = handle)

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline done: (State, A) => B < (S & S2),
        inline handle: [C] => (I[C], State, O[C] => A < (E & S)) => Outcome2[State, A < (E & S), B] < S2
    )(using inline _frame: Frame): B < (S & S2) =
        def loop(state: State, v: A < (E & S)): B < (S & S2) =
            v match
                case kyo: Kyo[?, ?] =>
                    new Kyo.HandleCont[I, O, E, A, B, S, S2]:
                        def tag   = effectTag
                        def value = v
                        def run[C](input: I[C], cont: O[C] => A < (E & S)) =
                            handle[C](input, state, cont).map {
                                case c: Loop.Continue2[State, A < (E & S)] @unchecked => loop(c._1, c._2)
                                case b                                                => b.asInstanceOf[B]
                            }
                        def complete(v: A) = done(state, v)
                case v =>
                    done(state, Kyo.unnest(v))
        loop(state, v)
    end handleLoop

end ArrowEffect
