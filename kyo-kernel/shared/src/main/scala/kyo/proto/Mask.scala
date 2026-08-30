package kyo.proto

import kyo.Frame
import kyo.Id
import kyo.Tag
import kyo.proto.kernel.ArrowEffect

/** Hides an effect's operations from the handlers between the mask and its [[Mask.run]] boundary.
  *
  * `Mask[E](v)` translates each operation of `E` in `v` into a `Mask[E]` operation carrying the original as an unevaluated payload. The
  * computation keeps evaluating in place, and every other effect in its row stays visible to local handlers; only `E`'s operations tunnel
  * out to the enclosing [[Mask.run]], where each payload re-raises `E` for the handlers outside that boundary and its answer flows back
  * into the masked computation.
  */
sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

object Mask:

    /** Masks the effect `E` in `v`, where `E` may be one effect or an intersection of several.
      *
      * Handlers for `E` between this call and [[run]] see none of `v`'s `E` operations; handlers for every other effect in the row are
      * unaffected. The effect to mask is named explicitly, `Mask[Ask](v)` or `Mask[Ask & Say](v)`: an intersection-tagged region answers
      * each member's operations, so one mask covers them all.
      *
      * The mask's own suspension is tagged at the named `E` on both ends, so [[run]] named the same way lands the tunnel by construction.
      * Each payload is re-raised at the caught operation's own tag, so an `Ask` operation re-emerges at [[run]] as an `Ask` operation and
      * the specific effect's handler outside answers it.
      */
    def apply[E](using
        Frame
    )[E2 >: E <: ArrowEffect[?, ?], A, S](v: A < (E2 & S))(
        using
        tag: Tag[E2],
        maskTag: Tag[Mask[E]]
    ): A < (Mask[E] & S) =
        ArrowEffect.handleContOperation(tag, v) {
            // the payload is the operation itself, carrying its own tag, and it conforms to the
            // mask's `A < E` input by row contravariance: `E2 >: E`, and the `E` row is honest
            // because a handler at `E` answers `E2`-tagged operations under the dispatch direction
            [X] => (operation, cont) => ArrowEffect.suspend[X](maskTag, operation).map(cont(_))
        }

    /** Unmasks: evaluates each masked operation at this boundary, re-exposing `S` to the handlers outside it. */
    def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
        ArrowEffect.handleCont(tag, v) {
            [C] => (input, cont) => input.map(cont(_))
        }
end Mask
