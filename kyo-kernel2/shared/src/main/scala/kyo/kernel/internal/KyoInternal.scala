package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Maybe
import kyo.Span
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

    /** An eval that ended before the computation did, holding everything needed to carry on.
      *
      * The three spans are the eval stack as it stood, rather than a continuation folded out of it: `dump`
      * chains entries together and re-wraps a stateful handler with its live state, and pushing that chain
      * takes it apart again, a round trip that does not preserve the shape it started from. Snapshotting the
      * arrays instead keeps the regions and their states exactly as they were, and being spans they are
      * immutable, so a parked computation is a complete value like any other: resumable more than once, on
      * any thread.
      *
      * The finalizers travel with it because the eval that parked is ending and its drain must not run: the
      * releases are owed by the computation, not by the slice of it that happened to execute. Whoever holds
      * the park decides between resuming it and discharging them.
      */
    // concrete, unlike its siblings: they are abstract because their members close over the site that builds
    // them, and a deferral's payload is a method on purpose so the body runs when the evaluator reads it.
    // Everything here is a value already in hand at the moment of parking, and there is one site that builds
    // one, so a class with fields is a class rather than an anonymous subclass of one
    final private[kyo] class Park[+A, -S](
        val value: Any < Any,
        val entries: Span[Arrow[?, ?, ?]],
        val states: Span[Maybe[Any]],
        val finalizers: Span[Finalizer[?]]
    ) extends Kyo[A, S]:
        override def toString: String = render(value)
    end Park

    /** A scope that answers its own failure.
      *
      * A node rather than an arrow, which is where it parts company with `Bracket`: a bracket's arrow exists
      * so the acquire settles before anything is owed, and that is what answers "did the acquire complete"
      * by construction. Nothing has to settle before a recovery is owed, so it is installed on the way in.
      */
    abstract private[kyo] class Catching[A, S] extends Kyo[A, S]:
        // a method, for the reason a deferral's payload is one: the guarded body has to run when the
        // evaluator reads it and not when the node is built, or `catching { throw ... }` throws before
        // anything guards it. Abstract rather than a by-name constructor parameter, which would store the
        // thunk in a field and read it through a pointer at every use
        def value: A < S
        def recover: Arrow[Throwable, A, S]
    end Catching

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
                    case k: Park[?, ?]                => loop(k.value, fuel - 1)
                    case k: Catching[?, ?]            => loop(k.value, fuel - 1)
                    case n: Nested[?]                 => loop(n.value, fuel - 1)
                    case settled                      => s"Kyo($settled)"
        loop(v, 64)
    end render

end Kyo
