/*
 * Converted from zio-test's TestSuccess.scala to use the Kyo effect system.
 * This conversion defines a basic TestSuccess abstraction with a message and a combine helper method.
 */

package kyo.test

sealed trait TestSuccess:
    def message: String

    // Combines this TestSuccess with another one.
    // In a real scenario, this might implement logic to combine multiple successes,
    // but here we simply return this instance for demonstration purposes.
    def combine(that: TestSuccess): TestSuccess = this
end TestSuccess

object TestSuccess:
    case object Succeeded extends TestSuccess:
        val message: String = "Test succeeded."

    // Create a TestSuccess with a custom success message.
    def apply(msg: String): TestSuccess = new TestSuccess:
        val message: String = msg
end TestSuccess
