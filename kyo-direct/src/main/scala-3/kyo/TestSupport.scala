package kyo

import scala.language.experimental.macros
import language.higherKinds
import kyo.ios._

import kyo.direct._

object TestSupport {
  transparent inline def runLiftTest[T, U](inline expected: T)(inline body: U) = {
    val actual: U = IOs.run(defer(body).asInstanceOf[U > IOs])
    if (expected != actual)
      throw new AssertionError("Expected " + expected + " but got " + actual)
  }
}
