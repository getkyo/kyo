package kyo

import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, S, E](effect: A < (S & Env[E]))

    /** Handles the Env[E] efffect with the provided value.
      *
      * @param dependency
      *   The value to provide for the environment
      * @return
      *   A computation that produces the result of this computation with the Env[E] effect handled
      */
    def provideValue[E1 >: E, ER](dependency: E1)(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        Env.run[E1, A, S, ER](dependency)(effect.asInstanceOf[A < (S & Env[E1 | ER])])

    /** Handles the Env[E] effect with the provided layer.
      *
      * @param layer
      *   The layers to perform this computation with
      * @return
      *   A computation that produces the result of this computation
      */
    inline def provideLayer[S1, E1 >: E, ER](layer: Layer[E1, S1])(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & S1 & Memo & reduce.SReduced) =
        for
            tm <- layer.run
            e1 = tm.get[E1]
        yield effect.provideValue(e1)

    /** Handles the Env[E] effect with the provided layers via Env.runLayer.
      *
      * @param layers
      *   The layers to handle this computation with
      * @return
      *   A computation that produces the result of this computation
      */
    transparent inline def provide(inline layers: Layer[?, ?]*): A < Nothing =
        Env.runLayer(layers*)(effect)

end extension
