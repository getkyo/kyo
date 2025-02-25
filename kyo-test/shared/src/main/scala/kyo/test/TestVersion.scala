package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * `TestVersion` provides information about the Scala version tests are being
 * run on to enable platform specific test configuration.
 */
object TestVersion {

  /**
   * Returns whether the current Scala version is Scala 2.
   */
  val isScala2: Boolean = false

  /**
   * Returns whether the current Scala version is Scala 2.12.
   */
  val isScala212: Boolean = false

  /**
   * Returns whether the current Scala version is Scala 2.13.
   */
  val isScala213: Boolean = false

  /**
   * Returns whether the current Scala version is Scala 3.
   */
  val isScala3: Boolean = true
}
