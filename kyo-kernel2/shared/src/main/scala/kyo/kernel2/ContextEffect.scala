package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Handler
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

    /** Detaches a computation at a fork boundary, handing it the inherited context snapshot.
      *
      * The snapshot materializes at a boundary drive, the same late resolution a context read gets: bindings installed between
      * construction and the boundary are visible. Noninheritable bindings are filtered before the fork sees them. The ContextSnapshot
      * suspension it rides on is interim: the context threading redesign replaces it with the context handed as a parameter, like the
      * current kernel.
      */
    private[kyo] def runDetached[A, S](f: kyo.kernel2.internal.Context => A < S)(using Frame): A < S =
        val snapshot = new Kyo.ContextSnapshot(summon[Frame]).asInstanceOf[kyo.kernel2.internal.Context < Any]
        snapshot.map(context => f(context.inherit))

    /** A marker trait for context effects that do not persist across asynchronous boundaries.
      *
      * When a context effect extends this trait, its values will not be inherited by child fibers after an asynchronous operation.
      * Instead, child fibers start with fresh values, making these effects behave similarly to non-inheritable thread locals.
      */
    trait Noninheritable:
        self: ContextEffect[?] =>

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
        new Handler.ContextBinding[A](effectTag.erased, transform, frame).install[E, S](v)
    end handle

end ContextEffect
