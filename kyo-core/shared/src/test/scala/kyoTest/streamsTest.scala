package kyoTest

import kyo.*

class streamsTest extends KyoTest:

    "initValue" - {
        "single value" in {
            assert(
                Streams.initValue(1).runSeq.pure == (Seq(1), ())
            )
        }

        "multiple values" in {
            assert(
                Streams.initValue(1, 2, 3).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }
    }

    "initSeq" - {
        "empty" in {
            assert(
                Streams.initSeq(Seq()).runSeq.pure == (Seq.empty, ())
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
            Channels.init[Int | Stream.Done](3).map { ch =>
                ch.put(1).andThen(ch.put(2)).andThen(ch.put(Stream.Done)).map { _ =>
                    Streams.initChannel(ch).runSeq.map { result =>
                        assert(result == (Seq(1, 2), ()))
                    }
                }
            }
        }

        "empty channel" in run {
            Channels.init[Int | Stream.Done](2).map { ch =>
                ch.put(Stream.Done).andThen {
                    Streams.initChannel(ch).runSeq.map { result =>
                        assert(result == (Seq.empty, ()))
                    }
                }
            }
        }
    }

    "emitValue" - {
        "single value" in {
            assert(
                Streams.initSource(Streams.emitValue(1)).runSeq.pure == (Seq(1), ())
            )
        }

        "multiple values" in {
            assert(
                Streams.initSource(Streams.emitValue(1, 2, 3)).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }
    }

    "emitSeq" - {
        "empty" in {
            assert(
                Streams.initSource(Streams.emitSeq(Seq())).runSeq.pure == (Seq.empty, ())
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
    }

    "take" - {
        "zero" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).take(0).runSeq.pure == (Seq.empty, ())
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
                Streams.initSeq(Seq.fill(100000)(1)).drop(5).runSeq.pure._1.size == 100000 - 5
            )
        }
    }

    "filter" - {
        "non-empty" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).filter(_ % 2 == 0).runSeq.pure == (Seq(2), ())
            )
        }

        "all in" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).filter(_ => true).runSeq.pure == (Seq(1, 2, 3), ())
            )
        }

        "all out" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).filter(_ => false).runSeq.pure == (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(1 to 100000).filter(_ % 2 == 0).runSeq.pure._1.size == 100000 / 2
            )
        }
    }

    "collect" - {
        "to string" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).collect {
                    case v if v % 2 == 0 => Streams.emitValue(s"even: $v")
                }.runSeq.pure == (Seq("even: 2"), ())
            )
        }

        "none" in {
            assert(
                Streams.initSeq(Seq(1, 2, 3)).collect {
                    case v if false => ???
                }.runSeq.pure == (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams.initSeq(1 to 100000).collect {
                    case v if v % 2 == 0 => Streams.emitValue(v)
                }.runSeq.pure._1.size == 100000 / 2
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

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(100000)(1)).transform(_ + 1).runSeq.pure ==
                    (Seq.fill(100000)(2), ())
            )
        }
    }

    "runChannel" - {
        "non-empty stream" in runJVM {
            Channels.init[Int | Stream.Done](3).map { ch =>
                Streams.initSeq(Seq(1, 2, 3)).runChannel(ch).map { _ =>
                    ch.take.map { v1 =>
                        assert(v1 == 1)
                        ch.take.map { v2 =>
                            assert(v2 == 2)
                            ch.take.map { v3 =>
                                assert(v3 == 3)
                                ch.take.map { done =>
                                    assert(done == Stream.Done)
                                }
                            }
                        }
                    }
                }
            }
        }

        "empty stream" in run {
            Channels.init[Int | Stream.Done](2).map { ch =>
                Streams.initSeq(Seq()).runChannel(ch).map { _ =>
                    ch.take.map { done =>
                        assert(done == Stream.Done)
                    }
                }
            }
        }

        "stack safety" in run {
            Channels.init[Int | Stream.Done](100001).map { ch =>
                Streams.initSeq(Seq.fill(100000)(1)).runChannel(ch).andThen {
                    ch.drain.map { seq =>
                        assert(seq.size == 100001)
                    }
                }
            }
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

        "stack safety" in {
            assert(
                Streams.initSeq(Seq.fill(100000)(1)).runFold(0)(_ + _).pure == (100000, ())
            )
        }
    }

end streamsTest
