package kyo.proto

import kyo.Frame
import kyo.Tag
import kyo.proto.Loop.Outcome
import kyo.proto.Loop.Outcome2
import scala.annotation.static

sealed abstract class Kyo[+A, -S]

object Kyo:

    abstract class Defer[A, B, +C, -S] extends Kyo[C, S]:
        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]
    end Defer

    object Defer:

        @static def apply[A, B, S](
            _value: A < S,
            _contA: Arrow[A, B, S]
        ): Kyo[B, S] =
            new Defer[A, B, B, S]:
                def value = _value
                def contA = _contA
                def contB = Arrow.id[B]

        @static def apply[A, B, C, S](
            _value: A < S,
            _contA: Arrow[A, B, S],
            _contB: Arrow[B, C, S]
        ): Kyo[C, S] =
            if _contB eq Arrow.Id then
                apply(_value, _contA.asInstanceOf[Arrow[A, C, S]])
            else
                new Defer[A, B, C, S]:
                    def value = _value
                    def contA = _contA
                    def contB = _contB
    end Defer

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Kyo[B, E & S]:
        def frame: Frame
        def tag: Tag[E]
        def input: I[A]
        def cont: Arrow[O[A], B, S]
    end Suspend

    abstract class Handle[E <: ArrowEffect[?, ?], A, B, +C, -S] extends Kyo[C, S]:
        def value: Kyo[A, E & S]
        def handler: Handler[E, A, B, S]
        def cont: Arrow[B, C, S]
    end Handle

end Kyo
