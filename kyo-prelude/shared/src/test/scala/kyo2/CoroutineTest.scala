package kyo2

class CoroutineTest extends Test:

    "single pause" in {
        val effect = Coroutine.pause
        val result = Coroutine.run(effect)
        assert(result.eval == ())
    }

    "multiple pauses" in {
        val effect =
            for
                _ <- Coroutine.pause
                _ <- Coroutine.pause
                _ <- Coroutine.pause
            yield ()
        val result = Coroutine.run(effect)
        assert(result.eval == ())
    }

    "single spawn" in {
        var executed = false
        val effect   = Coroutine.spawn { executed = true }
        val result   = Coroutine.run(effect)
        assert(result.eval == ())
        assert(executed)
    }

    "spawn with value" in {
        val effect = Coroutine.spawn { () }
        val result = Coroutine.run(effect)
        assert(result.eval == ())
    }

    "spawn after pause" in {
        var executed = false
        val effect =
            for
                _ <- Coroutine.pause
                _ <- Coroutine.spawn { executed = true }
            yield ()
        val result = Coroutine.run(effect)
        assert(result.eval == ())
        assert(executed)
    }

    "nested spawns" in {
        var order = List.empty[Int]
        val effect =
            for
                _ <- Coroutine.spawn {
                    order = 1 :: order
                    Coroutine.spawn {
                        order = 2 :: order
                    }
                }
                _ <- Coroutine.spawn {
                    order = 3 :: order
                }
            yield ()

        val result = Coroutine.run(effect)
        assert(result.eval == ())
        assert(order == List(2, 3, 1))
    }

    "interleaved pauses and spawns" in {
        var order = List.empty[Int]
        val effect =
            for
                _ <- Coroutine.pause
                _ <- Coroutine.spawn {
                    order = 1 :: order
                }
                _ <- Coroutine.pause
                _ <- Coroutine.spawn {
                    order = 2 :: order
                }
            yield ()

        val result = Coroutine.run(effect)
        assert(result.eval == ())
        assert(order == List(2, 1))
    }

    "recursive coroutine" in {
        def recursive(n: Int): Unit < Coroutine[Any] =
            if n == 0 then Coroutine.pause
            else Coroutine.spawn { recursive(n - 1) }

        val effect = recursive(100000)
        val result = Coroutine.run(effect)
        assert(result.eval == ())
    }

    "nested coroutines" in run {
        var order = List.empty[Int]
        val effect =
            for
                _ <- Coroutine.spawn {
                    order = 1 :: order
                    Coroutine.spawn {
                        order = 2 :: order
                        Coroutine.spawn {
                            order = 3 :: order
                        }
                    }
                }
                _ <- Coroutine.pause
                _ <- Coroutine.spawn {
                    order = 4 :: order
                }
            yield ()

        val result = Coroutine.run(effect)
        assert(result.eval == ())
        assert(order == List(3, 4, 2, 1))
    }

    "spawn with side effects" in run {
        var a, b, c = 0
        val effect =
            for
                _ <- Coroutine.spawn { a = 1 }
                _ <- Coroutine.spawn { b = 2 }
                _ <- Coroutine.spawn { c = 3 }
            yield a + b + c

        val result = Coroutine.run(effect)
        assert(result.eval == 6)
    }

    "complex control flow" in run {
        var executed = List.empty[Int]
        def subRoutine(n: Int): Unit < Coroutine[Any] =
            for
                _ <- Coroutine.pause
                _ <- Coroutine.spawn { executed = n :: executed }
                _ <- if n > 0 then subRoutine(n - 1) else Coroutine.pause
            yield ()

        val effect =
            for
                _ <- Coroutine.spawn { executed = 1 :: executed }
                _ <- subRoutine(3)
                _ <- Coroutine.spawn { executed = 5 :: executed }
            yield ()

        val result = Coroutine.run(effect)
        assert(result.eval == ())
        assert(executed == List(5, 0, 1, 2, 3, 1))
    }

    "error propagation" in run {
        val effect =
            for
                _ <- Coroutine.spawn { throw new RuntimeException("Spawn error") }
                _ <- Coroutine.pause
                _ <- Coroutine.spawn { () } // This should not execute
            yield ()

        assertThrows[RuntimeException] {
            Coroutine.run(effect).eval
        }
    }

    "large number of coroutines" in run {
        val n   = 10000
        var sum = 0
        val effect =
            for
                _ <- Kyo.seq.collectUnit((1 to n).map(i =>
                    Coroutine.spawn {
                        sum += i
                    }
                ))
            yield sum

        val result = Coroutine.run(effect)
        assert(result.eval == (1 to n).sum)
    }

    "Coroutine with Env and Var" in run {
        val effect =
            for
                _ <- Coroutine.spawn {
                    for
                        env   <- Env.get[String]
                        _     <- Var.update[Int](_ + 1)
                        value <- Var.get[Int]
                        _     <- Coroutine.pause
                        _     <- Var.update[List[String]](s"$env-$value" :: _)
                    yield ()
                }
                _ <- Coroutine.spawn {
                    for
                        env   <- Env.get[String]
                        _     <- Var.update[Int](_ + 5)
                        value <- Var.get[Int]
                        _     <- Coroutine.pause
                        _     <- Var.update[List[String]](s"$env-$value" :: _)
                    yield ()
                }
            yield ()

        val result =
            Coroutine.run(effect)
                .andThen(Var.get[List[String]])
                .pipe(Var.run(List.empty[String]))
                .pipe(Var.run(0))
                .pipe(Env.run("env"))

        assert(result.eval.reverse == List("env-1", "env-6"))
    }

    "Coroutine with Abort and Choice" in run {
        val effect =
            for
                _ <- Coroutine.spawn {
                    for
                        _ <- Coroutine.pause
                        _ <- Choice.eval(List(1, 2, 3)) { n =>
                            if n == 2 then Choice.drop
                            else
                                Coroutine.spawn {
                                    Var.update[List[String]](s"Choice $n" :: _)
                                }
                        }
                    yield ()
                }
                _ <- Coroutine.spawn {
                    for
                        _ <- Coroutine.pause
                        _ <- Abort.when(false)("This should not abort")
                        _ <- Var.update[List[String]]("After Abort check" :: _)
                    yield ()
                }
            yield ()

        val result =
            Coroutine
                .run(effect)
                .andThen(Var.get[List[String]])
                .pipe(Abort.run(_))
                .pipe(Var.run(List.empty[String]))
                .pipe(Choice.run)

        assert(result.eval == Seq(
            Result.success(List("Choice 1", "After Abort check")),
            Result.success(List("Choice 3", "After Abort check"))
        ))
    }

end CoroutineTest
