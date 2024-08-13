// package kyo

// import kyo.*
// import scala.util.Try

// class AbortCombinatorTest extends Test:

//     given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

//     "aborts" - {
//         "construct" - {
//             "should construct from either" in {
//                 val either: Either[String, Int] = Left("failure")
//                 val effect                      = Kyo.fromEither(either)
//                 assert(Abort.run[String](effect).eval == Left("failure"))
//             }

//             "should construct from fail" in {
//                 val effect = Kyo.fail("failure")
//                 assert(Abort.run[String](effect).eval == Left("failure"))
//             }

//             // "should construct from try" in {
//             //     val effect = Kyo.fromTry(Try(throw Exception("failure")))
//             //     assert(Abort.run[Throwable](effect).eval.left.toOption.get.getMessage == "failure")
//             // }

//             // "should construct from a throwing block" in {
//             //     val effect = Kyo.attempt(throw new Exception("failure"))
//             //     assert(Abort.run[Throwable](effect).eval.left.toOption.get.getMessage == "failure")
//             // }

//             // "should construct from a failing IO" in {
//             //     val effect = Kyo.attempt(IO(throw new Exception("failure")))
//             //     assert(IO.run(
//             //         Abort.run[Throwable](effect)
//             //     ).eval.left.toOption.get.getMessage == "failure")
//             // }
//         }

//         "handle" - {
//             "should handle" in {
//                 val effect1 = Abort.fail[String]("failure")
//                 summon[scala.reflect.ClassTag[String]]
//                 assert(effect1.handleAbort.eval == Left("failure"))

//                 val effect2 = Abort.get[Boolean](Right(1))
//                 assert(effect2.handleAbort.eval == Right(1))
//             }

//             "should handle incrementally" in {
//                 val effect1: Int < Abort[String | Boolean | Int | Double] =
//                     Abort.fail[String]("failure")
//                 val handled = effect1
//                     .handleSomeAbort[String]
//                     .handleSomeAbort[Boolean]
//                     .handleSomeAbort[Int]
//                     .handleSomeAbort[Double]
//                 assert(handled.eval == Right(Right(Right(Left("failure")))))
//             }

//             "should handle all aborts" in {
//                 val effect: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean | Int | Double]("failure")
//                 val handled: Either[String | Boolean | Int | Double, Int] < Any =
//                     effect.handleAbort
//                 assert(handled.eval == Left("failure"))
//             }
//         }

//         "convert" - {
//             "should convert all aborts to options" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean | Int | Double]("failure")
//                 val failureOptions: Int < Options = failure.abortsToOptions
//                 val handledFailureOptions         = Options.run(failureOptions)
//                 assert(handledFailureOptions.eval == None)
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val successOptions: Int < Options                         = success.abortsToOptions
//                 val handledSuccessOptions                                 = Options.run(successOptions)
//                 assert(handledSuccessOptions.eval == Some(23))
//             }

//             "should convert some aborts to options" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean]("failure")
//                 val failureOptions: Int < (Options & Abort[Boolean | Double | Int]) =
//                     failure.someAbortToOptions[String]
//                 val handledFailureOptions = Options.run(failureOptions)
//                 val handledFailureAbort   = Abort.run[Boolean | Double | Int](handledFailureOptions)
//                 assert(handledFailureAbort.eval == Right(None))
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val successOptions: Int < (Options & Abort[Boolean | Double | Int]) =
//                     success.someAbortToOptions[String]
//                 val handledSuccessOptions = Options.run(successOptions)
//                 val handledSuccessAbort   = Abort.run[Boolean | Double | Int](handledSuccessOptions)
//                 assert(handledSuccessAbort.eval == Right(Some(23)))
//             }

//             "should convert all aborts to choices" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean | Int | Double]("failure")
//                 val failureSeqs: Int < Choice = failure.abortsToChoice
//                 val handledFailureSeqs        = Choice.run(failureSeqs)
//                 assert(handledFailureSeqs.eval.isEmpty)
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val successSeqs: Int < Choice                             = success.abortsToChoice
//                 val handledSuccessSeqs                                    = Choice.run(successSeqs)
//                 assert(handledSuccessSeqs.eval == Seq(23))
//             }

//             "should convert some aborts to choices" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail("failure")
//                 val failureSeqs: Int < (Choice & Abort[Boolean | Double | Int]) =
//                     failure.someAbortToChoice[String]
//                 val handledFailureSeqs  = Choice.run(failureSeqs)
//                 val handledFailureAbort = Abort.run[Boolean | Double | Int](handledFailureSeqs)
//                 assert(handledFailureAbort.eval == Right(Seq.empty))
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val successSeqs: Int < (Choice & Abort[Boolean | Double | Int]) =
//                     success.someAbortToChoice[String]
//                 val handledSuccessSeqs  = Choice.run(successSeqs)
//                 val handledSuccessAbort = Abort.run[Boolean | Double | Int](handledSuccessSeqs)
//                 assert(handledSuccessAbort.eval == Right(Seq(23)))
//             }
//         }

//         "catch" - {
//             "should catch all aborts" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean | Double | Int]("failure")
//                 val handledFailure: Int < Any =
//                     failure.catchAbort {
//                         case "failure" => 100
//                         case other     => 200
//                     }
//                 assert(handledFailure.eval == 100)
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val handledSuccess: Int < Any =
//                     success.catchAbort {
//                         case "failure" => 100
//                         case other     => 200
//                     }
//                 assert(handledSuccess.eval == 23)
//             }

//             "should catch all aborts with a partial function" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean | Double | Int]("failure")
//                 val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
//                     failure.catchAbortPartial {
//                         case "failure" => 100
//                     }
//                 val handledFailure: Either[String | Boolean | Double | Int, Int] < Any =
//                     Abort.run[String | Boolean | Double | Int](caughtFailure)
//                 assert(handledFailure.eval == Right(100))
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
//                     success.catchAbortPartial {
//                         case "failure" => 100
//                     }
//                 val handledSuccess: Either[String | Boolean | Double | Int, Int] < Any =
//                     Abort.run[String | Boolean | Double | Int](caughtSuccess)
//                 assert(handledSuccess.eval == Right(23))
//             }

//             "should catch some aborts" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean]("failure")
//                 val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
//                     failure.catchSomeAbort[String](_ => 100)
//                 val handledFailure: Either[String | Boolean | Double | Int, Int] < Any =
//                     Abort.run[String | Boolean | Double | Int](caughtFailure)
//                 assert(handledFailure.eval == Right(100))
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
//                     success.catchSomeAbort[String] { _ => 100 }
//                 val handledSuccess: Either[String | Boolean | Double | Int, Int] < Any =
//                     Abort.run[String | Boolean | Double | Int](caughtSuccess)
//                 assert(handledSuccess.eval == Right(23))
//             }

//             "should catch some aborts with a partial function" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail[String | Boolean]("failure")
//                 val caughtFailure: Int < Abort[String | Boolean | Double | Int] =
//                     failure.catchSomeAbortPartial[String | Double] {
//                         case "failure" => 100
//                     }
//                 val handledFailure: Either[String | Boolean | Double | Int, Int] < Any =
//                     Abort.run[String | Boolean | Double | Int](caughtFailure)
//                 assert(handledFailure.eval == Right(100))
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val caughtSuccess: Int < Abort[String | Boolean | Double | Int] =
//                     success.catchSomeAbortPartial[String | Boolean] {
//                         case "failure" => 100
//                     }
//                 val handledSuccess: Either[String | Boolean | Double | Int, Int] < Any =
//                     Abort.run[String | Boolean | Double | Int](caughtSuccess)
//                 assert(handledSuccess.eval == Right(23))
//             }
//         }

//         "swap" - {
//             "should swap aborts" in {
//                 val failure: Int < Abort[String]        = Abort.fail("failure")
//                 val swappedFailure: String < Abort[Int] = failure.swapAbort
//                 val handledFailure                      = Abort.run(swappedFailure)
//                 assert(handledFailure.eval == Right("failure"))
//                 val success: Int < Abort[String]        = 23
//                 val swappedSuccess: String < Abort[Int] = success.swapAbort
//                 val handledSuccess                      = Abort.run(swappedSuccess)
//                 assert(handledSuccess.eval == Left(23))
//             }

//             "should swap some aborts" in {
//                 val failure: Int < Abort[String | Boolean | Double | Int] =
//                     Abort.fail("failure")
//                 val swappedFailure: String < Abort[Int | Boolean | Double] =
//                     failure.swapSomeAbort[String]
//                 val handledFailure2 = Abort.run[Int | Boolean | Double](swappedFailure)
//                 assert(handledFailure2.eval == Right("failure"))
//                 val success: Int < Abort[String | Boolean | Double | Int] = 23
//                 val swappedSuccess: String < Abort[Boolean | Double | Int] =
//                     success.swapSomeAbort[String]
//                 val handledSuccess  = Abort.run[Int](swappedSuccess)
//                 val handledSuccess2 = Abort.run[Boolean | Double](handledSuccess)
//                 assert(handledSuccess2.eval == Right(Left(23)))
//             }
//         }
//     }

// end abortsTest
