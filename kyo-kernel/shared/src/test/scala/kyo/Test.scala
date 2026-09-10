package kyo

import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.compiletime.testing.typeCheckErrors

/** ScalaTest base for the kernel's suites.
  *
  * The kernel sits under every other module, including the test framework: kyo-test runs its leaves as
  * fibers on the scheduler the kernel powers, and that scheduler's preemption writes into the stop channel
  * and safepoint state these suites assert on. A harness exemption cannot close that, because combinators
  * respawn nested fibers it does not reach. So the kernel tests on ScalaTest, on the sbt test threads, with
  * no scheduler underneath.
  */
abstract class Test extends AnyFreeSpec with NonImplicitAssertions:

    /** Asserts that `code` does not compile and that the compiler's message contains `expected`, which must be
      * nonempty: pinning the message fails the test when the error changes shape instead of accepting any rejection.
      */
    transparent inline def typeCheckFailure(inline code: String)(inline expected: String): Assertion =
        val errors = typeCheckErrors(code).iterator.map(_.message.replace("\r\n", "\n")).mkString("\n")
        if errors.isEmpty then fail("Code type-checked successfully, expected a failure")
        else if expected.nonEmpty && errors.contains(expected) then succeed
        else fail(s"expected: $expected\nactual: $errors")
    end typeCheckFailure
end Test
