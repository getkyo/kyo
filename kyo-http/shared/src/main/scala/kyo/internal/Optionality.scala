package kyo.internal

import kyo.Maybe
import scala.util.NotGiven

sealed trait Optionality[A]:
    type Value
    def isOptional: Boolean

object Optionality:
    type Aux[A, U] = Optionality[A] { type Value = U }

    given forMaybe[A]: Aux[Maybe[A], A] =
        new Optionality[Maybe[A]]:
            type Value = A
            def isOptional = true

    given forRequired[A](using NotGiven[A <:< Maybe[Any]]): Aux[A, A] =
        new Optionality[A]:
            type Value = A
            def isOptional = false
end Optionality
