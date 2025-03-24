package kyo

import kyo.*

class PollTest extends Test:

    "one" - {
        "empty" in {
            val result = Poll.run(Chunk.empty)(Poll.one[Int])
            assert(result.eval == Maybe.empty)
        }

        "returns value from chunk" in {
            val result = Poll.run(Chunk(1, 2, 3))(Poll.one[Int])
            assert(result.eval == Maybe(1))
        }

        "multiple" in {
            val result = Poll.run(Chunk(1, 2)) {
                for
                    v1 <- Poll.one[Int]
                    v2 <- Poll.one[Int]
                    v3 <- Poll.one[Int]
                yield (v1, v2, v3)
            }
            assert(result.eval == (Maybe(1), Maybe(2), Maybe.empty))
        }

        "with nested effects" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                for
                    v1 <- Poll.one[Int]
                    v2 <- Env.use[Int](env => Poll.one[Int].map(_.map(_ + env)))
                yield (v1, v2)
            }
            assert(Env.run(10)(result).eval == (Maybe(1), Maybe(12)))
        }
    }

    "andMap" - {
        "transforms value" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                Poll.andMap[Int] { maybe =>
                    maybe.fold(Maybe.empty)(v => Maybe(v * 2))
                }
            }
            assert(result.eval == Maybe(2))
        }

        "handles empty chunk" in {
            val result = Poll.run(Chunk.empty[Int]) {
                Poll.andMap[Int] { maybe =>
                    maybe.fold(Maybe.empty)(v => Maybe(v * 2))
                }
            }
            assert(result.eval == Maybe.empty)
        }

        "with effects" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                Poll.andMap[Int] {
                    case Absent     => Absent
                    case Present(v) => Env.use[Int](env => Maybe(env + v))
                }
            }
            assert(Env.run(10)(result).eval == Maybe(11))
        }

        "with nested effects" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                Poll.andMap[Int] {
                    case Absent     => Absent
                    case Present(v) => Env.use[Int](env => Maybe(env * v))
                }
            }
            assert(Env.run(2)(result).eval == Maybe(2))
        }

        "with multiple transformations" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                for
                    v1 <- Poll.andMap[Int](_.map(_ * 2))
                    v2 <- Poll.andMap[Int](_.map(_ + 1))
                yield (v1, v2)
            }
            assert(result.eval == (Maybe(2), Maybe(3)))
        }
    }

    "fold" - {
        "accumulates values" in {
            val result = Poll.fold[Int](0)((acc, v) => acc + v)
            assert(Poll.run(Chunk(1, 2, 3))(result).eval == 6)
        }

        "empty chunk" in {
            val result = Poll.fold[Int](0)((acc, v) => acc + v)
            assert(Poll.run(Chunk.empty)(result).eval == 0)
        }

        "with effect" in {
            val result = Poll.fold[Int](0)((acc, v) => Env.use[Int](_ + acc + v))
            assert(Env.run(1)(Poll.run(Chunk(1, 2, 3))(result)).eval == 9)
        }

        "with complex state accumulation" in {
            val result = Poll.fold[Int](0)((acc, v) => acc + v * 2)
            assert(Poll.run(Chunk(1, 2, 3))(result).eval == 12)
        }

        "with conditional accumulation" in {
            val result = Poll.fold[Int](0) { (acc, v) =>
                if v % 2 == 0 then acc + v else acc
            }
            assert(Poll.run(Chunk(1, 2, 3, 4))(result).eval == 6)
        }

        "with nested effects and accumulation" in {
            val result = Poll.fold[Int](0) { (acc, v) =>
                Env.use[Int] { env =>
                    Var.update[Int](_ + 1).map(_ => acc + v * env)
                }
            }
            val finalResult = Env.run(2)(Var.runTuple(0)(Poll.run(Chunk(1, 2, 3))(result)))
            assert(finalResult.eval == (3, 12))
        }
    }

    "runFirst" - {
        "basic operation" in run {
            Abort.run {
                for
                    cont1  <- Poll.runFirst(Poll.fold[Int](0)(_ + _)).map(Abort.get(_))
                    cont2  <- Poll.runFirst(cont1(Maybe(1))).map(Abort.get(_))
                    cont3  <- Poll.runFirst(cont2(Maybe(2))).map(Abort.get(_))
                    result <- Poll.run(Chunk(4, 5, 6))(cont3(Maybe.empty))
                yield assert(result == 3)
                end for
            }.map { r =>
                assert(r.isSuccess)
            }
        }

        "empty" in run {
            Abort.run {
                for
                    cont1  <- Poll.runFirst(Poll.andMap[Int](_ => 42)).map(Abort.get(_))
                    result <- Poll.run(Chunk.empty)(cont1(Maybe.empty))
                yield assert(result == 42)
                end for
            }.map { r =>
                assert(r.isSuccess)
            }
        }

        "with effects" in run {
            Abort.run {
                for
                    result <- Var.runTuple(0) {
                        for
                            cont1  <- Poll.runFirst(Poll.one[Int].map(_ => Var.update[Int](_ + 1))).map(Abort.get(_))
                            cont2  <- Poll.runFirst(cont1(Maybe(1))).map(Abort.get(_))
                            result <- Poll.run(Chunk.empty)(cont2(Maybe.empty))
                        yield result
                    }
                yield ()
                end for
            }.map { r =>
                assert(r.isFailure)
            }
        }
    }

    "run Chunk" - {

        "with empty chunk" in {
            val result = Poll.run(Chunk.empty)(Poll.one[Int])
            assert(result.eval.isEmpty)
        }

        "with multiple values" in {
            val result = Poll.run(Chunk(1, 2, 3))(Poll.andMap[Int](_.map(_ * 2)))
            assert(result.eval == Maybe(2))
        }

        "with nested polls" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                for
                    v1 <- Poll.one[Int]
                    v2 <- Poll.run(Chunk(v1.getOrElse(0) * 2)) {
                        Poll.one[Int]
                    }
                yield (v1, v2)
            }
            assert(result.eval == (Maybe(1), Maybe(2)))
        }
    }

    "run Emit" - {
        "one" in {
            val result = Poll.run(Emit.value(1))(Poll.one[Int])
            assert(result.eval == ((), Maybe(1)))
        }

        "two" in {
            val result = Poll.run(Emit.value(1).andThen(Emit.value(2))) {
                Poll.andMap[Int] {
                    case Absent     => Maybe.empty
                    case Present(v) => Maybe(v * 3)
                }
            }
            assert(result.eval == ((), Maybe(3)))
        }

        "basic emit-poll cycle" in run {
            val emitter =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "emitted"

            val poller =
                for
                    v1 <- Poll.one[Int]
                    v2 <- Poll.one[Int]
                yield (v1, v2)

            val result = Poll.run(emitter)(poller)
            assert(result.eval == ("emitted", (Maybe(1), Maybe(2))))
        }

        "early poller termination" in run {
            val emitter =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "emitted"

            val poller = Poll.one[Int]

            val result = Poll.run(emitter)(poller)
            assert(result.eval == ("emitted", Maybe(1)))
        }

        "fold with emit" in run {
            val emitter =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "done"

            val poller = Poll.fold[Int](0)(_ + _)

            val result = Poll.run(emitter)(poller)
            assert(result.eval == ("done", 6))
        }

        "interleaved effects" in run {
            var count = 0

            val emitter =
                for
                    _ <- Emit.value(1)
                    _ <- Var.update[Int](_ + 1)
                    _ <- Emit.value(2)
                    _ <- Var.update[Int](_ + 1)
                yield "emitted"

            val poller =
                for
                    v1 <- Poll.one[Int]
                    _  <- Var.update[Int](_ + 1)
                    v2 <- Poll.one[Int]
                yield (v1, v2)

            val result = Var.runTuple(0) {
                Poll.run(emitter)(poller)
            }
            assert(result.eval == (3, ("emitted", (Maybe(1), Maybe(2)))))
        }
    }

end PollTest
