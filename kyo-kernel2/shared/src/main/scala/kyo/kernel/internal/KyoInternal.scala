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

    /** A value bound for the extent of a computation, or a read of one.
      *
      * One node serves both, discriminated by `bound`: `Present` binds and marks the extent it applies to,
      * `Absent` reads whatever is bound. A bind is its own stack entry, as a recovery is, so the binding
      * lasts exactly as long as the entry: it leaves on every path that unwinds past it, it travels inside a
      * dumped continuation the way a region does, and a park snapshots it with everything else. A read marks
      * no extent, so it is pushed nowhere.
      *
      * What it binds is a function of what the enclosing scope binds, not a value, and the consumers force
      * that. Every `Local` shares one tag and one map, and each `let` binds `_.updated(this, value)` over
      * what is already there, so a binding that replaced would drop every local bound outside it. The
      * previous kernel resolved that function at each continuation crossing, since its context travelled as
      * an argument. Here it is resolved when the entry is installed and the result lives in the entry's state
      * slot, which makes both properties that matter hold: a read is a walk to the innermost matching entry
      * and a field read, never an allocation, and a scope re-entered under a different enclosing binding
      * resolves against that one, which is what a continuation captured inside a binding and resumed
      * elsewhere depends on.
      *
      * `E` places the effect rather than carrying it: a bind takes the node's row to `S`, which is what
      * providing the value means, and a read instantiates `S` as `E & S`, which is what requiring it means. A
      * read with a default takes neither, so a computation that reads one names no effect at all.
      */
    abstract private[kyo] class Binding[V, E <: ContextEffect[V], A, S] extends Kyo[A, S], Transform[Any, Any, Any]:

        def tag: Tag[E]

        /** What a read matches against, erased, which is the equality the previous kernel keyed its context
          * map by. Read here rather than at the walk, where the node's type is a wildcard and `Tag`'s
          * extension methods cannot infer their receiver.
          */
        final def key: Tag[Any] = tag.erased

        /** What this binds, given what the enclosing scope binds, or absent where this only reads. */
        def bound: Maybe[Maybe[V] => V]

        /** What a computation crossing a boundary inherits from this binding, or absent where it inherits
          * nothing.
          *
          * The same shape as `bound`, and for the same reason: a child resolves it against what it has bound
          * rather than carrying the parent's resolution, which is what makes an inherited `Local` merge into
          * the child's map instead of replacing it.
          *
          * Inheriting by default, and a binding that must not cross says so here. That keeps the marker out
          * of the kernel: nothing type-tests for a non-inheritable effect, each binding answers for itself.
          */
        def inherit: Maybe[Maybe[V] => V] = bound

        /** Where the value flows: the bound body for a bind, the read's continuation for a read.
          *
          * A method rather than an arrow, so neither use allocates one and the node is the only object
          * either costs.
          */
        def resume(held: Maybe[V]): A < (E & S)

        // identity on the way out: this holds a stack position so the extent ends where the value flows back
        override def apply(v: Any): Any < Any = v

        def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
            v match
                case kyo: Kyo[Any, S2] @unchecked => Effect.defer(kyo, this, next)
                case _                            => next(apply(Nested.unnest[Any](v)), Arrow.id)

        override def toString: String =
            s"Kyo(${if bound.isDefined then "bind" else "read"} ${tag.show}, ${frame.position.show})"
    end Binding

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
                    // its payload takes what is bound, which a rendering does not have, so the node names
                    // itself and stops there
                    case k: Binding[?, ?, ?, ?]   => k.toString
                    case k: Defer[?, ?, ?, ?]     => loop(k.value, fuel - 1)
                    case k: Handle[?, ?, ?, ?, ?] => loop(k.value, fuel - 1)
                    case k: Park[?, ?]            => loop(k.value, fuel - 1)
                    case k: Catching[?, ?]        => loop(k.value, fuel - 1)
                    case n: Nested[?]             => loop(n.value, fuel - 1)
                    case settled                  => s"Kyo($settled)"
        loop(v, 64)
    end render

end Kyo
