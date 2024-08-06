package kyo

import language.higherKinds
import scala.language.experimental.macros

object TestSupport:
    transparent inline def runLiftTest[T, U](inline expected: T)(inline body: U) =
        val actual: U = IO.run(defer(body).asInstanceOf[U < IO]).eval
        if !expected.equals(actual) then
            throw new AssertionError("Expected " + expected + " but got " + actual)
    end runLiftTest
end TestSupport
