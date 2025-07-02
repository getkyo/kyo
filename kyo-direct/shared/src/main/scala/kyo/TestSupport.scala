package kyo

import scala.language.experimental.macros

object TestSupport:
    transparent inline def runLiftTest[A, B](inline expected: A)(inline body: B): Unit =
        import AllowUnsafe.embrace.danger
        val actual: B = Sync.Unsafe.evalOrThrow(direct(body).asInstanceOf[B < Sync])
        if !expected.equals(actual) then
            throw new AssertionError("Expected " + expected + " but got " + actual)
    end runLiftTest
end TestSupport
