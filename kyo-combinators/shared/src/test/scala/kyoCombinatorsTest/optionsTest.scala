package KyoTest

import kyo.*
import kyoTest.KyoTest

class optionsTest extends KyoTest:

    "options" - {
        "construct" - {
            "should construct from option" in {
                val optionEmpty: Option[Int] = None
                val effectEmpty              = Kyo.fromOption(optionEmpty)
                assert(Options.run(effectEmpty).pure == None)
                val optionFull: Option[Int] = Some(23)
                val effectFull              = Kyo.fromOption(optionFull)
                assert(Options.run(effectFull).pure == Some(23))
            }

            "should construct from none" in {
                val effect = Kyo.none
                assert(Options.run(effect).pure == None)
            }
        }

        "handle" - {
            "should handle" in {
                val effect1: Int < Options = Kyo.none
                assert(effect1.handleOptions.pure == None)

                val effect2: Int < Options = 23
                assert(effect2.handleOptions.pure == Some(23))
            }
        }

        "convert" - {
            "should convert options to aborts" in {
                val failure: Int < Options              = Options.empty
                val failureAborts: Int < Abort[String] = failure.optionsToAborts("failure")
                val handledFailureAborts                = Abort.run[String](failureAborts)
                assert(handledFailureAborts.pure == Left("failure"))
                val success: Int < Options              = 23
                val successAborts: Int < Abort[String] = success.optionsToAborts("failure")
                val handledSuccessAborts                = Abort.run[String](successAborts)
                assert(handledSuccessAborts.pure == Right(23))
            }

            "should convert options to throwable aborts" in {
                val failure: Int < Options                 = Options.empty
                val failureAborts: Int < Abort[Throwable] = failure.optionsToThrowable
                val handledFailureAborts                   = Abort.run[Throwable](failureAborts)
                assert(handledFailureAborts.pure match
                    case Left(e: NoSuchElementException) => e.getMessage.contains("None.get")
                    case _                               => false
                )
                val success: Int < Options                 = 23
                val successAborts: Int < Abort[Throwable] = success.optionsToThrowable
                val handledSuccessAborts                   = Abort.run[Throwable](successAborts)
                assert(handledSuccessAborts.pure == Right(23))
            }

            "should convert options to unit aborts" in {
                val failure: Int < Options            = Options.empty
                val failureAborts: Int < Abort[Unit] = failure.optionsToUnit
                val handledFailureAborts              = Abort.run[Unit](failureAborts)
                assert(handledFailureAborts.pure == Left(()))
                val success: Int < Options            = 23
                val successAborts: Int < Abort[Unit] = success.optionsToUnit
                val handledSuccessAborts              = Abort.run[Unit](successAborts)
                assert(handledSuccessAborts.pure == Right(23))
            }

            "should convert options to seqs" in {
                val failure: Int < Options     = Options.empty
                val failureSeqs: Int < Choices = failure.optionsToChoices
                val handledFailureSeqs         = Choices.run(failureSeqs)
                assert(handledFailureSeqs.pure.isEmpty)
                val success: Int < Options     = 23
                val successSeqs: Int < Choices = success.optionsToChoices
                val handledSuccessSeqs         = Choices.run(successSeqs)
                assert(handledSuccessSeqs.pure == Seq(23))
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
    }

end optionsTest
