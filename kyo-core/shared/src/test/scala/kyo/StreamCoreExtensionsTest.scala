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

        def fromIteratorTests(chunkSize: Int): Unit =
            s"chunkSize = $chunkSize" - {
                "basic" in run {
                    val it     = Iterator(1, 2, 3, 4, 5)
                    val stream = Stream.fromIterator(it, chunkSize)
                    stream.run.map(res => assert(res == Chunk(1, 2, 3, 4, 5)))
                }

                "call by name" in run {

                    val stream = Stream.fromIterator(Iterator(1, 2, 3, 4, 5), chunkSize)
                    stream.run.map(res => assert(res == Chunk(1, 2, 3, 4, 5)))
                }

                "empty iterator" in run {
                    val it     = Iterator.empty
                    val stream = Stream.fromIterator(it, chunkSize)
                    stream.run.map(res => assert(res.isEmpty))
                }

                "reuse same stream" in run {
                    val it     = Iterator(1, 2, 3)
                    val stream = Stream.fromIterator(it, chunkSize)
                    for
                        first  <- stream.run
                        second <- stream.run
                    yield assert((first, second) == (Chunk(1, 2, 3), Chunk.empty))
                    end for
                }

                "large iterator" in run {
                    val size   = 10000
                    val it     = Iterator.from(0).take(size)
                    val stream = Stream.fromIterator(it, chunkSize)
                    stream.run.map(res => assert(res == Chunk.from(0 until size)))
                }

                "map with Choice" in run {
                    val it = Iterator("a", "b", "c")

                    val stream: Stream[String, IO & Choice] =
                        Stream.fromIterator(it, chunkSize).rechunk(10).map: str =>
                            Choice.eval(true, false).map:
                                case true  => str.toUpperCase
                                case false => str

                    end stream
                    Choice.run(stream.run).map: allCombinations =>
                        assert(allCombinations.size == 8)
                        assert(allCombinations.contains(Chunk("a", "B", "c")))

                }

                "recover from Panic" in run {
                    val it = Iterator.tabulate(5)({
                        case 3 => throw new RuntimeException("fail")
                        case i => i
                    })

                    val stream = Stream.fromIterator(it, chunkSize)

                    val panicTo42 = stream.handle(Abort.recoverError({
                        case _: Result.Panic      => Emit.value(Chunk(42))
                        case _: Result.Failure[?] => fail("should not happen")
                    }))

                    panicTo42.run.map: chunk =>
                        assert(chunk == Chunk(0, 1, 2, 42))

                }
            }

        "fromIterator" - {
            Seq(0, 1, 4, 32, 1024).foreach(fromIteratorTests)
        }

        def fromIteratorCatchingTests(chunkSize: Int): Unit =
            s"bufferSize = $chunkSize" - {

                "basic" in run {
                    val it     = Iterator(1, 2, 3, 4, 5)
                    val stream = Stream.fromIteratorCatching[Throwable](it, chunkSize)
                    stream.run.map(res => assert(res == Chunk(1, 2, 3, 4, 5)))
                }

                "call by name" in run {
                    val stream = Stream.fromIteratorCatching[Throwable](Iterator(1, 2, 3, 4, 5), chunkSize)
                    stream.run.map(res => assert(res == Chunk(1, 2, 3, 4, 5)))
                }

                "empty iterator" in run {
                    val it     = Iterator.empty
                    val stream = Stream.fromIteratorCatching[Throwable](it, chunkSize)
                    stream.run.map(res => assert(res.isEmpty))
                }

                "reuse same stream" in run {
                    val it     = Iterator(1, 2, 3)
                    val stream = Stream.fromIteratorCatching[Throwable](it, chunkSize)
                    for
                        first  <- stream.run
                        second <- stream.run
                    yield assert((first, second) == (Chunk(1, 2, 3), Chunk.empty))
                    end for
                }

                "large iterator" in run {
                    val size   = 9999
                    val it     = Iterator.from(0).take(size)
                    val stream = Stream.fromIteratorCatching[Throwable](it, chunkSize)
                    stream.run.map(res => assert(res == Chunk.from(0 until size)))
                }

                "lazy" in run {
                    val it = Iterator.tabulate(5)({
                        case 3 => throw new RuntimeException("fail")
                        case i => i
                    })

                    val stream = Stream.fromIteratorCatching[Throwable](it, chunkSize min 3)

                    stream.take(3).run.map: chunk =>
                        assert(chunk == Chunk(0, 1, 2))
                }

                "catching exception after values" in run {
                    val it = Iterator.tabulate(5)({
                        case 3 => throw new RuntimeException("fail")
                        case i => i
                    })

                    val stream = Stream.fromIteratorCatching[Throwable](it, chunkSize)

                    val errorTo42 = stream.handle(Abort.recoverOrThrow(e => Emit.value(Chunk(42))))

                    errorTo42.run.map: chunk =>
                        assert(chunk == Chunk(0, 1, 2, 42))
                }

                "compile error" in run {
                    val it = Iterator.tabulate(5)({
                        case 3 => throw new RuntimeException("fail")
                        case i => i
                    })

                    typeCheckFailure("Stream.fromIteratorCatching(it, chunkSize)")(
                        "Cannot catch Exceptions as `Failure[E]` using `E = Nothing`, they are turned into `Panic`"
                    )
                }

                "catching specific exception after values" in run {
                    class Oups(val value: Int) extends RuntimeException("fail")

                    val it = Iterator.tabulate(5)({
                        case 3 => throw new Oups(42)
                        case i => i
                    })

                    val stream = Stream.fromIteratorCatching[Oups](it, chunkSize).handle(Abort.recoverOrThrow((e: Oups) =>
                        Emit.value(Chunk(e.value))
                    ))

                    stream.run.map: chunk =>
                        assert(chunk == Chunk(0, 1, 2, 42))
                }

                "catching specific exception, panic" in run {
                    class Oups(val value: Int) extends RuntimeException("fail")

                    val it = Iterator.tabulate(5)({
                        case 3 => throw new RuntimeException("fail")
                        case i => i
                    })

                    val stream = Stream.fromIteratorCatching[Oups](it, chunkSize).handle(Abort.recoverOrThrow((e: Oups) =>
                        Emit.value(Chunk(e.value))
                    ))

                    Abort.run(stream.run).map({
                        case Result.Panic(_) => succeed
                        case _               => fail("should not be caught")

                    })
                }

                "map with Choice" in run {
                    val it = Iterator("a", "b", "c")
                    val stream: Stream[String, IO & Choice & Abort[Throwable]] =
                        Stream.fromIteratorCatching[Throwable](it, chunkSize).rechunk(10).map: str =>
                            Choice.eval(true, false).map:
                                case true  => str.toUpperCase
                                case false => str

                    Choice.run(stream.run).map: allCombinations =>
                        assert(allCombinations.size == 8)
                        assert(allCombinations.contains(Chunk("a", "B", "c")))

                }

            }

        "fromIteratorCatching" - {
            Seq(0, 1, 4, 32, 1024).foreach(fromIteratorCatchingTests)
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

        "groupedWithin" - {
            val stream = Stream {
                Loop(1): i =>
                    Emit.valueWith(Chunk(i)):
                        (if i % 5 == 0 then Async.sleep(30.millis) else Kyo.unit)
                            .andThen(Loop.continue(i + 1))
            }.take(11)

            "group by size" in run {
                stream.groupedWithin(3, Duration.Infinity).run.map: result =>
                    assert(result == Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8, 9), Chunk(10, 11)))
            }

            "group by time" in run {
                stream.groupedWithin(Int.MaxValue, 20.millis).run.map: result =>
                    assert(result == Chunk(Chunk(1, 2, 3, 4, 5), Chunk(6, 7, 8, 9, 10), Chunk(11)))
            }

            "group by size and time" in run {
                stream.groupedWithin(3, 20.millis).run.map: result =>
                    assert(result == Chunk(Chunk(1, 2, 3), Chunk(4, 5), Chunk(6, 7, 8), Chunk(9, 10), Chunk(11)))
            }

            "group to single chunk with max size + time" in run {
                stream.groupedWithin(Int.MaxValue, Duration.Infinity).run.map: result =>
                    assert(result == Chunk(1 to 11))
            }

            "empty" in run {
                Stream.empty[Int].groupedWithin(3, 100.millis).run.map: result =>
                    assert(result.isEmpty)
            }

            "single" in run {
                Stream.range(0, 1).groupedWithin(5, Duration.Infinity).run.map: result =>
                    assert(result == Chunk(Chunk(0)))
            }

            "with Env" in run {
                val envStream = Stream {
                    Env.use[Int]: maxValue =>
                        Loop(1): i =>
                            if i > maxValue then Loop.done
                            else
                                Emit.valueWith(Chunk(i)):
                                    Async.sleep(5.millis).andThen(Loop.continue(i + 1))
                }

                Env.run(7) {
                    envStream.groupedWithin(3, 15.millis).run.map: result =>
                        assert(result.flatten == (1 to 7))
                        assert(result.size >= 2) // Should have multiple groups due to timing
                }
            }

            // "with Abort" in run {
            //     val abortStream = Stream {
            //         Loop(1): i =>
            //             if i > 3 then Abort.fail("Stream failed")
            //             else Emit.valueWith(Chunk(i))(Loop.continue(i + 1))
            //     }

            //     Abort.run {
            //         abortStream.groupedWithin(5, Duration.Infinity).run
            //     }.map:
            //         case Result.Error(error) => assert(error.toString == "Stream failed")
            //         case Result.Success(_)   => fail("Expected stream to fail")
            // }
        }
    }

end StreamCoreExtensionsTest
