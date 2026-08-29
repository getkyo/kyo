package kyo.proto.kernel

import kyo.Frame
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Arrow.Transform
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
  * A read travels as a suspension carrying the update to apply to the bound value; the eval answers it from the innermost region that
  * provides the tag, rebinding the updated value for the rest of that region's extent. A read with a default needs no region at all: the
  * boundary answers it when nothing else did, which is why its row is `Any`.
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
        // built at the site: the identity update is a method body and the identity continuation a
        // constant, so neither takes a field
        new Kyo.SuspendContext[A, E, A, E]:
            override def frame = _frame
            def tag            = effectTag
            def update(v: A)   = v
            def cont           = Arrow.id

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
        // one allocation fulfilling both roles: the node is the read and its own continuation
        new Kyo.SuspendContext[A, E, B, E & S] with Transform[A, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def update(v: A)   = v
            def cont           = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)

    /** Requests a value from a context effect with a specified default. This version does not create a mandatory effect requirement: if no
      * handler provides a value, the boundary answers with the default, which is why the row is `Any`.
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
        new Kyo.SuspendContextDefault[A, E, A, Any]:
            override def frame = _frame
            def tag            = effectTag
            def default        = defaultValue
            def update(v: A)   = v
            def cont           = Arrow.id

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
        // one allocation fulfilling both roles: the node is the read and its own continuation
        new Kyo.SuspendContextDefault[A, E, B, S] with Transform[A, B, S]:
            override def frame = _frame
            def tag            = effectTag
            def default        = defaultValue
            def update(v: A)   = v
            def cont           = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)

    /** Handles a context effect by providing a value for a specific computation scope. Suspend operations within that scope receive the
      * provided value; the scope ends where the computation completes.
      *
      * The optional parameters say what happens at the edges of the extent, and each defaults to the plainest answer: `onFork` is what a
      * computation forked from here receives, and `onJoin` is what this holds once a fork ends, given what it held, what the fork
      * received, and how the fork ended.
      *
      * Note: the machine binds the provided value as given; deriving it from an outer binding of the same tag (the reference's layered
      * `ifDefined`) has no counterpart here.
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
    @nowarn("msg=anonymous")
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A,
        inline onFork: A => A < S = (current: A) => current,
        inline onJoin: (A, A, Result[Nothing, A]) => Result[Nothing, A] < S =
            (_: A, _: A, result: Result[Nothing, A]) => result
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerContext[A, E, B, B, S]:
                        def tag                                                     = effectTag
                        def fork(current: A)                                        = onFork(current)
                        def join(current: A, forked: A, result: Result[Nothing, A]) = onJoin(current, forked, result)
                        def done(state: A, v0: B)                                   = v0
                val state0 = value
                // the region node is built at the site: the identity continuation is a constant,
                // not a captured field
                new Kyo.Handle[E, B, B, B, S, A]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ => Nested.unnest[B](v)
        end match
    end handle

end ContextEffect
