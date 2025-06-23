package kyo

class PipeTest extends Test:

    val n = 100000

    def chunkSizes[V: Tag, S](stream: Stream[V, S]): Chunk[Int] < S =
        stream.mapChunk(chunk => Chunk(chunk.size)).run

    "pipes" - {
        "take" - {
            "zero" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.take(0)).run.eval == Seq.empty
                )
            }

            "negative" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.take(-1)).run.eval == Seq.empty
                )
            }

            "two" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.take(2)).run.eval == Seq(1, 2)
                )
            }

            "more than available" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.take(5)).run.eval == Seq(1, 2, 3)
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(100000)(1)).into(Pipe.take(5)).run.eval ==
                        Seq.fill(5)(1)
                )
            }

            "emit one at a time" in {
                def emit: Unit < (Emit[Chunk[Int]] & Var[Int]) =
                    for
                        n    <- Var.update[Int](_ + 1)
                        next <- Emit.valueWith(Chunk(n))(emit)
                    yield next
                end emit

                val stream         = Stream(Emit.valueWith(Chunk.empty[Int])(emit)).into(Pipe.take(5)).run
                val (count, chunk) = Var.runTuple(0)(stream).eval

                assert(
                    count == 5 && chunk == (1 to 5)
                )
            }

            "exact amount" in {
                def emit: Unit < (Emit[Chunk[Int]] & Var[Int]) =
                    val chunkSize = 5
                    for
                        end <- Var.update[Int](_ + chunkSize)
                        start = end - chunkSize + 1
                        chunk = Chunk.from(start to end)
                        next <- Emit.valueWith(Chunk.from(start to end))(emit)
                    yield next
                    end for
                end emit

                val stream         = Stream(Emit.valueWith(Chunk.empty[Int])(emit)).into(Pipe.take(7)).run
                val (count, chunk) = Var.runTuple(0)(stream).eval

                assert(
                    count == 10 && chunk == (1 to 7)
                )
            }
        }

        "drop" - {
            "zero" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.drop(0)).run.eval == Seq(1, 2, 3)
                )
            }

            "negative" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.drop(-1)).run.eval == Seq(1, 2, 3)
                )
            }

            "two" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.drop(2)).run.eval == Seq(3)
                )
            }

            "more than available" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.drop(5)).run.eval == Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.drop(5)).run.eval.size == n - 5
                )
            }

            "chunk smaller than n" in {
                val small = Stream.init(0 until 5)

                val result =
                    small
                        .concat(small)
                        .into(Pipe.drop(6))
                        .run
                        .eval

                assert(result == Seq(1, 2, 3, 4))
            }
        }

        "takeWhile" - {
            "take none" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.takeWhile[Int](_ < 0)).run.eval == Seq.empty
                )
            }

            "take some" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.takeWhile[Int](_ < 4)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "take all" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.takeWhile[Int](_ < 10)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.takeWhile[Int](_ => true)).run.eval ==
                        Seq.empty
                )
            }

            "with effects" in {
                val stream = Stream.init(Seq(1, 2, 3, 4, 5))
                val taken = stream.into(Pipe.takeWhile[Int] { v =>
                    Var.update[Int](_ + 1).map(_ < 4)
                }).run
                assert(Var.runTuple(0)(taken).eval == (4, Seq(1, 2, 3)))
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.takeWhile[Int](_ == 1)).run.eval ==
                        Seq.fill(n)(1)
                )
            }
        }

        "takeWhilePure" - {
            "take none" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.takeWhilePure[Int](_ < 0)).run.eval == Seq.empty
                )
            }

            "take some" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.takeWhilePure[Int](_ < 4)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "take all" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.takeWhilePure[Int](_ < 10)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.takeWhilePure[Int](_ => true)).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.takeWhilePure[Int](_ == 1)).run.eval ==
                        Seq.fill(n)(1)
                )
            }
        }

        "dropWhile" - {
            "drop none" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.dropWhile[Int](_ < 0)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "drop some" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.dropWhile[Int](_ < 4)).run.eval ==
                        Seq(4, 5)
                )
            }

            "drop all" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.dropWhile[Int](_ < 10)).run.eval ==
                        Seq.empty
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.dropWhile[Int](_ => false)).run.eval ==
                        Seq.empty
                )
            }

            "with effects" in {
                val stream = Stream.init(Seq(1, 2, 3, 4, 5))
                val dropped = stream.into(Pipe.dropWhile[Int] { v =>
                    Var.update[Int](_ + 1).map(_ < 3)
                }).run
                assert(Var.runTuple(0)(dropped).eval == (3, Seq(3, 4, 5)))
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1) ++ Seq(2)).into(Pipe.dropWhile[Int](_ == 1)).run.eval ==
                        Seq(2)
                )
            }
        }

        "dropWhilePure" - {
            "drop none" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.dropWhilePure[Int](_ < 0)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "drop some" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.dropWhilePure[Int](_ < 4)).run.eval ==
                        Seq(4, 5)
                )
            }

            "drop all" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.dropWhilePure[Int](_ < 10)).run.eval ==
                        Seq.empty
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.dropWhilePure[Int](_ => false)).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1) ++ Seq(2)).into(Pipe.dropWhilePure[Int](_ == 1)).run.eval ==
                        Seq(2)
                )
            }
        }

        "filter" - {
            "non-empty" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.filter[Int](_ % 2 == 0)).run.eval ==
                        Seq(2)
                )
            }

            "all in" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.filter[Int](_ => true)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "all out" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.filter[Int](_ => false)).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(1 to n).into(Pipe.filter[Int](_ % 2 == 0)).run.eval.size ==
                        n / 2
                )
            }

            "with effects" in {
                def predicate(i: Int) = Var.get[Boolean].map(b => Var.set(!b).andThen(b && !(i % 3 == 0)))
                val result            = Var.run(false)(Stream.init(1 to n).into(Pipe.filter(predicate)).run).eval
                assert(
                    result.size > 0 && result.forall(_ % 2 == 0) && result.forall(i => !(i % 3 == 0))
                )
            }
        }

        "filterPure" - {
            "non-empty" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.filterPure[Int](_ % 2 == 0)).run.eval ==
                        Seq(2)
                )
            }

            "all in" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.filterPure[Int](_ => true)).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "all out" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.filterPure[Int](_ => false)).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(1 to n).into(Pipe.filterPure[Int](_ % 2 == 0)).run.eval.size ==
                        n / 2
                )
            }
        }

        "collect" - {
            "non-empty" in {
                assert(
                    Stream.init(Seq(None, Some(2), None)).into(Pipe.collect[Option[Int]](Maybe.fromOption(_))).run.eval ==
                        Seq(2)
                )
            }

            "all in" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.collect[Int](Present(_))).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "all out" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).collectPure(_ => Absent).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(1 to n).into(Pipe.collect[Int](v => if v % 2 == 0 then Present(v) else Absent)).run.eval.size ==
                        n / 2
                )
            }

            "with effects" in {
                def predicate(v: Int) =
                    Var.update[Boolean](!_).map(if _ then Present(v) else Absent)
                val result = Var.run(false)(Stream.init(1 to 10).into(Pipe.collect(predicate)).run).eval
                assert(
                    result == (1 to 10 by 2)
                )
            }
        }

        "collectPure" - {
            "non-empty" in {
                assert(
                    Stream.init(Seq(None, Some(2), None)).into(Pipe.collectPure[Option[Int]](Maybe.fromOption(_))).run.eval ==
                        Seq(2)
                )
            }

            "all in" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.collectPure[Int](Present(_))).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "all out" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).collectPure(_ => Absent).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(1 to n).into(Pipe.collectPure[Int](v => if v % 2 == 0 then Present(v) else Absent)).run.eval.size ==
                        n / 2
                )
            }
        }

        "collectWhile" - {
            "take none" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.collectWhile[Int](i =>
                        if i < 0 then Present(i + 1) else Absent
                    )).run.eval == Seq.empty
                )
            }

            "take some" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.collectWhile[Int](i => if i < 4 then Present(i + 1) else Absent)).run.eval ==
                        Seq(2, 3, 4)
                )
            }

            "take some even if subsequent elements pass predicate" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.collectWhile[Int](i => if i != 4 then Present(i + 1) else Absent)).run.eval ==
                        Seq(2, 3, 4)
                )
            }

            "take all" in {
                assert(
                    Stream.init(Seq(1, 2, 3, 4, 5)).into(Pipe.collectWhile[Int](i => if i < 10 then Present(i + 1) else Absent)).run.eval ==
                        Seq(2, 3, 4, 5, 6)
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.collectWhile[Int](i => Present(i + 1))).run.eval ==
                        Seq.empty
                )
            }

            "with effects" in {
                val stream = Stream.init(Seq(1, 2, 3, 4, 5))
                val collected = stream.into(Pipe.collectWhile[Int] { v =>
                    Var.update[Boolean](!_).map(if _ then Present(v * 2) else Absent)
                }).run
                assert(Var.run(false)(collected).eval == Seq(2))
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.collectWhile[Int](i => Present(i))).run.eval ==
                        Seq.fill(n)(1)
                )
            }
        }

        "collectWhilePure" - {
            "take none" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.collectWhilePure[Int](i =>
                        if i < 0 then Present(i + 1) else Absent
                    )).run.eval == Seq.empty
                )
            }

            "take some" in {
                assert(
                    Stream.init(Seq(
                        1, 2, 3, 4, 5
                    )).into(Pipe.collectWhilePure[Int](i => if i < 4 then Present(i + 1) else Absent)).run.eval ==
                        Seq(2, 3, 4)
                )
            }

            "take some even if subsequent elements pass predicate" in {
                assert(
                    Stream.init(Seq(
                        1, 2, 3, 4, 5
                    )).into(Pipe.collectWhilePure[Int](i => if i != 4 then Present(i + 1) else Absent)).run.eval ==
                        Seq(2, 3, 4)
                )
            }

            "take all" in {
                assert(
                    Stream.init(Seq(
                        1, 2, 3, 4, 5
                    )).into(Pipe.collectWhilePure[Int](i => if i < 10 then Present(i + 1) else Absent)).run.eval ==
                        Seq(2, 3, 4, 5, 6)
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.collectWhilePure[Int](i => Present(i + 1))).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.collectWhilePure[Int](i => Present(i))).run.eval ==
                        Seq.fill(n)(1)
                )
            }
        }

        "changes" - {
            "no duplicates" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.changes[Int]).run.eval ==
                        Seq(1, 2, 3)
                )
            }

            "with duplicates" in {
                assert(
                    Stream.init(Seq(1, 2, 2, 3, 2, 3, 3)).into(Pipe.changes[Int]).run.eval ==
                        Seq(1, 2, 3, 2, 3)
                )
            }

            "with initial value" in {
                assert(
                    Stream.init(Seq(1, 2, 2, 3, 2, 3, 3)).into(Pipe.changes[Int](1)).run.eval ==
                        Seq(2, 3, 2, 3)
                )
            }

            "empty stream" in {
                assert(
                    Stream.init(Seq.empty[Int]).into(Pipe.changes[Int]).run.eval ==
                        Seq.empty
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.changes[Int]).run.eval ==
                        Seq(1)
                )
            }
        }

        "map" - {
            "double" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.map[Int](_ * 2)).run.eval == Seq(2, 4, 6)
                )
            }

            "to string" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.map[Int](_.toString)).run.eval ==
                        Seq("1", "2", "3")
                )
            }

            "with effects" in {
                val stream      = Stream.init(Seq(1, 2, 3))
                val transformed = stream.into(Pipe.map[Int](v => Env.use[Int](v * _))).run
                assert(Env.run(2)(transformed).eval == Seq(2, 4, 6))
            }

            "with failures" in {
                val stream      = Stream.init(Seq("1", "2", "abc", "3"))
                val transformed = stream.into(Pipe.map[String](v => Abort.catching[NumberFormatException](v.toInt))).run
                assert(Abort.run(transformed).eval.isFailure)
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.map[Int](_ + 1)).run.eval ==
                        Seq.fill(n)(2)
                )
            }
            "produce until" in {
                var counter = 0
                val result =
                    Stream
                        .init(0 until 100)
                        .into(Pipe.map[Int](_ => counter += 1))
                        .take(0)
                        .run
                        .eval
                assert(counter == 0)
                assert(result.isEmpty)
            }
        }

        "mapPure" - {
            "double" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.mapPure[Int](_ * 2)).run.eval == Seq(2, 4, 6)
                )
            }

            "to string" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.mapPure[Int](_.toString)).run.eval ==
                        Seq("1", "2", "3")
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.mapPure[Int](_ + 1)).run.eval ==
                        Seq.fill(n)(2)
                )
            }
            "produce until" in {
                var counter = 0
                val result =
                    Stream
                        .init(0 until 100)
                        .into(Pipe.mapPure[Int](_ => counter += 1))
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
                    Stream.init(Seq(1, 2, 3)).into(Pipe.mapChunk[Int](_.take(2).map(_ * 2))).run.eval == Seq(2, 4)
                )
            }

            "to string" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.mapChunk[Int](_.append(4).map(_.toString))).run.eval ==
                        Seq("1", "2", "3", "4")
                )
            }

            "with effects" in {
                val stream      = Stream.init(Seq(1, 2, 3))
                val transformed = stream.into(Pipe.mapChunk[Int](v => Env.use[Int](i => v.map(_ * i)))).run
                assert(Env.run(2)(transformed).eval == Seq(2, 4, 6))
            }

            "with failures" in {
                val stream      = Stream.init(Seq("1", "2", "abc", "3"))
                val transformed = stream.into(Pipe.mapChunk[String](c => Abort.catching[NumberFormatException](c.map(_.toInt)))).run
                assert(Abort.run(transformed).eval.isFailure)
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.mapChunk[Int](_.map(_ + 1))).run.eval ==
                        Seq.fill(n)(2)
                )
            }
            "produce until" in {
                var counter = 0
                val result =
                    Stream
                        .init(0 until 100)
                        .into(Pipe.mapChunk[Int](_.map(_ => counter += 1)))
                        .take(0)
                        .run
                        .eval
                assert(counter == 0)
                assert(result.isEmpty)
            }
        }

        "mapChunkPure" - {
            "double" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.mapChunkPure[Int](_.take(2).map(_ * 2))).run.eval == Seq(2, 4)
                )
            }

            "to string" in {
                assert(
                    Stream.init(Seq(1, 2, 3)).into(Pipe.mapChunkPure[Int](_.append(4).map(_.toString))).run.eval ==
                        Seq("1", "2", "3", "4")
                )
            }

            "stack safety" in {
                assert(
                    Stream.init(Seq.fill(n)(1)).into(Pipe.mapChunkPure[Int](_.map(_ + 1))).run.eval ==
                        Seq.fill(n)(2)
                )
            }
            "produce until" in {
                var counter = 0
                val result =
                    Stream
                        .init(0 until 100)
                        .into(Pipe.mapChunkPure[Int](_.map(_ => counter += 1)))
                        .take(0)
                        .run
                        .eval
                assert(counter == 0)
                assert(result.isEmpty)
            }
        }

        "tap" - {
            "non-empty stream" in {
                val stream = Stream
                    .init(Seq(1, 2, 3))
                    .into(Pipe.tap[Int](i => Var.update[Int](_ + i).unit))
                assert(Var.runTuple(0)(stream.run).eval == (6, Seq(1, 2, 3)))
            }
            "empty stream" in {
                val stream = Stream
                    .empty
                    .into(Pipe.tap[Int](i => Var.update[Int](_ + i).unit))
                assert(Var.runTuple(0)(stream.run).eval == (0, Seq()))
            }
        }

        "tapChunk" - {
            "non-empty stream" in {
                val stream = Stream
                    .apply(Emit.valueWith(Chunk(1, 2, 3))(Emit.value(Chunk(4, 5, 6))))
                    .into(Pipe.tapChunk[Int](c => Var.update[Int](_ + c.sum).unit))
                assert(Var.runTuple(0)(stream.run).eval == (21, Seq(1, 2, 3, 4, 5, 6)))
            }
            "empty stream" in {
                val stream = Stream
                    .empty
                    .into(Pipe.tapChunk[Int](c => Var.update[Int](_ + c.sum).unit))
                assert(Var.runTuple(0)(stream.run).eval == (0, Seq()))
            }
        }

        "rechunk" - {
            "negative" in {
                val sizes = chunkSizes(Stream.init(1 until 5).into(Pipe.rechunk(-10))).eval
                assert(sizes == Chunk(1, 1, 1, 1))
            }

            "smaller" in {
                val sizes = chunkSizes(Stream.range(1, 5000, step = 2).into(Pipe.rechunk(1024))).eval
                assert(sizes == Chunk(1024, 1024, 452))
            }

            "same" in {
                val sizes = chunkSizes(Stream.range(0, 250, chunkSize = 100).into(Pipe.rechunk(100))).eval
                assert(sizes == Chunk(100, 100, 50))
            }

            "larger" in {
                val sizes = chunkSizes(Stream.range(0, 5001).into(Pipe.rechunk(5000))).eval
                assert(sizes == Chunk(5000, 1))
            }

            "larger than stream" in {
                val sizes = chunkSizes(Stream.range(0, 100_000).into(Pipe.rechunk(500_000))).eval
                assert(sizes == Chunk(100_000))
            }

            "with effects" in {
                val stream = Stream.range(0, 100).map(i => Env.use[Int](_ + i)).into(Pipe.rechunk(48))
                val result = Env.run(10)(stream.run).eval
                val chunks = Env.run(10)(chunkSizes(stream)).eval
                assert(result == (10 until 110))
                assert(chunks == Chunk(48, 48, 4))
            }

            "order" in {
                val stream = Stream.range(0, 100).into(Pipe.rechunk(8))
                assert(stream.run.eval == (0 until 100))
            }

            "empty chunks" in {
                def emit(n: Int): Unit < (Emit[Chunk[Int]]) =
                    n match
                        case 0 => ()
                        case 5 => Emit.valueWith(Chunk.empty)(emit(n - 1))
                        case _ => Emit.valueWith(Chunk.fill(3)(n))(emit(n - 1))
                end emit

                val stream = Stream(emit(10)).into(Pipe.rechunk(10)).mapChunk(Chunk(_))
                assert(stream.run.eval == Chunk(
                    Chunk(10, 10, 10, 9, 9, 9, 8, 8, 8, 7),
                    Chunk(7, 7, 6, 6, 6), // empty chunk causes buffer flush
                    Chunk(4, 4, 4, 3, 3, 3, 2, 2, 2, 1),
                    Chunk(1, 1)
                ))
            }
        }

        "edge cases" - {
            "mapChunk with state-dependent abort" in run {
                val stream = Stream.init(Chunk(1, 2, 3, 4, 5))
                val result = Abort.run[String] {
                    Var.run(0) {
                        stream.into(Pipe.mapChunk[Int] { chunk =>
                            Var.update[Int](_ + chunk.size).map { newState =>
                                if newState > 3 then Abort.fail(s"State too high: $newState")
                                else chunk.map(_ * newState)
                            }
                        }).run
                    }
                }
                assert(result.eval == Result.fail("State too high: 5"))
            }

            "take with nested aborts and environment" in run {
                val stream = Stream.init(Seq(1, 2, 3, 4, 5))
                val result = Env.run(3) {
                    Abort.run[String] {
                        stream.into(Pipe.take(4)).flatMap { n =>
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

        "chunking" - {
            val chunkSize: Int = 64

            def maintains(ops: ((Stream[Int, Any] => Stream[Int, Any]), String)*): Unit =
                val streamSize: Int = 513
                for (transform, opName) <- ops do
                    s"$opName maintains chunks" in {
                        val base     = Stream.range(0, streamSize, chunkSize = chunkSize)
                        val expected = chunkSizes(base).eval

                        val transformed = transform(base)
                        val actual      = chunkSizes(transformed).eval

                        assert(actual == expected)
                    }
                end for
            end maintains

            maintains(
                (_.into(Pipe.map[Int](identity)), "map"),
                (_.into(Pipe.map[Int](Kyo.lift)), "map (kyo)"),
                (_.into(Pipe.mapChunk[Int](identity)), "mapChunk"),
                (_.into(Pipe.mapChunk[Int](Kyo.lift)), "mapChunk (kyo)"),
                (_.into(Pipe.tap[Int](identity)), "tap"),
                (_.into(Pipe.tapChunk[Int](identity)), "tapChunk"),
                (_.into(Pipe.take[Int](Int.MaxValue)), "take"),
                (_.into(Pipe.takeWhile[Int](_ => true)), "takeWhile"),
                (_.into(Pipe.takeWhile[Int](_ => Kyo.lift(true))), "takeWhile (kyo)"),
                (_.into(Pipe.dropWhile[Int](_ => false)), "dropWhile"),
                (_.into(Pipe.dropWhile[Int](_ => Kyo.lift(false))), "dropWhile (kyo)"),
                (_.into(Pipe.filter[Int](_ => true)), "filter"),
                (_.into(Pipe.filter[Int](_ => Kyo.lift(true))), "filter (kyo)"),
                (_.into(Pipe.changes[Int]), "changes"),
                (_.into(Pipe.rechunk[Int](chunkSize)), "rechunk")
            )
        }
    }

    "contramap" - {
        "pure" in run {
            val pipe   = Pipe.identity[Int].contramap((str: String) => str.length)
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = stream.into(pipe).run.eval
            assert(result == Seq(1, 2, 3))
        }

        "effectful" in run {
            val pipe   = Pipe.identity[Int].contramap((str: String) => Var.update[String](_ + str).andThen(str.length))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple("")(stream.into(pipe).run).eval
            assert(result == ("abesee", Seq(1, 2, 3)))
        }
    }

    "contramapPure" in run {
        val pipe   = Pipe.identity[Int].contramapPure((str: String) => str.length)
        val stream = Stream.init(Seq("a", "be", "see"))
        val result = stream.into(pipe).run.eval
        assert(result == Seq(1, 2, 3))
    }

    "contramapChunk" - {
        "pure" in run {
            val pipe   = Pipe.identity[Int].contramapChunk((chunk: Chunk[String]) => Chunk(chunk.size))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = stream.into(pipe).run.eval
            assert(result == Seq(3))
        }

        "effectful" in run {
            val pipe = Pipe.identity[Int].contramapChunk((chunk: Chunk[String]) =>
                Var.update[String](_ + chunk.mkString).andThen(Chunk(chunk.size))
            )
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple("")(stream.into(pipe).run).eval
            assert(result == ("abesee", Seq(3)))
        }
    }

    "contramapChunkPure" in run {
        val pipe   = Pipe.identity[Int].contramapChunkPure((chunk: Chunk[String]) => Chunk(chunk.size))
        val stream = Stream.init(Seq("a", "be", "see"))
        val result = stream.into(pipe).run.eval
        assert(result == Seq(3))
    }

    "map" - {
        "pure" in run {
            val pipe   = Pipe.identity[String].map((str: String) => str.length)
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = stream.into(pipe).run.eval
            assert(result == Seq(1, 2, 3))
        }

        "effectful" in run {
            val pipe   = Pipe.identity[String].map((str: String) => Var.update[String](_ + str).andThen(str.length))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple("")(stream.into(pipe).run).eval
            assert(result == ("abesee", Seq(1, 2, 3)))
        }
    }

    "mapPure" in run {
        val pipe   = Pipe.identity[String].mapPure((str: String) => str.length)
        val stream = Stream.init(Seq("a", "be", "see"))
        val result = stream.into(pipe).run.eval
        assert(result == Seq(1, 2, 3))
    }

    "mapChunk" - {
        "pure" in run {
            val pipe   = Pipe.identity[String].mapChunk((chunk: Chunk[String]) => Chunk(chunk.size))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = stream.into(pipe).run.eval
            assert(result == Seq(3))
        }

        "effectful" in run {
            val pipe = Pipe.identity[String].mapChunk((chunk: Chunk[String]) =>
                Var.update[String](_ + chunk.mkString).andThen(Chunk(chunk.size))
            )
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple("")(stream.into(pipe).run).eval
            assert(result == ("abesee", Seq(3)))
        }
    }

    "mapChunkPure" in run {
        val pipe   = Pipe.identity[String].mapChunkPure((chunk: Chunk[String]) => Chunk(chunk.size))
        val stream = Stream.init(Seq("a", "be", "see"))
        val result = stream.into(pipe).run.eval
        assert(result == Seq(3))
    }

    "join" - {
        "pipe" in run {
            val pipe   = Pipe.identity[String].join(Pipe.mapChunk[String]((chunk: Chunk[String]) => Chunk(chunk.size)))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = stream.into(pipe).run.eval
            assert(result == Seq(3))
        }

        "sink" in run {
            val sink   = Pipe.map[String](_.length).join(Sink.fold[Int, Int](0)(_ + _))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = stream.into(sink).eval
            assert(result == 6)
        }
    }

end PipeTest
