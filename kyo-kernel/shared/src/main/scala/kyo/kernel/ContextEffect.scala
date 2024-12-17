package kyo.kernel

import kyo.*
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
  * Context effects come in two varieties. By default, values are inherited across async boundaries when computations are suspended and
  * resumed. Effects that mix the ContextEffect.Isolated trait do not cross async boundaries, requiring fresh values when computation
  * resumes asynchronously. This isolation is useful for values that should remain within a single async context, like thread-local data.
  *
  * The polymorphic type parameter A defines what type of value is required:
  * @tparam A
  *   The type of value that will be provided by a handler
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    /** A marker trait for context effects that do not persist across asynchronous boundaries.
      *
      * When a context effect extends this trait, its values will not be inherited by child fibers after an asynchronous operation. Instead,
      * child fibers start with fresh values, making these effects behave similarly to non-inheritable thread locals.
      */
    trait Isolated:
        self: ContextEffect[?] =>

    /** Creates a suspended computation that requests a value from a context effect. This establishes a requirement for a value that must be
      * satisfied by a handler higher up in the program. The requirement becomes part of the effect type, ensuring that handlers must
      * provide the requested value before the program can execute.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @return
      *   A computation that will receive the requested value when executed
      */
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline frame: Frame): A < E =
        suspendAndMap(effectTag)(identity)

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
    inline def suspendAndMap[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: Safepoint ?=> A => B < S
    )(using inline frame: Frame): B < (E & S) =
        suspendAndMap(effectTag, bug("Unexpected pending context effect: " + effectTag.show))(f)

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
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline default: => A
    )(using inline frame: Frame): A < Any =
        suspendAndMap(effectTag, default)(identity)

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
    inline def suspendAndMap[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline default: => A
    )(
        inline f: Safepoint ?=> A => B < S
    )(using inline _frame: Frame): B < S =
        new KyoDefer[B, S]:
            def frame = _frame
            def apply(v: Unit, context: Context)(using Safepoint) =
                Safepoint.handle(v)(
                    suspend = this,
                    continue = f(context.getOrElse(effectTag, default).asInstanceOf[A])
                )

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
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(
        using
        inline _frame: Frame,
        inline flat: Flat[A]
    ): B < S =
        handle(effectTag, value, _ => value)(v)

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
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(
        using
        inline _frame: Frame,
        inline flat: Flat[A]
    ): B < S =
        @nowarn("msg=anonymous")
        def handleLoop(v: B < (E & S))(using Safepoint): B < S =
            v match
                case kyo: KyoSuspend[IX, OX, EX, Any, B, S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, B, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            val tag = effectTag // avoid inlining the tag multiple times
                            val updated =
                                if !context.contains(tag) then context.set(tag, ifUndefined)
                                else context.set(tag, ifDefined(context.get(tag)))
                            handleLoop(kyo(v, updated))
                        end apply
                case kyo =>
                    kyo.asInstanceOf[B]
        handleLoop(v)
    end handle
end ContextEffect
