package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * `GenFailureDetails` keeps track of relevant information related to a failure
 * in a generative test.
 */
sealed abstract class GenFailureDetails {
  type Value

  val initialInput: Value
  val shrunkenInput: Value
  val iterations: Long
}

object GenFailureDetails {
  def apply[A](initialInput0: A, shrunkenInput0: A, iterations0: Long): GenFailureDetails =
    new GenFailureDetails {
      type Value = A

      val initialInput: Value  = initialInput0
      val shrunkenInput: Value = shrunkenInput0
      val iterations: Long     = iterations0
    }
}
