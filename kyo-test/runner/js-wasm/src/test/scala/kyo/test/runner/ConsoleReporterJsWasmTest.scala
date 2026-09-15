package kyo.test.runner

import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** `ConsoleReporter.autoDetect` on Scala.js, where `java.lang.System.getenv` always returns `null`.
  *
  * `autoDetect` is read once, when `ConsoleReporter` initializes, which happens before any test body runs, so the build puts `NO_COLOR=1` in
  * this module's JS and Wasm test process environment instead of a test writing it.
  */
class ConsoleReporterJsWasmTest extends AnyFunSuite with NonImplicitAssertions:

    test("NO_COLOR in the process environment turns colors off") {
        assert(!ConsoleReporter.autoDetect, "NO_COLOR=1 is in the test process environment, so colors should be off")
    }

end ConsoleReporterJsWasmTest
