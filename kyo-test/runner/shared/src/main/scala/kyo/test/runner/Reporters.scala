package kyo.test.runner

import java.io.PrintStream
import kyo.test.TestReporter
import kyo.test.Verbosity

/** Factory namespace for constructing reporter instances.
  *
  * Prefer these factories over constructing reporters directly; they encapsulate the default parameters (e.g. auto-detecting color support
  * for the console reporter).
  *
  * On JVM, `Reporters.junitXml` is also available (defined in the JVM platform source set).
  *
  * @see
  *   [[kyo.test.TestReporter]] the reporter interface
  * @see
  *   [[kyo.test.runner.ConsoleReporter]] human-readable console output
  * @see
  *   [[kyo.test.runner.TapReporter]] TAP version 13 output
  * @see
  *   [[kyo.test.runner.CombinedReporter]] fan-out to multiple reporters
  */
object Reporters:
    def console(verbosity: Verbosity): ConsoleReporter =
        ConsoleReporter(verbosity, ConsoleReporter.autoDetect)
    def tap(out: PrintStream): TapReporter =
        TapReporter(out)
    def combined(reporters: TestReporter*): CombinedReporter =
        CombinedReporter(reporters*)
end Reporters
