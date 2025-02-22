package kyo.test

import kyo.*
import kyo.Abort
import kyo.Async
import kyo.Combinators.*
import kyo.Env
import kyo.IO

object TestConstructor:
    // Constructs a test case with a name and a test specification.
    // The spec is a computation returning a Boolean wrapped in the Kyo effect system.
    def test(name: String, spec: => Boolean < IO): TestCase < (Env[Any] & Abort[Throwable] & IO) =
        for
            result <- Kyo.attempt(spec)
            _      <- Kyo.debugln(s"Test '$name' executed with result: $result")
        yield TestCase(name, result)
end TestConstructor

case class TestCase(name: String, success: Boolean)
