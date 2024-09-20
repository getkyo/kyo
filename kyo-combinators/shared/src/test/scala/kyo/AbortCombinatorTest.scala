package kyo

import scala.util.Try

class AbortCombinatorTest extends Test:

    given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

    "abort" - {
        "construct" - {
            "should construct from Result" in {
                val result: Result[String, Int] = Result.error("failure")
                val effect                      = Kyo.fromResult(result)
                assert(Abort.run[String](effect).eval == Result.error("failure"))
                val result1: Result[String, Int] = Result.succeed(1)
                val effect1                      = Kyo.fromResult(result1)
                assert(Abort.run[String](effect1).eval == Result.succeed(1))
            }

            "should construct from fail" in {
                val effect = Kyo.error("failure")
                assert(Abort.run[String](effect).eval == Result.error("failure"))
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
                val effect1 = Abort.error[String]("failure")
                assert(effect1.handleAbort.eval == Result.error("failure"))

                val effect2 = Abort.get[Boolean](Right(1))
                assert(effect2.handleAbort.eval == Result.succeed(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Abort[String | Boolean | Int | Double] =
                    Abort.error[String]("failure")
                val handled = effect1
                    .handleSomeAbort[String]()
                    .handleSomeAbort[Boolean]()
                    .handleSomeAbort[Int]()
                    .handleSomeAbort[Double]()
                assert(handled.eval == Result.succeed(Result.succeed(Result.succeed(Result.error("failure")))))
            }

            "should handle abort" in {
                val effect: Int < Abort[String] =
                    Abort.error[String]("failure")
                val handled: Result[String, Int] < Any =
                    effect.handleAbort
                assert(handled.eval == Result.error("failure"))
            }
        }

        "convert" - {

            "should convert all abort to empty" in {
                val failure: Int < Abort[String] =
                    Abort.error("failure")
                val failureEmpty: Int < Abort[Maybe.Empty] = failure.abortToEmpty
                val handledFailureEmpty                    = Abort.run[Maybe.Empty](failureEmpty)
                assert(handledFailureEmpty.eval == Result.Error(Maybe.Empty))
                val success: Int < Abort[String]           = 23
                val successEmpty: Int < Abort[Maybe.Empty] = success.abortToEmpty
                val handledSuccessEmpty                    = Abort.run[Any](successEmpty)
                assert(handledSuccessEmpty.eval == Result.Success(23))
            }

            "should convert some abort to empty" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.error("failure")
                val failureEmpty: Int < Abort[Maybe.Empty | Boolean | Double | Int] =
                    failure.someAbortToEmpty[String]()
                val handledFailureEmpty = Choice.run(failureEmpty)
                val handledFailureAbort = Abort.run[Any](handledFailureEmpty)
                assert(handledFailureAbort.eval == Result.error(Maybe.Empty))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val successEmpty: Int < (Abort[Maybe.Empty | Boolean | Double | Int]) =
                    success.someAbortToEmpty[String]()
                val handledSuccessEmpty = Abort.run[Any](successEmpty)
                assert(handledSuccessEmpty.eval == Result.succeed(23))
            }

            "should convert all abort to choice" in {
                val failure: Int < Abort[String] =
                    Abort.error("failure")
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
                    Abort.error("failure")
                val failureChoice: Int < (Choice & Abort[Boolean | Double | Int]) =
                    failure.someAbortToChoice[String]()
                val handledFailureChoice = Choice.run(failureChoice)
                val handledFailureAbort  = Abort.run[Any](handledFailureChoice)
                assert(handledFailureAbort.eval == Result.succeed(Seq.empty))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val successChoice: Int < (Choice & Abort[Boolean | Double | Int]) =
                    success.someAbortToChoice[String]()
                val handledSuccessChoice = Choice.run(successChoice)
                val handledSuccessAbort  = Abort.run[Any](handledSuccessChoice)
                assert(handledSuccessAbort.eval == Result.succeed(Seq(23)))
            }
        }

        "catch" - {
            "should catch abort" in {
                val failure: Int < Abort[String] =
                    Abort.error("failure")
                val handledFailure: Int < Any =
                    failure.catchAbort {
                        case "wrong"   => 99
                        case "failure" => 100
                    }
                assert(handledFailure.eval == 100)
                val success: Int < Abort[String] = 23
                val handledSuccess: Int < Any =
                    success.catchAbort {
                        case "wrong"   => 99
                        case "failure" => 100
                    }
                assert(handledSuccess.eval == 23)
            }

            "should catch all abort with a partial function" in {
                val failure: Int < Abort[String] =
                    Abort.error("failure")
                val caughtFailure: Int < Abort[String] =
                    failure.catchAbortPartial {
                        case "failure" => 100
                    }
                val handledFailure: Result[String, Int] < Any =
                    Abort.run[String](caughtFailure)
                assert(handledFailure.eval == Result.succeed(100))
                val success: Int < Abort[String] = 23
                val caughtSuccess: Int < Abort[String] =
                    success.catchAbortPartial {
                        case "failure" => 100
                    }
                val handledSuccess: Result[String, Int] < Any =
                    Abort.run(caughtSuccess)
                assert(handledSuccess.eval == Result.succeed(23))
            }

            "should catch some abort" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.error[String | Boolean]("failure")
                val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
                    failure.catchSomeAbort[String]()(_ => 100)
                val handledFailure: Result[Any, Int] < Any =
                    Abort.run[Any](caughtFailure)
                assert(handledFailure.eval == Result.succeed(100))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
                    success.catchSomeAbort[String]() { _ => 100 }
                val handledSuccess: Result[Any, Int] < Any =
                    Abort.run[Any](caughtSuccess)
                assert(handledSuccess.eval == Result.succeed(23))
            }

            "should catch some abort with a partial function" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.error[String | Boolean]("failure")
                val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
                    failure.catchSomeAbortPartial[String]() {
                        case "failure" => 100
                    }
                val handledFailure: Result[Any, Int] < Any =
                    Abort.run[Any](caughtFailure)
                assert(handledFailure.eval == Result.succeed(100))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
                    success.catchSomeAbortPartial[String]() {
                        case "failure" => 100
                    }
                val handledSuccess: Result[Any, Int] < Any =
                    Abort.run[Any](caughtSuccess)
                assert(handledSuccess.eval == Result.succeed(23))
            }
        }

        "swap" - {
            "should swap abort" in {
                val failure: Int < Abort[String]        = Abort.error("failure")
                val swappedFailure: String < Abort[Int] = failure.swapAbort
                val handledFailure                      = Abort.run(swappedFailure)
                assert(handledFailure.eval == Result.succeed("failure"))
                val success: Int < Abort[String]        = 23
                val swappedSuccess: String < Abort[Int] = success.swapAbort
                val handledSuccess                      = Abort.run(swappedSuccess)
                assert(handledSuccess.eval == Result.error(23))
            }

            "should swap some abort" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.error("failure")
                val swappedFailure: String < Abort[Int | Boolean | Double] =
                    failure.swapSomeAbort[String]()
                val handledFailure2 = Abort.run[Any](swappedFailure)
                assert(handledFailure2.eval == Result.succeed("failure"))
                val success: Int < Abort[String | Boolean | Double | Int] = 23
                val swappedSuccess: String < Abort[Boolean | Double | Int] =
                    success.swapSomeAbort[String]()
                val handledSuccess  = Abort.run[Int](swappedSuccess)
                val handledSuccess2 = Abort.run[Any](handledSuccess)
                assert(handledSuccess2.eval == Result.succeed(Result.error(23)))
            }
        }

        "handleSomeAbort" - {
            "should handle some abort" in {
                val effect: Int < Abort[String | Boolean | Int] = Abort.error("error")
                val handled                                     = effect.handleSomeAbort[String]()
                assert(Abort.run[Any](handled).eval == Result.succeed(Result.error("error")))

                val effect2: Int < Abort[String | Boolean | Int] = Abort.error(true)
                val handled2                                     = effect2.handleSomeAbort[String]()
                assert(Abort.run[Any](handled2).eval.isFail)
            }
        }

        "someAbortToChoice" - {
            "should convert some abort to choice" in {
                val effect: Int < Abort[String | Boolean] = Abort.error("error")
                val choiceEffect                          = effect.someAbortToChoice[String]()
                assert(Abort.run[Any](Choice.run(choiceEffect)).eval.value.get.isEmpty)

                val effect2: Int < Abort[String | Boolean] = 42
                val choiceEffect2                          = effect2.someAbortToChoice[String]()
                assert(Abort.run[Any](Choice.run(choiceEffect2)).eval == Seq(42))
            }
        }

        "someAbortToEmpty" - {
            "should convert some abort to empty" in {
                val effect: Int < Abort[String | Boolean] = Abort.error("error")
                val emptyEffect                           = effect.someAbortToEmpty[String]()
                assert(Abort.run[Any](emptyEffect).eval == Result.error(Maybe.Empty))

                val effect2: Int < Abort[String | Boolean] = 42
                val emptyEffect2                           = effect2.someAbortToEmpty[String]()
                assert(Abort.run[Any](emptyEffect2).eval == Result.succeed(42))
            }
        }

        "catchSomeAbort" - {
            "should catch some abort" in {
                val effect: Int < Abort[String | Boolean] = Abort.error("error")
                val caught                                = effect.catchSomeAbort[String]()(_ => 99)
                assert(Abort.run[Any](caught).eval == Result.succeed(99))

                val effect2: Int < Abort[String | Boolean] = Abort.error(true)
                val caught2                                = effect2.catchSomeAbort[String]()(_ => 99)
                assert(Abort.run[Boolean](caught2).eval == Result.error(true))

                val effect3: Int < Abort[String | Boolean] = 42
                val caught3                                = effect3.catchSomeAbort[String]()(_ => 99)
                assert(Abort.run[Any](caught3).eval == Result.succeed(42))
            }
        }

        "catchSomeAbortPartial" - {
            "should catch some abort with partial function" in {
                val effect: Int < Abort[String | Boolean] = Abort.error("error")
                val caught = effect.catchSomeAbortPartial[String]() {
                    case "error" => 99
                }
                assert(Abort.run[Any](caught).eval == Result.succeed(99))

                val effect2: Int < Abort[String | Boolean] = Abort.error("other")
                val caught2 = effect2.catchSomeAbortPartial[String]() {
                    case "error" => 99
                }
                assert(Abort.run[Any](caught2).eval == Result.error("other"))

                val effect3: Int < Abort[String | Boolean] = 42
                val caught3 = effect3.catchSomeAbortPartial[String]() {
                    case "error" => 99
                }
                assert(Abort.run[Any](caught3).eval == Result.succeed(42))
            }
        }

        "swapSomeAbort" - {
            "should swap some abort" in {
                val effect: Int < Abort[String | Boolean] = Abort.error("error")
                val swapped                               = effect.swapSomeAbort[String]()
                assert(Abort.run[Any](swapped).eval == Result.succeed("error"))

                val effect2: Int < Abort[String | Boolean] = Abort.error(true)
                val swapped2                               = effect2.swapSomeAbort[String]()
                assert(Abort.run[Any](swapped2).eval.isFail)

                val effect3: Int < Abort[String | Boolean] = 42
                val swapped3                               = effect3.swapSomeAbort[String]()
                assert(Abort.run[Any](swapped3).eval == Result.error(42))
            }
        }
    }

end AbortCombinatorTest
