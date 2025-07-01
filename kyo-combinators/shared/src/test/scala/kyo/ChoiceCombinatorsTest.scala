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
                val effect: Int < Choice = Choice.eval(1, 2, 3)
                assert(effect.handleChoice.eval == Seq(1, 2, 3))
            }
        }

        "filter" - {
            "should filter" in {
                val effect: Int < Choice = Choice.eval(1, 2, 3)
                val filteredEffect       = effect.filterChoice(_ < 3)
                assert(filteredEffect.handleChoice.eval == Seq(1, 2))
            }
        }

        "iteration" - {
            "should iterate using foreach" in run {
                var state             = 0
                def effectFor(i: Int) = Sync { state += i; state }
                val effect            = Kyo.foreach(1 to 10)(effectFor)
                assert(state == 0)
                effect.map { result =>
                    //                   1, 2, 3,  4,  5,  6,  7,  8,  9, 10
                    assert(result == Seq(1, 3, 6, 10, 15, 21, 28, 36, 45, 55))
                    assert(state == 55)
                }
            }

            "should iterate using collect" in run {
                var state = 0
                val effect = Kyo.collect(1 to 10) { i =>
                    Sync(Maybe.when(i % 2 == 0) { { state += i; i * 2 } })
                }
                assert(state == 0)
                effect.map { result =>
                    assert(result == Seq(4, 8, 12, 16, 20))
                    assert(state == 30)
                }
            }

            "should iterate using traverse" in run {
                var state   = 0
                val effects = (1 to 10).map(i => Sync { state += i; state })
                val effect  = Kyo.traverse(effects)
                assert(state == 0)
                effect.map { result =>
                    assert(result == Seq(1, 3, 6, 10, 15, 21, 28, 36, 45, 55))
                    assert(state == 55)
                }
            }

            "should iterate using traverseDiscard" in run {
                var state   = 0
                val effects = (1 to 10).map(i => Sync { state += i; state })
                val effect  = Kyo.traverseDiscard(effects)
                assert(state == 0)
                effect.map { result =>
                    assert(result == ())
                    assert(state == 55)
                }
            }
        }
    }
end ChoiceCombinatorTest
