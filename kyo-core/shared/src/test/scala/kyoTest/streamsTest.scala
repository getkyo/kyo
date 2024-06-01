package kyoTest

import kyo.*

class streamsTest extends KyoTest:

    val n = 10000

    "initSeq" - {
        "empty" in {
            assert(
                Streams.initSeq(Seq[Int]()).runSeq.pure == (Seq.empty, ())
            )
        }

        "non-empty" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }
    }

    "initChannel" - {
        "non-empty channel" in run {
            Channels.init[Chunk[Int] | Stream.Done](3).map { ch =>
                ch.put(Chunks.init(1, 2)).andThen(ch.put(Stream.Done)).map { _ =>
                    Streams.initChannel(ch).runSeq.map { result =>
                        assert(result == (Seq(1, 2), ()))
                    }
                }
            }
        }

        "empty channel" in run {
            Channels.init[Chunk[Int] | Stream.Done](2).map { ch =>
                ch.put(Stream.Done).andThen {
                    Streams.initChannel(ch).runSeq.map { result =>
                        assert(result == (Seq.empty, ()))
                    }
                }
            }
        }
    }

    "emitSeq" - {
        "empty" in {
            assert(
                Streams.initSource(Streams.emitSeq(Seq.empty[Int])).runSeq.pure == (Seq.empty, ())
            )
        }

        "non-empty" in {
            assert(
                Streams.initSource(Streams.emitSeq(Seq(1, 2, 3))).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }
    }

    "buffer" - {
        "non-empty" in run {
            Streams.initSeq(Seq(1, 2, 3)).buffer(2).runSeq.map { r =>
                assert(r == (Seq(1, 2, 3), ()))
            }
        }

        "empty" in run {
            Streams.initSeq(Seq.empty[Int]).buffer(2).runSeq.map { r =>
                assert(r == (Seq.empty, ()))
            }
        }

        "other effects" - {
            "ok" - {
                "IOs" in run {
                    Streams.initSource[Int](IOs(Streams.emitSeq(Seq(1)))).buffer(10).runSeq.map { r =>
                        assert(r == (Seq(1), ()))
                    }
                }
                "Fibers" in run {
                    Streams.initSource[Int](
                        Fibers.delay(1.nanos)(Streams.emitSeq(Seq(1)))
                    ).buffer(10).runSeq.map { r =>
                        assert(r == (Seq(1), ()))
                    }
                }
            }
            "nok" in run {
                assertDoesNotCompile("Streams.initSource[Int](Vars[Int].get).buffer(10)")
            }
        }
    }

    "take" - {
        "zero" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).take(0).runSeq.pure == (Seq.empty, ())
            )
        }

        "negative" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).take(-1).runSeq.pure == (Seq.empty, ())
            )
        }

        "two" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).take(2).runSeq.pure == (Seq(1, 2), ())
            )
        }

        "more than available" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).take(5).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(100000)(1)).take(5).runSeq.pure ==
                    (Seq.fill(5)(1), ())
            )
        }
    }

    "drop" - {
        "zero" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).drop(0).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }

        "negative" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).drop(-1).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }

        "two" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).drop(2).runSeq.pure == (Seq(3), ())
            )
        }

        "more than available" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).drop(5).runSeq.pure == (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1)).drop(5).runSeq.pure._1.size == n - 5
            )
        }
    }

    "takeWhile" - {
        "take none" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).takeWhile(_ < 0).runSeq.pure == (Seq.empty, ())
            )
        }

        "take some" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3, 4, 5)).takeWhile(_ < 4).runSeq.pure == (Seq(
                    1,
                    2,
                    3
                ), ())
            )
        }

        "take all" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).takeWhile(_ < 10).runSeq.pure ==
                    (Seq(1, 2, 3), ())
            )
        }

        "empty stream" in {
            assert(
                Streams.initSeq(Seq.empty[Int]).takeWhile(_ => true).runSeq.pure ==
                    (Seq.empty, ())
            )
        }

        "with effects" in {
            var count  = 0
            val stream = Streams.initSeq(Seq(1, 2, 3, 4, 5))
            val taken = stream.takeWhile { v =>
                IOs { count += 1; count < 4 }
            }.runSeq
            assert(IOs.run(taken).pure == (Seq(1, 2, 3), ()))
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1)).takeWhile(_ == 1).runSeq.pure ==
                    (Seq.fill(n)(1), ())
            )
        }
    }

    "dropWhile" - {
        "drop none" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).dropWhile(_ < 0).runSeq.pure ==
                    (Seq(1, 2, 3), ())
            )
        }

        "drop some" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3, 4, 5)).dropWhile(_ < 4).runSeq.pure ==
                    (Seq(4, 5), ())
            )
        }

        "drop all" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).dropWhile(_ < 10).runSeq.pure ==
                    (Seq.empty, ())
            )
        }

        "empty stream" in {
            assert(
                Streams.initSeq(Seq.empty[Int]).dropWhile(_ => false).runSeq.pure ==
                    (Seq.empty, ())
            )
        }

        "with effects" in {
            var count  = 0
            val stream = Streams.initSeq(Seq(1, 2, 3, 4, 5))
            val dropped = stream.dropWhile { v =>
                IOs { count += 1; count < 3 }
            }.runSeq
            assert(IOs.run(dropped).pure == (Seq(3, 4, 5), ()))
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1) ++ Seq(2)).dropWhile(_ == 1).runSeq.pure ==
                    (Seq(2), ())
            )
        }
    }

    "filter" - {
        "non-empty" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).filter(_ % 2 == 0).runSeq.pure ==
                    (Seq(2), ())
            )
        }

        "all in" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).filter(_ => true).runSeq.pure ==
                    (Seq(1, 2, 3), ())
            )
        }

        "all out" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).filter(_ => false).runSeq.pure ==
                    (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(1 to n).filter(_ % 2 == 0).runSeq.pure._1.size ==
                    n / 2
            )
        }
    }

    "changes" - {
        "no duplicates" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).changes.runSeq.pure ==
                    (Seq(1, 2, 3), ())
            )
        }

        "with duplicates" in {
            assert(
                Streams.initSeq(Seq(1, 2, 2, 3, 2, 3, 3)).changes.runSeq.pure ==
                    (Seq(1, 2, 3, 2, 3), ())
            )
        }

        "empty stream" in {
            assert(
                Streams.initSeq(Seq.empty[Int]).changes.runSeq.pure ==
                    (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1)).changes.runSeq.pure ==
                    (Seq(1), ())
            )
        }
    }

    "collect" - {
        "to string" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).collect {
                    case v if v % 2 == 0 => Streams.emitSeq(Seq(s"even: $v"))
                }.runSeq.pure == (Seq("even: 2"), ())
            )
        }

        "none" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).collect {
                    case v if false => (): Unit < Streams[Int]
                }.runSeq.pure == (Seq.empty, ())
            )
        }

        "with effects" in {
            val stream = Streams.initSeq(Seq(1, 2, 3, 4, 5))
            val collected = stream.collect {
                case v if v % 2 == 0 =>
                    IOs(println(s"Even: $v")).andThen(Streams.emitSeq(Seq(v)))
            }.runSeq
            assert(IOs.run(collected).pure == (Seq(2, 4), ()))
        }

        "stack safety" in {
            assert(
                Streams.initSeq(1 to n).collect {
                    case v if v % 2 == 0 => Streams.emitSeq(Seq(v))
                }.runSeq.pure._1.size == n / 2
            )
        }
    }

    "transform" - {
        "double" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).transform(_ * 2).runSeq.pure == (Seq(2, 4, 6), ())
            )
        }

        "to string" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).transform(_.toString).runSeq.pure ==
                    (Seq("1", "2", "3"), ())
            )
        }

        "with effects" in {
            val stream      = Streams.initSeq(Seq(1, 2, 3))
            val transformed = stream.transform(v => IOs(v * 2)).runSeq
            assert(IOs.run(transformed).pure == (Seq(2, 4, 6), ()))
        }

        "with failures" in {
            val stream      = Streams.initSeq(Seq("1", "2", "abc", "3"))
            val transformed = stream.transform(v => Aborts.catching[NumberFormatException](v.toInt)).runSeq
            assert(Aborts.run(transformed).pure.isLeft)
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1)).transform(_ + 1).runSeq.pure ==
                    (Seq.fill(n)(2), ())
            )
        }
        "produce until" in pendingUntilFixed {
            IOs.run {
                Atomics.initLong(0).map { counter =>
                    Streams
                        .initSeq(0 until 100)
                        .transform(_ => counter.getAndIncrement)
                        .take(0)
                        .runSeq
                        .map {
                            case (seq, _) =>
                                assert(seq.isEmpty)
                        }.map { _ =>
                            counter.get.map(transforms => assert(transforms == 0))
                        }
                }
            }.pure
            ()
        }
    }

    "transformChunks" - {
        "double" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).transformChunks(_.take(2).map(_ * 2)).runSeq.pure == (Seq(2, 4), ())
            )
        }

        "to string" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).transformChunks(_.append(4).map(_.toString)).runSeq.pure ==
                    (Seq("1", "2", "3", "4"), ())
            )
        }

        "with effects" in {
            val stream      = Streams.initSeq(Seq(1, 2, 3))
            val transformed = stream.transformChunks(v => IOs(v.map(_ * 2))).runSeq
            assert(IOs.run(transformed).pure == (Seq(2, 4, 6), ()))
        }

        "with failures" in {
            val stream      = Streams.initSeq(Seq("1", "2", "abc", "3"))
            val transformed = stream.transformChunks(c => Aborts.catching[NumberFormatException](c.map(_.toInt))).runSeq
            assert(Aborts.run(transformed).pure.isLeft)
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1)).transformChunks(_.map(_ + 1)).runSeq.pure ==
                    (Seq.fill(n)(2), ())
            )
        }
        "produce until" in pendingUntilFixed {
            IOs.run {
                Atomics.initLong(0).map { counter =>
                    Streams
                        .initSeq(0 until 100)
                        .transformChunks(_.map(_ => counter.getAndIncrement))
                        .take(0)
                        .runSeq
                        .map {
                            case (seq, _) =>
                                assert(seq.isEmpty)
                        }.map { _ =>
                            counter.get.map(transforms => assert(transforms == 0))
                        }
                }
            }.pure
            ()
        }
    }

    "concat" - {
        "non-empty streams" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3))
                    .concat(Streams.initSeq(Seq(4, 5, 6)))
                    .runSeq.pure == (Seq(1, 2, 3, 4, 5, 6), ((), ()))
            )
        }

        "empty left stream" in {
            assert(
                Streams.initSeq(Seq.empty[Int])
                    .concat(Streams.initSeq(Seq(1, 2, 3)))
                    .runSeq.pure == (Seq(1, 2, 3), ((), ()))
            )
        }

        "empty right stream" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3))
                    .concat(Streams.initSeq(Seq.empty[Int]))
                    .runSeq.pure == (Seq(1, 2, 3), ((), ()))
            )
        }

        "both streams empty" in {
            assert(
                Streams.initSeq(Seq.empty[Int])
                    .concat(Streams.initSeq(Seq.empty[Int]))
                    .runSeq.pure == (Seq.empty, ((), ()))
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(1 to n)
                    .concat(Streams.initSeq(n + 1 to 2 * n))
                    .runSeq.pure == ((1 to (2 * n)).toSeq, ((), ()))
            )
        }
    }

    "runChannel" - {
        "non-empty stream" in runJVM {
            Channels.init[Chunk[Int] | Stream.Done](3).map { ch =>
                Streams.initSeq(Seq(1, 2, 3)).runChannel(ch).map { _ =>
                    ch.take.map { v1 =>
                        assert(v1.asInstanceOf[Chunk[Int]].toSeq == Seq(1, 2, 3))
                        ch.take.map { done =>
                            assert(done.asInstanceOf[Stream.Done] eq Stream.Done)
                        }
                    }
                }
            }
        }

        "empty stream" in run {
            Channels.init[Chunk[Int] | Stream.Done](2).map { ch =>
                Streams.initSeq(Seq()).runChannel(ch).map { _ =>
                    ch.take.map { done =>
                        assert(done.asInstanceOf[Stream.Done] eq Stream.Done)
                    }
                }
            }
        }

        "stack safety" in run {
            Channels.init[Chunk[Int] | Stream.Done](n + 1).map { ch =>
                Streams.initSeq(Seq.fill(n)(1)).runChannel(ch).andThen {
                    ch.drain.map { seq =>
                        assert(seq.size == 2)
                        assert(seq.head.asInstanceOf[Chunk[Int]].size == n)
                        assert(seq.tail.head.isInstanceOf[Stream.Done])
                    }
                }
            }
        }
    }

    "runDiscard" - {
        "non-empty stream" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).runDiscard.pure == ()
            )
        }

        "empty stream" in {
            assert(
                Streams.initSeq(Seq.empty[Int]).runDiscard.pure == ()
            )
        }
    }

    "runFold" - {
        "sum" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).runFold(0)(_ + _).pure == (6, ())
            )
        }

        "concat" in {
            assert(
                Streams.initSeq(Seq("a", "b", "c")).runFold("")(_ + _).pure == ("abc", ())
            )
        }

        "early termination" in {
            val stream = Streams.initSeq(Seq(1, 2, 3, 4, 5))
            val folded = stream.runFold(0) { (acc, v) =>
                if acc < 6 then acc + v else Aborts.fail(())
            }
            assert(Aborts.run[Unit](folded).pure.fold(_ => true, _ => false))
        }

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(n)(1)).runFold(0)(_ + _).pure == (n, ())
            )
        }
    }

    "nesting with other effect" in {
        val stream: Stream[Unit, Int, Any] < IOs =
            IOs(Seq(1, 2, 3)).map(seq => Streams.initSeq(seq))
        IOs.run(stream.map(_.runSeq)).pure
        succeed
    }

end streamsTest
