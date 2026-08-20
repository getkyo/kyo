package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.static
import scala.annotation.tailrec

sealed abstract private[kyo] class Kyo[+A, -S]

// Public object, private[kyo] members: see the note on Safepoint for the accessor the other shape emits.
object Kyo:

    abstract private[kyo] class Defer[A, B, +C, -S] extends Kyo[C, S]:
        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def toString: String = render(value)
    end Defer

    abstract private[kyo] class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Kyo[B, E & S]:
        def frame: Frame
        def tag: Tag[E]
        def input: I[A]
        def cont: Arrow[O[A], B, S]

        override def toString: String =
            s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"
    end Suspend

    abstract private[kyo] class Handle[E <: ArrowEffect[?, ?], A, B, +C, -S] extends Kyo[C, S]:
        def value: Kyo[A, E & S]
        def handler: Handler[E, A, B, S]
        def cont: Arrow[B, C, S]

        override def toString: String = render(value)
    end Handle

    /** A node's rendering is the rendering of the operation it is waiting on, which is the frame a reader wants: a deferral and a region
      * carry no site of their own. The walk is a loop with a depth cap rather than recursion, so rendering a deeply nested computation in a
      * debugger cannot overflow the stack.
      */
    @static private def render(v: Any): String =
        @tailrec def loop(v: Any, fuel: Int): String =
            if fuel == 0 then "Kyo(<deeply nested>)"
            else
                v match
                    case k: Suspend[?, ?, ?, ?, ?, ?] => k.toString
                    case k: Defer[?, ?, ?, ?]         => loop(k.value, fuel - 1)
                    case k: Handle[?, ?, ?, ?, ?]     => loop(k.value, fuel - 1)
                    case n: Nested[?]                 => loop(n.value, fuel - 1)
                    case settled                      => s"Kyo($settled)"
        loop(v, 64)
    end render

end Kyo
