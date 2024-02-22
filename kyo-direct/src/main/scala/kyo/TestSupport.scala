package kyo

import scala.language.experimental.macros
import language.higherKinds

import kyo.direct.*

object TestSupport:
    transparent inline def runLiftTest[T, U](inline expected: T)(inline body: U)(implicit
        f: Flat[U < Any]
    ) =
        val actual: U = IOs.run(defer(body).asInstanceOf[U < IOs])
        if expected != actual then
            throw new AssertionError("Expected " + expected + " but got " + actual)
    end runLiftTest
end TestSupport
