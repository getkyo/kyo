package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import scala.annotation.nowarn

/** An effect that declares the need for a value provided by a handler's scope.
  *
  * Where [[ArrowEffect]] declares operations awaiting implementation, a `ContextEffect[V]` declares a value awaiting provision. Reads
  * observe the innermost binding installed by a handler; handlers install or transform bindings for a scope. Because the state is a plain
  * value with no write-back, context effects fork by copying, which is what makes them cheap to carry across execution boundaries.
  *
  * @tparam V
  *   The type of value provided by handlers of this effect
  */
abstract class ContextEffect[+V] extends Effect

object ContextEffect:

    /** Reads the value of `E` from the innermost binding in scope. */
    @nowarn("msg=anonymous")
    inline def suspend[V, E <: ContextEffect[V]](
        inline effectTag: Tag[E]
    )(using inline _frame: Frame): V < E =
        new Kyo.ContextRead[V, E]:
            def tag     = effectTag
            def default = Maybe.Absent
            def frame   = _frame
        .asInstanceOf[V < E]

    /** Reads the value of `E` and maps it in one step. */
    inline def suspendWith[V, E <: ContextEffect[V], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: V => B < S
    )(using inline _frame: Frame): B < (E & S) =
        suspend[V, E](effectTag).map(f)

    /** Reads the value of `E` if a binding is in scope, or the default otherwise.
      *
      * The read carries no effect requirement: with no binding in scope the default is used, so the computation runs unhandled.
      */
    @nowarn("msg=anonymous")
    inline def suspend[V, E <: ContextEffect[V]](
        inline effectTag: Tag[E],
        inline default: => V
    )(using inline _frame: Frame): V < Any =
        val fallback: () => V = () => default
        new Kyo.ContextRead[V, E]:
            def tag     = effectTag
            def default = Maybe(fallback)
            def frame   = _frame
        .asInstanceOf[V < Any]
    end suspend

    /** Provides a constant binding for `E` within the computation's scope. */
    def handle[V, E <: ContextEffect[V], A, S](
        effectTag: Tag[E],
        value: V
    )(v: A < (E & S))(using frame: Frame): A < S =
        handle(effectTag, value, _ => value)(v)

    /** Provides a binding for `E`, transforming any outer binding.
      *
      * `ifUndefined` supplies the value when no outer binding exists; `ifDefined` derives this scope's value from the outer one. Nested
      * handlers compose: reads observe the innermost binding, and each binding's transform sees the resolution of the bindings outside it.
      */
    def handle[V, E <: ContextEffect[V], A, S](
        effectTag: Tag[E],
        ifUndefined: => V,
        ifDefined: V => V
    )(v: A < (E & S))(using frame: Frame): A < S =
        val transform: Maybe[Any] => Any =
            case Maybe.Present(outer) => ifDefined(outer.asInstanceOf[V])
            case Maybe.Absent         => ifUndefined
        val h = new Handler.Context(effectTag.asInstanceOf[Tag[Any]], transform, frame)
        h.asInstanceOf[Arrow[Any, Any, Any]](v.asInstanceOf[Any < Any]).asInstanceOf[A < S]
    end handle

end ContextEffect
