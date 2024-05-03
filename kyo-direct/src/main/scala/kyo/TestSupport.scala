package kyo

import kyo.*
import language.higherKinds
import scala.language.experimental.macros

object TestSupport:
    transparent inline def runLiftTest[T: Flat, U](inline expected: T)(inline body: U) =
        val actual: U = Defers.run(defer(body).asInstanceOf[U < Defers]).pure
        if !expected.equals(actual) then
            throw new AssertionError("Expected " + expected + " but got " + actual)
    end runLiftTest
end TestSupport
