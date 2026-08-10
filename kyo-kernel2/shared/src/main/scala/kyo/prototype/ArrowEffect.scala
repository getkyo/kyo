package kyo.prototype

import kyo.Frame
import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.tailrec

abstract class ArrowEffect[I[_], O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    def suspend[I[_], O[_], E <: ArrowEffect[I, O], X](_tag: Tag[E], _input: I[X])(using _frame: Frame): O[X] < E =
        new Kyo.Suspend[I, O, E, X, O[X], E]:
            def tag   = _tag
            def input = _input
            def frame = _frame
            def cont  = Arrow[O[X]]

    @nowarn("msg=anonymous")
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E], v: A < (E & S))(
        f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using _frame: Frame): A < S =
        def rotated[C, S2](next: Arrow[A, C, S2]): Arrow.Transform[A, C, S & S2] =
            new Arrow.Transform[A, C, S & S2]:
                def frame = _frame
                def apply[D, S3](v: A < S3, next2: Arrow[C, D, S3]) =
                    handleLoop(v.asInstanceOf[A < (E & S)], next.chain(next2)).asInstanceOf[D < (S & S2 & S3)]

        @tailrec def handleLoop[C, S2](v: A < (E & S), next: Arrow[A, C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if tag.erased <:< kyo.tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    handleLoop(f[Any](anchored.input, o => anchored.cont(o)), next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2)]
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                    val step  = defer.cont.step
                    handleLoop(step.head(defer.value, step.tail), next)
                case v =>
                    val step = next.step
                    step.head(v.asInstanceOf[A < S2], step.tail).asInstanceOf[C < (S & S2)]
        end handleLoop

        handleLoop(v, Arrow[A])
    end handle

end ArrowEffect
