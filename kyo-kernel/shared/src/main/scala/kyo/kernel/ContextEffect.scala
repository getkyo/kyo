package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn

/** Represents the requirement for a value that will be provided later by a handler.
  *
  * Where an [[ArrowEffect]] declares operations awaiting an implementation, a context effect declares a value awaiting provision. It says a
  * computation expects to find an `A` bound around it, and reading that value is the only thing the computation can do with it. A handler
  * binds the value for an extent, and outside that extent it is not there, so the binding is dynamically scoped rather than threaded through
  * signatures. Different handlers can bind different values in different scopes, and the row tracks the requirement either way.
  *
  * A read comes in two forms. The required form puts the effect in the row, and a computation only evaluates once its row is empty, so a
  * required read is unreachable until a handler has bound a value. That is a static guarantee over the whole program, not a check that
  * happens to pass. The defaulted form adds nothing to the row, because it cannot fail.
  *
  * #### Crossing an async boundary
  *
  * What a forked computation sees is the handler's choice, and it is the decision to get right when binding one:
  *   - [[ContextEffect.handleInheritable]] makes the value visible to a fork as it stands.
  *   - [[ContextEffect.handleNonInheritable]] keeps it out of a fork, for a value tied to one execution context.
  *   - [[ContextEffect.handle]] takes explicit fork and join strategies, for a value that has to be transformed when a fork takes it or
  *     merged when the fork rejoins.
  *
  * Note: the raise that backs a missing binding is reachable only by discarding the row with a cast. Reach for a handler or a default.
  *
  * @tparam A
  *   The type of value that will be provided by a handler
  *
  * @see
  *   [[ContextEffect.suspend]] For reading the bound value, with or without a default
  * @see
  *   [[ContextEffect.handleInheritable]] For binding a value over a computation
  * @see
  *   [[ArrowEffect]] For the other kind of effect, an operation answered by a handler
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    /** Reads the value bound for this context effect, requiring that something has bound one.
      *
      * The effect joins the row, and a computation only evaluates once its row is empty, so this read cannot be reached until a handler has
      * bound a value. The overload taking a default is the way to read without requiring one.
      *
      * @param effectTag
      *   Identifies the context effect to read
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): A < E =
        new Pending.SuspendContext[A, E, A, E]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe.empty
            def cont           = Arrow.id

    /** Reads the bound value and transforms it in the same node, rather than reading and mapping afterwards.
      *
      * This is what a helper wants where it would otherwise write `suspend(tag).map(f)`: `f` is fused into the read, so the value is
      * transformed where it arrives instead of through a separate node.
      *
      * @param effectTag
      *   Identifies the context effect to read
      * @param f
      *   Transforms the bound value
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

    /** Reads the bound value, falling back to `default` when nothing has bound one.
      *
      * The effect does not join the row, because this read cannot fail. That is what lets a computation using it evaluate with nothing bound
      * around it at all.
      *
      * @param effectTag
      *   Identifies the context effect to read
      * @param defaultValue
      *   Used when no handler has bound a value, evaluated only then
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(using inline _frame: Frame): A < Any =
        new Pending.SuspendContext[A, E, A, Any]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe(defaultValue)
            def cont           = Arrow.id
        end new
    end suspend

    /** Reads the bound value with a fallback and transforms it in the same node.
      *
      * `f` receives whichever value the read produced, bound or default, and the effect stays out of the row as in the plain defaulted read.
      *
      * @param effectTag
      *   Identifies the context effect to read
      * @param defaultValue
      *   Used when no handler has bound a value, evaluated only then
      * @param f
      *   Transforms the value that was read
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =
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
      */
    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)(derive, (parent: A) => parent, (parent: A, _: A, _: A) => parent)(v)

    /** Handles a context effect with a value that does not cross into forks.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param value
      *   The value to provide to the computation
      * @param v
      *   The computation requiring the context value
      */
    inline def handleNonInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handleNonInheritable(effectTag)((_: Maybe[A]) => value)(v)

    /** Handles a context effect with a value that does not cross into forks, deriving it from the outer one.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param ifUndefined
      *   The value to use when no existing value is found
      * @param ifDefined
      *   The transformation to apply to any existing value
      * @param v
      *   The computation requiring the context value
      */
    inline def handleNonInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handleNonInheritable(effectTag)((outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined))(v)

    /** Handles a context effect whose value does not cross into forks.
      *
      * Where [[handleInheritable]] hands a fork the value this scope holds, this hands it the value the region would
      * have taken with nothing bound outside it, so a fork starts the region over rather than continuing it. That is
      * what a value tied to one execution needs, `Bracket`'s cell being the standing example: a child that inherited
      * it would release a resource the scope that acquired it is still using.
      *
      * `join` keeps the scope's own value, there being nothing a fork could have carried back.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param derive
      *   Computes the region's value from the outer value, absent when no outer handler provides one
      * @param v
      *   The computation requiring the context value
      */
    inline def handleNonInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        def derived(outer: Maybe[A]): A = derive(outer)
        handle(effectTag)(
            derived,
            fork = (_: A) => derived(Maybe.empty),
            join = (parent: A, _: A, _: A) => parent
        )(v)
    end handleNonInheritable

    /** Handles a context effect with explicit fork and join strategies.
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

    /** Handles a context effect with explicit fork and join strategies and a release hook.
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

    /** Binds a value over a computation, with the full set of strategies.
      *
      * The arms cover the value's whole life in the region: `derive` produces it from whatever an outer handler bound, `fork` and `join`
      * decide what a forked computation starts with and what the parent holds once that fork rejoins, and exactly one of `done` or `release`
      * runs at the end, according to whether the region completed normally.
      *
      * The narrower entry points are this one with arms filled in. [[handleInheritable]] forks the parent's value as it stands and keeps the
      * parent's on join; [[handleNonInheritable]] derives a fresh value for the fork instead, as though no outer binding existed.
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
