package kyo

import scala.util.Try

class AbortCombinatorTest extends Test:

    given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

    "abort" - {
        "construct" - {
            "should construct from Result" in {
                val result: Result[String, Int] = Result.fail("failure")
                val effect                      = Kyo.fromResult(result)
                assert(Abort.run[String](effect).eval == Result.fail("failure"))
                val result1: Result[String, Int] = Result.success(1)
                val effect1                      = Kyo.fromResult(result1)
                assert(Abort.run[String](effect1).eval == Result.success(1))
            }

            "should construct from fail" in {
                val effect = Kyo.fail("failure")
                assert(Abort.run[String](effect).eval == Result.fail("failure"))
            }

            "should construct from try" in {
                val effect = Kyo.fromTry(Try(throw Exception("failure")))
                assert(Abort.run[Throwable](effect).eval.failure.get.getMessage == "failure")
                val effect1 = Kyo.fromTry(Try(1))
                assert(Abort.run[Throwable](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from option" in {
                val effect = Kyo.fromOption(None)
                assert(Abort.run[Maybe.Empty](effect).eval.failure.get == Maybe.Empty)
                val effect1 = Kyo.fromOption(Some(1))
                assert(Abort.run[Maybe.Empty](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from maybe" in {
                val effect = Kyo.fromMaybe(Maybe.Empty)
                assert(Abort.run[Maybe.Empty](effect).eval.failure.get == Maybe.Empty)
                val effect1 = Kyo.fromMaybe(Maybe.Defined(1))
                assert(Abort.run[Maybe.Empty](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from a throwing block" in {
                val effect = Kyo.attempt(throw new Exception("failure"))
                assert(Abort.run[Throwable](effect).eval.failure.get.getMessage == "failure")
                val effect1 = Kyo.attempt(1)
                assert(Abort.run[Throwable](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from an IO" in {
                val effect = Kyo.attempt(IO(throw new Exception("failure")))
                assert(IO.run(
                    Abort.run[Throwable](effect)
                ).eval.failure.get.getMessage == "failure")
                val effect1 = Kyo.attempt(IO(1))
                assert(IO.run(
                    Abort.run[Throwable](effect1)
                ).eval.getOrElse(-1) == 1)
            }
        }

        "handle" - {
            "should handle" in {
                val effect1 = Abort.fail[String]("failure")
                assert(effect1.handleAbort.eval == Result.fail("failure"))

                val effect2 = Abort.get[Boolean](Right(1))
                assert(effect2.handleAbort.eval == Result.success(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail[String]("failure")
                val handled = effect1
                    .handleSomeAbort[String]()
                    .handleSomeAbort[Boolean]()
                    .handleSomeAbort[Int]()
                    .handleSomeAbort[Double]()
                assert(handled.eval == Result.success(Result.success(Result.success(Result.fail("failure")))))
            }

            "should handle all abort" in {
                val effect: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail[Int](1)
                val handled: Result[String | Boolean | Int | Double, Int] < Any =
                    effect.handleAbort
                assert(handled.eval == Result.fail(1))
            }
        }

        "convert" - {

            "should convert all abort to empty" in {
                val failure: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail("failure")
                val failureEmpty: Int < Abort[Maybe.Empty] = failure.abortToEmpty
                val handledFailureEmpty                    = Abort.run[Maybe.Empty](failureEmpty)
                assert(handledFailureEmpty.eval == Result.Fail(Maybe.Empty))
                val success: Int < Abort[String | Boolean | Int | Double] = 23
                val successEmpty: Int < Abort[Maybe.Empty]                = success.abortToEmpty
                val handledSuccessEmpty                                   = Abort.run[Any](successEmpty)
                assert(handledSuccessEmpty.eval == Result.Success(23))
            }

            "should convert some abort to empty" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val failureEmpty: Int < Abort[Maybe.Empty | Boolean | Double | Int] =
                    failure.someAbortToEmpty[String]()
                val handledFailureEmpty = Choice.run(failureEmpty)
                val handledFailureAbort = Abort.run[Any](handledFailureEmpty)
                assert(handledFailureAbort.eval == Result.fail(Maybe.Empty))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val successEmpty: Int < (Abort[Maybe.Empty | Boolean | Double | Int]) =
                    success.someAbortToEmpty[String]()
                val handledSuccessEmpty = Abort.run[Any](successEmpty)
                assert(handledSuccessEmpty.eval == Result.success(23))
            }

            "should convert all abort to choice" in {
                val failure: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail("failure")
                val failureChoice: Int < Choice = failure.abortToChoice
                val handledFailureChoice        = Choice.run(failureChoice)
                assert(handledFailureChoice.eval.isEmpty)
                val success: Int < Abort[String] = 23
                val successChoice: Int < Choice  = success.abortToChoice
                val handledSuccessChoice         = Choice.run(successChoice)
                assert(handledSuccessChoice.eval == Seq(23))
            }

            "should convert some abort to choice" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val failureChoice: Int < (Choice & Abort[Boolean | Double | Int]) =
                    failure.someAbortToChoice[String]()
                val handledFailureChoice = Choice.run(failureChoice)
                val handledFailureAbort  = Abort.run[Any](handledFailureChoice)
                assert(handledFailureAbort.eval == Result.success(Seq.empty))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val successChoice: Int < (Choice & Abort[Boolean | Double | Int]) =
                    success.someAbortToChoice[String]()
                val handledSuccessChoice = Choice.run(successChoice)
                val handledSuccessAbort  = Abort.run[Any](handledSuccessChoice)
                assert(handledSuccessAbort.eval == Result.success(Seq(23)))
            }
        }

        "catch" - {
            "should catch all abort" in {
                val failure: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail("failure")
                val handledFailure: Int < Any =
                    failure.catchAbort {
                        case "wrong"    => 99
                        case "failure"  => 100
                        case _: String  => 101
                        case _: Boolean => 102
                        case _: Int     => 103
                        case _: Double  => 104
                    }
                assert(handledFailure.eval == 100)
                val success: Int < Abort[String | Boolean | Int | Double] = 23
                val handledSuccess: Int < Any =
                    success.catchAbort {
                        case "wrong"    => 99
                        case "failure"  => 100
                        case _: String  => 101
                        case _: Boolean => 102
                        case _: Int     => 103
                        case _: Double  => 104
                    }
                assert(handledSuccess.eval == 23)
            }

            "should catch all abort with a partial function" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val caughtFailure: Int < Abort[String] =
                    failure.catchAbortPartial {
                        case "failure" => 100
                    }
                val handledFailure: Result[String, Int] < Any =
                    Abort.run[String](caughtFailure)
                assert(handledFailure.eval == Result.success(100))
                val success: Int < Abort[String] = 23
                val caughtSuccess: Int < Abort[String] =
                    success.catchAbortPartial {
                        case "failure" => 100
                    }
                val handledSuccess: Result[String, Int] < Any =
                    Abort.run(caughtSuccess)
                assert(handledSuccess.eval == Result.success(23))
            }

            "should catch some abort" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail[String | Boolean]("failure")
                val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
                    failure.catchSomeAbort[String]()(_ => 100)
                val handledFailure: Result[Any, Int] < Any =
                    Abort.run[Any](caughtFailure)
                assert(handledFailure.eval == Result.success(100))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
                    success.catchSomeAbort[String]() { _ => 100 }
                val handledSuccess: Result[Any, Int] < Any =
                    Abort.run[Any](caughtSuccess)
                assert(handledSuccess.eval == Result.success(23))
            }

            "should catch some abort with a partial function" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail[String | Boolean]("failure")
                val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
                    failure.catchSomeAbortPartial[String]() {
                        case "failure" => 100
                    }
                val handledFailure: Result[Any, Int] < Any =
                    Abort.run[Any](caughtFailure)
                assert(handledFailure.eval == Result.success(100))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
                    success.catchSomeAbortPartial[String]() {
                        case "failure" => 100
                    }
                val handledSuccess: Result[Any, Int] < Any =
                    Abort.run[Any](caughtSuccess)
                assert(handledSuccess.eval == Result.success(23))
            }
        }

        "swap" - {
            "should swap abort" in {
                val failure: Int < Abort[String]        = Abort.fail("failure")
                val swappedFailure: String < Abort[Int] = failure.swapAbort
                val handledFailure                      = Abort.run(swappedFailure)
                assert(handledFailure.eval == Result.success("failure"))
                val success: Int < Abort[String]        = 23
                val swappedSuccess: String < Abort[Int] = success.swapAbort
                val handledSuccess                      = Abort.run(swappedSuccess)
                assert(handledSuccess.eval == Result.fail(23))
            }

            "should swap some abort" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val swappedFailure: String < Abort[Int | Boolean | Double] =
                    failure.swapSomeAbort[String]()
                val handledFailure2 = Abort.run[Any](swappedFailure)
                assert(handledFailure2.eval == Result.success("failure"))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val swappedSuccess: String < Abort[Boolean | Double | Int] =
                    success.swapSomeAbort[String]()
                val handledSuccess  = Abort.run[Int](swappedSuccess)
                val handledSuccess2 = Abort.run[Any](handledSuccess)
                assert(handledSuccess2.eval == Result.success(Result.fail(23)))
            }
        }
    }
end AbortCombinatorTest
