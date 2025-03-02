package kyo

class MemoTest extends Test:

    "apply" - {
        "memoizes pure functions" in {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val result = Memo.run {
                for
                    a <- f(5)
                    b <- f(5)
                    c <- f(6)
                    d <- f(5)
                yield (a, b, c, d)
            }

            assert(result.eval == (10, 10, 12, 10))
            assert(count == 2)
        }

        "memoizes effectful functions" in {
            var count = 0
            val f = Memo[Int, Int, Env[Int]] { x =>
                Env.use[Int] { env =>
                    count += 1
                    x * env
                }
            }

            val result = Memo.run {
                Env.run(2) {
                    for
                        a <- f(5)
                        b <- f(5)
                        c <- f(6)
                        d <- f(5)
                    yield (a, b, c, d)
                }
            }

            assert(result.eval == (10, 10, 12, 10))
            assert(count == 2)
        }
    }

    "memoization key" - {
        "isolates memoized functions" in {
            var count = 0

            def createMemoizedFunction() = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val f1 = createMemoizedFunction()
            val f2 = createMemoizedFunction()

            val result = Memo.run {
                for
                    a <- f1(5)
                    b <- f1(5)
                    c <- f2(5)
                    d <- f2(5)
                yield (a, b, c, d)
            }

            assert(result.eval == (10, 10, 10, 10))
            assert(count == 2)
        }

        "differentiates between identical functions created at different call sites" in {
            var count = 0

            def createAndUseMemoizedFunction() =
                val f = Memo[Int, Int, Any] { x =>
                    count += 1
                    x * 2
                }
                f(5)
            end createAndUseMemoizedFunction

            val result = Memo.run {
                for
                    a <- createAndUseMemoizedFunction()
                    b <- createAndUseMemoizedFunction()
                yield (a, b)
            }

            assert(result.eval == (10, 10))
            assert(count == 2)
        }

        "uses structural equality for memoization keys" in {
            var count = 0

            case class Key(value: Int)

            val f = Memo[Key, Int, Any] { key =>
                count += 1
                key.value * 2
            }

            val result = Memo.run {
                for
                    a <- f(Key(5))
                    b <- f(Key(5)) // Structurally equal to the first Key(5)
                    c <- f(Key(6))
                yield (a, b, c)
            }

            assert(result.eval == (10, 10, 12))
            assert(count == 2)
        }

        "differentiates between functions with different types" in {
            var countInt    = 0
            var countString = 0

            val fInt = Memo[Int, Int, Any] { x =>
                countInt += 1
                x * 2
            }

            val fString = Memo[String, Int, Any] { s =>
                countString += 1
                s.length
            }

            val result = Memo.run {
                for
                    a <- fInt(5)
                    b <- fString("hello")
                    c <- fInt(5)
                    d <- fString("hello")
                yield (a, b, c, d)
            }

            assert(result.eval == (10, 5, 10, 5))
            assert(countInt == 1)
            assert(countString == 1)
        }

        "handles memoization with multiple parameters correctly" in {
            var count = 0

            val f = Memo[(Int, String), String, Any] { case (x, s) =>
                count += 1
                s"$s-$x"
            }

            val result = Memo.run {
                for
                    a <- f((1, "a"))
                    b <- f((2, "b"))
                    c <- f((1, "a"))
                    d <- f((2, "b"))
                    e <- f((1, "b")) // Different combination
                yield (a, b, c, d, e)
            }

            assert(result.eval == ("a-1", "b-2", "a-1", "b-2", "b-1"))
            assert(count == 3)
        }
    }

    "interaction with other effects" - {
        "works with Env" in {
            var count = 0
            val f = Memo[Int, Int, Env[Int]] { x =>
                Env.use[Int] { env =>
                    count += 1
                    x * env
                }
            }

            val result = Memo.run {
                Env.run(2) {
                    for
                        a <- f(5)
                        b <- f(5)
                        c <- f(6)
                    yield (a, b, c)
                }
            }

            assert(result.eval == (10, 10, 12))
            assert(count == 2)
        }

        "works with Abort" in {
            var count = 0
            val f = Memo[Int, Int, Abort[String]] { x =>
                count += 1
                if x < 0 then Abort.fail("Negative input")
                else x * 2
            }

            val result = Memo.run {
                Abort.run {
                    for
                        a <- f(5)
                        b <- f(-1)
                        c <- f(5)
                    yield (a, b, c)
                }
            }

            assert(result.eval == Result.fail("Negative input"))
            assert(count == 2)
        }

        "memoizes effects correctly" in {
            var sideEffect = 0
            val f = Memo[Int, Int, Env[Int]] { x =>
                Env.use[Int] { env =>
                    sideEffect += 1
                    x * env
                }
            }

            val result = Memo.run {
                Env.run(2) {
                    for
                        a <- f(5)
                        b <- f(5)
                        c <- f(6)
                        _ <- Env.run(3) {
                            for
                                d <- f(5)
                                e <- f(6)
                            yield (d, e)
                        }
                    yield (a, b, c)
                }
            }

            assert(result.eval == (10, 10, 12))
            assert(sideEffect == 2)
        }

        "memoizes across different effect combinations" in {
            var count = 0
            val f = Memo[Int, Int, Env[Int] & Var[String] & Abort[String]] { x =>
                count += 1
                for
                    env <- Env.get[Int]
                    _   <- Var.update[String](s => s"$s-$x")
                    _   <- if x > 10 then Abort.fail("Too large") else Abort.get(Right(()))
                yield x * env
                end for
            }

            val result = Memo.run {
                Env.run(2) {
                    Var.run("initial") {
                        Abort.run {
                            for
                                a <- f(5)
                                b <- f(5)
                                c <- f(6)
                                d <- f(11)
                                e <- f(6)
                            yield (a, b, c, d, e)
                        }
                    }
                }
            }

            assert(result.eval == Result.fail("Too large"))
            assert(count == 3)
        }
    }

    "isolate" - {
        "combines caches from isolated and outer scopes" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val result = Memo.run {
                for
                    a <- f(5)
                    b <- Isolate.Stateful[Memo, Any].run {
                        for
                            c <- f(6)
                            d <- f(5)
                        yield (c, d)
                    }
                    c <- f(6)
                    d <- f(7)
                yield (count, a, b, c, d)
            }
            assert(result.eval == (3, 10, (12, 10), 12, 14))
            assert(count == 3)
        }

        "proper state restoration after nested isolations" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val result = Memo.run {
                for
                    start <- f(1)
                    v1 <- Isolate.Stateful[Memo, Any].run {
                        f(2)
                    }
                    middle <- f(3)
                    v2 <- Isolate.Stateful[Memo, Any].run {
                        f(4)
                    }
                    end <- f(5)
                yield (count, start, v1, middle, v2, end)
            }
            assert(result.eval == (5, 2, 4, 6, 8, 10))
            assert(count == 5)
        }

        "composition" - {
            "can combine multiple isolates" in run {
                var count = 0
                val f = Memo[Int, Int, Any] { x =>
                    count += 1
                    x * 2
                }

                val isolate = Isolate.Stateful[Memo, Any].andThen(Emit.isolate.merge[Int])

                val result = Memo.run {
                    Emit.run {
                        isolate.run {
                            for
                                a <- f(1)
                                _ <- Emit.value(a)
                                b <- f(2)
                                _ <- Emit.value(b)
                            yield (a, b)
                        }
                    }
                }
                assert(result.eval == (Chunk(2, 4), (2, 4)))
                assert(count == 2)
            }

            "preserves individual isolation behaviors when composed" in run {
                var count = 0
                val f = Memo[Int, Int, Any] { x =>
                    count += 1
                    x * 2
                }

                val i1 = Isolate.Stateful[Memo, Any]
                val i2 = Emit.isolate.merge[Int]

                val result = Memo.run {
                    Emit.run {
                        for
                            a <- f(1)
                            _ <- i1.run {
                                for
                                    b <- f(2)
                                    _ <- Emit.value(b)
                                yield b
                            }
                            c <- f(2)
                            _ <- i2.run {
                                for
                                    d <- f(3)
                                    _ <- Emit.value(d)
                                yield d
                            }
                            e <- f(3)
                        yield (count, a, c, e)
                    }
                }
                assert(result.eval == (Chunk(4, 6), (3, 2, 4, 6)))
                assert(count == 3)
            }
        }
    }

end MemoTest
