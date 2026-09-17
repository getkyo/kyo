package kyo.test.runner

import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** `ConsoleReporter.autoDetect` on Scala.js, where `java.lang.System.getenv` always returns `null`.
  *
  * `autoDetect` is read once, when `ConsoleReporter` initializes, which happens before any test body runs, so the build puts `NO_COLOR=1` in
  * this module's JS and Wasm test process environment instead of a test writing it. A page has no process environment, so the leaf cancels
  * on the browser rows.
  */
class ConsoleReporterJsWasmTest extends AnyFunSuite with NonImplicitAssertions:

    test("NO_COLOR in the process environment turns colors off") {
        assume(Platform.isNodeLike, "reads NO_COLOR from Node's process.env, which a page has not")
        assert(!ConsoleReporter.autoDetect, "NO_COLOR=1 is in the test process environment, so colors should be off")
    }

end ConsoleReporterJsWasmTest
