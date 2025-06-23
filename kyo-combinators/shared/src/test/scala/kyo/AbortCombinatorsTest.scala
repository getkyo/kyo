package kyo

import scala.util.Try

class AbortCombinatorsTest extends Test:

    given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

    "abort" - {
        "construct" - {
            "should construct from Result" in {
                val result: Result[String, Int] = Result.fail("failure")
                val effect                      = Kyo.fromResult(result)
                assert(Abort.run[String](effect).eval == Result.fail("failure"))
                val result1: Result[String, Int] = Result.succeed(1)
                val effect1                      = Kyo.fromResult(result1)
                assert(Abort.run[String](effect1).eval == Result.succeed(1))
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

            "should construct from an Sync" in {
                import AllowUnsafe.embrace.danger
                val effect = Kyo.attempt(Sync(throw new Exception("failure")))
                assert(Sync.Unsafe.evalOrThrow(
                    Abort.run[Throwable](effect)
                ).failure.get.getMessage == "failure")
                val effect1 = Kyo.attempt(Sync(1))
                assert(Sync.Unsafe.evalOrThrow(
                    Abort.run[Throwable](effect1)
                ).getOrElse(-1) == 1)
            }
        }

        "result" - {
            "should handle" in {
                val effect1 = Abort.fail[String]("failure")
                assert(effect1.result.eval == Result.fail("failure"))

                val effect2 = Abort.get[Boolean](Right(1))
                assert(effect2.result.eval == Result.succeed(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail[String]("failure")
                val handled = effect1
                    .forAbort[String].result
                    .forAbort[Boolean].result
                    .forAbort[Int].result
                    .forAbort[Double].result
                assert(handled.eval == Result.succeed(Result.succeed(Result.succeed(Result.fail("failure")))))
            }

            "should handle union" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val handled: Result[String | Boolean | Double | Int, Int] < Any =
                    failure.result
                assert(handled.eval == Result.fail("failure"))
            }
        }

        "orPanic" - {
            "should not affect success" in {
                val effect: Int < Abort[String] = 23
                assert(Abort.run(effect.orPanic).eval == Result.Success(23))
            }

            "should not affect panic" in {
                val exc                         = new RuntimeException("message")
                val effect: Int < Abort[String] = Abort.panic(exc)
                assert:
                    Abort.run(effect.orPanic).eval == Result.Panic(exc)
            }

            "should convert Failure to PanicException and panic" in {
                val error                       = "error message"
                val effect: Int < Abort[String] = Abort.fail(error)
                assert:
                    Abort.run(effect.orPanic).eval == Result.Panic(PanicException(error))
            }
        }

        "orThrow" - {
            "should not affect success" in {
                val effect: Int < Abort[String] = 23
                assert(effect.orThrow.eval == 23)
            }

            "should throw panic" in {
                val exc                         = new RuntimeException("message")
                val effect: Int < Abort[String] = Abort.panic(exc)
                try
                    val _ = effect.orThrow.eval
                    fail("Failed to throw expected exception")
                catch
                    case e: RuntimeException => assert(e == exc)
                    case other               => fail(s"Failed to throw RuntimeException: $other")
                end try
            }

            "should convert Failure to PanicException and throw" in {
                val error                       = "error message"
                val effect: Int < Abort[String] = Abort.fail(error)
                try
                    val _ = effect.orThrow.eval
                    fail("Failed to throw expected exception")
                catch
                    case PanicException(err: String) => assert(err == error)
                    case other                       => fail(s"Failed to throw PanicException: $other")
                end try
            }
        }

        "resultPartial" - {
            "should handle" in {
                val effect1 = Abort.fail[String]("failure")
                assert(effect1.resultPartial.orThrow.eval == Result.fail("failure"))

                val effect2 = Abort.get[Boolean](Right(1))
                assert(effect2.resultPartial.orThrow.eval == Result.succeed(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail[String]("failure")
                val handled = effect1
                    .forAbort[String].resultPartial
                    .forAbort[Boolean].resultPartial
                    .forAbort[Int].resultPartial
                    .resultPartial
                assert(handled.orThrow.eval == Result.succeed(Result.succeed(Result.succeed(Result.fail("failure")))))
            }

            "should handle union" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val handled: Result.Partial[String | Boolean | Double | Int, Int] < Abort[Nothing] =
                    failure.resultPartial
                assert(handled.orThrow.eval == Result.fail("failure"))
            }

            "should not handle panic" in {
                val exc                          = new Exception("message")
                val failure: Int < Abort[String] = Abort.panic(exc)
                assert:
                    Abort.run(failure.resultPartial).eval == Result.Panic(exc)
            }
        }

        "resultPartialOrThrow" - {
            "should handle" in {
                val effect1 = Abort.fail[String]("failure")
                assert(effect1.resultPartialOrThrow.eval == Result.fail("failure"))

                val effect2 = Abort.get[Boolean](Right(1))
                assert(effect2.resultPartialOrThrow.eval == Result.succeed(1))
            }

            "should handle incrementally" in {
                val effect1: Int < Abort[String | Boolean | Int | Double] =
                    Abort.fail[String]("failure")
                val handled = effect1
                    .forAbort[String].resultPartial
                    .forAbort[Boolean].resultPartial
                    .forAbort[Int].resultPartial
                    .resultPartialOrThrow
                assert(handled.eval == Result.succeed(Result.succeed(Result.succeed(Result.fail("failure")))))
            }

            "should handle union" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val handled: Result.Partial[String | Boolean | Double | Int, Int] < Any =
                    failure.resultPartialOrThrow
                assert(handled.eval == Result.fail("failure"))
            }

            "should not handle panic" in {
                val exc                          = new Exception("message")
                val failure: Int < Abort[String] = Abort.panic(exc)
                try
                    val _ = failure.resultPartialOrThrow.eval
                    fail("Failed to throw expected exception")
                catch
                    case caught => assert(caught == exc)
                end try
            }
        }

        "mapAbort" - {
            "should map abort" in {
                val effect1       = Abort.fail[String]("failure")
                val effect1Mapped = effect1.mapAbort(_.size)
                assert(effect1Mapped.result.eval == Result.fail(7))
            }

            "should map union abort" in {
                val effect1 = Abort.fail[String | Int | Boolean]("failure")
                val effect1Mapped = effect1.mapAbort:
                    case str: String => 0
                    case _           => -1
                assert(effect1Mapped.result.eval == Result.fail(0))
            }
        }

        "convert" - {

            "should convert all abort to absent" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val failureEmpty: Int < Abort[Absent] = failure.abortToAbsent
                val handledFailureEmpty               = Abort.run[Absent](failureEmpty)
                assert(handledFailureEmpty.eval == Result.Failure(Absent))
                val success: Int < Abort[String]      = 23
                val successEmpty: Int < Abort[Absent] = success.abortToAbsent
                val handledSuccessEmpty               = Abort.run[Any](successEmpty)
                assert(handledSuccessEmpty.eval == Result.Success(23))
            }

            "should convert all union abort to absent" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val failureEmpty: Int < Abort[Absent] =
                    failure.abortToAbsent
                val handledFailureAbort = Abort.run[Any](failureEmpty)
                assert(handledFailureAbort.eval == Result.fail(Absent))
            }

            "should convert all abort to dropped choice" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val failureChoice: Int < Choice = failure.abortToChoiceDrop
                val handledFailureChoice        = Choice.run(failureChoice)
                assert(handledFailureChoice.eval.isEmpty)
                val success: Int < Abort[String] = 23
                val successChoice: Int < Choice  = success.abortToChoiceDrop
                val handledSuccessChoice         = Choice.run(successChoice)
                assert(handledSuccessChoice.eval == Seq(23))
            }

            "should convert all union abort to dropped choice" in {
                val failure: Int < Abort[String | Boolean | Double | Int] =
                    Abort.fail("failure")
                val failureChoice: Int < Choice = failure.abortToChoiceDrop
                val handledFailureChoice        = Choice.run(failureChoice)
                assert(handledFailureChoice.eval == Result.succeed(Seq.empty))
            }

            "should convert all abort to throwable abort" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val failureThrowable: Int < Abort[Throwable] = failure.abortToThrowable
                val handledFailureThrowable                  = Abort.run(failureThrowable)
                assert(handledFailureThrowable.eval == Result.fail(PanicException("failure")))
                val success: Int < Abort[String]             = 23
                val successThrowable: Int < Abort[Throwable] = success.abortToThrowable
                val handledSuccessThrowable                  = Abort.run(successThrowable)
                assert(handledSuccessThrowable.eval == 23)
            }

            "should convert all union abort to throwable abort" in {
                val failure: Int < Abort[String | Boolean | Double | Throwable] =
                    Abort.fail("failure")
                val failureThrowable: Int < Abort[Throwable] = failure.abortToThrowable
                val handledFailureThrowable                  = Abort.run(failureThrowable)
                assert(handledFailureThrowable.eval == Result.fail(PanicException("failure")))
            }

            "should not be callable on throwable abort" in {
                val failure: Int < Abort[IllegalArgumentException] = 23
                typeCheckFailure("failure.abortToThrowable")(
                    "value abortToThrowable is not a member of Int < kyo.Abort[IllegalArgumentException]"
                )
            }

            "should convert empty choice to absent abort" in {
                val failure: Int < Choice                     = Choice.eval()
                val converted: Int < (Choice & Abort[Absent]) = failure.choiceDropToAbsent
                val handled                                   = Abort.run(Choice.run(converted))
                assert(handled.eval == Result.fail(Absent))

                val success: Int < Choice                      = Choice.eval(23)
                val converted2: Int < (Choice & Abort[Absent]) = success.choiceDropToAbsent
                val handled2                                   = Abort.run(Choice.run(converted2))
                assert(handled2.eval == Result.succeed(Chunk(23)))
            }
        }

        "recover" - {
            "should catch all abort" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val handledFailure: Int < Any =
                    failure.recover {
                        case "wrong"   => 99
                        case "failure" => 100
                    }.orThrow
                assert(handledFailure.eval == 100)
                val success: Int < Abort[String] = 23
                val handledSuccess: Int < Any =
                    success.recover {
                        case "wrong"   => 99
                        case "failure" => 100
                    }.orThrow
                assert(handledSuccess.eval == 23)
            }

            "should catch all union abort" in {
                val failure: Int < Abort[String | Int | Boolean] =
                    Abort.fail("failure")
                val handledFailure: Int < Any =
                    failure.recover {
                        case _: String  => 1
                        case _: Int     => 2
                        case _: Boolean => 3
                    }.orThrow
                assert(handledFailure.eval == 1)
            }

            "should catch all abort with a partial function" in {
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val caughtFailure: Int < Abort[String] =
                    failure.recoverSome {
                        case "failure" => 100
                    }
                val handledFailure: Result[String, Int] < Any =
                    Abort.run[String](caughtFailure)
                assert(handledFailure.eval == Result.succeed(100))
                val success: Int < Abort[String] = 23
                val caughtSuccess: Int < Abort[String] =
                    success.recoverSome {
                        case "failure" => 100
                    }
                val handledSuccess: Result[String, Int] < Any =
                    Abort.run(caughtSuccess)
                assert(handledSuccess.eval == Result.succeed(23))
            }

            "should catch all union abort with a partial function" in {
                val failure: Int < Abort[String | Int | Boolean] =
                    Abort.fail("failure")
                val partialHandled: Int < Abort[String | Int | Boolean] =
                    failure.recoverSome {
                        case _: String => 1
                    }
                val handledFailure: Result[String | Int | Boolean, Int] < Any =
                    partialHandled.result
                assert(handledFailure.eval == Result.Success(1))
            }
        }

        "foldAbort" - {
            "should handle success and fail case, leaving panics unhandled, when two handlers provided" in {
                val success: Int < Abort[String] = 23
                val handledSuccess: String < Abort[Nothing] =
                    success.foldAbort(
                        i => i.toString,
                        identity
                    )
                assert(handledSuccess.orThrow.eval == "23")
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val handledFailure: String < Abort[Nothing] =
                    failure.foldAbort(
                        i => i.toString,
                        identity
                    )
                assert(handledFailure.orThrow.eval == "failure")
                val exc                        = Exception("message")
                val panic: Int < Abort[String] = Abort.panic(exc)
                val handledPanic: String < Abort[Nothing] = panic.foldAbort(
                    i => i.toString,
                    identity
                )
                assert:
                    Abort.run(handledPanic).eval == Result.Panic(exc)
            }

            "should handle all cases when three handlers provided" in {
                val success: Int < Abort[String] = 23
                val handledSuccess: String < Any =
                    success.foldAbort(
                        i => i.toString,
                        identity,
                        _.getMessage
                    )
                assert(handledSuccess.eval == "23")
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val handledFailure: String < Any =
                    failure.foldAbort(
                        i => i.toString,
                        identity,
                        _.getMessage
                    )
                assert(handledFailure.eval == "failure")
                val panic: Int < Abort[String] = Abort.panic(Exception("message"))
                val handledPanic: String < Any =
                    panic.foldAbort(
                        i => i.toString,
                        identity,
                        _.getMessage
                    )
                assert(handledPanic.eval == "message")
            }
        }

        "foldAbortOrThrow" - {
            "should handle success and fail case, throwing panics" in {
                val success: Int < Abort[String] = 23
                val handledSuccess: String < Any =
                    success.foldAbortOrThrow(
                        i => i.toString,
                        identity
                    )
                assert(handledSuccess.eval == "23")
                val failure: Int < Abort[String] =
                    Abort.fail("failure")
                val handledFailure: String < Any =
                    failure.foldAbortOrThrow(
                        i => i.toString,
                        identity
                    )
                assert(handledFailure.eval == "failure")
                val exc                        = Exception("message")
                val panic: Int < Abort[String] = Abort.panic(exc)
                try
                    val _ = panic.foldAbortOrThrow(
                        i => i.toString,
                        identity
                    ).eval
                    fail("Failed to throw expected exception")
                catch
                    case caught => assert(caught == exc)
                end try
            }
        }

        "swap" - {
            "should swap abort" in {
                val failure: Int < Abort[String]        = Abort.fail("failure")
                val swappedFailure: String < Abort[Int] = failure.swapAbort
                val handledFailure                      = Abort.run(swappedFailure)
                assert(handledFailure.eval == Result.succeed("failure"))
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
                assert(handledFailure.eval == Result.succeed("failure"))
            }
        }

        "retry" - {
            "retry n times" - {
                "succeeding" in run {
                    val effect =
                        for
                            _ <- Var.update[Int](_ + 1)
                            i <- Var.get[Int]
                        yield i

                    Var.run(0) {
                        effect.retry(5).map: i =>
                            Var.get[Int].map: i2 =>
                                assert(i == 1 && i2 == 1)
                    }
                }

                "failing" in run {
                    val effect =
                        for
                            _ <- Var.update[Int](_ + 1)
                            i <- Var.get[Int]
                            _ <- Abort.fail(i)
                        yield ()

                    Var.run(0) {
                        Abort.run(effect.retry(5)).map: result =>
                            Var.get[Int].map: i =>
                                assert(result == Result.Failure(6) && i == 6)
                    }
                }

                "failing then succeeeding" in run {
                    val effect =
                        for
                            _ <- Var.update[Int](_ + 1)
                            i <- Var.get[Int]
                            _ <- if i < 5 then Abort.fail("fail") else Kyo.unit
                        yield i

                    Var.run(0) {
                        effect.retry(5).map: i =>
                            Var.get[Int].map: i2 =>
                                assert(i == 5 && i2 == 5)
                    }
                }
            }
        }

        "forAbort" - {

            "handle" - {
                "should handle single Abort" in {
                    val effect: Int < Abort[String | Boolean | Int] = Abort.fail("error")
                    val handled                                     = effect.forAbort[String].result
                    assert(Abort.run[Any](handled).eval == Result.succeed(Result.fail("error")))

                    val effect2: Int < Abort[String | Boolean | Int] = Abort.fail(true)
                    val handled2                                     = effect2.forAbort[String].result
                    assert(Abort.run[Any](handled2).eval == Result.fail(true))
                }
            }

            "mapAbort" - {
                "should map single Abort" in {
                    val effect1       = Abort.fail[String | Int | Boolean]("failure")
                    val effect1Mapped = effect1.forAbort[String].mapAbort(_.size)
                    assert(effect1Mapped.result.eval == Result.fail(7))

                    val effect2       = Abort.fail[String | Int | Boolean](1)
                    val effect2Mapped = effect2.forAbort[String].mapAbort(_.size)
                    assert(effect2Mapped.result.eval == Result.fail(1))
                }
            }

            "toChoiceDrop" - {
                "should convert some abort to choice" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val choiceEffect                          = effect.forAbort[String].toChoiceDrop
                    assert(Abort.run[Any](Choice.run(choiceEffect)).eval.value.get.isEmpty)

                    val effect2: Int < Abort[String | Boolean] = 42
                    val choiceEffect2                          = effect2.forAbort[String].toChoiceDrop
                    assert(Abort.run[Any](Choice.run(choiceEffect2)).eval == Seq(42))
                }
            }

            "toAbsent" - {
                "should convert some abort to empty" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val emptyEffect                           = effect.forAbort[String].toAbsent
                    assert(Abort.run[Any](emptyEffect).eval == Result.fail(Absent))

                    val effect2: Int < Abort[String | Boolean] = 42
                    val emptyEffect2                           = effect2.forAbort[String].toAbsent
                    assert(Abort.run[Any](emptyEffect2).eval == Result.succeed(42))
                }
            }

            "toThrowable" - {
                "should convert abort to throwable abort, wrapping non-throwable failures in PanicExceptions" in {
                    val effect: Int < Abort[Throwable | String | Boolean] = Abort.fail(new IllegalStateException("error"))
                    val asThrow: Int < Abort[Throwable | Boolean]         = effect.forAbort[Throwable | String].toThrowable
                    Abort.run[Any](asThrow).eval match
                        case Result.Error(exc: IllegalStateException) => assert(exc.getMessage() == "error")
                        case other                                    => fail(s"Unexpected result $other")

                    val effect2: Int < Abort[Throwable | String | Boolean] = Abort.fail("error")
                    val asThrow2: Int < Abort[Throwable | Boolean]         = effect2.forAbort[Throwable | String].toThrowable
                    Abort.run[Any](asThrow2).eval match
                        case Result.Error(PanicException(msg)) => assert(msg == "error")
                        case other                             => fail(s"Unexpected result $other")

                    val effect3: Int < Abort[Throwable | String | Boolean] = 23
                    val asThrow3                                           = effect3.forAbort[Throwable | String].toThrowable
                    assert(Abort.run[Any](asThrow3).eval == Result.Success(23))
                }
            }

            "recover" - {
                "should catch some abort" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val caught                                = effect.forAbort[String].recover(_ => 99)
                    assert(Abort.run[Any](caught).eval == Result.succeed(99))

                    val effect2: Int < Abort[String | Boolean] = Abort.fail(true)
                    val caught2                                = effect2.forAbort[String].recover(_ => 99)
                    assert(Abort.run[Boolean](caught2).eval == Result.fail(true))

                    val effect3: Int < Abort[String | Boolean] = 42
                    val caught3                                = effect3.forAbort[String].recover(_ => 99)
                    assert(Abort.run[Any](caught3).eval == Result.succeed(42))
                }
            }

            "recoverSome" - {
                "should catch some abort with partial function" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val caught = effect.forAbort[String].recoverSome {
                        case "error" => 99
                    }
                    assert(Abort.run[Any](caught).eval == Result.succeed(99))

                    val effect2: Int < Abort[String | Boolean] = Abort.fail("other")
                    val caught2 = effect2.forAbort[String].recoverSome {
                        case "error" => 99
                    }
                    assert(Abort.run[Any](caught2).eval == Result.fail("other"))

                    val effect3: Int < Abort[String | Boolean] = 42
                    val caught3 = effect3.forAbort[String].recoverSome {
                        case "error" => 99
                    }
                    assert(Abort.run[Any](caught3).eval == Result.succeed(42))
                }
            }

            "swap" - {
                "should swap some abort" in {
                    val effect: Int < Abort[String | Boolean] = Abort.fail("error")
                    val swapped                               = effect.forAbort[String].swap
                    assert(Abort.run[Any](swapped).eval == Result.succeed("error"))

                    val effect2: Int < Abort[String | Boolean] = Abort.fail(true)
                    val swapped2                               = effect2.forAbort[String].swap
                    assert(Abort.run[Any](swapped2).eval.isFailure)

                    val effect3: Int < Abort[String | Boolean] = 42
                    val swapped3                               = effect3.forAbort[String].swap
                    assert(Abort.run[Any](swapped3).eval == Result.fail(42))
                }
            }

            "should handle reduced union" in {
                val effect: Int < Abort[String | Boolean | Int]         = Abort.fail("error")
                val handled: Result[String | Int, Int] < Abort[Boolean] = effect.forAbort[String | Int].result
                assert(Abort.run[Any](handled).eval == Result.succeed(Result.fail("error")))

                val effect2: Int < Abort[String | Boolean | Int]         = Abort.fail(5)
                val handled2: Result[String | Int, Int] < Abort[Boolean] = effect2.forAbort[String | Int].result
                assert(Abort.run[Any](handled2).eval == Result.succeed(Result.fail(5)))

                val effect3: Int < Abort[String | Boolean | Int]         = Abort.fail(true)
                val handled3: Result[String | Int, Int] < Abort[Boolean] = effect3.forAbort[String | Int].result
                assert(Abort.run[Any](handled3).eval == Result.fail(true))
            }

            "fold" - {
                "should handle success and fail case, throwing panics, when two handlers provided" in {
                    val success: Int < Abort[String | Boolean] = 23
                    val handledSuccess: String < Abort[Boolean] =
                        success.forAbort[String].fold(
                            i => i.toString,
                            identity
                        )
                    assert(Abort.run[Boolean](handledSuccess).eval == Result.Success("23"))
                    val failure: Int < Abort[String | Boolean] =
                        Abort.fail("failure")
                    val handledFailure: String < Abort[Boolean] =
                        failure.forAbort[String].fold(
                            i => i.toString,
                            identity
                        )
                    assert(Abort.run[Boolean](handledFailure).eval == Result.Success("failure"))
                    val panic: Int < Abort[String | Boolean] = Abort.panic(Exception("message"))
                    try
                        panic.forAbort[String].fold(
                            i => i.toString,
                            identity
                        )
                        succeed
                    catch
                        case e: Exception => assert(e.getMessage == "message")
                    end try
                }

                "should handle all cases when three handlers provided" in {
                    val success: Int < Abort[String] = 23
                    val handledSuccess: String < Any =
                        success.foldAbort(
                            i => i.toString,
                            identity,
                            _.getMessage
                        )
                    assert(handledSuccess.eval == "23")
                    val failure: Int < Abort[String] =
                        Abort.fail("failure")
                    val handledFailure: String < Any =
                        failure.foldAbort(
                            i => i.toString,
                            identity,
                            _.getMessage
                        )
                    assert(handledFailure.eval == "failure")
                    val panic: Int < Abort[String] = Abort.panic(Exception("message"))
                    val handledPanic: String < Any =
                        panic.foldAbort(
                            i => i.toString,
                            identity,
                            _.getMessage
                        )
                    assert(handledPanic.eval == "message")
                }
            }

            "retry" - {
                "retry n times" - {
                    "succeeding" in run {
                        val effect: Int < (Var[Int] & Abort[String | Int]) =
                            for
                                _ <- Var.update[Int](_ + 1)
                                i <- Var.get[Int]
                            yield i

                        Var.run(0) {
                            effect.forAbort[Int].retry(5).map: i =>
                                Var.get[Int].map: i2 =>
                                    assert(i == 1 && i2 == 1)
                        }
                    }

                    "failing retried type" in run {
                        val effect: Unit < (Var[Int] & Abort[String | Int]) =
                            for
                                _ <- Var.update[Int](_ + 1)
                                i <- Var.get[Int]
                                _ <- Abort.fail(i)
                            yield ()

                        Var.run(0) {
                            Abort.run(effect.forAbort[Int].retry(5)).map: result =>
                                Var.get[Int].map: i =>
                                    assert(result == Result.Failure(6) && i == 6)
                        }
                    }

                    "failing non-retried type" in run {
                        val effect: Unit < (Var[Int] & Abort[String | Int]) =
                            for
                                _ <- Var.update[Int](_ + 1)
                                i <- Var.get[Int]
                                _ <- if i == 3 then Abort.fail("fail") else Kyo.unit
                                _ <- Abort.fail(i)
                            yield ()

                        Var.run(0) {
                            Abort.run(effect.forAbort[Int].retry(5)).map: result =>
                                Var.get[Int].map: i =>
                                    assert(result == Result.Failure("fail") && i == 3)
                        }
                    }

                    "failing then succeeeding" in run {
                        val effect: Int < (Var[Int] & Abort[String | Int]) =
                            for
                                _ <- Var.update[Int](_ + 1)
                                i <- Var.get[Int]
                                _ <- if i < 5 then Abort.fail("fail") else Kyo.unit
                            yield i

                        Var.run(0) {
                            effect.retry(5).map: i =>
                                Var.get[Int].map: i2 =>
                                    assert(i == 5 && i2 == 5)
                        }
                    }
                }
            }
        }
    }

end AbortCombinatorsTest
