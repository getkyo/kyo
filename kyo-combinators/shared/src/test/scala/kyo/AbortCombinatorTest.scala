package KyoTest

import kyo.*
import kyoTest.KyoTest
import scala.util.Try

class abortsTest extends KyoTest:

    given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

    "aborts" - {
        "construct" - {
            "should construct from either" in {
                val either: Either[String, Int] = Left("failure")
                val effect                      = Kyo.fromEither(either)
                assert(Aborts.run[String](effect).pure == Left("failure"))
            }

            "should construct from fail" in {
                val effect = Kyo.fail("failure")
                assert(Aborts.run[String](effect).pure == Left("failure"))
            }

            "should construct from try" in {
                val effect = Kyo.fromTry(Try(throw Exception("failure")))
                assert(Aborts.run[Throwable](effect).pure.left.toOption.get.getMessage == "failure")
            }

            "should construct from a throwing block" in {
                val effect = Kyo.attempt(throw new Exception("failure"))
                assert(Aborts.run[Throwable](effect).pure.left.toOption.get.getMessage == "failure")
            }

            "should construct from a failing IO" in {
                val effect = Kyo.attempt(IOs(throw new Exception("failure")))
                assert(IOs.run(
                    Aborts.run[Throwable](effect)
                ).pure.left.toOption.get.getMessage == "failure")
            }
        }

        "handle" - {
            "should handle" in {
                val effect1 = Aborts.fail[String]("failure")
                summon[scala.reflect.ClassTag[String]]
                assert(effect1.handleAborts.pure == Left("failure"))

                val effect2 = Aborts.get[Boolean, Int](Right(1))
                assert(effect2.handleAborts.pure == Right(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Aborts[String | Boolean | Int | Double] =
                    Aborts.fail[String]("failure")
                val handled = effect1
                    .handleSomeAborts[String]
                    .handleSomeAborts[Boolean]
                    .handleSomeAborts[Int]
                    .handleSomeAborts[Double]
                assert(handled.pure == Right(Right(Right(Left("failure")))))
            }

            "should handle all aborts" in {
                val effect: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean | Int | Double]("failure")
                val handled: Either[String | Boolean | Int | Double, Int] < Any =
                    effect.handleAborts
                assert(handled.pure == Left("failure"))
            }
        }

        "convert" - {
            "should convert all aborts to options" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean | Int | Double]("failure")
                val failureOptions: Int < Options = failure.abortsToOptions
                val handledFailureOptions         = Options.run(failureOptions)
                assert(handledFailureOptions.pure == None)
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val successOptions: Int < Options                          = success.abortsToOptions
                val handledSuccessOptions                                  = Options.run(successOptions)
                assert(handledSuccessOptions.pure == Some(23))
            }

            "should convert some aborts to options" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean]("failure")
                val failureOptions: Int < (Options & Aborts[Boolean | Double | Int]) =
                    failure.someAbortsToOptions[String]
                val handledFailureOptions = Options.run(failureOptions)
                val handledFailureAborts  = Aborts.run[Boolean | Double | Int](handledFailureOptions)
                assert(handledFailureAborts.pure == Right(None))
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val successOptions: Int < (Options & Aborts[Boolean | Double | Int]) =
                    success.someAbortsToOptions[String]
                val handledSuccessOptions = Options.run(successOptions)
                val handledSuccessAborts  = Aborts.run[Boolean | Double | Int](handledSuccessOptions)
                assert(handledSuccessAborts.pure == Right(Some(23)))
            }

            "should convert all aborts to choices" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean | Int | Double]("failure")
                val failureSeqs: Int < Choices = failure.abortsToChoices
                val handledFailureSeqs         = Choices.run(failureSeqs)
                assert(handledFailureSeqs.pure.isEmpty)
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val successSeqs: Int < Choices                             = success.abortsToChoices
                val handledSuccessSeqs                                     = Choices.run(successSeqs)
                assert(handledSuccessSeqs.pure == Seq(23))
            }

            "should convert some aborts to choices" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail("failure")
                val failureSeqs: Int < (Choices & Aborts[Boolean | Double | Int]) =
                    failure.someAbortsToChoices[String]
                val handledFailureSeqs   = Choices.run(failureSeqs)
                val handledFailureAborts = Aborts.run[Boolean | Double | Int](handledFailureSeqs)
                assert(handledFailureAborts.pure == Right(Seq.empty))
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val successSeqs: Int < (Choices & Aborts[Boolean | Double | Int]) =
                    success.someAbortsToChoices[String]
                val handledSuccessSeqs   = Choices.run(successSeqs)
                val handledSuccessAborts = Aborts.run[Boolean | Double | Int](handledSuccessSeqs)
                assert(handledSuccessAborts.pure == Right(Seq(23)))
            }
        }

        "catch" - {
            "should catch all aborts" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean | Double | Int]("failure")
                val handledFailure: Int < Any =
                    failure.catchAborts {
                        case "failure" => 100
                        case other     => 200
                    }
                assert(handledFailure.pure == 100)
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val handledSuccess: Int < Any =
                    success.catchAborts {
                        case "failure" => 100
                        case other     => 200
                    }
                assert(handledSuccess.pure == 23)
            }

            "should catch all aborts with a partial function" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean | Double | Int]("failure")
                val caughtFailure: Int < Aborts[String | Boolean | Double | Int] =
                    failure.catchAbortsPartial {
                        case "failure" => 100
                    }
                val handledFailure: Either[String | Boolean | Double | Int, Int] < Any =
                    Aborts.run[String | Boolean | Double | Int](caughtFailure)
                assert(handledFailure.pure == Right(100))
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Aborts[String | Boolean | Double | Int] =
                    success.catchAbortsPartial {
                        case "failure" => 100
                    }
                val handledSuccess: Either[String | Boolean | Double | Int, Int] < Any =
                    Aborts.run[String | Boolean | Double | Int](caughtSuccess)
                assert(handledSuccess.pure == Right(23))
            }

            "should catch some aborts" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean]("failure")
                val caughtFailure: Int < Aborts[String | Boolean | Double | Int] =
                    failure.catchSomeAborts[String](_ => 100)
                val handledFailure: Either[String | Boolean | Double | Int, Int] < Any =
                    Aborts.run[String | Boolean | Double | Int](caughtFailure)
                assert(handledFailure.pure == Right(100))
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Aborts[String | Boolean | Double | Int] =
                    success.catchSomeAborts[String] { _ => 100 }
                val handledSuccess: Either[String | Boolean | Double | Int, Int] < Any =
                    Aborts.run[String | Boolean | Double | Int](caughtSuccess)
                assert(handledSuccess.pure == Right(23))
            }

            "should catch some aborts with a partial function" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail[String | Boolean]("failure")
                val caughtFailure: Int < Aborts[String | Boolean | Double | Int] =
                    failure.catchSomeAbortsPartial[String | Double] {
                        case "failure" => 100
                    }
                val handledFailure: Either[String | Boolean | Double | Int, Int] < Any =
                    Aborts.run[String | Boolean | Double | Int](caughtFailure)
                assert(handledFailure.pure == Right(100))
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val caughtSuccess: Int < Aborts[String | Boolean | Double | Int] =
                    success.catchSomeAbortsPartial[String | Boolean] {
                        case "failure" => 100
                    }
                val handledSuccess: Either[String | Boolean | Double | Int, Int] < Any =
                    Aborts.run[String | Boolean | Double | Int](caughtSuccess)
                assert(handledSuccess.pure == Right(23))
            }
        }

        "swap" - {
            "should swap aborts" in {
                val failure: Int < Aborts[String]        = Aborts.fail("failure")
                val swappedFailure: String < Aborts[Int] = failure.swapAborts
                val handledFailure                       = Aborts.run(swappedFailure)
                assert(handledFailure.pure == Right("failure"))
                val success: Int < Aborts[String]        = 23
                val swappedSuccess: String < Aborts[Int] = success.swapAborts
                val handledSuccess                       = Aborts.run(swappedSuccess)
                assert(handledSuccess.pure == Left(23))
            }

            "should swap some aborts" in {
                val failure: Int < Aborts[String | Boolean | Double | Int] =
                    Aborts.fail("failure")
                val swappedFailure: String < Aborts[Int | Boolean | Double] =
                    failure.swapSomeAborts[String]
                val handledFailure2 = Aborts.run[Int | Boolean | Double](swappedFailure)
                assert(handledFailure2.pure == Right("failure"))
                val success: Int < Aborts[String | Boolean | Double | Int] = 23
                val swappedSuccess: String < Aborts[Boolean | Double | Int] =
                    success.swapSomeAborts[String]
                val handledSuccess  = Aborts.run[Int](swappedSuccess)
                val handledSuccess2 = Aborts.run[Boolean | Double](handledSuccess)
                assert(handledSuccess2.pure == Right(Left(23)))
            }
        }
    }

end abortsTest
