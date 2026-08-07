package kyo.proto4

import kyo.Frame
import kyo.Tag
import scala.annotation.nowarn

/** An effect whose operations are functions awaiting implementation.
  *
  * An `ArrowEffect[I, O]` declares a family of operations indexed by a type `C`: each operation takes an input `I[C]` and produces an
  * output `O[C]`. Suspending an operation describes it as data; a handler interprets it, choosing per handle site how much control it
  * needs: answer in place, stop the computation, or receive the continuation as a first-class [[Arrow]].
  *
  * Effects that never resume declare it in their output type: an effect with output `Const[Nothing]` cannot be resumed by any handler,
  * whatever format the handler uses.
  *
  * @tparam I
  *   The operation input constructor
  * @tparam O
  *   The operation output constructor
  */
abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    /** Suspends an operation of the effect `E`.
      *
      * Returns the operation as a pending computation. The operation runs when a handler for `E` interprets it.
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    ): O[A] < E =
        val in = operationInput
        new Kyo.Suspend[I, O, E, A]:
            def input = in
            def tag   = effectTag
            def frame = _frame
        .asInstanceOf[O[A] < E]
    end suspend

    /** Suspends an operation and maps its output in one step. */
    inline def suspendWith[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline operationInput: I[A]
    )(
        inline f: O[A] => B < S
    ): B < (E & S) =
        suspend[A](using _frame)[I, O, E](effectTag, operationInput).map(f)

end ArrowEffect
