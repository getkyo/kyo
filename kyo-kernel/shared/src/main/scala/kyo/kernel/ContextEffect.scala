package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn

/** Represents the requirement for a value that will be provided later by a handler.
  *
  * Where an [[ArrowEffect]] declares operations, a context effect declares a value: a computation expects to find an `A` bound around it, and
  * reading it is the only thing it can do. A handler binds the value for an extent, dynamically scoped rather than threaded through
  * signatures; different handlers can bind different values in different scopes, and the row tracks the requirement either way.
  *
  * A read has two forms. The required form puts the effect in the row, and a computation only evaluates once its row is empty, so a required
  * read is unreachable until a handler has bound a value, a static guarantee over the whole program. The defaulted form adds nothing to the
  * row, because it cannot fail.
  *
  * What a forked computation sees is the handler's choice: [[ContextEffect.handleInheritable]] makes the value visible to a fork as it
  * stands, [[ContextEffect.handleNonInheritable]] keeps it out (for a value tied to one execution context), and [[ContextEffect.handle]]
  * takes explicit fork and join strategies. The raise backing a missing binding is reachable only by discarding the row with a cast, so
  * reach for a handler or a default.
  *
  * @tparam A
  *   The type of value that will be provided by a handler
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    /** Reads the value bound for this context effect. The effect joins the row (evaluating only once the row is empty), so this read cannot be
      * reached until a handler has bound a value.
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): A < E =
        new Pending.SuspendContext[A, E, A, E]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe.empty
            def cont           = Arrow.id

    /** Reads the bound value and transforms it in the same node, fusing `f` into the read rather than reading and mapping afterwards.
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < (E & S) =
        new Pending.SuspendContextWith[A, E, B, E & S]:
            override def frame                                           = _frame
            def tag                                                      = effectTag
            def default                                                  = Maybe.empty
            def cont                                                     = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)

    /** Reads the bound value, falling back to `defaultValue` when nothing has bound one. The effect stays out of the row (it cannot fail), so
      * a computation using it can evaluate with nothing bound around it at all.
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

    /** Reads the bound value with a fallback and transforms it in the same node: `f` receives whichever value the read produced, bound or
      * default.
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =
        new Pending.SuspendContextWith[A, E, B, S]:
            override def frame                                           = _frame
            def tag                                                      = effectTag
            def default                                                  = Maybe(defaultValue)
            def cont                                                     = this
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
        handleInheritable(effectTag, (_: Maybe[A]) => value)(v)

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
        handleInheritable(effectTag, (outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined))(v)

    /** Binds a value derived from the outer one (`derive`, absent when none), inherited across async boundaries. */
    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline derive: Maybe[A] => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag, derive, (parent: A) => parent, (parent: A, _: A, _: A) => parent)(v)

    /** Binds a value that does not cross into forks. */
    inline def handleNonInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handleNonInheritable(effectTag, (_: Maybe[A]) => value)(v)

    /** Binds a value that does not cross into forks: `ifUndefined` when no outer handler bound one, otherwise `ifDefined` on the outer value. */
    inline def handleNonInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handleNonInheritable(effectTag, (outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined))(v)

    /** Binds a value derived from the outer one that does not cross into forks.
      *
      * Where [[handleInheritable]] hands a fork the value this scope holds, this hands it the value the region would have taken with nothing
      * bound outside it, so a fork starts the region over. That is what a value tied to one execution needs, `Bracket`'s cell being the
      * standing example: a child that inherited it would release a resource the scope that acquired it is still using. `join` keeps the
      * scope's own value, there being nothing a fork could have carried back.
      */
    inline def handleNonInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline derive: Maybe[A] => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        def derived(outer: Maybe[A]): A = derive(outer)
        handle(
            effectTag,
            derived,
            fork = (_: A) => derived(Maybe.empty),
            join = (parent: A, _: A, _: A) => parent
        )(v)
    end handleNonInheritable

    /** Binds a value over a computation with explicit `fork` and `join` strategies, `ifUndefined`/`ifDefined` choosing the region's value. */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A,
        inline fork: A => A,
        inline join: (A, A, A) => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag, (outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined), fork, join)(v)

    /** [[handle]] with explicit `fork` and `join` strategies and a `release` hook run once when the region ends. */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A,
        inline fork: A => A,
        inline join: (A, A, A) => A,
        inline release: (A, Maybe[Throwable]) => Unit
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag, (outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined), fork, join, release = release)(v)

    /** Binds a value over a computation, with the full set of strategies covering the value's whole life in the region.
      *
      * `derive` produces the region's value from whatever an outer handler bound (absent when none), `fork` computes what a forked
      * computation starts with from the parent's value, `join` computes what the parent holds once the fork rejoins from the parent's,
      * forked-start and forked-end values, and `release` runs when the region ends, told `Absent` for a clean end or the failure on an
      * unwind. It runs once per evaluation: a region carried in a remainder that a clause resumes on another evaluator stack (a nested eval,
      * another thread) ends there and is drained again by the handler that owed it, so a handler that must release once across those guards
      * its state, as [[Bracket]] does with its cell.
      */
    @nowarn("msg=anonymous")
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline derive: Maybe[A] => A,
        inline fork: A => A,
        inline join: (A, A, A) => A,
        inline release: (A, Maybe[Throwable]) => Unit = (_: A, _: Maybe[Throwable]) => ()
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        def derived(outer: Maybe[A]): A                         = derive(outer)
        def forked(parent: A): A                                = fork(parent)
        def joined(parent: A, fk: A, child: A): A               = join(parent, fk, child)
        def released(state: A, failure: Maybe[Throwable]): Unit = release(state, failure)
        val h                                                   =
            new Handler.ContextHandler[A, E, B, S]:
                def tag                                          = effectTag
                def derive(outer: Maybe[A])                      = derived(outer)
                def fork(parent: A)                              = forked(parent)
                def join(parent: A, forked: A, child: A)         = joined(parent, forked, child)
                def release(state: A, failure: Maybe[Throwable]) = released(state, failure)

        new Pending.HandleContext[A, E, B, S]:
            override def frame = _frame
            def value          = v
            def handler        = h
        end new
    end handle
end ContextEffect
