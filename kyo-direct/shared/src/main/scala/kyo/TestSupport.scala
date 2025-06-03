package kyo

import scala.language.experimental.macros

object TestSupport:
    transparent inline def runLiftTest[A, B](inline expected: A)(inline body: B): Unit =
        import AllowUnsafe.embrace.danger
        val actual: B = IO.Unsafe.evalOrThrow(defer(body).asInstanceOf[B < IO])
        if !expected.equals(actual) then
            throw new AssertionError("Expected " + expected + " but got " + actual)
    end runLiftTest
end TestSupport
