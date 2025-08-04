package kyo

import kyo.Mask.CanMask
import kyo.kernel.ArrowEffect

/** Mask effect(s) [[S]] to protect from being handled. To be used in functions that accept effects with generic effect types in order to
  * avoid accidentally handling them. Can only be used within [[Mask.use]] blocks.
  *
  * @tparam S
  *   The effect type that is masked
  * @see
  *   [[Mask]]
  */
sealed trait Masked[S] extends ArrowEffect[[A] =>> A < S, Id]

/** Utility for protecting any effect of type [[S]] from being handled
  *
  * @tparam S
  *   The effect type that can be masked
  */
opaque type Mask[S] = Unit

object Mask:
    extension [S](mask: Mask[S])(using tag: Tag[Masked[S]])
        /** Mask an effect from being handled, wrapping its effect type [[S]] with [[Masked]]
          *
          * @param effect
          *   The effect to be masked
          * @return
          *   A new effect with its effect intersection protected from handling
          */
        def apply[A](effect: A < S)(using CanMask, Frame): A < Masked[S] =
            ArrowEffect.suspend[A](tag, effect)
    end extension

    sealed abstract class CanMask
    private val canMaskInstance = new CanMask {}

    private def run[S](using Frame)[A, S1](effect: A < (Masked[S] & S1))(using tag: Tag[Masked[S]]): A < (S & S1) =
        ArrowEffect.handle(tag, effect)(
            handle = [C] => (input, cont) => input.map(c => cont(c))
        )

    /** Use a [[Mask]] instance to protect one or more effects with effect type [[S]] from being handled within the scope of a provided
      * function by lifting them to [[Masked]][S]. To be used in functions that accept effects with generic effect types in order to avoid
      * accidentally handling them. The masked type [[Masked]][S] is handled to [[S]] at the end of the scope.
      *
      * @tparam S
      *   Effect type intersection of the effects to be masked within the scope of [[f]]
      * @param f
      *   Function that uses a [[Mask]][S] instance to protect one or more effects from being handled
      * @return
      *   An effect with the original masked effect(s) [[S]] along with any additional effects [[S1]] accumulated when using them
      */
    def use[S](using Tag[Masked[S]], Frame)[A, S1](f: CanMask ?=> Mask[S] => A < (Masked[S] & S1)): A < (S & S1) =
        given CanMask     = canMaskInstance
        val mask: Mask[S] = ()
        val result        = f(mask)
        Mask.run[S](result)
    end use
end Mask
