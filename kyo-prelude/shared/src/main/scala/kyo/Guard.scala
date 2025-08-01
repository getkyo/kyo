package kyo

import kyo.Guard.CanGuard
import kyo.kernel.ArrowEffect

/** Protect effect(s) [[S]] from being handled. To be used in functions that accept effects with generic effect types in order to avoid
  * accidentally handling them. Can only be used within [[Guard.use]] blocks.
  *
  * @tparam S
  *   The effect type that is guarded
  * @see
  *   [[Guard]]
  */
sealed trait Guarded[S] extends ArrowEffect[[A] =>> A < S, Id]

/** Utility for protecting any effect of type [[S]] from being handled
  *
  * @tparam S
  *   The effect type that can be guarded
  */
sealed class Guard[S](using tag: Tag[Guarded[S]]):

    /** Guard an effect from being handled, wrapping its effect type [[S]] with [[Guarded]]
      *
      * @param effect
      *   The effect to be guarded
      * @return
      *   A new effect with its effect intersection protected from handling
      */
    def apply[A](effect: A < S)(using CanGuard, Frame): A < Guarded[S] =
        ArrowEffect.suspend[A](tag, effect)
end Guard

object Guard:
    sealed abstract class CanGuard
    private val canGuardInstance = new CanGuard {}

    private def run[S](using Frame)[A, S1](effect: A < (Guarded[S] & S1))(using tag: Tag[Guarded[S]]): A < (S & S1) =
        ArrowEffect.handle(tag, effect)(
            handle = [C] => (input, cont) => input.map(c => cont(c))
        )

    /** Use a [[Guard]] instance to protect one or more effects with effect type [[S]] from being handled within the scope of a provided
      * function by lifting them to [[Guarded]][S]. To be used in functions that accept effects with generic effect types in order to avoid
      * accidentally handling them. The guarded type [[Guarded]][S] is handled to [[S]] at the end of the scope.
      *
      * Example:
      *
      * def genericFunction[S](effect: Int < S): Int < S = Guard.use: guard => Abort.fold(identity, _ => 0): // Converts failure to 0
      * guard(Kyo.zip(effect, effect)).map: case (i, j) => val result = i + j if result < 0 then Abort.fail("negative!") else i + j
      *
      * Abort.run[String](genericFunction(Abort.fail("failed!"))).eval // Result: Result.Failure("failed!") <-- failure is not converted to
      * 0
      *
      * @tparam S
      *   Effect type intersection of the effects to be guarded within the scope of [[f]]
      * @param f
      *   Function that uses a [[Guard]][S] instance to protect one or more effects from being handled
      * @return
      *   An effect with the original guarded effect(s) [[S]] along with any additional effects [[S1]] accumulated when using them
      */
    def use[S](using Tag[Guarded[S]], Frame)[A, S1](f: CanGuard ?=> Guard[S] => A < (Guarded[S] & S1)): A < (S & S1) =
        given CanGuard = canGuardInstance
        val guard      = Guard[S]
        val result     = f(guard)
        Guard.run[S](result)
    end use
end Guard
