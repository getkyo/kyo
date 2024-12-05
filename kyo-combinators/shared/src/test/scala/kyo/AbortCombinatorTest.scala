package kyo

import scala.concurrent.Future
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
                assert(Abort.run[Absent](effect).eval.failure.get == Absent)
                val effect1 = Kyo.fromOption(Some(1))
                assert(Abort.run[Absent](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from maybe" in {
                val effect = Kyo.fromMaybe(Absent)
                assert(Abort.run[Absent](effect).eval.failure.get == Absent)
                val effect1 = Kyo.fromMaybe(Present(1))
                assert(Abort.run[Absent](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from a throwing block" in {
                val effect = Kyo.attempt(throw new Exception("failure"))
                assert(Abort.run[Throwable](effect).eval.failure.get.getMessage == "failure")
                val effect1 = Kyo.attempt(1)
                assert(Abort.run[Throwable](effect1).eval.getOrElse(-1) == 1)
            }

            "should construct from an IO" in {
                import AllowUnsafe.embrace.danger
                val effect = Kyo.attempt(IO(throw new Exception("failure")))
                assert(IO.Unsafe.run(
                    Abort.run[Throwable](effect)
                ).eval.failure.get.getMessage == "failure")
                val effect1 = Kyo.attempt(IO(1))
                assert(IO.Unsafe.run(
                    Abort.run[Throwable](effect1)
                ).eval.getOrElse(-1) == 1)
            }
        }

        "handle" - {
            "should handle" in {
                val effect1 = Abort.fail[String]("failure")
                assert(effect1.result.eval == Result.fail("failure"))

                val effect2 = Abort.get[Boolean](Right(1))
                assert(effect2.result.eval == Result.success(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail[String]("failure")
                val handled = effect1
                    .forAbort[String].result
                    .forAbort[Boolean].result
                    .forAbort[Int].result
                    .forAbort[Double].result
                assert(handled.eval == Result.success(Result.success(Result.success(Result.fail("failure")))))
            }

            "should handle abort" in {
                val effect: Int < Abort[String] =
                    Abort.fail[String]("failure")
                val handled: Result[String, Int] < Any =
                    effect.result
                assert(handled.eval == Result.fail("failure"))
            }

            "should handle union" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val handled: Result[String | Boolean | Double | Int, Int] < Any =
                    failure.result
                assert(handled.eval == Result.fail("failure"))
            }
        }

        "convert" - {

            "should convert all abort to empty" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val failureEmpty: Int < Abort[Absent] = failure.abortToEmpty
                val handledFailureEmpty               = Abort.run[Absent](failureEmpty)
                assert(handledFailureEmpty.eval == Result.Fail(Absent))
                val success: Int < Abort[String]      = 23
                val successEmpty: Int < Abort[Absent] = success.abortToEmpty
                val handledSuccessEmpty               = Abort.run[Any](successEmpty)
                assert(handledSuccessEmpty.eval == Result.Success(23))
            }

            "should convert all union abort to empty" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val failureEmpty: Int < Abort[Absent] =
                    failure.abortToEmpty
                val handledFailureAbort = Abort.run[Any](failureEmpty)
                assert(handledFailureAbort.eval == Result.fail(Absent))
            }

            "should convert all abort to choice" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val failureChoice: Int < Choice = failure.abortToChoice
                val handledFailureChoice        = Choice.run(failureChoice)
                assert(handledFailureChoice.eval.isEmpty)
                val success: Int < Abort[String] = 23
                val successChoice: Int < Choice  = success.abortToChoice
                val handledSuccessChoice         = Choice.run(successChoice)
                assert(handledSuccessChoice.eval == Seq(23))
            }

            "should convert all union abort to choice" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val failureChoice: Int < Choice = failure.abortToChoice
                val handledFailureChoice        = Choice.run(failureChoice)
                assert(handledFailureChoice.eval == Result.success(Seq.empty))
            }
        }

        "catch" - {
            "should catch all abort" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val handledFailure: Int < Any =
                    failure.catching {
                        case "wrong"   => 99
                        case "failure" => 100
                    }
                assert(handledFailure.eval == 100)
                val success: Int < Abort[String] = 23
                val handledSuccess: Int < Any =
                    success.catching {
                        case "wrong"   => 99
                        case "failure" => 100
                    }
                assert(handledSuccess.eval == 23)
            }

            "should catch all union abort" in {
                val failure: Int < Abort[String | Int | Boolean] =
                    Abort.fail("failure")
                val handledFailure: Int < Any =
                    failure.catching {
                        case _: String  => 1
                        case _: Int     => 2
                        case _: Boolean => 3
                    }
                assert(handledFailure.eval == 1)
            }

            "should catch all abort with a partial function" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val caughtFailure: Int < Abort[String] =
                    failure.catchingSome {
                        case "failure" => 100
                    }
                val handledFailure: Result[String, Int] < Any =
                    Abort.run[String](caughtFailure)
                assert(handledFailure.eval == Result.success(100))
                val success: Int < Abort[String] = 23
                val caughtSuccess: Int < Abort[String] =
                    success.catchingSome {
                        case "failure" => 100
                    }
                val handledSuccess: Result[String, Int] < Any =
                    Abort.run(caughtSuccess)
                assert(handledSuccess.eval == Result.success(23))
            }

            "should catch all union abort with a partial function" in {
                val failure: Int < Abort[String | Int | Boolean] =
                    Abort.fail("failure")
                val partialHandled: Int < Abort[String | Int | Boolean] =
                    failure.catchingSome {
                        case _: String => 1
                    }
                val handledFailure: Result[String | Int | Boolean, Int] < Any =
                    partialHandled.result
                assert(handledFailure.eval == Result.Success(1))
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

            "should swap union abort" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val swappedFailure: (String | Boolean | Double | Int) < Abort[Int] = failure.swapAbort
                val handledFailure                                                 = Abort.run(swappedFailure)
                assert(handledFailure.eval == Result.success("failure"))
            }
        }

        "forAbort" - {

            "handle" - {
                "should handle single Abort" in {
                    val effect: Int < Abort[String | Boolean | Int] = Abort.fail("error")
                    val handled                                     = effect.forAbort[String].result
                    assert(Abort.run[Any](handled).eval == Result.success(Result.fail("error")))

                    val effect2: Int < Abort[String | Boolean | Int] = Abort.fail(true)
                    val handled2                                     = effect2.forAbort[String].result
                    assert(Abort.run[Any](handled2).eval == Result.fail(true))
                }
            }

            "toChoice" - {
                "should convert some abort to choice" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val choiceEffect                          = effect.forAbort[String].toChoice
                    assert(Abort.run[Any](Choice.run(choiceEffect)).eval.value.get.isEmpty)

                    val effect2: Int < Abort[String | Boolean] = 42
                    val choiceEffect2                          = effect2.forAbort[String].toChoice
                    assert(Abort.run[Any](Choice.run(choiceEffect2)).eval == Seq(42))
                }
            }

            "toEmpty" - {
                "should convert some abort to empty" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val emptyEffect                           = effect.forAbort[String].toEmpty
                    assert(Abort.run[Any](emptyEffect).eval == Result.fail(Absent))

                    val effect2: Int < Abort[String | Boolean] = 42
                    val emptyEffect2                           = effect2.forAbort[String].toEmpty
                    assert(Abort.run[Any](emptyEffect2).eval == Result.success(42))
                }
            }

            "caught" - {
                "should catch some abort" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val caught                                = effect.forAbort[String].catching(_ => 99)
                    assert(Abort.run[Any](caught).eval == Result.success(99))

                    val effect2: Int < Abort[String | Boolean] = Abort.fail(true)
                    val caught2                                = effect2.forAbort[String].catching(_ => 99)
                    assert(Abort.run[Boolean](caught2).eval == Result.fail(true))

                    val effect3: Int < Abort[String | Boolean] = 42
                    val caught3                                = effect3.forAbort[String].catching(_ => 99)
                    assert(Abort.run[Any](caught3).eval == Result.success(42))
                }
            }

            "caughtPartial" - {
                "should catch some abort with partial function" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val caught = effect.forAbort[String].catchingSome {
                        case "error" => 99
                    }
                    assert(Abort.run[Any](caught).eval == Result.success(99))

                    val effect2: Int < Abort[String | Boolean] = Abort.fail("other")
                    val caught2 = effect2.forAbort[String].catchingSome {
                        case "error" => 99
                    }
                    assert(Abort.run[Any](caught2).eval == Result.fail("other"))

                    val effect3: Int < Abort[String | Boolean] = 42
                    val caught3 = effect3.forAbort[String].catchingSome {
                        case "error" => 99
                    }
                    assert(Abort.run[Any](caught3).eval == Result.success(42))
                }
            }

            "swap" - {
                "should swap some abort" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val swapped                               = effect.forAbort[String].swap
                    assert(Abort.run[Any](swapped).eval == Result.success("error"))

                    val effect2: Int < Abort[String | Boolean] = Abort.fail(true)
                    val swapped2                               = effect2.forAbort[String].swap
                    assert(Abort.run[Any](swapped2).eval.isFail)

                    val effect3: Int < Abort[String | Boolean] = 42
                    val swapped3                               = effect3.forAbort[String].swap
                    assert(Abort.run[Any](swapped3).eval == Result.fail(42))
                }
            }

            "should handle reduced union" in {
                val effect: Int < Abort[String | Boolean | Int]         = Abort.fail("error")
                val handled: Result[String | Int, Int] < Abort[Boolean] = effect.forAbort[String | Int].result
                assert(Abort.run[Any](handled).eval == Result.success(Result.fail("error")))

                val effect2: Int < Abort[String | Boolean | Int]         = Abort.fail(5)
                val handled2: Result[String | Int, Int] < Abort[Boolean] = effect2.forAbort[String | Int].result
                assert(Abort.run[Any](handled2).eval == Result.success(Result.fail(5)))

                val effect3: Int < Abort[String | Boolean | Int]         = Abort.fail(true)
                val handled3: Result[String | Int, Int] < Abort[Boolean] = effect3.forAbort[String | Int].result
                assert(Abort.run[Any](handled3).eval == Result.fail(true))
            }
        }

        "orPanic" - {
            "should remove Abort effects and run successfully if effect has no failures" in {
                val effect: Int < Abort[String] =
                    for
                        i      <- (1: Int < Any)
                        result <- if i > 1 then Abort.fail("error") else (i: Int < Any)
                    yield i

                val effectOrDie: Int < Any = effect.orPanic
                assert(effectOrDie.eval == 1)
            }

            "should remove throwable Abort and throw KyoBugException with error as cause" in {
                val exc = IndexOutOfBoundsException("test-error")
                val effect: Int < Abort[Throwable] =
                    Abort.fail(exc).map(_ => 23)

                try
                    effect.orPanic.eval
                    assert(???)
                catch
                    case bug =>
                        assert(bug.getMessage.contains(exc.toString))
                end try
            }

            "should remove non-throwable Abort and throw PanicException" in {
                val effect: Int < Abort[String] =
                    Abort.fail("error").map(_ => 23)

                try
                    effect.orPanic.eval
                    assert(???)
                catch
                    case bug =>
                        val panic = PanicException("error")
                        assert(bug.getMessage.contains(panic.toString))
                end try
            }
        }
    }

    "readme" in run {
        import scala.concurrent.duration.*
        import java.io.IOException

        trait HelloService:
            def sayHelloTo(saluee: String): Unit < (IO & Abort[Throwable])

        object HelloService:
            val live = Layer(Live)

            object Live extends HelloService:
                override def sayHelloTo(saluee: String): Unit < (IO & Abort[Throwable]) =
                    Kyo.suspendAttempt { // Adds IO & Abort[Throwable] effect
                        println(s"Hello $saluee!")
                    }
            end Live
        end HelloService

        val keepTicking: Nothing < (Async & Abort[IOException]) =
            (Console.print(".") *> Kyo.sleep(1.second)).forever

        val effect: Unit < (Async & Resource & Abort[Throwable] & Env[HelloService]) =
            for
                nameService <- Kyo.service[HelloService]      // Adds Env[NameService] effect
                _           <- keepTicking.forkScoped         // Adds Async, Abort[IOException], and Resource effects
                saluee      <- Console.readln
                _           <- Kyo.sleep(2.seconds)           // Uses Async (semantic blocking)
                _           <- nameService.sayHelloTo(saluee) // Lifts Abort[IOException] to Abort[Throwable]
            yield ()
            end for
        end effect

        // There are no combinators for handling IO or blocking Async, since this should
        // be done at the edge of the program
        Async.run {      // Handles Async
            Kyo.scoped { // Handles Resource
                Memo.run:
                    effect
                        .catching((thr: Throwable) => // Handles Abort[Throwable]
                            Kyo.logDebug(s"Failed printing to console: ${thr}")
                        )
                        .provide(HelloService.live) // Works like ZIO[R,E,A]#provide
                        .map(_ => assert(true))
            }
        }.map(_.toFuture)

    }

end AbortCombinatorTest
