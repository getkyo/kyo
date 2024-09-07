package kyo

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

class ConstructorsTest extends Test:

    "Kyo constructors" - {
        "debugln" - {
            "should print a message to the console" in {
                val effect = Kyo.debugln("Test message")
                val result = IO.run(effect).eval
                // Note: This test doesn't actually verify console output
                assert(result == ())
            }
        }

        "fail" - {
            "should create an effect that fails with Abort[E]" in {
                val effect = Kyo.fail("Error message")
                val result = Abort.run[String](effect).eval
                assert(result == Result.fail("Error message"))
            }
        }

        "fromEither" - {
            "should create an effect from Either[E, A]" in {
                val successEffect = Kyo.fromEither(Right(42))
                val failureEffect = Kyo.fromEither(Left("Error"))

                val successResult = Abort.run[String](successEffect).eval
                val failureResult = Abort.run[String](failureEffect).eval

                assert(successResult == Result.success(42))
                assert(failureResult == Result.fail("Error"))
            }
        }

        "fromOption" - {
            "should create an effect from Option[A]" in {
                val someEffect = Kyo.fromOption(Some(42))
                val noneEffect = Kyo.fromOption(None)

                val someResult = Abort.run[Maybe.Empty](someEffect).eval
                val noneResult = Abort.run[Maybe.Empty](noneEffect).eval

                assert(someResult == Result.success(42))
                assert(noneResult == Result.fail(Maybe.Empty))
            }
        }

        "fromMaybe" - {
            "should create an effect from Maybe[A]" in {
                val definedEffect = Kyo.fromMaybe(Maybe.Defined(42))
                val emptyEffect   = Kyo.fromMaybe(Maybe.Empty)

                val definedResult = Abort.run[Maybe.Empty](definedEffect).eval
                val emptyResult   = Abort.run[Maybe.Empty](emptyEffect).eval

                assert(definedResult == Result.success(42))
                assert(emptyResult == Result.fail(Maybe.Empty))
            }
        }

        "fromResult" - {
            "should create an effect from Result[E, A]" in {
                val successEffect = Kyo.fromResult(Result.success(42))
                val failureEffect = Kyo.fromResult(Result.fail("Error"))

                val successResult = Abort.run[String](successEffect).eval
                val failureResult = Abort.run[String](failureEffect).eval

                assert(successResult == Result.success(42))
                assert(failureResult == Result.fail("Error"))
            }
        }

        "fromTry" - {
            "should create an effect from Try[A]" in {
                val successEffect = Kyo.fromTry(Success(42))
                val failureEffect = Kyo.fromTry(Failure(new Exception("Error")))

                val successResult = Abort.run[Throwable](successEffect).eval
                val failureResult = Abort.run[Throwable](failureEffect).eval

                assert(successResult == Result.success(42))
                assert(failureResult.isInstanceOf[Result.Fail[?]])
            }
        }

        "logInfo" - {
            "should log an informational message" in {
                val effect = Kyo.logInfo("Info message")
                val result = IO.run(effect).eval
                assert(result == ())
            }
        }

        "logWarn" - {
            "should log a warning message" in {
                val effect = Kyo.logWarn("Warning message")
                val result = IO.run(effect).eval
                assert(result == ())
            }
        }

        "logDebug" - {
            "should log a debug message" in {
                val effect = Kyo.logDebug("Debug message")
                val result = IO.run(effect).eval
                assert(result == ())
            }
        }

        "logError" - {
            "should log an error message" in {
                val effect = Kyo.logError("Error message")
                val result = IO.run(effect).eval
                assert(result == ())
            }
        }

        "logTrace" - {
            "should log a trace message" in {
                val effect = Kyo.logTrace("Trace message")
                val result = IO.run(effect).eval
                assert(result == ())
            }
        }

        "suspend" - {
            "should suspend an effect using IO" in {
                var executed = false
                val effect = Kyo.suspend {
                    executed = true
                    42
                }
                assert(!executed)
                val result = IO.run(effect).eval
                assert(executed)
                assert(result == 42)
            }
        }

        "suspendAttempt" - {
            "should suspend an effect and handle exceptions" in {
                val successEffect = Kyo.suspendAttempt(42)
                val failureEffect = Kyo.suspendAttempt(throw new Exception("Error"))

                val successResult = IO.run(Abort.run[Throwable](successEffect)).eval
                val failureResult = IO.run(Abort.run[Throwable](failureEffect)).eval

                assert(successResult == Result.success(42))
                assert(failureResult.isInstanceOf[Result.Fail[?]])
            }
        }
    }

end ConstructorsTest
