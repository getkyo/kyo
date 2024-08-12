package KyoTest

import kyo.*
import kyoTest.KyoTest

class choicesTest extends KyoTest:

    "choices" - {
        "construct" - {
            "should construct choices from a sequence" in {
                val effect = Kyo.fromSeq(Seq(1, 2, 3))
                assert(Choices.run(effect).pure == Seq(1, 2, 3))
            }
        }

        "handle" - {
            "should handle" in {
                val effect: Int < Choices = Choices.get(Seq(1, 2, 3))
                assert(effect.handleChoices.pure == Seq(1, 2, 3))
            }
        }

        "filter" - {
            "should filter" in {
                val effect: Int < Choices = Choices.get(Seq(1, 2, 3))
                val filteredEffect        = effect.filterChoices(_ < 3)
                assert(filteredEffect.handleChoices.pure == Seq(1, 2))
            }
        }

        "convert" - {
            "should convert choices to options, constructing Some from Seq#head" in {
                val failure: Int < Choices        = Choices.get(Nil)
                val failureOptions: Int < Options = failure.choicesToOptions
                val handledFailureOptions         = Options.run(failureOptions)
                assert(handledFailureOptions.pure == None)
                val success: Int < Choices        = Choices.get(Seq(1, 2, 3))
                val successOptions: Int < Options = success.choicesToOptions
                val handledSuccessOptions         = Options.run(successOptions)
                assert(handledSuccessOptions.pure == Some(1))
            }

            "should convert choices to aborts, constructing Right from Seq#head" in {
                val failure: Int < Choices              = Choices.get(Nil)
                val failureAborts: Int < Abort[String] = failure.choicesToAborts("failure")
                val handledFailureAborts                = Abort.run[String](failureAborts)
                assert(handledFailureAborts.pure == Left("failure"))
                val success: Int < Choices              = Choices.get(Seq(1, 2, 3))
                val successAborts: Int < Abort[String] = success.choicesToAborts("failure")
                val handledSuccessAborts                = Abort.run[String](successAborts)
                assert(handledSuccessAborts.pure == Right(1))
            }

            "should convert choices to throwable aborts, constructing Right from Seq#head" in {
                val failure: Int < Choices                 = Choices.get(Nil)
                val failureAborts: Int < Abort[Throwable] = failure.choicesToThrowable
                val handledFailureAborts                   = Abort.run[Throwable](failureAborts)
                assert(handledFailureAborts.pure.left.toOption.get.getMessage.contains(
                    "head of empty list"
                ))
                val success: Int < Choices                 = Choices.get(Seq(1, 2, 3))
                val successAborts: Int < Abort[Throwable] = success.choicesToThrowable
                val handledSuccessAborts                   = Abort.run[Throwable](successAborts)
                assert(handledSuccessAborts.pure == Right(1))
            }

            "should convert choices to unit aborts, constructing Right from Seq#head" in {
                val failure: Int < Choices            = Choices.get(Nil)
                val failureAborts: Int < Abort[Unit] = failure.choicesToUnit
                val handledFailureAborts              = Abort.run[Unit](failureAborts)
                assert(handledFailureAborts.pure == Left(()))
                val success: Int < Choices            = Choices.get(Seq(1, 2, 3))
                val successAborts: Int < Abort[Unit] = success.choicesToUnit
                val handledSuccessAborts              = Abort.run[Unit](successAborts)
                assert(handledSuccessAborts.pure == Right(1))
            }
        }

        "catch" - {
            "should catch" in {
                val effect1: Int < Options = Kyo.none
                assert(effect1.catchOptions(100).pure == 100)

                val effect2: Int < Options = 23
                assert(effect2.catchOptions(100).pure == 23)
            }
        }

        "swap" - {
            "should swap" in {
                val failure: Int < Options         = Options.empty
                val swappedFailure: Unit < Options = failure.swapOptions
                val handledFailure                 = Options.run(swappedFailure)
                assert(handledFailure.pure == Some(()))
                val success: Int < Options         = 23
                val swappedSuccess: Unit < Options = success.swapOptions
                val handledSuccess                 = Options.run(swappedSuccess)
                assert(handledSuccess.pure == None)
            }

            "should swap as" in {
                val failure: Int < Options           = Options.empty
                val swappedFailure: String < Options = failure.swapOptionsAs("failure")
                val handledFailure                   = Options.run(swappedFailure)
                assert(handledFailure.pure == Some("failure"))
                val success: Int < Options           = 23
                val swappedSuccess: String < Options = success.swapOptionsAs("failure")
                val handledSuccess                   = Options.run(swappedSuccess)
                assert(handledSuccess.pure == None)
            }
        }

        "iteration" - {
            "should iterate using foreach" in {
                var state             = 0
                def effectFor(i: Int) = IO { state += i; state }
                val effect            = Kyo.foreach(1 to 10)(effectFor)
                assert(state == 0)
                val result = IO.run(effect).pure
                //                   1, 2, 3,  4,  5,  6,  7,  8,  9, 10
                assert(result == Seq(1, 3, 6, 10, 15, 21, 28, 36, 45, 55))
                assert(state == 55)
            }

            "should iterate using collect" in {
                var state = 0
                val effect = Kyo.collect(1 to 10) {
                    case i if i % 2 == 0 => IO { state += i; i * 2 }
                }
                assert(state == 0)
                val result = IO.run(effect).pure
                assert(result == Seq(4, 8, 12, 16, 20))
                assert(state == 30)
            }

            "should iterate using traverse" in {
                var state   = 0
                val effects = (1 to 10).map(i => IO { state += i; state })
                val effect  = Kyo.traverse(effects)
                assert(state == 0)
                val result = IO.run(effect).pure
                assert(result == Seq(1, 3, 6, 10, 15, 21, 28, 36, 45, 55))
                assert(state == 55)
            }

            "should iterate using traverseDiscard" in {
                var state   = 0
                val effects = (1 to 10).map(i => IO { state += i; state })
                val effect  = Kyo.traverseDiscard(effects)
                assert(state == 0)
                val result = IO.run(effect).pure
                assert(result == ())
                assert(state == 55)
            }
        }
    }

end choicesTest
