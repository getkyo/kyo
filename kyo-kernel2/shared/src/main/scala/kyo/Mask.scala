package kyo

import kyo.kernel.ArrowEffect

sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

object Mask:
    def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
        ArrowEffect.handleCont(tag, v) {
            [C] => (input, cont) => input.map(cont(_))
        }

    def apply[S](using Frame)[A](v: A < S)(using tag: Tag[Mask[S]]): A < Mask[S] =
        ArrowEffect.suspend[A](tag, v)
end Mask
