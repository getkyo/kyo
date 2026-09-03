package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn

/** Represents the requirement for a value that will be provided later by a handler.
  *
  * While ArrowEffect represents functions awaiting implementation, ContextEffect represents values awaiting provision. It captures the need
  * for a value of type A without specifying where that value comes from. When a handler provides a value, that value becomes available
  * within the handler's scope - once the handler's scope ends, the value is no longer available to computations.
  *
  * This mechanism aligns with dependency injection patterns - handlers act as injectors that provide values within their scope, and
  * different handlers can provide different values in different scopes. The composition of effects automatically tracks these requirements
  * through the type system.
  *
  * What a value does at an async boundary is decided by the handler that provides it. `handleInheritable` makes the value visible to
  * forked computations as is; `handle` takes explicit fork and join strategies, which is how values that should remain within a single
  * async context, like thread-local data, or values that must merge back after a fork are expressed.
  *
  * The polymorphic type parameter A defines what type of value is required:
  * @tparam A
  *   The type of value that will be provided by a handler
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    // Diverges from main: the `Noninheritable` marker is gone (D4, inheritance is a handler strategy below), and a suspension builds a
    // `Pending.SuspendContext` node where main builds a `KyoDefer` that reads the `Context`.

    /** Creates a suspended computation that requests a value from a context effect. This establishes a requirement for a value that must be
      * satisfied by a handler higher up in the program. The requirement becomes part of the effect type, ensuring that handlers must
      * provide the requested value before the program can execute.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @return
      *   A computation that will receive the requested value when executed
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): A < E =
        new Pending.SuspendContext[A, E, A, E]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe.empty
            def cont           = Arrow.id

    /** Creates a suspended computation that requests a context value and transforms it immediately upon receipt. This combines the
      * operations of requesting and transforming a context value into a single step.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param f
      *   The transformation to apply to the received value
      * @return
      *   A computation containing the transformed value
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < (E & S) =
        new Pending.SuspendContextWith[A, E, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe.empty
            def cont           = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)

    /** Requests a value from a context effect with a specified default value. Unlike standard suspend, this version does not create a
      * mandatory effect requirement. If no handler provides a value, the computation proceeds with the default value instead. This makes
      * the context value optional rather than required.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param default
      *   The value to use when no handler provides one
      * @return
      *   A computation that provides either the context value or default
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline default: => A
    )(using inline _frame: Frame): A < Any =
        def defaultValue: A = default
        new Pending.SuspendContext[A, E, A, Any]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe(defaultValue)
            def cont           = Arrow.id
        end new
    end suspend

    /** Requests an optional context value and transforms it, using a default if no value is available. This combines requesting an optional
      * context value with immediate transformation. The transformation function receives either the context value if available or the
      * default value if not.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param default
      *   The value to use when no handler provides one
      * @param f
      *   The transformation to apply to either the context or default value
      * @return
      *   A computation containing the transformed value
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline default: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =
        def defaultValue: A = default
        new Pending.SuspendContextWith[A, E, B, S]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe(defaultValue)
            def cont           = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)
        end new
    end suspendWith

    // Diverges from main: main's `handle` inherits across async boundaries unless the effect mixes in the
    // Noninheritable marker. Here inheritance is a per-handler strategy (D4): `handleInheritable` is main's
    // default and `handle` takes explicit fork and join strategies plus the optional done and release hooks.

    /** Handles a context effect by providing a value for a specific computation scope. This satisfies suspend operations within that scope
      * by making the provided value available to them. The handler establishes a region where the context value is defined and can be
      * accessed.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param value
      *   The value to provide to the computation
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value provided
      */
    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handleInheritable(effectTag)((_: Maybe[A]) => value)(v)

    /** Handles a context effect by either providing a new value or transforming an existing one. This allows for layered handling of
      * context values, where a handler can either establish a new value when none exists or modify a value that was provided by an outer
      * handler.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param ifUndefined
      *   The value to use when no existing value is found
      * @param ifDefined
      *   The transformation to apply to any existing value
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value handled
      */
    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(
        using inline _frame: Frame
    ): B < S =
        handleInheritable(effectTag)((outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined))(v)

    /** Handles a context effect by deriving the region's value from the outer one, if any. The value is inherited across async boundaries.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param derive
      *   Computes the region's value from the outer value, absent when no outer handler provides one
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value handled
      */
    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)(derive, (parent: A) => parent, (parent: A, _: A, _: A) => parent)(v)

    /** Handles a context effect with explicit strategies for what a forked computation sees and how its value comes back on join.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param ifUndefined
      *   The value to use when no existing value is found
      * @param ifDefined
      *   The transformation to apply to any existing value
      * @param fork
      *   Computes the value a forked computation starts with from the parent's
      * @param join
      *   Computes the parent's value after a fork completes, from the parent's, the forked start and the forked end values
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value handled
      */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline ifUndefined: A,
        inline ifDefined: A => A,
        inline fork: A => A,
        inline join: (A, A, A) => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)((outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined), fork, join)(v)

    /** Handles a context effect with explicit fork and join strategies and a release hook, called with the region's value when the region
      * is abandoned or fails.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param ifUndefined
      *   The value to use when no existing value is found
      * @param ifDefined
      *   The transformation to apply to any existing value
      * @param fork
      *   Computes the value a forked computation starts with from the parent's
      * @param join
      *   Computes the parent's value after a fork completes, from the parent's, the forked start and the forked end values
      * @param release
      *   Called with the region's value and the failure when the region does not complete normally
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value handled
      */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline ifUndefined: A,
        inline ifDefined: A => A,
        inline fork: A => A,
        inline join: (A, A, A) => A,
        inline release: (A, Throwable) => Unit
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)((outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined), fork, join, release = release)(v)

    /** Handles a context effect with the full set of strategies. The region's value is derived from the outer one at entry; fork and join
      * decide what crosses a fork and what comes back; done runs with the value when the region completes and release when it does not.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param derive
      *   Computes the region's value from the outer value, absent when no outer handler provides one
      * @param fork
      *   Computes the value a forked computation starts with from the parent's
      * @param join
      *   Computes the parent's value after a fork completes, from the parent's, the forked start and the forked end values
      * @param done
      *   Called with the region's value when the region completes normally
      * @param release
      *   Called with the region's value and the failure when the region does not complete normally
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value handled
      */
    @nowarn("msg=anonymous")
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A,
        inline fork: A => A,
        inline join: (A, A, A) => A,
        inline done: A => Unit = (_: A) => (),
        inline release: (A, Throwable) => Unit = (_: A, _: Throwable) => ()
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        def derived(outer: Maybe[A]): A             = derive(outer)
        def forked(parent: A): A                    = fork(parent)
        def joined(parent: A, fk: A, child: A): A   = join(parent, fk, child)
        def completed(state: A): Unit               = done(state)
        def released(state: A, ex: Throwable): Unit = release(state, ex)
        val h =
            new Handler.ContextHandler[A, E, B, S]:
                def tag                                                    = effectTag
                def derive(outer: Maybe[A])                                = derived(outer)
                def fork(parent: A)                                        = forked(parent)
                def join(parent: A, forked: A, child: A)                   = joined(parent, forked, child)
                override private[kyo] def done(state: A)                   = completed(state)
                override private[kyo] def release(state: A, ex: Throwable) = released(state, ex)

        new Pending.HandleContext[A, E, B, S]:
            override def frame = _frame
            def value          = v
            def handler        = h
        end new
    end handle
end ContextEffect
