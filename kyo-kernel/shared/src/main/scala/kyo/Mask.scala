package kyo

import kyo.kernel.ArrowEffect

/** Hides an effect's operations from the handlers between the mask and its [[Mask.run]] boundary.
  *
  * `Mask[E](v)` translates each operation of `E` in `v` into a `Mask[E]` operation carrying the original as an unevaluated payload. The
  * computation keeps evaluating in place, and every other effect in its row stays visible to local handlers; only `E`'s operations tunnel
  * out to the enclosing [[Mask.run]], where each payload re-raises `E` for the handlers outside that boundary and its answer flows back
  * into the masked computation.
  */
sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

object Mask:

    /** Masks the effect `E` in `v`.
      *
      * Handlers for `E` between this call and [[run]] see none of `v`'s `E` operations; handlers for every other effect in the row are
      * unaffected. The effect to mask is named explicitly: `Mask[Ask](v)`.
      */
    def apply[E](using
        Frame
    )[I[_], O[_], E2 >: E <: ArrowEffect[I, O], A, S](v: A < (E2 & S))(
        using
        tag: Tag[E2],
        maskTag: Tag[Mask[E2]]
    ): A < (Mask[E2] & S) =
        ArrowEffect.handleCont(tag, v) {
            [C] => (input, cont) => ArrowEffect.suspend[O[C]](maskTag, ArrowEffect.suspend[C](tag, input)).map(cont(_))
        }

    /** Unmasks: evaluates each masked operation at this boundary, re-exposing `S` to the handlers outside it. */
    def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
        ArrowEffect.handleCont(tag, v) {
            [C] => (input, cont) => input.map(cont(_))
        }
end Mask
