package kyo

class StreamCoreExtensionsTest extends Test:

    "factory" - {
        "collectAll" in run {
            Choice.run {
                for
                    size <- Choice.eval(0, 1, 32, 100)
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
                    size <- Choice.eval(0, 1, 32, 1024)
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
                        size <- Choice.eval(0, 1, 32, 1024)
                        left <- Choice.eval(true, false)
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
                        size <- Choice.eval(0, 1, 32, 1024)
                        left <- Choice.eval(true, false)
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

        val randomSleep = Random.nextInt(10).map(i => Async.sleep(i.millis))

        "mapPar" - {
            "should map all elements preserving order" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        par <- Choice.eval(1, 2, 4, Async.defaultConcurrency, Int.MaxValue)
                        buf <- Choice.eval(1, 4, 5, 8, 12, par, Int.MaxValue)
                        s2 = stream.mapPar(par, buf)(i => IO(i + 1))
                        res <- s2.run
                    yield assert(
                        res == (2 to 13)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }

            "should preserve order when first transformation is delayed" in run {
                val stream = Stream.init(1 to 4)
                val test =
                    for
                        par <- Choice.eval(2, 4, Async.defaultConcurrency)
                        s2 = stream.mapPar(par)(i => if i == 1 then Async.sleep(10.millis).andThen(i + 1) else i + 1)
                        res <- s2.run
                    yield assert(
                        res == (2 to 5)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "mapParUnordered" - {
            "should map all elements" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        par <- Choice.eval(1, 2, 4, Async.defaultConcurrency)
                        buf <- Choice.eval(1, 4, 5, 8, 12)
                        s2 = stream.mapParUnordered(par, buf)(i => IO(i + 1))
                        res <- s2.run
                    yield assert(
                        res.toSet == (2 to 13).toSet
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }

            "should not preserve order when first transformation is delayed" in run {
                val stream = Stream.init(1 to 4)
                val test =
                    for
                        par <- Choice.eval(2, 4, Async.defaultConcurrency)
                        s2 = stream.mapParUnordered(par)(i => if i == 1 then Async.sleep(10.millis).andThen(i + 1) else i + 1)
                        res <- s2.run
                    yield assert(
                        res.toSet == (2 to 5).toSet &&
                            res != (2 to 5)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "mapChunkPar" - {
            "should map all chunks preserving order" in run {
                pending
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        par <- Choice.eval(1, 2, 4, Async.defaultConcurrency)
                        buf <- Choice.eval(1, 4, 5, 8, 12)
                        s2 = stream.mapChunkPar(par, buf)(c => IO(c.map(_ + 1)))
                        res <- s2.run
                    yield assert(
                        res == (2 to 13)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }

            "should preserve order when first transformation is delayed" in run {
                pending
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8))
                val test =
                    for
                        par <- Choice.eval(2, 4, Async.defaultConcurrency)
                        s2 =
                            stream.mapChunkPar(par)(c => if c.head == 1 then Async.sleep(10.millis).andThen(c.map(_ + 1)) else c.map(_ + 1))
                        res <- s2.run
                    yield assert(
                        res == (2 to 9)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "mapChunkParUnordered" - {
            "should map all chunks" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8)).concat(Stream.init(9 to 12))
                val test =
                    for
                        par <- Choice.eval(1, 2, 4, Async.defaultConcurrency)
                        buf <- Choice.eval(1, 4, 5, 8, 12)
                        s2 = stream.mapChunkParUnordered(par, buf)(c => IO(c.map(_ + 1)))
                        res <- s2.run
                    yield assert(
                        res.toSet == (2 to 13).toSet
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }

            "should not preserve order when first transformation is delayed" in run {
                val stream = Stream.init(1 to 4).concat(Stream.init(5 to 8))
                val test =
                    for
                        par <- Choice.eval(2, 4, Async.defaultConcurrency)
                        s2 =
                            stream.mapChunkParUnordered(par)(c =>
                                if c.head == 1 then Async.sleep(10.millis).andThen(c.map(_ + 1)) else c.map(_ + 1)
                            )
                        res <- s2.run
                    yield assert(
                        res.toSet == (2 to 9).toSet &&
                            res != (2 to 9)
                    )
                    end for
                end test

                Choice.run(test).andThen(succeed)
            }
        }

        "broadcast" - {
            val stream = Stream.init(0 to 10)

            "broadcastDynamicWith" in run {
                stream.broadcastDynamicWith { streamHub =>
                    Kyo.zip(streamHub.subscribe, streamHub.subscribe)
                }.map:
                    case (s1, s2) =>
                        Kyo.zip(s1.run, s2.run).map:
                            case (c1, c2) => assert(c1 == c2 && c1 == (0 to 10))
            }

            "broadcast2" in run {
                stream.broadcast2().map:
                    case (s1, s2) =>
                        Kyo.zip(s1.run, s2.run).map:
                            case (c1, c2) => assert(Set(c1, c2).size == 1 && c1 == (0 to 10))
            }

            "broadcast3" in run {
                stream.broadcast3().map:
                    case (s1, s2, s3) =>
                        Kyo.zip(s1.run, s2.run, s3.run).map:
                            case (c1, c2, c3) => assert(Set(c1, c2, c3).size == 1 && c1 == (0 to 10))
            }

            "broadcast4" in run {
                stream.broadcast4().map:
                    case (s1, s2, s3, s4) =>
                        Kyo.zip(s1.run, s2.run, s3.run, s4.run).map:
                            case (c1, c2, c3, c4) => assert(Set(c1, c2, c3, c4).size == 1 && c1 == (0 to 10))
            }

            "broadcast5" in run {
                stream.broadcast5().map:
                    case (s1, s2, s3, s4, s5) =>
                        Kyo.zip(s1.run, s2.run, s3.run, s4.run, s5.run).map:
                            case (c1, c2, c3, c4, c5) => assert(Set(c1, c2, c3, c4, c5).size == 1 && c1 == (0 to 10))
            }

            "broadcastN" in run {
                stream.broadcastN(50).map: streamChunk =>
                    Kyo.foreach(streamChunk)(_.run).map: resultChunks =>
                        assert(resultChunks.size == 50 && resultChunks.toSet.size == 1 && resultChunks.headMaybe.contains(0 to 10))
            }

            "dynamic" - {
                "broadcasted in unison" in runNotJS {
                    Channel.initWith[Maybe[Int]](1024): channel =>
                        val lazyStream = channel.streamUntilClosed(256).collectWhile(v => v)
                        lazyStream.broadcasted().map: reusableStream =>
                            Latch.initWith(10): latch =>
                                Async.run(Async.foreach(1 to 10)(_ => latch.release.andThen(reusableStream.run))).map: runFiber =>
                                    latch.await.andThen:
                                        Async.run(Kyo.foreach(0 to 10)(i => channel.put(Present(i))).andThen(channel.put(Absent))).andThen:
                                            runFiber.get.map: resultChunks =>
                                                assert(
                                                    resultChunks.size == 10 && resultChunks.toSet.size == 1 && resultChunks.head == (0 to 10)
                                                )
                }

                "broadcasted should produce empty results when running after original stream completes" in run {
                    stream.broadcasted().map: reusableStream =>
                        reusableStream.run.map: res1 =>
                            reusableStream.run.map: res2 =>
                                assert(res1 == (0 to 10) && res2.isEmpty)
                }

                "broadcasted should produce failure when running after original stream fails" in run {
                    val failingStream = Stream(Stream.init(0 to 10).emit.andThen(Abort.fail("message")))
                    failingStream.broadcasted().map: reusableStream =>
                        Abort.run[String](reusableStream.run).map: res1 =>
                            Abort.run[String](reusableStream.run).map: res2 =>
                                assert(res1 == Result.Failure("message") && res2 == Result.Failure("message"))
                }

                "broadcastDynamic in unison" in runNotJS {
                    Channel.initWith[Maybe[Int]](1024): channel =>
                        val lazyStream = channel.streamUntilClosed(256).collectWhile(v => v)
                        lazyStream.broadcastDynamic().map: streamHub =>
                            Latch.initWith(10): latch =>
                                Async.run(
                                    Async.foreach(1 to 10)(_ => latch.release.andThen(streamHub.subscribe.map(_.run)))
                                ).map: runFiber =>
                                    latch.await.andThen:
                                        Async.run(Kyo.foreach(0 to 10)(i => channel.put(Present(i))).andThen(channel.put(Absent))).andThen:
                                            runFiber.get.map: resultChunks =>
                                                assert(
                                                    resultChunks.size == 10 && resultChunks.toSet.size == 1 && resultChunks.head == (0 to 10)
                                                )
                }

                "broadcastDynamic subscriptions should be empty when subscribing after original stream completes" in run {
                    stream.broadcastDynamic().map: streamHub =>
                        streamHub.subscribe.map(_.run).map: res1 =>
                            streamHub.subscribe.map(_.run).map: res2 =>
                                assert(res1 == (0 to 10) && res2.isEmpty)
                }

                "broadcasted subscriptions should fail when subscribing after original stream fails" in run {
                    val failingStream = Stream(Stream.init(0 to 10).emit.andThen(Abort.fail("message")))
                    failingStream.broadcastDynamic().map: streamHub =>
                        Abort.run[String](streamHub.subscribe.map(_.run)).map: res1 =>
                            Abort.run[String](streamHub.subscribe.map(_.run)).map: res2 =>
                                assert(res1 == Result.Failure("message") && res2 == Result.Failure("message"))
                }
            }
        }
    }

end StreamCoreExtensionsTest
