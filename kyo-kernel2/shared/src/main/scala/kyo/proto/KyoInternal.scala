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
