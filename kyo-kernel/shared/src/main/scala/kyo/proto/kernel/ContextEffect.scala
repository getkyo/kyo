package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Arrow.Transform
import kyo.proto.kernel.internal.Context
import kyo.proto.kernel.internal.Handler.HandlerContext
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import scala.annotation.nowarn

/** Represents the requirement for a value that will be provided later by a handler.
  *
  * While ArrowEffect represents functions awaiting implementation, ContextEffect represents values awaiting provision. It captures the need
  * for a value of type A without specifying where that value comes from. When a handler provides a value, that value becomes available
  * within the handler's scope: once the handler's scope ends, the value is no longer available to computations.
  *
  * A read travels as a suspension against the eval's threaded context; the eval answers it where the node says it is answered, hands the
  * continuation the updated context, and the read's own transform extracts the tag's value. An update rebinds the value for the rest of
  * the providing region's extent. A read with a default needs no region at all: it is answered wherever it stands, the extraction falling
  * back to the default when nothing binds the tag, which is why its row is `Any`.
  *
  * @tparam A
  *   The type of value that will be provided by a handler
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    /** Creates a suspended computation that requests a value from a context effect. The requirement becomes part of the effect type,
      * ensuring that a handler must provide the value before the program can execute.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @return
      *   A computation that will receive the requested value when executed
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): A < E =
        // the read is its own extraction: the node travels while its tag is unbound, and once the
        // eval answers it with the context, the transform takes the tag's value out. The row `E`
        // is this glue's claim: the mandatory read is the one whose extraction has no fallback
        new Kyo.SuspendContextTransform[A, E, A, E]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = ctx.contains(effectTag)
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        cont2(Nested.nest[A, S2](Nested.unnest[Context](v).apply[A, E](effectTag)), Arrow.id)

    /** Creates a suspended computation that requests a context value and transforms it immediately upon receipt.
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
        // one allocation fulfilling both roles: the node is the read, its own continuation, and
        // the extraction feeding the transform
        new Kyo.SuspendContextTransform[A, E, B, E & S]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = ctx.contains(effectTag)
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                    => cont2(f(Nested.unnest[Context](v).apply[A, E](effectTag)), Arrow.id)

    /** Requests a value from a context effect with a specified default. This version does not create a mandatory effect requirement: it is
      * answered wherever it stands, falling back to the default when no handler provides a value, which is why the row is `Any`.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param defaultValue
      *   The value to use when no handler provides one
      * @return
      *   A computation that provides either the context value or the default
      */
    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(using inline _frame: Frame): A < Any =
        // the same node as the mandatory read, answered everywhere: the extraction falls back to
        // the default when nothing binds the tag, which is why this glue's row claim is `Any`
        new Kyo.SuspendContextTransform[A, E, A, Any]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = true
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        val ctx = Nested.unnest[Context](v)
                        val a   = if ctx.contains(effectTag) then ctx.apply[A, E](effectTag) else defaultValue
                        cont2(Nested.nest[A, S2](a), Arrow.id)

    /** Requests an optional context value and transforms it, using the default if no value is available.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param defaultValue
      *   The value to use when no handler provides one
      * @param f
      *   The transformation to apply to either the context or the default value
      * @return
      *   A computation containing the transformed value
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =
        // one allocation fulfilling both roles: the node is the read, its own continuation, and
        // the fallback extraction feeding the transform
        new Kyo.SuspendContextTransform[A, E, B, S]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = true
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        val ctx = Nested.unnest[Context](v)
                        val a   = if ctx.contains(effectTag) then ctx.apply[A, E](effectTag) else defaultValue
                        cont2(f(a), Arrow.id)

    /** Rebinds the innermost binding of the tag to the transformed value, for the remainder of that binding's extent. The read and the
      * write are one operation: the computation answers with the updated value, and everything that runs inside the binding's extent after
      * this operation sees it. Unlike [[handle]], whose derived value is scoped to the computation it wraps, an update outlives its own
      * expression, which is what lets a helper method write a binding its caller keeps reading.
      *
      * An update needs a binding to write, so the row is mandatory; there is no defaulted variant, because a boundary-answered update
      * would compute a value and discard the write.
      *
      * @param effectTag
      *   Identifies which context effect to update
      * @param f
      *   The transformation to apply to the bound value
      * @return
      *   A computation answering the updated value
      */
    @nowarn("msg=anonymous")
    inline def update[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E]
    )(
        inline f: A => A
    )(using inline _frame: Frame): A < E =
        // the same node as the reads: the non-identity write is the whole difference, and the
        // extraction reads the context the write already updated, so the answer is the new value
        new Kyo.SuspendContextTransform[A, E, A, E]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = ctx.contains(effectTag)
            def update(ctx: Context)  = ctx.update(effectTag, f(ctx.apply[A, E](effectTag)))
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        cont2(Nested.nest[A, S2](Nested.unnest[Context](v).apply[A, E](effectTag)), Arrow.id)

    /** Handles a context effect by providing a value for a specific computation scope. Suspend operations within that scope receive the
      * provided value; the scope ends where the computation completes. Installed inside another binding of the same tag, this one wins for
      * its extent: the delegation rebinds the given value whatever the enclosing binding holds.
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
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)((_: Maybe[A]) => value)(v)

    /** Handles a context effect by deriving the bound value from whatever the enclosing scope binds: the derivation receives the enclosing
      * binding's value, Absent when nothing is bound, so `_.fold(ifUndefined)(ifDefined)` is the layered shape. Because a binding resolves
      * when it is installed, a region captured and re-installed under a different enclosing binding derives from that one instead.
      *
      * The optional parameters say what happens at the edges of the extent, and each defaults to the plainest answer: `onFork` is what a
      * computation forked from here receives, and `onJoin` is what this holds once a fork ends, given what it held, what the fork
      * received, and how the fork ended. A release rides the handler protocol (`Handler.release`); this surface does not take one.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param derive
      *   The bound value as a function of what the enclosing scope binds
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
        inline onFork: A => A < S = (current: A) => current,
        inline onJoin: (A, A, Result[Nothing, A]) => Result[Nothing, A] < S =
            (_: A, _: A, result: Result[Nothing, A]) => result
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerContext[A, E, B, B, S]:
                        def tag                                                     = effectTag
                        def resolve(outer: Maybe[A])                                = derive(outer)
                        def fork(current: A)                                        = onFork(current)
                        def join(current: A, forked: A, result: Result[Nothing, A]) = onJoin(current, forked, result)
                        def done(state: A, v0: B)                                   = v0
                // the region node is built at the site: the identity continuation is a constant and
                // the state slot delegates to the derivation, so neither takes a field and nothing
                // evaluates before installation
                new Kyo.Handle[E, B, B, B, S, A]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = h.resolve(Maybe.empty)
                    def cont           = Arrow.id
                end new
            case _ => Nested.unnest[B](v)
        end match
    end handle

end ContextEffect
