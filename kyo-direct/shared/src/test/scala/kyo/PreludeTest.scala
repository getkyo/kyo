package kyo

class PreludeTest extends Test:

    "abort" - {
        "basic usage" in run {
            val effect: Int < Abort[String] =
                direct {
                    if true then Abort.fail("error").now
                    else 42
                }

            Abort.run(effect).map { result =>
                assert(result == Result.fail("error"))
            }
        }

        "abort with recovery" in run {
            val effect: Int < Abort[String] =
                direct {
                    val result: Int = Abort.get[String](Left("first error")).now
                    result + 1
                }

            Abort.recover[String](_ => 42)(effect).map { result =>
                assert(result == 42)
            }
        }
    }

    "env" - {
        "basic usage" in run {
            val effect =
                direct {
                    val env = Env.get[Int].now
                    env * 2
                }

            Env.run(21)(effect).map { result =>
                assert(result == 42)
            }
        }

        "nested environments" in run {
            val effect =
                direct {
                    val outer = Env.get[String].now
                    val combined = Env.run(42) {
                        direct {
                            val inner = Env.get[Int].now
                            s"$outer: $inner"
                        }
                    }.now
                    combined
                }

            Env.run("Answer")(effect).map { result =>
                assert(result == "Answer: 42")
            }
        }

        "multiple environments" in run {
            val effect =
                direct {
                    val str = Env.get[String].now
                    val num = Env.get[Int].now
                    s"$str: $num"
                }

            val withString = Env.run("Test")(effect)
            val withBoth   = Env.run(42)(withString)
            withBoth.map { result =>
                assert(result == "Test: 42")
            }
        }
    }

    "var" - {
        "basic operations" in run {
            val effect =
                direct {
                    val initial = Var.get[Int].now
                    Var.update[Int](_ + 1).now
                    val afterInc = Var.get[Int].now
                    Var.set(100).now
                    val afterSet = Var.get[Int].now
                    (initial, afterInc, afterSet)
                }

            Var.run(41)(effect).map { case (initial, afterInc, afterSet) =>
                assert(initial == 41)
                assert(afterInc == 42)
                assert(afterSet == 100)
            }
        }

        "nested vars" in run {
            val effect =
                direct {
                    val outer = Var.get[Int].now
                    val nested = Var.run(outer * 2) {
                        direct {
                            val inner = Var.get[Int].now
                            Var.update[Int](_ + 1).now
                            Var.get[Int].now
                        }
                    }.now
                    (outer, nested)
                }

            Var.run(21)(effect).map { case (outer, nested) =>
                assert(outer == 21)
                assert(nested == 43)
            }
        }

        "var with other effects" in run {
            val effect =
                direct {
                    val env     = Env.get[Int].now
                    val initial = Var.get[Int].now
                    Var.update[Int](_ + env).now
                    val result = Abort.run[String] {
                        direct {
                            val current = Var.get[Int].now
                            if current > 50 then Abort.fail("Too large").now
                            else current
                        }
                    }.now
                    (initial, result)
                }

            Env.run(10) {
                Var.run(42)(effect)
            }.map { case (initial, result) =>
                assert(initial == 42)
                assert(result == Result.fail("Too large"))
            }
        }
    }

    "memo" - {
        "basic memoization" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val effect =
                direct {
                    val a = f(5).now
                    val b = f(5).now
                    val c = f(6).now
                    (a, b, c, count)
                }

            Memo.run(effect).map { case (a, b, c, callCount) =>
                assert(a == 10)
                assert(b == 10)
                assert(c == 12)
                assert(callCount == 2)
            }
        }

        "memo with other effects" in run {
            var count = 0
            val f = Memo[Int, Int, Env[Int]] { x =>
                count += 1
                Env.use[Int](_ + x)
            }

            val effect =
                direct {
                    val a = f(5).now
                    val b = f(5).now
                    val c = f(6).now
                    (a, b, c, count)
                }

            Env.run(10) {
                Memo.run(effect)
            }.map { case (a, b, c, callCount) =>
                assert(a == 15)
                assert(b == 15)
                assert(c == 16)
                assert(callCount == 2)
            }
        }
    }

    "choice" - {
        "basic choices" in run {
            val effect =
                direct {
                    val x = Choice.eval(1, 2, 3).now
                    val y = Choice.eval("a", "b").now
                    s"$x$y"
                }

            Choice.run(effect).map { results =>
                assert(results == Seq("1a", "1b", "2a", "2b", "3a", "3b"))
            }
        }

        "choice with conditions" in run {
            val effect =
                direct {
                    val x = Choice.eval(1, -2, -3).now
                    val y = Choice.eval("ab", "cde").now
                    if x > 0 then y.length * x
                    else y.length
                }

            Choice.run(effect).map { result =>
                assert(result == Seq(2, 3, 2, 3, 2, 3))
            }
        }

        "choice with filtering" in run {
            val effect =
                direct {
                    val x = Choice.eval(1, 2, 3, 4).now
                    Choice.dropIf(x % 2 == 0).now
                    x
                }

            Choice.run(effect).map { results =>
                assert(results == Seq(1, 3))
            }
        }
    }

    "emit" - {
        "basic emissions" in run {
            val effect =
                direct {
                    Emit.value(1).now
                    Emit.value(2).now
                    Emit.value(3).now
                    "done"
                }

            Emit.run(effect).map { case (emitted, result) =>
                assert(emitted == Chunk(1, 2, 3))
                assert(result == "done")
            }
        }

        "emit with conditions" in run {
            val effect =
                direct {
                    val a = Env.get[Int].now
                    if a > 5 then
                        Emit.value(a).now
                        ()
                    val b = a * 2
                    if b < 20 then
                        Emit.value(b).now
                        ()
                    "done"
                }

            Env.run(8) {
                Emit.run(effect)
            }.map { case (emitted, result) =>
                assert(emitted == Chunk(8, 16))
                assert(result == "done")
            }
        }

        "nested emit effects" in run {
            val effect =
                direct {
                    Emit.value(1).now
                    val nested = Emit.run {
                        direct {
                            Emit.value(2).now
                            Emit.value(3).now
                            "nested"
                        }
                    }.now
                    Emit.value(4).now
                    (nested._1, nested._2)
                }

            Emit.run(effect).map { case (outer, (inner, result)) =>
                assert(outer == Chunk(1, 4))
                assert(inner == Chunk(2, 3))
                assert(result == "nested")
            }
        }
    }

    "poll" - {
        "basic polling" in run {
            val effect =
                direct {
                    Poll.one[Int].now
                }

            Poll.run(Chunk(1, 2, 3))(effect).map { result =>
                assert(result == Maybe(1))
            }
        }

        "poll with fold" in run {
            val effect = Poll.fold[Int](0) { (acc, v) =>
                direct {
                    Sync(acc).now + v
                }
            }

            Poll.run(Chunk(2, 4, 8, 16))(effect).map { result =>
                assert(result == 30)
            }
        }
    }

    "stream" - {
        "basic operations" in run {
            direct {
                val stream =
                    Stream.init(Seq(1, 2, 3, 4, 5))
                        .map { x =>
                            val doubled = x * 2
                            doubled
                        }
                        .filter { x =>
                            x % 3 == 0
                        }.now
                val results = stream.run.now
                assert(results == Seq(6))
            }
        }

        "stream with other effects" in run {
            val effect =
                direct {
                    val env = Env.get[Int].now
                    val stream = Stream.init(1 to 3)
                        .map { x =>
                            direct {
                                val value = Var.get[Int].now
                                Var.update[Int](_ + x).now
                                x * env + value
                            }
                        }.now
                    stream.run.now
                }

            Env.run(10) {
                Var.run(1)(effect)
            }.map { results =>
                assert(results == Seq(11, 22, 34))
            }
        }
    }

    "Choice" in run {
        val x = Choice.eval(1, -2, -3)
        val y = Choice.eval("ab", "cde")

        val v: Int < Choice =
            direct {
                val xx = x.now
                xx + (
                    if xx > 0 then y.now.length * x.now
                    else y.now.length
                )
            }

        Choice.run(v).map { result =>
            assert(result == Seq(3, -3, -5, 4, -5, -8, 0, 1, -1, 0))
        }
    }

    "Choice + filter" in run {
        val x = Choice.eval(1, -2, -3)
        val y = Choice.eval("ab", "cde")

        val v: Int < Choice =
            direct {
                val xx = x.now
                val r =
                    xx + (
                        if xx > 0 then y.now.length * x.now
                        else y.now.length
                    )
                Choice.dropIf(r <= 0).now
                r
            }

        Choice.run(v).map { result =>
            assert(result == Seq(3, 4, 1))
        }
    }
end PreludeTest
