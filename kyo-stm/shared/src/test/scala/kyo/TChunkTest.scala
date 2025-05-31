package kyo

class TChunkTest extends Test:

    "init" - {
        "creates an empty chunk" in run {
            for
                chunk  <- TChunk.init[Int]
                empty  <- STM.run(chunk.isEmpty)
                size   <- STM.run(chunk.size)
                result <- STM.run(chunk.snapshot)
            yield
                assert(empty)
                assert(size == 0)
                assert(result.isEmpty)
        }

        "creates a chunk with initial values" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                empty  <- STM.run(chunk.isEmpty)
                size   <- STM.run(chunk.size)
                result <- STM.run(chunk.snapshot)
            yield
                assert(!empty)
                assert(size == 3)
                assert(result == Chunk(1, 2, 3))
        }
    }

    "basic operations" - {
        "append" in run {
            for
                chunk  <- TChunk.init[Int]
                _      <- STM.run(chunk.append(1))
                _      <- STM.run(chunk.append(2))
                _      <- STM.run(chunk.append(3))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3))
        }

        "get" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                first  <- STM.run(chunk.get(0))
                second <- STM.run(chunk.get(1))
                third  <- STM.run(chunk.get(2))
            yield
                assert(first == 1)
                assert(second == 2)
                assert(third == 3)
        }

        "head and last" in run {
            for
                chunk <- TChunk.init(1, 2, 3)
                head  <- STM.run(chunk.head)
                last  <- STM.run(chunk.last)
            yield
                assert(head == 1)
                assert(last == 3)
        }

        "compact" in run {
            for
                chunk <- TChunk.init[Int]
                _     <- STM.run(chunk.append(1))
                _     <- STM.run(chunk.append(2))
                _     <- STM.run(chunk.append(3))
                _     <- STM.run(chunk.compact)
                snap  <- STM.run(chunk.snapshot)
            yield
                assert(snap.isInstanceOf[Chunk.Indexed[Int]])
                assert(snap == Chunk(1, 2, 3))
        }

        "use" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                sum    <- STM.run(chunk.use(_.sum))
                first  <- STM.run(chunk.use(_.head))
                mapped <- STM.run(chunk.use(_.map(_ * 2)))
            yield
                assert(sum == 6)
                assert(first == 1)
                assert(mapped == Chunk(2, 4, 6))
        }
    }

    "modification operations" - {
        "take" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.take(3))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3))
        }

        "drop" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.drop(2))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(3, 4, 5))
        }

        "dropRight" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.dropRight(2))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3))
        }

        "slice" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.slice(1, 4))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(2, 3, 4))
        }

        "concat" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                _      <- STM.run(chunk.concat(Chunk(4, 5, 6)))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3, 4, 5, 6))
        }
    }

    "filter" in run {
        for
            chunk  <- TChunk.init(1, 2, 3, 4, 5)
            _      <- STM.run(chunk.filter(_ % 2 == 0))
            result <- STM.run(chunk.snapshot)
        yield assert(result == Chunk(2, 4))
    }

    "transaction isolation" - {
        "concurrent modifications" in run {
            for
                chunk  <- TChunk.init[Int]
                _      <- Async.foreach(1 to 100, 100)(i => STM.run(chunk.append(i)))
                result <- STM.run(chunk.snapshot)
            yield
                assert(result.toSet == (1 to 100).toSet)
                assert(result.length == 100)
        }

        "rollback on failure" in run {
            for
                chunk <- TChunk.init(1, 2, 3)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- chunk.append(4)
                            _ <- chunk.append(5)
                            _ <- Abort.fail(new Exception("Test failure"))
                        yield ()
                    }
                }
                snapshot <- STM.run(chunk.snapshot)
            yield
                assert(result.isFailure)
                assert(snapshot == Chunk(1, 2, 3))
        }

        "maintains compactness after transaction" in run {
            for
                chunk <- TChunk.init(1, 2, 3)
                _     <- STM.run(chunk.compact)
                _ <- STM.run {
                    for
                        _ <- chunk.append(4)
                        _ <- chunk.append(5)
                        _ <- chunk.compact
                    yield ()
                }
                snap <- STM.run(chunk.snapshot)
            yield
                assert(snap.isInstanceOf[Chunk.Indexed[Int]])
                assert(snap == Chunk(1, 2, 3, 4, 5))
        }
    }

    "concurrency" - {
        val repeats = 100

        "concurrent modifications" in runNotJS {
            (for
                size     <- Choice.eval(1, 10, 100)
                chunk    <- TChunk.init[Int]()
                _        <- Async.foreach(1 to size, size)(i => STM.run(chunk.append(i)))
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.length == size &&
                    snapshot.toSet == (1 to size).toSet
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent filtering" in runNotJS {
            (for
                size  <- Choice.eval(1, 10, 100)
                chunk <- TChunk.init[Int]()
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => chunk.append(i))
                }
                _ <- Async.fill(5, 5)(
                    STM.run(chunk.filter(_ % 2 == 0))
                )
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.forall(_ % 2 == 0) &&
                    snapshot.length == size / 2
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent slice operations" in runNotJS {
            (for
                size  <- Choice.eval(1, 10, 100)
                chunk <- TChunk.init[Int]()
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => chunk.append(i))
                }
                _ <- Async.fill(5, 5)(
                    STM.run {
                        for
                            midpoint <- chunk.size.map(_ / 2)
                            _        <- chunk.slice(0, midpoint)
                        yield ()
                    }
                )
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.length <= size / 2 &&
                    snapshot.toSet.subsetOf((1 to size).toSet)
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent compaction" in runNotJS {
            (for
                size  <- Choice.eval(1, 10, 100)
                chunk <- TChunk.init[Int]()
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => chunk.append(i))
                }
                _ <- Async.fill(5, 5)(
                    STM.run(chunk.compact)
                )
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.isInstanceOf[Chunk.Indexed[Int]] &&
                    snapshot.length == size &&
                    snapshot.toSet == (1 to size).toSet
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }
    }
end TChunkTest
