package kyo

class StreamTest extends Test:

    val n = 10000

    "init" - {
        "empty" in {
            assert(
                Stream.init(Seq[Int]()).runSeq.eval == Seq.empty
            )
        }

        "non-empty" in {
            assert(
                Stream.init(Seq(1, 2, 3)).runSeq.eval == Seq(1, 2, 3)
            )
        }
    }

    "initChunk" - {
        "empty" in {
            assert(
                Stream.init(Chunk.empty[Int]).runSeq.eval == Seq.empty
            )
        }

        "non-empty" in {
            assert(
                Stream.init(Chunk(1, 2, 3)).runSeq.eval == Seq(1, 2, 3)
            )
        }
    }

    "take" - {
        "zero" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(0).runSeq.eval == Seq.empty
            )
        }

        "negative" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(-1).runSeq.eval == Seq.empty
            )
        }

        "two" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(2).runSeq.eval == Seq(1, 2)
            )
        }

        "more than available" in {
            assert(
                Stream.init(Seq(1, 2, 3)).take(5).runSeq.eval == Seq(1, 2, 3)
            )
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(100000)(1)).take(5).runSeq.eval ==
                    Seq.fill(5)(1)
            )
        }
    }

    "drop" - {
        "zero" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(0).runSeq.eval == Seq(1, 2, 3)
            )
        }

        "negative" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(-1).runSeq.eval == Seq(1, 2, 3)
            )
        }

        "two" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(2).runSeq.eval == Seq(3)
            )
        }

        "more than available" in {
            assert(
                Stream.init(Seq(1, 2, 3)).drop(5).runSeq.eval == Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).drop(5).runSeq.eval.size == n - 5
            )
        }
    }

    "takeWhile" - {
        "take none" in {
            assert(
                Stream.init(Seq(1, 2, 3)).takeWhile(_ < 0).runSeq.eval == Seq.empty
            )
        }

        "take some" in {
            assert(
                Stream.init(Seq(1, 2, 3, 4, 5)).takeWhile(_ < 4).runSeq.eval ==
                    Seq(1, 2, 3)
            )
        }

        "take all" in {
            assert(
                Stream.init(Seq(1, 2, 3)).takeWhile(_ < 10).runSeq.eval ==
                    Seq(1, 2, 3)
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).takeWhile(_ => true).runSeq.eval ==
                    Seq.empty
            )
        }

        "with effects" in {
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val taken = stream.takeWhile { v =>
                Var.update[Int](_ + 1).map(_ < 4)
            }.runSeq
            assert(Var.runTuple(0)(taken).eval == (4, Seq(1, 2, 3)))
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).takeWhile(_ == 1).runSeq.eval ==
                    Seq.fill(n)(1)
            )
        }
    }

    "dropWhile" - {
        "drop none" in {
            assert(
                Stream.init(Seq(1, 2, 3)).dropWhile(_ < 0).runSeq.eval ==
                    Seq(1, 2, 3)
            )
        }

        "drop some" in {
            assert(
                Stream.init(Seq(1, 2, 3, 4, 5)).dropWhile(_ < 4).runSeq.eval ==
                    Seq(4, 5)
            )
        }

        "drop all" in {
            assert(
                Stream.init(Seq(1, 2, 3)).dropWhile(_ < 10).runSeq.eval ==
                    Seq.empty
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).dropWhile(_ => false).runSeq.eval ==
                    Seq.empty
            )
        }

        "with effects" in {
            var count  = 0
            val stream = Stream.init(Seq(1, 2, 3, 4, 5))
            val dropped = stream.dropWhile { v =>
                Var.update[Int](_ + 1).map(_ < 3)
            }.runSeq
            assert(Var.runTuple(0)(dropped).eval == (3, Seq(3, 4, 5)))
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1) ++ Seq(2)).dropWhile(_ == 1).runSeq.eval ==
                    Seq(2)
            )
        }
    }

    "filter" - {
        "non-empty" in {
            assert(
                Stream.init(Seq(1, 2, 3)).filter(_ % 2 == 0).runSeq.eval ==
                    Seq(2)
            )
        }

        "all in" in {
            assert(
                Stream.init(Seq(1, 2, 3)).filter(_ => true).runSeq.eval ==
                    Seq(1, 2, 3)
            )
        }

        "all out" in {
            assert(
                Stream.init(Seq(1, 2, 3)).filter(_ => false).runSeq.eval ==
                    Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(1 to n).filter(_ % 2 == 0).runSeq.eval.size ==
                    n / 2
            )
        }
    }

    "changes" - {
        "no duplicates" in {
            assert(
                Stream.init(Seq(1, 2, 3)).changes.runSeq.eval ==
                    Seq(1, 2, 3)
            )
        }

        "with duplicates" in {
            assert(
                Stream.init(Seq(1, 2, 2, 3, 2, 3, 3)).changes.runSeq.eval ==
                    Seq(1, 2, 3, 2, 3)
            )
        }

        "with initial value" in {
            assert(
                Stream.init(Seq(1, 2, 2, 3, 2, 3, 3)).changes(1).runSeq.eval ==
                    Seq(2, 3, 2, 3)
            )
        }

        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).changes.runSeq.eval ==
                    Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).changes.runSeq.eval ==
                    Seq(1)
            )
        }
    }

    "map" - {
        "double" in {
            assert(
                Stream.init(Seq(1, 2, 3)).map(_ * 2).runSeq.eval == Seq(2, 4, 6)
            )
        }

        "to string" in {
            assert(
                Stream.init(Seq(1, 2, 3)).map(_.toString).runSeq.eval ==
                    Seq("1", "2", "3")
            )
        }

        "with effects" in {
            val stream      = Stream.init(Seq(1, 2, 3))
            val transformed = stream.map(v => Env.use[Int](v * _)).runSeq
            assert(Env.run(2)(transformed).eval == Seq(2, 4, 6))
        }

        "with failures" in {
            val stream      = Stream.init(Seq("1", "2", "abc", "3"))
            val transformed = stream.map(v => Abort.catching[NumberFormatException](v.toInt)).runSeq
            assert(Abort.run(transformed).eval.isFail)
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).map(_ + 1).runSeq.eval ==
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
                    .runSeq
                    .eval
            assert(counter == 0)
            assert(result.isEmpty)
        }
    }

    "mapChunk" - {
        "double" in {
            assert(
                Stream.init(Seq(1, 2, 3)).mapChunk(_.take(2).map(_ * 2)).runSeq.eval == Seq(2, 4)
            )
        }

        "to string" in {
            assert(
                Stream.init(Seq(1, 2, 3)).mapChunk(_.append(4).map(_.toString)).runSeq.eval ==
                    Seq("1", "2", "3", "4")
            )
        }

        "with effects" in {
            val stream      = Stream.init(Seq(1, 2, 3))
            val transformed = stream.mapChunk(v => Env.use[Int](i => v.map(_ * i))).runSeq
            assert(Env.run(2)(transformed).eval == Seq(2, 4, 6))
        }

        "with failures" in {
            val stream      = Stream.init(Seq("1", "2", "abc", "3"))
            val transformed = stream.mapChunk(c => Abort.catching[NumberFormatException](c.map(_.toInt))).runSeq
            assert(Abort.run(transformed).eval.isFail)
        }

        "stack safety" in {
            assert(
                Stream.init(Seq.fill(n)(1)).mapChunk(_.map(_ + 1)).runSeq.eval ==
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
                    .runSeq
                    .eval
            assert(counter == 0)
            assert(result.isEmpty)
        }
    }

    "flatMap" - {
        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).flatMap(i => Stream.init(Seq(i, i + 1))).runSeq.eval == Seq.empty
            )
        }

        "one-to-many transformation" in {
            assert(
                Stream.init(Seq(1, 2, 3)).flatMap(i => Stream.init(Seq(i, i + 1))).runSeq.eval == Seq(1, 2, 2, 3, 3, 4)
            )
        }

        "nested effects" in {
            val result = Env.run(10) {
                Stream.init(Seq(1, 2, 3))
                    .flatMap(i => Stream.init(Seq(i, i + 1)).map(j => Env.use[Int](_ + j)))
                    .runSeq
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
                    .runSeq
            }
            assert(result.eval == Result.fail("Too large"))
        }

        "chunked input" in {
            val input  = Stream.init(Chunk(1, 2, 3))
            val result = input.flatMap(i => Stream.init(Seq(i, i * 10))).runSeq
            assert(result.eval == Seq(1, 10, 2, 20, 3, 30))
        }
    }

    "flatMapChunk" - {
        "empty stream" in {
            assert(
                Stream.init(Seq.empty[Int]).flatMapChunk(c => Stream.init(c.map(_ + 1).eval)).runSeq.eval == Seq.empty
            )
        }

        "chunk transformation" in {
            val result = Stream.init(Seq(1, 2, 3))
                .flatMapChunk(c => Stream.init(c.map(_ * 2).eval.append(0)))
                .runSeq
            assert(result.eval == Seq(2, 4, 6, 0))
        }

        "nested effects" in {
            val result = Env.run(10) {
                Stream.init(Seq(1, 2, 3))
                    .flatMapChunk(c => Env.use[Int](i => Stream.init(c.map(_ + i).eval)))
                    .runSeq
            }
            assert(result.eval == Seq(11, 12, 13))
        }

        "early termination" in {
            val result = Abort.run[String] {
                Stream.init(Seq(1, 2, 3))
                    .flatMapChunk(c =>
                        if !c.filter(_ > 2).eval.isEmpty then Abort.fail("Too large")
                        else Stream.init(c.map(_ * 2).eval)
                    )
                    .runSeq
            }
            assert(result.eval == Result.fail("Too large"))
        }

        "combining multiple chunks" in {
            val input = Stream.init(Chunk(1, 2, 3))
            val result = input.flatMapChunk(c1 =>
                Stream.init(Chunk(4, 5, 6)).flatMapChunk(c2 =>
                    Stream.init(c1.concat(c2))
                )
            ).runSeq
            assert(result.eval == Seq(1, 2, 3, 4, 5, 6))
        }
    }

    "concat" - {
        "non-empty streams" in {
            assert(
                Stream.init(Seq(1, 2, 3))
                    .concat(Stream.init(Seq(4, 5, 6)))
                    .runSeq.eval == Seq(1, 2, 3, 4, 5, 6)
            )
        }

        "empty left stream" in {
            assert(
                Stream.init(Seq.empty[Int])
                    .concat(Stream.init(Seq(1, 2, 3)))
                    .runSeq.eval == Seq(1, 2, 3)
            )
        }

        "empty right stream" in {
            assert(
                Stream.init(Seq(1, 2, 3))
                    .concat(Stream.init(Seq.empty[Int]))
                    .runSeq.eval == Seq(1, 2, 3)
            )
        }

        "both streams empty" in {
            assert(
                Stream.init(Seq.empty[Int])
                    .concat(Stream.init(Seq.empty[Int]))
                    .runSeq.eval == Seq.empty
            )
        }

        "stack safety" in {
            assert(
                Stream.init(1 to n)
                    .concat(Stream.init(n + 1 to 2 * n))
                    .runSeq.eval == (1 to (2 * n)).toSeq
            )
        }
    }

    "runDiscard" - {
        "non-empty stream" in {
            assert(
                Stream.init(Seq(1, 2, 3)).runDiscard.eval == ()
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
                sum += chunk.foldLeft(0)(_ + _).eval
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
                    sum += chunk.foldLeft(0)(_ + _).eval * multiplier
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
        Env.run(Seq(1, 2, 3))(stream.map(_.runSeq)).eval
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
                    .runSeq
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
                            Stream.init(c.map(i => if i > 3 then Abort.fail(s"Odd abort: $i") else i))
                        else
                            abortCounter += 1
                            Stream.init(c)
                    )
                    .runSeq
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
                        .runSeq
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
                    .runSeq
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
                }.runSeq
            }
            assert(Env.run(0)(result).eval == Result.fail("Even counter: 2"))
            assert(counter == 2)
        }

        "mapChunk with state-dependent abort" in run {
            var state  = 0
            val stream = Stream.init(Chunk(1, 2, 3, 4, 5))
            val result = Abort.run[String] {
                Var.run(0) {
                    stream.mapChunk { chunk =>
                        Var.update[Int](_ + chunk.size).map { newState =>
                            if newState > 3 then Abort.fail(s"State too high: $newState")
                            else chunk.map(_ * newState)
                        }
                    }.runSeq
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
                }.runSeq
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
                    }.runSeq
                }
            }
            assert(result.eval == Result.fail("Value 4 exceeds limit 3"))
        }
    }

end StreamTest
