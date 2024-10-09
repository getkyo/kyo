package kyo

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

class ConstructorsTest extends Test:

    "Kyo constructors" - {
        "debugln" - {
            "should print a message to the console" in run {
                val effect = Kyo.debugln("Test message")
                effect.map { result =>
                    // Note: This test doesn't actually verify console output
                    assert(result == ())
                }
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

                val someResult = Abort.run[Empty](someEffect).eval
                val noneResult = Abort.run[Empty](noneEffect).eval

                assert(someResult == Result.success(42))
                assert(noneResult == Result.fail(Empty))
            }
        }

        "fromMaybe" - {
            "should create an effect from Maybe[A]" in {
                val definedEffect = Kyo.fromMaybe(Defined(42))
                val emptyEffect   = Kyo.fromMaybe(Empty)

                val definedResult = Abort.run[Empty](definedEffect).eval
                val emptyResult   = Abort.run[Empty](emptyEffect).eval

                assert(definedResult == Result.success(42))
                assert(emptyResult == Result.fail(Empty))
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
            "should log an informational message" in run {
                val effect = Kyo.logInfo("Info message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logWarn" - {
            "should log a warning message" in run {
                val effect = Kyo.logWarn("Warning message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logDebug" - {
            "should log a debug message" in run {
                val effect = Kyo.logDebug("Debug message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logError" - {
            "should log an error message" in run {
                val effect = Kyo.logError("Error message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "logTrace" - {
            "should log a trace message" in run {
                val effect = Kyo.logTrace("Trace message")
                effect.map { result =>
                    assert(result == ())
                }
            }
        }

        "suspend" - {
            "should suspend an effect using IO" in run {
                var executed = false
                val effect = Kyo.suspend {
                    executed = true
                    42
                }
                assert(!executed)
                effect.map { result =>
                    assert(executed)
                    assert(result == 42)
                }
            }
        }

        "suspendAttempt" - {
            "should suspend an effect and handle exceptions" in {
                import AllowUnsafe.embrace.danger
                val successEffect = Kyo.suspendAttempt(42)
                val failureEffect = Kyo.suspendAttempt(throw new Exception("Error"))

                val successResult = IO.Unsafe.run(Abort.run[Throwable](successEffect)).eval
                val failureResult = IO.Unsafe.run(Abort.run[Throwable](failureEffect)).eval

                assert(successResult == Result.success(42))
                assert(failureResult.isInstanceOf[Result.Fail[?]])
            }
        }

        "foreachPar" - {
            "should apply a function to each element in parallel" in run {
                val input  = Seq(1, 2, 3, 4, 5)
                val result = Kyo.foreachPar(input)(x => Async.sleep(1.millis).andThen(x * 2))
                result.map { r =>
                    assert(r == Seq(2, 4, 6, 8, 10))
                }
            }
            "should support context effects" in run {
                val input  = Seq(1, 2, 3, 4, 5)
                val result = Kyo.foreachPar(input)(x => Env.use[Int](d => Async.sleep(d.millis)).andThen(x * 2))
                Env.run(1) {
                    result.map { r =>
                        assert(r == Seq(2, 4, 6, 8, 10))
                    }
                }
            }
        }

        "foreachParDiscard" - {
            "should apply a function to each element in parallel and discard the results" in run {
                val input = Seq(1, 2, 3, 4, 5)
                AtomicInt.init(0).map { counter =>
                    Kyo.foreachParDiscard(input) { x =>
                        Async.sleep(1.millis).andThen(counter.incrementAndGet)
                    }.map { _ =>
                        counter.get.map { count =>
                            assert(count == 5)
                        }
                    }
                }
            }
            "should support context effects" in run {
                val input = Seq(1, 2, 3, 4, 5)
                Env.run(1) {
                    AtomicInt.init(0).map { counter =>
                        Kyo.foreachParDiscard(input) { x =>
                            Env.use[Int](d => Async.sleep(d.millis)).andThen(counter.incrementAndGet)
                        }.map { _ =>
                            counter.get.map { count =>
                                assert(count == 5)
                            }
                        }
                    }
                }
            }
        }
    }

end ConstructorsTest
