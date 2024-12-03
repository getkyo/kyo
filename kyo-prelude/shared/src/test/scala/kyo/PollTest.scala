package kyo

import kyo.*
import kyo.kernel.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class PollTest extends Test:

    "one" - {
        "with acknowledgment" in {
            val result = Poll.run(Chunk.empty)(Poll.one[Int](Ack.Continue()))
            assert(result.eval == Maybe.empty)
        }

        "without explicit acknowledgment" in {
            val result = Poll.run(Chunk.empty)(Poll.one[Int])
            assert(result.eval == Maybe.empty)
        }

        "returns value from chunk" in {
            val result = Poll.run(Chunk(1, 2, 3))(Poll.one[Int])
            assert(result.eval == Maybe(1))
        }

        "with multiple acknowledgments" in {
            val result = Poll.run(Chunk(1, 2, 3)) {
                for
                    v1 <- Poll.one[Int](Ack.Continue())
                    v2 <- Poll.one[Int](Ack.Stop)
                yield (v1, v2)
            }
            assert(result.eval == (Maybe(1), Maybe.empty))
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
            for
                (v1, cont1) <- Poll.runFirst(Poll.fold[Int](0)(_ + _))
                (v2, cont2) <- Poll.runFirst(cont1(Maybe(1)))
                (v3, cont3) <- Poll.runFirst(cont2(Maybe(2)))
                result      <- Poll.run(Chunk(4, 5, 6))(cont3(Maybe.empty))
            yield
                assert(v1 != Ack.Stop && v2 != Ack.Stop && v3 != Ack.Stop)
                assert(result == 3)
            end for
        }

        "empty" in run {
            for
                (v1, cont1) <- Poll.runFirst(Poll.andMap[Int](Ack.Stop)(_ => 42))
                result      <- Poll.run(Chunk.empty)(cont1(Maybe.empty))
            yield assert(result == 42)
            end for
        }

        "with effects" in run {
            for
                result <- Var.runTuple(0) {
                    for
                        (v1, cont1) <- Poll.runFirst(Poll.one[Int].map(_ => Var.update[Int](_ + 1)))
                        (v2, cont2) <- Poll.runFirst(cont1(Maybe(1)))
                        result      <- Poll.run(Chunk.empty)(cont2(Maybe.empty))
                    yield result
                }
            yield
                val (counter, _) = result
                assert(counter == 1)
            end for
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

        "early termination with Ack.Stop" in {
            val result = Poll.run(Chunk(1, 2, 3, 4)) {
                Poll.one[Int](Ack.Stop)
            }
            assert(result.eval.isEmpty)
        }

        "with conditional acknowledgments" in {
            val result = Poll.run(Chunk(1, 2, 3, 4)) {
                for
                    v1 <- Poll.one[Int](Ack.Continue())
                    v2 <- Poll.one[Int](if v1.contains(1) then Ack.Stop else Ack.Continue())
                yield (v1, v2)
            }
            assert(result.eval == (Maybe(1), Maybe.empty))
        }

        "with effects and acknowledgments" in {
            val result = Env.run(2) {
                Poll.run(Chunk(1, 2, 3, 4)) {
                    for
                        threshold <- Env.get[Int]
                        v1        <- Poll.one[Int](Ack.Continue())
                        v2        <- Poll.one[Int](if v1.exists(_ < threshold) then Ack.Continue() else Ack.Stop)
                    yield (v1, v2)
                }
            }
            assert(result.eval == (Maybe(1), Maybe(2)))
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
            val result = Poll.run(Emit(1))(Poll.one[Int])
            assert(result.eval == (Ack.Continue(), Maybe(1)))
        }

        "two" in {
            val result = Poll.run(Emit(1).andThen(Emit(2))) {
                Poll.andMap[Int] {
                    case Absent     => Maybe.empty
                    case Present(v) => Maybe(v * 3)
                }
            }
            assert(result.eval == (Ack.Stop, Maybe(3)))
        }

        "basic emit-poll cycle" in run {
            val emitter =
                for
                    _ <- Emit(1)
                    _ <- Emit(2)
                    _ <- Emit(3)
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
                    ack1 <- Emit(1)
                    ack2 <- Emit(2)
                    ack3 <- Emit(3)
                yield (ack1, ack2, ack3, "emitted")

            val poller = Poll.one[Int](Ack.Stop)

            val result = Poll.run(emitter)(poller)
            assert(result.eval == ((Ack.Stop, Ack.Stop, Ack.Stop, "emitted"), Maybe(1)))
        }

        "fold with emit" in run {
            val emitter =
                for
                    _ <- Emit(1)
                    _ <- Emit(2)
                    _ <- Emit(3)
                yield "done"

            val poller = Poll.fold[Int](0)(_ + _)

            val result = Poll.run(emitter)(poller)
            assert(result.eval == ("done", 6))
        }

        "interleaved effects" in run {
            var count = 0

            val emitter =
                for
                    _ <- Emit(1)
                    _ <- Var.update[Int](_ + 1)
                    _ <- Emit(2)
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
