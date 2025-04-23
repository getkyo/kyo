package kyo

class StreamCoreExtensionsTest extends Test:

    "factory" - {
        "collectAll" in run {
            Choice.run {
                for
                    size <- Choice.get(Seq(0, 1, 32, 100))
                    s1     = Stream.init(0 to 99 by 3)
                    s2     = Stream.init(1 to 99 by 3)
                    s3     = Stream.init(2 to 99 by 3)
                    merged = Stream.collectAll(Seq(s1, s2, s3), size)
                    res <- merged.run
                yield assert(res.sorted == (0 to 99))
            }.andThen(succeed)
        }

        "collectAllHalting" in runNotJS {
            Choice.run {
                for
                    size <- Choice.get(Seq(0, 1, 32, 1024))
                    s1     = Stream(Loop.forever(Emit.value(Chunk(100))))
                    s2     = Stream.init(0 to 50)
                    merged = Stream.collectAllHalting(Seq(s1, s2), size)
                    res <- merged.run
                yield assert((0 to 50).toSet.subsetOf(res.toSet))
            }.andThen(succeed)
        }

        "multiple effects" in run {
            // Env[Int] & Abort[String]
            val s1 = Stream:
                Env.get[Int].map(i =>
                    Loop(i)(i1 =>
                        if i1 > 100 then Abort.fail("failure")
                        else if i1 == 0 then Loop.done
                        else Emit.valueWith(Chunk(i1))(Loop.continue(i1 - 1))
                    )
                )
            // Async
            val s2 = Stream(Async.delay(1.milli)(Stream.init(101 to 105).emit))
            Env.run(5):
                Stream.collectAll(Seq(s1, s2)).run.map: res =>
                    assert(res.toSet == Set.from(1 to 5) ++ Set.from(101 to 105))
        }
    }

    "combinator" - {
        "mergeHaltingLeft/Right" - {
            "should halt if non-halting side completes" in run {
                Choice.run {
                    for
                        size <- Choice.get(Seq(0, 1, 32, 1024))
                        left <- Choice.get(Seq(true, false))
                        s1     = Stream.init(0 to 50)
                        s2     = Stream(Loop.forever(Emit.value(Chunk(100))))
                        merged = if left then s1.mergeHaltingLeft(s2, size) else s2.mergeHaltingRight(s1, size)
                        res <- merged.run
                    yield assert(res.sorted.startsWith(0 to 50))
                }.andThen(succeed)
            }

            "should not halt if non-halting side completes" in run {
                val s1Set = Set.from(0 to 20)
                val s2Set = Set(21, 22)
                val s1 = Stream:
                    Async.sleep(10.millis).andThen((Kyo.foreachDiscard(s1Set.toSeq)(i => Emit.value(Chunk(i)))))
                val s2 = Stream.init(s2Set.toSeq)
                Choice.run {
                    for
                        size <- Choice.get(Seq(0, 1, 32, 1024))
                        left <- Choice.get(Seq(true, false))
                        // Make sure we get case where all three values of s2 have been consumed (not guaranteed)
                        assertion <- Loop(Set.empty[Int]) { lastRes =>
                            if s2Set.subsetOf(lastRes) then
                                Loop.done(assert(s1Set.subsetOf(lastRes)))
                            else if left then
                                s1.mergeHaltingLeft(s2, size).run.map: res =>
                                    Loop.continue(res.toSet)
                            else
                                s2.mergeHaltingRight(s1, size).run.map: res =>
                                    Loop.continue(res.toSet)
                            end if
                        }
                    yield assertion
                }.andThen(succeed)
            }
        }

        "multiple effects" in run {
            // Env[Int] & Abort[String]
            val s1 = Stream:
                Env.get[Int].map(i =>
                    Loop(i)(i1 =>
                        if i1 > i then Abort.fail("failure") // Should never be true
                        else if i1 == 0 then Loop.done
                        else Emit.valueWith(Chunk(i1))(Loop.continue(i1 - 1))
                    )
                )
            // Async
            val s2 = Stream(Async.delay(1.milli)(Stream.init(101 to 105).emit))
            Env.run(5):
                s1.merge(s2).run.map: res =>
                    assert(res.toSet == Set.from(1 to 5) ++ Set.from(101 to 105))
        }

        val randomSleep = Random.nextInt(5).map(i => Async.sleep(i.millis))

        "mapPar" - {
            "should map all elements preserving order" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        size <- Choice.get(Seq(2, 4, 6, 8, 9, 12, 13))
                        q    <- Queue.init[Int](12)
                        s2 = stream.mapPar(size)(i => randomSleep.andThen(q.offer(i)).andThen(i + 1))
                        resStream <- s2.run
                        resQueue  <- q.drain
                    yield assert(
                        // Order should be preserved in transformed stream
                        resStream == (2 to 13) &&
                            // Order should not be preserved in queue
                            resQueue != (1 to 12) &&
                            resQueue.toSet == (1 to 12).toSet
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "mapParUnordered" - {
            "should map all elements without preserving order" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        size <- Choice.get(Seq(2, 4, 6, 8, 9, 12, 13))
                        q    <- Queue.init[Int](12)
                        s2 = stream.mapParUnordered(size)(i => randomSleep.andThen(q.offer(i)).andThen(i + 1))
                        resStream <- s2.run
                        resQueue  <- q.drain
                    yield assert(
                        // Order should not be preserved in transformed stream
                        resStream != (2 to 13) &&
                            resStream.toSet == (2 to 13).toSet &&
                            // Order should not be preserved in queue
                            resQueue != (1 to 12) &&
                            resQueue.toSet == (1 to 12).toSet
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "mapChunkPar" - {
            "should map all chunks preserving order" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        size <- Choice.get(Seq(2, 4, 6, 8, 9, 12, 13))
                        q    <- Queue.init[Int](12)
                        s2 = stream.mapChunkPar(size)(chunk => randomSleep.andThen(q.offer(chunk.reduce(_ + _))).andThen(chunk.map(_ + 1)))
                        resStream <- s2.run
                        resQueue  <- q.drain
                    yield assert(
                        // Order should be preserved in transformed stream
                        resStream == (2 to 13) &&
                            // Order need not be preserved in queue
                            resQueue.toSet == Set(10, 26, 42)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "mapChunkParUnordered" - {
            "should map all chunks without preserving order" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        size <- Choice.get(Seq(2, 4, 6, 8, 9, 12, 13))
                        q    <- Queue.init[Int](12)
                        s2 = stream.mapChunkParUnordered(size)(chunk =>
                            randomSleep.andThen(q.offer(chunk.reduce(_ + _))).andThen(chunk.map(_ + 1))
                        )
                        resStream <- s2.run
                        resQueue  <- q.drain
                    yield assert(
                        // Order should be preserved in transformed stream
                        resStream.toSet == (2 to 13).toSet &&
                            // Order need not be preserved in queue
                            resQueue.toSet == Set(10, 26, 42)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }
    }

end StreamCoreExtensionsTest
