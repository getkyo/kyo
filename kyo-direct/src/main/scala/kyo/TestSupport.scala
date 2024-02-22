package kyo

import kyo.direct.*
import language.higherKinds
import scala.language.experimental.macros

object TestSupport:
    transparent inline def runLiftTest[T, U](inline expected: T)(inline body: U)(implicit
        f: Flat[U < Any]
    ) =
        val actual: U = IOs.run(defer(body).asInstanceOf[U < IOs])
        if expected != actual then
            throw new AssertionError("Expected " + expected + " but got " + actual)
    end runLiftTest
end TestSupport
