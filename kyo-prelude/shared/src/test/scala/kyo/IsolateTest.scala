package kyo

class IsolateTest extends Test:

    "run" - {
        "with Var isolate" in run {
            val result = Var.runTuple(1) {
                Var.isolate.update[Int].run {
                    for
                        start <- Var.get[Int]
                        _     <- Var.set(start + 1)
                        end   <- Var.get[Int]
                    yield (start, end)
                }
            }
            assert(result.eval == (2, (1, 2)))
        }

        "with Memo isolate" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val result = Memo.run {
                Memo.isolate.merge.run {
                    for
                        a <- f(1)
                        b <- f(1)
                    yield (a, b, count)
                }
            }
            assert(result.eval == (2, 2, 1))
        }

        "with Emit isolate" in run {
            val result = Emit.run {
                Emit.isolate.merge[Int].run {
                    for
                        _ <- Emit.value(1)
                        _ <- Emit.value(2)
                    yield "done"
                }
            }
            assert(result.eval == (Chunk(1, 2), "done"))
        }
    }

    "andThen" - {
        "composing Var and Emit isolates" in run {
            val varIsolate  = Var.isolate.update[Int]
            val emitIsolate = Emit.isolate.merge[Int]

            val combined = varIsolate.andThen(emitIsolate)

            val result = Var.runTuple(1) {
                Emit.run {
                    combined.run {
                        for
                            start <- Var.get[Int]
                            _     <- Emit.value(start)
                            _     <- Var.set(start + 1)
                            end   <- Var.get[Int]
                            _     <- Emit.value(end)
                        yield (start, end)
                    }
                }
            }
            assert(result.eval == (2, (Chunk(1, 2), (1, 2))))
        }

        "composing Memo and Var isolates" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val memoIsolate = Memo.isolate.merge
            val varIsolate  = Var.isolate.update[Int]

            val combined = memoIsolate.andThen(varIsolate)

            val result = Memo.run {
                Var.runTuple(1) {
                    combined.run {
                        for
                            a <- f(1)
                            _ <- Var.set(a)
                            b <- f(1)
                            v <- Var.get[Int]
                        yield (a, b, v, count)
                    }
                }
            }
            assert(result.eval == (2, (2, 2, 2, 1)))
        }

        "composing three isolates" in run {
            val varIsolate  = Var.isolate.update[Int]
            val emitIsolate = Emit.isolate.merge[Int]
            val memoIsolate = Memo.isolate.merge

            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val combined = varIsolate.andThen(emitIsolate).andThen(memoIsolate)

            val result = Var.runTuple(1) {
                Emit.run {
                    Memo.run {
                        combined.run {
                            for
                                start <- Var.get[Int]
                                _     <- Emit.value(start)
                                a     <- f(start)
                                _     <- Var.set(a)
                                end   <- Var.get[Int]
                                _     <- Emit.value(end)
                                b     <- f(end)
                            yield (start, end, a, b, count)
                        }
                    }
                }
            }
            assert(result.eval == (2, (Chunk(1, 2), (1, 2, 2, 4, 2))))
        }
    }

    "enables transactional behavior via Abort interaction" - {
        "Var isolate rolls back on abort" in run {
            val result = Var.runTuple(0) {
                Abort.run {
                    for
                        start <- Var.get[Int]
                        _ <- Var.isolate.update[Int].run {
                            for
                                _ <- Var.set(start + 1)
                                _ <- Abort.fail("Failed")
                            yield ()
                        }
                        end <- Var.get[Int]
                    yield (start, end)
                }
            }
            assert(result.eval == (0, Result.fail("Failed")))
        }

        "Emit isolate discards emissions on abort" in run {
            val result = Emit.run {
                Abort.run {
                    for
                        _ <- Emit.value(1)
                        _ <- Emit.isolate.merge[Int].run {
                            for
                                _ <- Emit.value(2)
                                _ <- Emit.value(3)
                                _ <- Abort.fail("Failed")
                            yield ()
                        }
                        _ <- Emit.value(4)
                    yield "done"
                }
            }
            assert(result.eval == (Chunk(1), Result.fail("Failed")))
        }

        "Memo isolate preserves cache isolation on abort" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            val result = Memo.run {
                Abort.run {
                    for
                        a <- f(1)
                        _ <- Memo.isolate.merge.run {
                            for
                                b <- f(2)
                                _ <- Abort.fail("Failed")
                            yield b
                        }
                        c <- f(2)
                    yield (a, c)
                }
            }
            assert(result.eval == Result.fail("Failed"))
            assert(count == 2)
        }

        "nested isolates maintain transactional behavior" in run {
            val result = Var.runTuple(0) {
                Emit.run {
                    Abort.run {
                        for
                            _ <- Var.set(1)
                            _ <- Emit.value("start")
                            _ <-
                                Var.isolate.update[Int]
                                    .andThen(Emit.isolate.merge[String])
                                    .run {
                                        for
                                            _ <- Var.set(2)
                                            _ <- Emit.value("inner")
                                            _ <- (Var.isolate.update[Int].andThen(Emit.isolate.merge[String])).run {
                                                for
                                                    _ <- Var.set(3)
                                                    _ <- Emit.value("nested")
                                                    _ <- Abort.fail("Failed")
                                                yield ()
                                            }
                                        yield ()
                                    }
                            v <- Var.get[Int]
                            _ <- Emit.value("end")
                        yield v
                    }
                }
            }
            assert(result.eval == (1, (Chunk("start"), Result.fail("Failed"))))
        }

        "multiple effects maintain consistency on abort" in run {
            var memoCount = 0
            val f = Memo[Int, Int, Any] { x =>
                memoCount += 1
                x * 2
            }

            val result = Var.runTuple(0) {
                Emit.run {
                    Memo.run {
                        Abort.run {
                            for
                                _ <- Var.set(1)
                                _ <- Emit.value("start")
                                a <- f(1)
                                _ <- Var.isolate.update[Int]
                                    .andThen(Emit.isolate.merge[String])
                                    .andThen(Memo.isolate.merge)
                                    .run {
                                        for
                                            _ <- Var.set(2)
                                            _ <- Emit.value("inner")
                                            b <- f(2)
                                            _ <- Abort.fail("Failed")
                                        yield (a, b)
                                    }
                                v <- Var.get[Int]
                                _ <- Emit.value("end")
                            yield v
                        }
                    }
                }
            }
            assert(result.eval == (1, (Chunk("start"), Result.fail("Failed"))))
            assert(memoCount == 2)
        }
    }
end IsolateTest
