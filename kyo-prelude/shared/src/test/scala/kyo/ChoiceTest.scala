package kyo

import Tagged.*

class ChoiceTest extends Test:

    "eval with a single choice" in {
        assert(
            Choice.run(Choice.evalWith(Seq(1))(i => (i + 1))).eval == Seq(2)
        )
    }

    "eval with multiple choices" in {
        assert(
            Choice.run(Choice.evalWith(Seq(1, 2, 3))(i => (i + 1))).eval == Seq(2, 3, 4)
        )
    }

    "nested eval" in {
        assert(
            Choice.run(Choice.evalWith(Seq(1, 2, 3))(i =>
                Choice.eval(i * 10, i * 100)
            )).eval == Seq(10, 100, 20, 200, 30, 300)
        )
    }

    "drop" in {
        assert(
            Choice.run(Choice.evalWith(Seq(1, 2, 3))(i =>
                if i < 2 then Choice.drop else Choice.eval(i * 10, i * 100)
            )).eval == Seq(20, 200, 30, 300)
        )
    }

    "filter" in {
        assert(
            Choice.run(Choice.evalWith(Seq(1, 2, 3))(i =>
                Choice.dropIf(i < 2).map(_ => Choice.eval(i * 10, i * 100))
            )).eval == Seq(20, 200, 30, 300)
        )
    }

    "empty choices" in {
        assert(
            Choice.run(Choice.evalWith(Seq.empty[Int])(_ => 42)).eval == Seq.empty[Int]
        )
    }

    "nested drop" in {
        assert(
            Choice.run(
                Choice.evalWith(Seq(1, 2, 3))(i =>
                    Choice.evalWith(Seq(i * 10, i * 100))(j =>
                        if j > 100 then Choice.drop else j
                    )
                )
            ).eval == Seq(10, 100, 20, 30)
        )
    }

    "nested filter" in {
        assert(
            Choice.run(
                Choice.evalWith(Seq(1, 2, 3))(i =>
                    Choice.dropIf(i % 2 != 0).map(_ =>
                        Choice.evalWith(Seq(i * 10, i * 100))(j =>
                            Choice.dropIf(j >= 300).map(_ => j)
                        )
                    )
                )
            ).eval == Seq(20, 200)
        )
    }

    "large number of choices" in {
        val largeChoice = Seq.range(0, 100000)
        try
            assert(
                Choice.run(Choice.eval(largeChoice*)).eval == largeChoice
            )
        catch
            case ex: StackOverflowError => fail()
        end try
    }

    "large number of suspensions" taggedAs notNative in pendingUntilFixed {
        // https://github.com/getkyo/kyo/issues/208
        var v = Choice.eval(1)
        for _ <- 0 until 100000 do
            v = v.map(_ => Choice.eval(1))
        try
            assert(
                Choice.run(v).eval == Seq(1)
            )
        catch
            case ex: StackOverflowError => fail()
        end try
        ()
    }

    "interaction with collection operations" - {
        "foreach" in {
            val result = Choice.run(
                Kyo.foreach(List("x", "y")) { str =>
                    Choice.eval(true, false).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            ).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "collect" in {
            val effects =
                List("x", "y").map { str =>
                    Choice.eval(true, false).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            val result = Choice.run(Kyo.collectAll(effects)).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "foldLeft" in {
            val result = Choice.run(
                Kyo.foldLeft(List(1, 1))(0) { (acc, _) =>
                    Choice.eval(0, 1).map(n => acc + n)
                }
            ).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.size == 4)
        }

        "foreach - array" in {
            val result = Choice.run(
                Kyo.foreach(Array("x", "y")) { str =>
                    Choice.eval(true, false).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            ).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "collect - array" in {
            val effects =
                Array("x", "y").map { str =>
                    Choice.eval(true, false).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            val result = Choice.run(Kyo.collectAll(effects)).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "foldLeft - array" in {
            val result = Choice.run(
                Kyo.foldLeft(Array(1, 1))(0) { (acc, _) =>
                    Choice.eval(0, 1).map(n => acc + n)
                }
            ).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.size == 4)
        }
    }

    "runStream" - {
        "returns all possible outcomes" in {
            val computation = Choice.eval(1, 2, 3)
            val stream      = Choice.runStream(computation)
            val result      = stream.run.eval

            assert(result == Chunk(1, 2, 3))
        }

        "handles empty choices" in {
            val stream = Choice.runStream(Choice.eval[Int]())
            val result = stream.run.eval
            assert(result.isEmpty)
        }

        "supports incremental consumption" in {
            val computation = Choice.eval(1, 2, 3, 4, 5)
            val stream      = Choice.runStream(computation)

            val firstThree = stream.take(3).run.eval

            assert(firstThree.size == 3)
            assert(firstThree.forall(n => n >= 1 && n <= 5))
        }

        "works with filtering" in {
            val computation =
                for
                    x <- Choice.eval(1, 2, 3, 4)
                    _ <- Choice.dropIf(x % 2 == 0)
                yield x

            val result = Choice.runStream(computation).run.eval

            assert(result == Chunk(1, 3))
        }

        "integrates with stream operations" in {
            val computation = Choice.eval(1, 2, 3, 4, 5)
            val stream      = Choice.runStream(computation)

            val result = stream
                .filter[Int](_ % 2 == 1)
                .map(_ * 10)
                .run.eval

            assert(result == Chunk(10, 30, 50))
        }

        "interaction with other effects" - {
            "with Var" in {
                val computation =
                    for
                        x       <- Choice.eval(1, 2, 3)
                        _       <- Var.update[Int](_ + x)
                        current <- Var.get[Int]
                    yield current

                val stream = Choice.runStream(computation)
                val result = Var.runTuple(0)(stream.run).eval

                assert(result._1 == 6)
                assert(result._2 == Chunk(1, 3, 6))
            }

            "with Env" in {
                val computation =
                    for
                        x          <- Choice.eval(1, 2, 3)
                        multiplier <- Env.get[Int]
                    yield x * multiplier

                val stream = Choice.runStream(computation)
                val result = Env.run(10)(stream.run).eval

                assert(result == Chunk(10, 20, 30))
            }

            "with filtering" in {
                val computation =
                    for
                        x <- Choice.eval(1, 2, 3, 4)
                        _ <- Choice.dropIf(x % 2 == 0)
                    yield x

                val stream = Choice.runStream(computation)
                val result = stream.run.eval

                assert(result == Chunk(1, 3))
            }

            "with nested effects" in {
                val computation =
                    for
                        x <- Choice.eval(1, 2, 3)
                        y <- Env.use[Int](multiplier =>
                            Var.updateWith[Int](_ + x) { current =>
                                if current > 5 then Choice.drop else x * multiplier
                            }
                        )
                    yield y

                val stream = Choice.runStream(computation)
                val result = Env.run(10)(Var.runTuple(0)(stream.run)).eval

                assert(result._1 == 6)
                assert(result._2 == Chunk(10, 20))
            }

            "with incremental consumption and state" in {
                val computation =
                    for
                        x <- Choice.eval(1, 2, 3, 4, 5)
                        _ <- Var.update[Int](_ + x)
                    yield x

                val stream = Choice.runStream(computation)
                val result = Var.runTuple(0)(stream.take(3).run).eval

                assert(result._1 == 15)
                assert(result._2 == Chunk(1, 2, 3))
            }

            "with isolate" in {
                val computation =
                    for
                        x <- Choice.eval(1, 2, 3)
                        _ <- Var.isolate.discard[Int].run {
                            Var.update[Int](_ + x * 10)
                        }
                    yield x

                val stream = Choice.runStream(computation)
                val result = Var.runTuple(0)(stream.run).eval

                assert(result._1 == 0)
                assert(result._2 == Chunk(1, 2, 3))
            }
        }
    }

end ChoiceTest
