package kyo.test

import kyo.Frame
import kyo.Maybe

/** Thrown when an assertion fails.
  *
  * Carries the power-assert diagram (a multi-line string showing the expression tree with intermediate values), the source frame, an
  * optional user-supplied message, and an optional root cause throwable. The diagram is prepended to the exception message so that stack
  * traces printed by test frameworks display it directly.
  *
  * @param diagram
  *   the rendered power-assert diagram
  * @param frame
  *   the source location of the failing assertion
  * @param msg
  *   an optional user-supplied failure message
  * @param cause
  *   an optional root cause; `Absent` when the failure is purely a value mismatch
  *
  * WARNING: This class intentionally extends RuntimeException (not KyoException). ConsoleReporter reads getMessage() verbatim to render the
  * power-assert diagram. KyoException's environment-aware getMessage() would prepend frame-rendering text, corrupting the multi-line
  * diagram layout.
  *
  * @see
  *   [[kyo.test.TestResult]] the leaf outcome enum; catching this exception produces the `Failed` case
  * @see
  *   [[kyo.test.internal.TestBase]] the assertion macros that throw this exception on mismatch
  * @see
  *   `kyo.test.runner.ConsoleReporter` which renders the diagram field as output
  */
final class AssertionFailed(
    val diagram: String,
    val frame: Frame,
    val msg: Maybe[String],
    val cause: Maybe[Throwable]
// Unsafe: null sentinel, RuntimeException ctor accepts null Throwable to signal absence
) extends RuntimeException(AssertionFailed.message(diagram), cause.getOrElse(null))
end AssertionFailed

object AssertionFailed:

    /** Upper bound (in characters) on the exception message. The message carries the failure diagram and is
      * what test frameworks serialize; the Scala Native test interface ships it back to the JVM via
      * `DataOutputStream.writeUTF`, which caps at 65535 bytes. ~16K characters stays under that even at the
      * ~3 bytes/char worst case of modified UTF-8, so an assertion (or snapshot) failure on a very large
      * value (a rendered SVG, a big collection) can never overflow and crash that RPC channel. Every
      * assertion failure flows through this constructor, so bounding here covers the power-assert, `==`
      * diff, and snapshot paths uniformly.
      */
    private val MaxMessageChars = 16384

    private def message(diagram: String): String =
        val bounded =
            if diagram.length <= MaxMessageChars then diagram
            else diagram.substring(0, MaxMessageChars) + s"...(${diagram.length} chars total, truncated)"
        s"\n$bounded"
    end message

    /** Construct an [[AssertionFailed]] from its components.
      *
      * Preferred over the primary constructor when callers hold the components individually; the `make` name signals that this is a
      * controlled-construction path, not a direct allocation.
      */
    def make(diagram: String, frame: Frame, msg: Maybe[String], cause: Maybe[Throwable]): AssertionFailed =
        new AssertionFailed(diagram, frame, msg, cause)
end AssertionFailed

/** Thrown when a test is explicitly cancelled via `cancel(reason)`.
  *
  * Cancellation is distinct from failure: it signals that the test could not run (e.g., a required environment was unavailable), not that
  * the system under test misbehaved.
  *
  * @param reason
  *   a human-readable description of why the test was cancelled
  * @param frame
  *   the source location of the `cancel` call
  * @see
  *   [[kyo.test.TestResult]] the leaf outcome enum; catching this exception produces the `Cancelled` case
  * @see
  *   [[kyo.test.internal.TestBase]] the DSL class whose `cancel` method throws this exception
  * @see
  *   [[kyo.test.AssertionFailed]] the analogous exception for assertion failures
  */
final class TestCancelled(val reason: String)(using Frame) extends kyo.KyoException(reason)

/** Wraps a non-`Throwable` value raised by an `Abort[Any]` leaf failure so the runner's `Abort[Throwable]` discharge
  * pipeline can carry and report it.
  *
  * The user-facing leaf baseline is `Abort[Any]` (a leaf may abort with any value, not only a `Throwable`). The
  * `Abort[Any] -> Abort[Throwable]` conversion performed once at body registration (see `kyo.test.internal.TestContext`)
  * passes a `Throwable` failure through unwrapped and wraps a non-`Throwable` failure in this type, mirroring
  * `kyo.KyoApp.FailureException`.
  *
  * @param value
  *   the non-`Throwable` value the leaf aborted with
  */
final class LeafAborted(val value: Any) extends RuntimeException(s"test aborted with a non-throwable value: $value")
