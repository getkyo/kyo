package kyo.kernel.internal

import kyo.Arrow
import kyo.Arrow.Transform
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
    // the node is also the entry that marks the scope: identity on the completing path, since a value
    // flowing back through is what ends the scope, and the recovery the unwind asks on the way down.
    // Nothing else has to be allocated when the eval enters one
    abstract private[kyo] class Catching[A, S] extends Kyo[A, S], Transform[Any, Any, Any], Recover:
        // a method, for the reason a deferral's payload is one: the guarded body has to run when the
        // evaluator reads it and not when the node is built, or `catching { throw ... }` throws before
        // anything guards it. Abstract rather than a by-name constructor parameter, which would store the
        // thunk in a field and read it through a pointer at every use
        def value: A < S

        override def apply(v: Any): Any < Any = v

        def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
            v match
                case kyo: Kyo[Any, S2] @unchecked => Effect.defer(kyo, this, next)
                case _                            => next(apply(Nested.unnest[Any](v)), Arrow.id)

        override def toString: String = render(value)
    end Catching

    abstract private[kyo] class Handle[E <: ArrowEffect[?, ?], A, B, +C, -S] extends Kyo[C, S]:
        // the pending type rather than `Kyo`, and a method rather than a field, so a region can hold its body
        // unforced: the eval reads this after the handler is on the stack, which is what lets a recovering
        // region guard a body that throws while it is being built. The handlers that match their body at
        // construction hand back the value they matched, so nothing converts
        def value: A < (E & S)
        def handler: Handler[E, A, B, S]
        def cont: Arrow[B, C, S]

        override def toString: String = render(value)
    end Handle

    /** The operation a computation is standing on, or the value itself where it is standing on none.
      *
      * Walks only what can be read without running anything: a box and a park hold their payload in a field,
      * where a deferral and a region hold a method that runs user code when it is read. Stopping at those is
      * the point, since the caller is inspecting a computation it does not intend to evaluate.
      */
    @static private[kyo] def standing(v: Any): Any =
        @tailrec def loop(v: Any): Any =
            v match
                case n: Nested[?]  => loop(n.value)
                case p: Park[?, ?] => loop(p.value)
                case v             => v
        loop(v)
    end standing

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
