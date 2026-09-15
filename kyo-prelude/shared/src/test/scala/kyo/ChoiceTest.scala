package kyo

import kyo.kernel.Bracket
import scala.annotation.nowarn

class ChoiceTest extends kyo.test.Test[Any]:

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

    "large number of suspensions".notNative.notWasm in {
        // #208: suspensions run on the evaluator's loop, not the call stack, so this depth does not overflow.
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
            @nowarn("msg=deprecated")
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
            @nowarn("msg=deprecated")
            val result = Choice.run(Kyo.collectAll(effects)).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "foldLeft - array" in {
            @nowarn("msg=deprecated")
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
                .filter(_ % 2 == 1)
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

    "brackets" - {

        "a bracket outside the region releases once, after every branch" in {
            var log = Chunk.empty[String]
            val v =
                Bracket("res") { _ =>
                    Choice.run {
                        Choice.eval(1, 2, 3).map { n =>
                            log = log.append(s"branch$n")
                            n
                        }
                    }
                }((_, _) => log = log.append("release"))
            assert(v.eval == Chunk(1, 2, 3))
            // The release belongs to an extent outside the region, so every branch runs against the same live resource.
            assert(log == Chunk("branch1", "branch2", "branch3", "release"))
        }

        "a bracket outside the region is live in every branch" in {
            var released = false
            val v =
                Bracket(1) { res =>
                    Choice.run {
                        Choice.eval(1, 2, 3).map(n => (n, released))
                    }
                }((_, _) => released = true)
            // a branch seeing the release already run would mean the extent ended on the first branch and
            // the later ones ran against a spent resource
            assert(v.eval == Chunk((1, false), (2, false), (3, false)))
            assert(released)
        }

        "a bracket inside a branch releases once per branch" in {
            var opens  = 0
            var closes = 0
            val v = Choice.run {
                for
                    n <- Choice.eval(1, 2, 3)
                    r <- Bracket({ opens += 1; n })(a => a * 10)((_, _) => closes += 1)
                yield r
            }
            assert(v.eval == Chunk(10, 20, 30))
            // each branch acquires its own: replaying the continuation mints a fresh resource rather than re-entering a released one
            assert(opens == 3)
            assert(closes == 3)
        }

        "a bracket acquired in a branch is released before the next branch acquires" in {
            var log = Chunk.empty[String]
            val v = Choice.run {
                for
                    n <- Choice.eval(1, 2)
                    r <- Bracket({ log = log.append(s"open$n"); n })(a => a)((_, _) => log = log.append(s"close$n"))
                yield r
            }
            assert(v.eval == Chunk(1, 2))
            assert(log == Chunk("open1", "close1", "open2", "close2"))
        }

        "a bracket inside the streamed choice is held across every branch and released once" in {
            // runStream pulls each branch through a handleFirst region and continues it in its own loop, so the
            // bracket travels with each branch's remainder. The region declares escaping and repeated, so the
            // bracket is held across every branch and discharged once by the scope below.
            var log = Chunk.empty[String]
            val v =
                Choice.runStream {
                    Bracket("res")(_ =>
                        Choice.eval(1, 2, 3).map { n =>
                            log = log.append(s"branch$n"); n
                        }
                    )((_, _) => log = log.append("release"))
                }.run
            assert(v.eval == Chunk(1, 2, 3))
            assert(log == Chunk("branch1", "branch2", "branch3", "release"))
        }

        "a bracket around the streamed choice releases once after every branch" in {
            // the bracket sits below the region that replays, so it is not part of any branch's remainder:
            // every branch runs against the live resource and the release runs once when the stream body ends
            var log = Chunk.empty[String]
            val v =
                Stream {
                    Bracket("res")(_ =>
                        Choice.runStream(
                            Choice.eval(1, 2, 3).map { n =>
                                log = log.append(s"branch$n"); n
                            }
                        ).emit
                    )((_, _) => log = log.append("release"))
                }.run
            assert(v.eval == Chunk(1, 2, 3))
            assert(log == Chunk("branch1", "branch2", "branch3", "release"))
        }

        "nested choice points stream in the order run collects them".pendingUntilFixed(
            "ported from robustness; nested choice points stream in a different order than run collects them; behavior gap in this branch's Choice streaming order"
        ) in {
            val computation =
                Choice.eval(1, 2).map { a =>
                    if a == 1 then Choice.eval(10, 11) else a
                }
            assert(Choice.runStream(computation).run.eval == Choice.run(computation).eval)
            assert(Choice.runStream(computation).run.eval == Chunk(10, 11, 2))
        }

    }

end ChoiceTest
