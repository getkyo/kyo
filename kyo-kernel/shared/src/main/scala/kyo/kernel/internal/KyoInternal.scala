package kyo.kernel.internal

import kyo.<
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.kernel.ArrowEffect

/** The core representation of suspended computations in Kyo's effect system.
  *
  * A value of type Kyo[A, S] packages together the key pieces needed to represent and resume a suspended computation:
  *
  *   - An input value that will be transformed by an effect handler
  *   - A tag identifying which handler should interpret the effect
  *   - A continuation that resumes computation with the handler's result
  *
  * Continuations form the heart of composition in Kyo. Each continuation receives a result and returns a new Kyo instance, creating a chain
  * where each step's output feeds into the next step's computation.
  *
  * This encoding is designed to be JIT-friendly - when paired with inlining in the pending type, it typically produces monomorphic call
  * sites since concrete Kyo instances directly chain to other concrete instances.
  *
  * @tparam A
  *   The type of value this computation will eventually produce
  * @tparam S
  *   The type-level set of effects this computation may perform
  */
sealed abstract private[kernel] class Kyo[+A, -S] extends Serializable

/** Base class of suspended computations, separated from Kyo solely to hide its additional type parameters and to avoid variance checking.
  * Contains the actual storage and continuation machinery.
  */
abstract private[kernel] class KyoSuspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S]
    extends Kyo[B, S]:

    /** The tag identifying which effect is being suspended */
    def tag: Tag[E]

    /** The input value to be transformed by the effect handler */
    def input: I[A]

    /** The stack frame where this suspension was created */
    def frame: Frame

    /** Continues the computation with a handler's result.
      *
      * @param v
      *   The handler's output value
      * @param context
      *   The current effect context
      * @return
      *   The remainder of the computation
      */
    def apply(v: O[A], context: Context)(using Safepoint): B < S

    /** String representation for debugging */
    final override def toString =
        s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"
end KyoSuspend

/** Specialized effect type for pure computation deferral using Unit input/output.
  */
abstract private[kernel] class KyoContinue[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](kyo: KyoSuspend[I, O, E, A, ?, ?])
    extends KyoSuspend[I, O, E, A, B, S]:
    val tag   = kyo.tag
    val input = kyo.input
end KyoContinue

/** A internal effect type for pure deferred computations.
  *
  * Defer is a singleton effect that represents suspended pure computations with no input requirements. It uses Const[Unit] for both input
  * and output since it only needs to control evaluation timing without transforming values.
  *
  * Used internally by the kernel to implement context effects and to achieve stack safety for recursive computations.
  */
sealed private[kernel] trait Defer extends ArrowEffect[Const[Unit], Const[Unit]]

/** Specialized KyoSuspend for deferred computations using the Defer effect. Hardcodes Unit types to minimize overhead.
  */
abstract private[kernel] class KyoDefer[A, S] extends KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, S]:
    final def tag   = Tag[Defer]
    final def input = ()
end KyoDefer
