package kyo.internal

import kyo.Maybe
import scala.util.NotGiven

sealed trait Optionality[A]:
    type Value
    def isOptional: Boolean
    def isOption: Boolean
end Optionality

object Optionality:
    type Aux[A, U] = Optionality[A] { type Value = U }

    given forMaybe[A]: Aux[Maybe[A], A] =
        new Optionality[Maybe[A]]:
            type Value = A
            def isOptional = true
            def isOption   = false

    given forOption[A]: Aux[Option[A], A] =
        new Optionality[Option[A]]:
            type Value = A
            def isOptional = true
            def isOption   = true

    given forRequired[A](using NotGiven[A <:< Maybe[Any]], NotGiven[A <:< Option[Any]]): Aux[A, A] =
        new Optionality[A]:
            type Value = A
            def isOptional = false
            def isOption   = false
end Optionality
