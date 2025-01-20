package kyo

import kyo.Ack.*

class StreamTest extends Test:

    val n = 100000

    def chunkSizes[V: Tag, S](stream: Stream[V, S]): Chunk[Int] < S =
        stream.mapChunk(chunk => Chunk(chunk.size)).run

    "empty" in {
        assert(
            Stream.empty[Int].run.eval == Seq.empty
        )
    }

    "init" - {
        "empty" in {
            assert(
                Stream.init(Seq[Int]()).run.eval == Seq.empty
            )
        }

        "non-empty" in {
            assert(
                Stream.init(Seq(1, 2, 3)).run.eval == Seq(1, 2, 3)
            )
        }

        "lazy" in {
            var i = 0
            val _ = Stream.init {
                i += 1
                Seq.empty[Int]
            }

            assert(i == 0)
        }

        "chunk size" in {
            def size(n: Int, c: Int): Chunk[Int] =
                chunkSizes(Stream.init(Seq.fill(n)(""), chunkSize = c)).eval

            assert(size(10240, 4096) == Chunk(4096, 4096, 2048))
            assert(size(301, 100) == Chunk(100, 100, 100, 1))
            assert(size(5, 0) == Chunk(1, 1, 1, 1, 1))
        }
    }

    "initChunk" - {
        "empty" in {
            assert(
                Stream.init(Chunk.empty[Int]).run.eval == Seq.empty
            )
        }

        "non-empty" in {
            assert(
                Stream.init(Chunk(1, 2, 3)).run.eval == Seq(1, 2, 3)
            )
        }
    }

    "range" - {
        "empty" in {
            assert(Stream.range(0, 0).run.eval == Seq.empty)
        }

        "negative" in {
            assert(Stream.range(0, -10).run.eval == (0 until -10))
            assert(Stream.range(0, -10, step = -1).run.eval == (0 until -10 by -1))
        }

        "positive" in {
            assert(Stream.range(0, 1024).run.eval == (0 until 1024))
        }

        "step" - {
            "zero" in {
                assert(Stream.range(0, 1024, 0).run.eval == Seq.empty)
            }

            "positive" in {
                assert(Stream.range(0, 1024, 2).run.eval == (0 until 1024 by 2))
            }

            "negative" in {
                assert(Stream.range(0, -10, -2).run.eval == (0 until -10 by -2))
            }
        }

        "chunk size" in {
            def size(n: Int, c: Int): Chunk[Int] =
                chunkSizes(Stream.range(0, n, chunkSize = c)).eval

            assert(size(10240, 4096) == Chunk(4096, 4096, 2048))
            assert(size(301, 100) == Chunk(100, 100, 100, 1))
        }

        "stack safety" in {
            assert(Stream.range(0, n).take(5).run.eval == (0 until 5))
        }
    }

    "take" - {
        "zero" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(0).run.eval == Seq.empty
            )
        }

        "negative" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(-1).run.eval == Seq.empty
            )
        }

        "two" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(2).run.eval == Seq(1, 2)
            )
        }

        "more than available" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(5).run.eval == Seq(1, 2, 3)
            )
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(100000)(1)).take(5).run.eval ==
                    Seq.fill(5)(1)
            )
        }

        "emit one at a time" in {
            def emit(ack: Ack): Ack < (Emit[Chunk[Int]] & Var[Int]) =
                ack match
                    case Stop => Stop: Ack < Any
                    case Continue(maxItems) =>
                        for
                            n    <- Var.update[Int](_ + 1)
                            next <- Emit.valueWith(Chunk(n))(emit)
                        yield next
            end emit

            val stream         = Stream(Emit.valueWith(Chunk.empty[Int])(emit(_))).take(5).run
            val (count, chunk) = Var.runTuple(0)(stream).eval

            assert(
                count == 5 && chunk == (1 to 5)
            )
        }

        "exact amount" in {
            def emit(ack: Ack): Ack < (Emit[Chunk[Int]] & Var[Int]) =
                ack match
                    case Stop => Stop: Ack < Any
                    case Continue(maxItems) =>
                        for
                            end <- Var.update[Int](_ + maxItems)
                            start = end - maxItems + 1
                            next <- Emit.valueWith(Chunk.from(start to end): Chunk[Int])(emit)
                        yield next
                        end for
            end emit

            val stream         = Stream(Emit.valueWith(Chunk.empty[Int])(emit(_))).take(5).run
            val (count, chunk) = Var.runTuple(0)(stream).eval

            assert(
                count == 5 && chunk == (1 to 5)
            )
        }
    }

    "drop" - {
        "zero" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(0).run.eval == Seq(1, 2, 3)
            )
        }

        "negative" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(-1).run.eval == Seq(1, 2, 3)
            )
        }

        "two" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(2).run.eval == Seq(3)
            )
        }

        "more than available" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(5).run.eval == Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).drop(5).run.eval.size == n - 5
            )
        }

        "chunk smaller than n" in {
            val small = Stream.init(0 until 5)

            val result =
                small
                    .concat(small)
                    .drop(6)
                    .run
                    .eval

            assert(result == Seq(1, 2, 3, 4))
        }
    }

    "takeWhile" - {
        "take none" in {
            assert(
                Stream.init(Seq(1, 2, 3)).takeWhile(_ < 0).run.eval == Seq.empty
            )
        }

        "take some" in {
            assert(
                Stream.init(Seq(1, 2, 3, 4, 5)).takeWhile(_ < 4).run.eval ==
                    Seq(1, 2, 3)
            )
        }

        "take all" in {
            assert(
                Stream.init(Seq(1, 2, 3)).takeWhile(_ < 10).run.eval ==
                    Seq(1, 2, 3)
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).takeWhile(_ => true).run.eval ==
                    Seq.empty
            )
        }

        "with effects" in {
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val taken = stream.takeWhile { v =>
                Var.update[Int](_ + 1).map(_ < 4)
            }.run
            assert(Var.runTuple(0)(taken).eval == (4, Seq(1, 2, 3)))
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).takeWhile(_ == 1).run.eval ==
                    Seq.fill(n)(1)
            )
        }
    }

    "dropWhile" - {
        "drop none" in {
            assert(
                Stream.init(Seq(1, 2, 3)).dropWhile(_ < 0).run.eval ==
                    Seq(1, 2, 3)
            )
        }

        "drop some" in {
            assert(
                Stream.init(Seq(1, 2, 3, 4, 5)).dropWhile(_ < 4).run.eval ==
                    Seq(4, 5)
            )
        }

        "drop all" in {
            assert(
                Stream.init(Seq(1, 2, 3)).dropWhile(_ < 10).run.eval ==
                    Seq.empty
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).dropWhile(_ => false).run.eval ==
                    Seq.empty
            )
        }

        "with effects" in {
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val dropped = stream.dropWhile { v =>
                Var.update[Int](_ + 1).map(_ < 3)
            }.run
            assert(Var.runTuple(0)(dropped).eval == (3, Seq(3, 4, 5)))
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1) ++ Seq(2)).dropWhile(_ == 1).run.eval ==
                    Seq(2)
            )
        }
    }

    "filter" - {
        "non-empty" in {
            assert(
                Stream.init(Seq(1, 2, 3)).filter(_ % 2 == 0).run.eval ==
                    Seq(2)
            )
        }

        "all in" in {
            assert(
                Stream.init(Seq(1, 2, 3)).filter(_ => true).run.eval ==
                    Seq(1, 2, 3)
            )
        }

        "all out" in {
            assert(
                Stream.init(Seq(1, 2, 3)).filter(_ => false).run.eval ==
                    Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(1 to n).filter(_ % 2 == 0).run.eval.size ==
                    n / 2
            )
        }
    }

    "changes" - {
        "no duplicates" in {
            assert(
                Stream.init(Seq(1, 2, 3)).changes.run.eval ==
                    Seq(1, 2, 3)
            )
        }

        "with duplicates" in {
            assert(
                Stream.init(Seq(1, 2, 2, 3, 2, 3, 3)).changes.run.eval ==
                    Seq(1, 2, 3, 2, 3)
            )
        }

        "with initial value" in {
            assert(
                Stream.init(Seq(1, 2, 2, 3, 2, 3, 3)).changes(1).run.eval ==
                    Seq(2, 3, 2, 3)
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).changes.run.eval ==
                    Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).changes.run.eval ==
                    Seq(1)
            )
        }
    }

    "map" - {
        "double" in {
            assert(
                Stream.init(Seq(1, 2, 3)).map(_ * 2).run.eval == Seq(2, 4, 6)
            )
        }

        "to string" in {
            assert(
                Stream.init(Seq(1, 2, 3)).map(_.toString).run.eval ==
                    Seq("1", "2", "3")
            )
        }

        "with effects" in {
            val stream      = Stream.init(Seq(1, 2, 3))
            val transformed = stream.map(v => Env.use[Int](v * _)).run
            assert(Env.run(2)(transformed).eval == Seq(2, 4, 6))
        }

        "with failures" in {
            val stream      = Stream.init(Seq("1", "2", "abc", "3"))
            val transformed = stream.map(v => Abort.catching[NumberFormatException](v.toInt)).run
            assert(Abort.run(transformed).eval.isFail)
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).map(_ + 1).run.eval ==
                    Seq.fill(n)(2)
            )
        }
        "produce until" in {
            var counter = 0
            val result =
                Stream
                    .init(0 until 100)
                    .map(_ => counter += 1)
                    .take(0)
                    .run
                    .eval
            assert(counter == 0)
            assert(result.isEmpty)
        }
    }

    "mapChunk" - {
        "double" in {
            assert(
                Stream.init(Seq(1, 2, 3)).mapChunk(_.take(2).map(_ * 2)).run.eval == Seq(2, 4)
            )
        }

        "to string" in {
            assert(
                Stream.init(Seq(1, 2, 3)).mapChunk(_.append(4).map(_.toString)).run.eval ==
                    Seq("1", "2", "3", "4")
            )
        }

        "with effects" in {
            val stream      = Stream.init(Seq(1, 2, 3))
            val transformed = stream.mapChunk(v => Env.use[Int](i => v.map(_ * i))).run
            assert(Env.run(2)(transformed).eval == Seq(2, 4, 6))
        }

        "with failures" in {
            val stream      = Stream.init(Seq("1", "2", "abc", "3"))
            val transformed = stream.mapChunk(c => Abort.catching[NumberFormatException](c.map(_.toInt))).run
            assert(Abort.run(transformed).eval.isFail)
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).mapChunk(_.map(_ + 1)).run.eval ==
                    Seq.fill(n)(2)
            )
        }
        "produce until" in {
            var counter = 0
            val result =
                Stream
                    .init(0 until 100)
                    .mapChunk(_.map(_ => counter += 1))
                    .take(0)
                    .run
                    .eval
            assert(counter == 0)
            assert(result.isEmpty)
        }
    }

    "flatMap" - {
        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).flatMap(i => Stream.init(Seq(i, i + 1))).run.eval == Seq.empty
            )
        }

        "one-to-many transformation" in {
            assert(
                Stream.init(Seq(1, 2, 3)).flatMap(i => Stream.init(Seq(i, i + 1))).run.eval == Seq(1, 2, 2, 3, 3, 4)
            )
        }

        "nested effects" in {
            val result = Env.run(10) {
                Stream.init(Seq(1, 2, 3))
                    .flatMap(i => Stream.init(Seq(i, i + 1)).map(j => Env.use[Int](_ + j)))
                    .run
            }
            assert(result.eval == Seq(11, 12, 12, 13, 13, 14))
        }

        "early termination" in {
            val result = Abort.run[String] {
                Stream.init(Seq(1, 2, 3))
                    .flatMap(i =>
                        if i > 2 then Abort.fail("Too large")
                        else Stream.init(Seq(i, i + 1))
                    )
                    .run
            }
            assert(result.eval == Result.fail("Too large"))
        }

        "chunked input" in {
            val input  = Stream.init(Chunk(1, 2, 3))
            val result = input.flatMap(i => Stream.init(Seq(i, i * 10))).run
            assert(result.eval == Seq(1, 10, 2, 20, 3, 30))
        }
    }

    "flatMapChunk" - {
        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).flatMapChunk(c => Stream.init(c.map(_ + 1))).run.eval == Seq.empty
            )
        }

        "chunk transformation" in {
            val result = Stream.init(Seq(1, 2, 3))
                .flatMapChunk(c => Stream.init(c.map(_ * 2) :+ 0))
                .run
            assert(result.eval == Seq(2, 4, 6, 0))
        }

        "nested effects" in {
            val result = Env.run(10) {
                Stream.init(Seq(1, 2, 3))
                    .flatMapChunk(c => Env.use[Int](i => Stream.init(c.map(_ + i))))
                    .run
            }
            assert(result.eval == Seq(11, 12, 13))
        }

        "early termination" in {
            val result = Abort.run[String] {
                Stream.init(Seq(1, 2, 3))
                    .flatMapChunk(c =>
                        if !c.filter(_ > 2).isEmpty then Abort.fail("Too large")
                        else Stream.init(c.map(_ * 2))
                    )
                    .run
            }
            assert(result.eval == Result.fail("Too large"))
        }

        "combining multiple chunks" in {
            val input = Stream.init(Chunk(1, 2, 3))
            val result = input.flatMapChunk(c1 =>
                Stream.init(Chunk(4, 5, 6)).flatMapChunk(c2 =>
                    Stream.init(c1.concat(c2))
                )
            ).run
            assert(result.eval == Seq(1, 2, 3, 4, 5, 6))
        }
    }

    "tap" - {
        "non-empty stream" in {
            val stream = Stream
                .init(Seq(1, 2, 3))
                .tap(i => Var.update[Int](_ + i).unit)
            assert(Var.runTuple(0)(stream.run).eval == (6, Seq(1, 2, 3)))
        }
        "empty stream" in {
            val stream = Stream
                .empty[Int]
                .tap(i => Var.update[Int](_ + i).unit)
            assert(Var.runTuple(0)(stream.run).eval == (0, Seq()))
        }
    }

    "tapChunk" - {
        "non-empty stream" in {
            val stream = Stream
                .apply(Emit.valueWith(Chunk(1, 2, 3))(_ => Emit.value(Chunk(4, 5, 6))))
                .tapChunk(c => Var.update[Int](_ + c.sum).unit)
            assert(Var.runTuple(0)(stream.run).eval == (21, Seq(1, 2, 3, 4, 5, 6)))
        }
        "empty stream" in {
            val stream = Stream
                .empty[Int]
                .tapChunk(c => Var.update[Int](_ + c.sum).unit)
            assert(Var.runTuple(0)(stream.run).eval == (0, Seq()))
        }
    }

    "concat" - {
        "non-empty streams" in {
            assert(
                Stream.init(Seq(1, 2, 3))
                    .concat(Stream.init(Seq(4, 5, 6)))
                    .run.eval == Seq(1, 2, 3, 4, 5, 6)
            )
        }

        "empty left stream" in {
            assert(
                Stream.init(Seq.empty[Int])
                    .concat(Stream.init(Seq(1, 2, 3)))
                    .run.eval == Seq(1, 2, 3)
            )
        }

        "empty right stream" in {
            assert(
                Stream.init(Seq(1, 2, 3))
                    .concat(Stream.init(Seq.empty[Int]))
                    .run.eval == Seq(1, 2, 3)
            )
        }

        "both streams empty" in {
            assert(
                Stream.init(Seq.empty[Int])
                    .concat(Stream.init(Seq.empty[Int]))
                    .run.eval == Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(1 to n)
                    .concat(Stream.init(n + 1 to 2 * n))
                    .run.eval == (1 to (2 * n))
            )
        }
    }

    "rechunk" - {
        "negative" in {
            val sizes = chunkSizes(Stream.init(1 until 5).rechunk(-10)).eval
            assert(sizes == Chunk(1, 1, 1, 1))
        }

        "smaller" in {
            val sizes = chunkSizes(Stream.range(1, 5000, step = 2).rechunk(1024)).eval
            assert(sizes == Chunk(1024, 1024, 452))
        }

        "same" in {
            val sizes = chunkSizes(Stream.range(0, 250, chunkSize = 100).rechunk(100)).eval
            assert(sizes == Chunk(100, 100, 50))
        }

        "larger" in {
            val sizes = chunkSizes(Stream.range(0, 5001).rechunk(5000)).eval
            assert(sizes == Chunk(5000, 1))
        }

        "larger than stream" in {
            val sizes = chunkSizes(Stream.range(0, 100_000).rechunk(500_000)).eval
            assert(sizes == Chunk(100_000))
        }

        "with effects" in {
            val stream = Stream.range(0, 100).map(i => Env.use[Int](_ + i)).rechunk(48)
            val result = Env.run(10)(stream.run).eval
            val chunks = Env.run(10)(chunkSizes(stream)).eval
            assert(result == (10 until 110))
            assert(chunks == Chunk(48, 48, 4))
        }

        "order" in {
            val stream = Stream.range(0, 100).rechunk(8)
            assert(stream.run.eval == (0 until 100))
        }

        "empty chunks" in {
            def emit(n: Int): Ack < (Emit[Chunk[Int]]) =
                n match
                    case 0 => Stop
                    case 5 => Emit.valueWith(Chunk.empty)(_ => emit(n - 1))
                    case _ => Emit.valueWith(Chunk.fill(3)(n))(_ => emit(n - 1))
            end emit

            val stream = Stream(emit(10)).rechunk(10).mapChunk(Chunk(_))
            assert(stream.run.eval == Chunk(
                Chunk(10, 10, 10, 9, 9, 9, 8, 8, 8, 7),
                Chunk(7, 7, 6, 6, 6), // empty chunk causes buffer flush
                Chunk(4, 4, 4, 3, 3, 3, 2, 2, 2, 1),
                Chunk(1, 1)
            ))
        }
    }

    "runDiscard" - {
        "non-empty stream" in {
            assert(
                Var.run(0) {
                    Stream.init(0 until 100).map(i => Var.update[Int](_ + i)).runDiscard.andThen(Var.get[Int])
                }.eval == 4950
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).runDiscard.eval == ()
            )
        }
    }

    "runForeach" - {
        "executes the function for each element" in run {
            var sum = 0
            Stream.init(Seq(1, 2, 3, 4, 5)).runForeach(i => sum += i).map { _ =>
                assert(sum == 15)
            }
        }

        "works with empty stream" in run {
            var executed = false
            Stream.init(Seq.empty[Int]).runForeach(_ => executed = true).map { _ =>
                assert(!executed)
            }
        }

        "works with effects" in run {
            var sum = 0
            Stream.init(Seq(1, 2, 3, 4, 5)).runForeach { i =>
                Env.use[Int] { multiplier =>
                    sum += i * multiplier
                }
            }.pipe(Env.run(2)).map { _ =>
                assert(sum == 30)
            }
        }

        "short-circuits on abort" in run {
            var sum = 0
            val result = Abort.run[String] {
                Stream.init(Seq(1, 2, 3, 4, 5)).runForeach { i =>
                    sum += i
                    if i >= 3 then Abort.fail("Reached 3")
                    else Abort.get(Right(()))
                }
            }
            result.map { r =>
                assert(r == Result.fail("Reached 3"))
                assert(sum == 6)
            }
        }
    }

    "runForeachChunk" - {
        "executes the function for each chunk" in run {
            var sum = 0
            Stream.init(Chunk(1, 2, 3, 4, 5)).runForeachChunk(chunk =>
                sum += chunk.foldLeft(0)(_ + _)
            ).map { _ =>
                assert(sum == 15)
            }
        }

        "works with empty stream" in run {
            var executed = false
            Stream.init(Chunk.empty[Int]).runForeachChunk(_ => executed = true).map { _ =>
                assert(!executed)
            }
        }

        "works with effects" in run {
            var sum = 0
            Stream.init(Chunk(1, 2, 3, 4, 5)).runForeachChunk { chunk =>
                Env.use[Int] { multiplier =>
                    sum += chunk.foldLeft(0)(_ + _) * multiplier
                }
            }.pipe(Env.run(2)).map { _ =>
                assert(sum == 30)
            }
        }
    }

    "runFold" - {
        "sum" in {
            assert(
                Stream.init(Seq(1, 2, 3)).runFold(0)(_ + _).eval == 6
            )
        }

        "concat" in {
            assert(
                Stream.init(Seq("a", "b", "c")).runFold("")(_ + _).eval == "abc"
            )
        }

        "early termination" in {
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val folded = stream.runFold(0) { (acc, v) =>
                if acc < 6 then acc + v else Abort.fail(())
            }
            assert(Abort.run[Unit](folded).eval.fold(_ => true)(_ => false))
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).runFold(0)(_ + _).eval == n
            )
        }
    }

    "nesting with other effect" in {
        val stream: Stream[Int, Any] < Env[Seq[Int]] =
            Env.use[Seq[Int]](seq => Stream.init(seq))
        Env.run(Seq(1, 2, 3))(stream.map(_.run)).eval
        succeed
    }

    "edge cases" - {

        "flatMap with nested aborts" in {
            val result = Abort.run[String] {
                Stream.init(Seq(1, 2, 3))
                    .flatMap(i =>
                        Stream.init(Seq(i, i + 1)).flatMap(j =>
                            if j > 3 then Abort.fail(s"Value too large: $j")
                            else Stream.init(Seq(j, j * 10))
                        )
                    )
                    .run
            }
            assert(result.eval == Result.fail("Value too large: 4"))
        }

        "flatMapChunk with alternating aborts" in {
            var abortCounter = 0
            val result = Abort.run[String] {
                Stream.init(Seq(1, 2, 3, 4, 5))
                    .flatMapChunk(c =>
                        if abortCounter % 2 == 0 then
                            abortCounter += 1
                            Stream.init(Kyo.foreach(c)(i => if i > 3 then Abort.fail(s"Odd abort: $i") else i))
                        else
                            abortCounter += 1
                            Stream.init(c)
                    )
                    .run
            }
            assert(result.eval == Result.fail("Odd abort: 4"))
        }

        "flatMap with env-dependent abort" in {
            val result = Env.run(3) {
                Abort.run[String] {
                    Stream.init(Seq(1, 2, 3, 4, 5))
                        .flatMap(i =>
                            Stream.init(Seq(i)).flatMap(j =>
                                Env.use[Int](threshold =>
                                    if j > threshold then Abort.fail(s"Exceeded threshold $threshold: $j")
                                    else Stream.init(Chunk(j))
                                )
                            )
                        )
                        .run
                }
            }
            assert(result.eval == Result.fail("Exceeded threshold 3: 4"))
        }

        "flatMap with interleaved effects" in {
            var sum = 0
            val result = Env.run(10) {
                Stream.init(Seq(1, 2, 3))
                    .flatMap(i =>
                        Stream.init(Seq(i, i + 1)).map { j =>
                            sum += j
                            Env.use[Int](env => if sum > env then Abort.fail("Sum too large") else j)
                        }
                    )
                    .run
            }
            assert(Abort.run(result).eval == Result.fail("Sum too large"))
        }

        "nested flatMap with alternating effects" in run {
            var counter = 0
            val stream  = Stream.init(Seq(1, 2, 3, 4, 5))
            val result = Abort.run[String] {
                stream.flatMap { n =>
                    counter += 1
                    if counter % 2 == 0 then
                        Abort.fail(s"Even counter: $counter")
                    else
                        Stream.init(Seq(n, n * 10)).map { m =>
                            if m > 20 then Abort.fail(s"Value too large: $m")
                            else Env.use[Int](_ + m)
                        }
                    end if
                }.run
            }
            assert(Env.run(0)(result).eval == Result.fail("Even counter: 2"))
            assert(counter == 2)
        }

        "mapChunk with state-dependent abort" in run {
            val stream = Stream.init(Chunk(1, 2, 3, 4, 5))
            val result = Abort.run[String] {
                Var.run(0) {
                    stream.mapChunk { chunk =>
                        Var.update[Int](_ + chunk.size).map { newState =>
                            if newState > 3 then Abort.fail(s"State too high: $newState")
                            else chunk.map(_ * newState)
                        }
                    }.run
                }
            }
            assert(result.eval == Result.fail("State too high: 5"))
        }

        "flatMap with env-dependent chunking" in run {
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val result = Env.run(2) {
                stream.flatMap { n =>
                    Env.use[Int] { chunkSize =>
                        Stream.init(Chunk.fill(chunkSize)(n))
                    }
                }.run
            }
            assert(result.eval == Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5))
        }

        "take with nested aborts and environment" in run {
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val result = Env.run(3) {
                Abort.run[String] {
                    stream.take(4).flatMap { n =>
                        Env.use[Int] { limit =>
                            if n > limit then Abort.fail(s"Value $n exceeds limit $limit")
                            else Stream.init(Seq(n, n * 10))
                        }
                    }.run
                }
            }
            assert(result.eval == Result.fail("Value 4 exceeds limit 3"))
        }
    }

end StreamTest
