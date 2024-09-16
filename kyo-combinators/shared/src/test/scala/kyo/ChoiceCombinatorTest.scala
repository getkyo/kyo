package kyo

class ChoiceCombinatorTest extends Test:

    "choice" - {
        "construct" - {
            "should construct choice from a sequence" in {
                val effect = Kyo.fromSeq(Seq(1, 2, 3))
                assert(Choice.run(effect).eval == Seq(1, 2, 3))
            }
        }

        "handle" - {
            "should handle" in {
                val effect: Int < Choice = Choice.get(Seq(1, 2, 3))
                assert(effect.handleChoice.eval == Seq(1, 2, 3))
            }
        }

        "filter" - {
            "should filter" in {
                val effect: Int < Choice = Choice.get(Seq(1, 2, 3))
                val filteredEffect       = effect.filterChoice(_ < 3)
                assert(filteredEffect.handleChoice.eval == Seq(1, 2))
            }
        }

        "convert" - {

            "should convert choice to abort, constructing Right from Seq#head" in {
                val failure: Int < Choice             = Choice.get(Nil)
                val failureAbort: Int < Abort[String] = failure.choiceToAbort("failure")
                val handledFailureAbort               = Abort.run[String](failureAbort)
                assert(handledFailureAbort.eval == Result.fail("failure"))
                val success: Int < Choice             = Choice.get(Seq(1, 2, 3))
                val successAbort: Int < Abort[String] = success.choiceToAbort("failure")
                val handledSuccessAbort               = Abort.run[String](successAbort)
                assert(handledSuccessAbort.eval == Result.success(1))
            }

            "should convert choice to throwable abort, constructing Right from Seq#head" in {
                val failure: Int < Choice                = Choice.get(Nil)
                val failureAbort: Int < Abort[Throwable] = failure.choiceToThrowable
                val handledFailureAbort                  = Abort.run[Throwable](failureAbort)
                assert(handledFailureAbort.eval.failure.get.getMessage.contains(
                    "head of empty list"
                ))
                val success: Int < Choice                = Choice.get(Seq(1, 2, 3))
                val successAbort: Int < Abort[Throwable] = success.choiceToThrowable
                val handledSuccessAbort                  = Abort.run[Throwable](successAbort)
                assert(handledSuccessAbort.eval == Result.success(1))
            }

            "should convert choice to empty abort, constructing Right from Seq#head" in {
                val failure: Int < Choice                  = Choice.get(Nil)
                val failureAbort: Int < Abort[Maybe.Empty] = failure.choiceToEmpty
                val handledFailureAbort                    = Abort.run[Maybe.Empty](failureAbort)
                assert(handledFailureAbort.eval == Result.fail(Maybe.Empty))
                val success: Int < Choice                  = Choice.get(Seq(1, 2, 3))
                val successAbort: Int < Abort[Maybe.Empty] = success.choiceToEmpty
                val handledSuccessAbort                    = Abort.run[Maybe.Empty](successAbort)
                assert(handledSuccessAbort.eval == Result.success(1))
            }
        }

        "iteration" - {
            "should iterate using foreach" in {
                var state             = 0
                def effectFor(i: Int) = IO { state += i; state }
                val effect            = Kyo.foreach(1 to 10)(effectFor)
                assert(state == 0)
                val result = IO.run(effect).eval
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
                val result = IO.run(effect).eval
                assert(result == Seq(4, 8, 12, 16, 20))
                assert(state == 30)
            }

            "should iterate using traverse" in {
                var state   = 0
                val effects = (1 to 10).map(i => IO { state += i; state })
                val effect  = Kyo.traverse(effects)
                assert(state == 0)
                val result = IO.run(effect).eval
                assert(result == Seq(1, 3, 6, 10, 15, 21, 28, 36, 45, 55))
                assert(state == 55)
            }

            "should iterate using traverseDiscard" in {
                var state   = 0
                val effects = (1 to 10).map(i => IO { state += i; state })
                val effect  = Kyo.traverseDiscard(effects)
                assert(state == 0)
                val result = IO.run(effect).eval
                assert(result == ())
                assert(state == 55)
            }
        }
    }
end ChoiceCombinatorTest
