package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.annotation.publicInBinary

private[proto] def short(v: Any): String =
    v match
        case v: Pending[?, ?]            => v.toString
        case v: Arrow.Chain[?, ?, ?, ?]  => v.toString
        case _: Arrow.Id[?]              => "Id"
        case _: Arrow.Transform[?, ?, ?] => "Transform"
        case v                           => v.toString

/** The failure a released extent is told about: its holder gave up on resuming the continuation, so the extent ends without an outcome. A
  * shared stackless instance: the signal's identity is the information, not a trace.
  */
private[kyo] object Discarded extends Exception("continuation discarded", null, false, false)

/** A construction site rendered as the call it was: the enclosing method, the combinator it called, and the position to jump to. */
private[proto] def site(frame: Frame): String =
    val callee = frame.calleeName
    if callee.isEmpty then s"${frame.callerName}(${frame.position.show})"
    else s"${frame.callerName}.$callee(${frame.position.show})"
end site

/** A pending computation: the node arm of the pending union.
  *
  * Sealed with every node class in this file, so union membership is closed: a value either settled or is one of the nodes below, and the
  * eval's destructuring match is exhaustive. Bare arrows are not members, which is what makes "not all arrows are computations" structural.
  *
  * Allocation reporting fires from the sealed node classes' constructors, never from this trait: a trait body statement emits a `$init$`
  * plus a call in every mixing class even when disabled folds it away, while a class constructor whose folded body is empty leaves no
  * trace. Sealedness is what keeps the three carriers exhaustive.
  */
sealed trait Pending[+A, -S] extends kyo.proto.Kyo[A, S]:
    def frame: Frame = Frame.internal

    /** The releases this computation still owes, as a computation for the holder to sequence into its own stream, which is what lets the
      * ambient context reach them; running it detached loses that. Only an open region owes anything, so only the region carriers compose:
      * a rotation speaks its wrapped suspension then its rotated handler, and a Handle its interior then its own extent, so nesting
      * releases innermost first. Everything else states that it owes nothing. Abstract on purpose: every node class must declare its
      * stance, so a new carrier cannot silently miss its override.
      */
    def release(ex: Throwable): Any < Any
end Pending

// Public object, private-free members for the same reason as before: the combinators' inline
// expansions reach the node classes from call sites outside this package.
object Kyo:

    abstract class Defer[A, B, C, -S] @publicInBinary private[kyo] () extends Pending[C, S]:
        Debugger.onAlloc(this)

        def release(ex: Throwable): Any < Any =
            value match
                case p: Pending[?, ?] => p.release(ex)
                case _                => ()

        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def toString =
            def slot(a: Arrow[?, ?, ?]): String = if a eq this then s"this(${site(frame)})" else short(a)
            s"Defer(${short(value)}, ${slot(contA)}, ${slot(contB)})"
    end Defer

    /** A deferral that is also the arrow it defers to, as a class rather than a mixin at each site.
      *
      * The fusion the proto is built on puts a node and an arrow in one object, and every site that did so wrote
      * `new Defer[...] with Arrow.Transform[...]`. `Arrow` and `Transform` are traits and `Defer` implements none of their members, so each
      * such anonymous class is the first class in its chain and Scala emits mixin forwarders for `head`, `tail`, `chain` and `apply` into
      * every one of them. Dozens of classes, one per fusion site, each with its own copy of the same four bodies: the JIT then has no unique
      * target to bind and no small receiver profile either, so those calls never inline. Measured on `handleLoopAnswersInPlace`, that is 35
      * of the megamorphic sites on the hot path.
      *
      * Extending one class instead moves the emission here, once. This is what `kyo.kernel` does with `Arrow.TransformBase`, whose fusion
      * sites carry no forwarders at all.
      */
    abstract class DeferTransform[A, B, -S] extends Defer[A, B, B, S] with Arrow.Transform[A, B, S]

    /** The rotations: a suspension that is also the arrow its answer flows into.
      *
      * Absorbing registers into a node and rebuilding a region across a foreign crossing both produce one object playing both roles, and
      * both wrote the mixin at the site. Same emission as [[DeferTransform]] and same cost, so the same fix: the mixin happens once, here,
      * and the sites extend a class.
      */
    abstract class SuspendArrowTransform[I[_], O[_], E <: ArrowEffect[I, O], State, A, S]
        extends SuspendArrow[I, O, E, State, A, S] with Arrow.Transform[O[State], A, S]

    abstract class SuspendContextTransform[State, E <: ContextEffect[State], A, S]
        extends SuspendContext[State, E, A, S] with Arrow.Transform[State, A, S]

    abstract class SuspendContextDefaultTransform[State, E <: ContextEffect[State], A, S]
        extends SuspendContextDefault[State, E, A, S] with Arrow.Transform[State, A, S]

    abstract class HandleTransform[E <: Effect, A, B, C, -S, State]
        extends Handle[E, A, B, C, S, State] with Arrow.Transform[B, C, S]

    sealed abstract class Suspend[E <: Effect, A, S] extends Pending[A, S]:
        Debugger.onAlloc(this)

        def release(ex: Throwable): Any < Any = ()

        type Op
        def tag: Tag[E]
        def cont: Arrow[Op, A, S]
        def withCont[B, S2](c: Arrow[Op, B, S2]): Suspend[E, B, S2]
    end Suspend

    abstract class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], State, A, S] extends Suspend[E, A, S]:
        type Op = O[State]
        def input: I[State]
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendArrow(tag, input, c)
        override def toString =
            s"SuspendArrow(${tag.show}, $input, ${if cont eq this then "this" else short(cont)})"
    end SuspendArrow

    // TODO let's avoid these factory methods. The development is a lot about memoty layout, allocations, being explicit about instantiations is good and gives space to find optimizations for example avoiding a field for a contsntat
    object SuspendArrow:
        def apply[I[_], O[_], E <: ArrowEffect[I, O], State, A, S](
            t: Tag[E],
            in: I[State],
            c: Arrow[O[State], A, S]
        ): SuspendArrow[I, O, E, State, A, S] =
            new SuspendArrow[I, O, E, State, A, S]:
                def tag   = t
                def input = in
                def cont  = c
    end SuspendArrow

    abstract class SuspendContext[State, E <: ContextEffect[State], A, S] extends Suspend[E, A, S]:
        type Op = State
        def update(v: State): State
        def withCont[B, S2](c: Arrow[Op, B, S2]) =
            // the copy delegates to the original through the outer reference instead of capturing
            // each member as its own field
            new SuspendContext[State, E, B, S2]:
                def tag              = SuspendContext.this.tag
                def update(v: State) = SuspendContext.this.update(v)
                def cont             = c
        override def toString =
            s"SuspendContext(${tag.show}, ${if cont eq this then "this" else short(cont)})"
    end SuspendContext

    abstract class SuspendContextDefault[State, E <: ContextEffect[State], A, S] extends Suspend[E, A, S]:
        type Op = State
        def default: State
        def update(v: State): State
        def withCont[B, S2](c: Arrow[Op, B, S2]) =
            // the copy delegates to the original through the outer reference instead of capturing
            // each member as its own field
            new SuspendContextDefault[State, E, B, S2]:
                def tag              = SuspendContextDefault.this.tag
                def default          = SuspendContextDefault.this.default
                def update(v: State) = SuspendContextDefault.this.update(v)
                def cont             = c
        override def toString =
            s"SuspendContextDefault(${tag.show}, $default, ${if cont eq this then "this" else short(cont)})"
    end SuspendContextDefault

    def handle[E <: Effect, A, B, S, State](v: A < (E & S), handler: Handler[E, A, B, S, State], state: State): B < S =
        v match
            case kyo: Pending[A, E & S] @unchecked =>
                // built directly rather than through the companion: the identity continuation is a
                // constant, not a captured field
                val h  = handler
                val st = state
                new Handle[E, A, B, B, S, State]:
                    def value   = kyo
                    def handler = h
                    def state   = st
                    def cont    = Arrow.id
                end new
            case _ =>
                // a completion delivers the raw payload, so the union representation is stripped here
                handler.done(state, Nested.unnest[A](v))

    abstract class Handle[E <: Effect, A, B, C, -S, State] extends Pending[C, S]:
        Debugger.onAlloc(this)

        def release(ex: Throwable): Any < Any =
            Debugger.onRelease(handler, ex)
            value match
                case p: Pending[?, ?] => p.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                case _                => handler.release(state, ex)
        end release

        def value: A < (E & S)
        def handler: Handler[E, A, B, S, State]
        def state: State
        def cont: Arrow[B, C, S]

        override def toString =
            if cont.isInstanceOf[Arrow.Id[?]] then s"Handle(${short(value)}, $handler, $state)"
            else s"Handle(${short(value)}, $handler, $state, ${if cont eq this then "this" else short(cont)})"
    end Handle

    object Handle:
        def apply[E <: Effect, A, B, C, S, State](
            v: A < (E & S),
            h: Handler[E, A, B, S, State],
            st: State,
            c: Arrow[B, C, S]
        ): Handle[E, A, B, C, S, State] =
            new Handle[E, A, B, C, S, State]:
                def value   = v
                def handler = h
                def state   = st
                def cont    = c
    end Handle
end Kyo
