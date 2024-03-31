package kyoTest

import kyo.*

class streamsTest extends KyoTest:

    "emit" - {

        "empty" in {
            assert(
                Streams[Int].runSeq(()).pure == (Seq.empty, ())
            )
        }

        "value" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].emit(1).andThen(Streams[Int].emit(2))
                ).pure ==
                    (Seq(1, 2), ())
            )
        }

        "varargs" in {
            assert(
                Streams[Int].runSeq(Streams[Int].emit(1, 2)).pure ==
                    (Seq(1, 2), ())
            )
        }

        "seq" in {
            assert(
                Streams[Int].runSeq(Streams[Int].emit(Seq(1, 2))).pure ==
                    (Seq(1, 2), ())
            )
        }

        "channel" in run {
            Channels.init[Int | Streams.Done](3).map { ch =>
                ch.put(1).andThen(ch.put(2)).andThen(ch.put(Streams.Done)).map { _ =>
                    Streams[Int].runSeq(Streams[Int].emit(ch)).map { result =>
                        assert(result == (Seq(1, 2), ()))
                    }
                }
            }
        }

        "empty channel" in run {
            Channels.init[Int | Streams.Done](2).map { ch =>
                ch.put(Streams.Done).andThen {
                    Streams[Int].runSeq(Streams[Int].emit(ch)).map { result =>
                        assert(result == (Seq.empty, ()))
                    }
                }
            }
        }

    }

    "buffer" - {
        "non-empty" in run {
            Streams[Int].runSeq(
                Streams[Int].buffer(2)(Streams[Int].emit(1, 2, 3))
            ).map { r =>
                assert(r == (Seq(1, 2, 3), ()))
            }
        }

        "empty" in run {
            Streams[Int].runSeq(
                Streams[Int].buffer(2)(Streams[Int].emit(Seq.empty[Int]))
            ).map { r =>
                assert(r == (Seq.empty, ()))
            }
        }
    }

    "take" - {

        "zero" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].take(0)(Streams[Int].emit(1, 2, 3))
                ).pure == (Seq.empty, ())
            )
        }

        "two" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].take(2)(Streams[Int].emit(1, 2, 3))
                ).pure == (Seq(1, 2), ())
            )
        }

        "more than available" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].take(5)(Streams[Int].emit(1, 2, 3))
                ).pure == (Seq(1, 2, 3), ())
            )
        }

        "stack safety" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].take(5)(Streams[Int].emit(Seq.fill(100000)(1)))
                ).pure == (Seq.fill(5)(1), ())
            )
        }
    }

    "drop" - {

        "zero" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].drop(0)(Streams[Int].emit(1, 2, 3))
                ).pure == (Seq(1, 2, 3), ())
            )
        }

        "two" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].drop(2)(Streams[Int].emit(1, 2, 3))
                ).pure == (Seq(3), ())
            )
        }

        "more than available" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].drop(5)(Streams[Int].emit(1, 2, 3))
                ).pure == (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].drop(5)(Streams[Int].emit(Seq.fill(100000)(1)))
                ).pure._1.size == 100000 - 5
            )
        }
    }

    "filter" - {
        "non-empty" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].filter(Streams[Int].emit(1, 2, 3))(_ % 2 == 0)
                ).pure == (Seq(2), ())
            )
        }

        "all in" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].filter(Streams[Int].emit(1, 2, 3))(_ => true)
                ).pure == (Seq(1, 2, 3), ())
            )
        }

        "all out" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].filter(Streams[Int].emit(1, 2, 3))(_ => false)
                ).pure == (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].filter(Streams[Int].emit(1 to 100000))(_ % 2 == 0)
                ).pure._1.size == 100000 / 2
            )
        }
    }

    "collect" - {
        "to string" in {
            assert(
                Streams[String].runSeq(Streams[Int].collect(Streams[Int].emit(1, 2, 3)) {
                    case v if v % 2 == 0 => Streams[String].emit(s"even: $v")
                }).pure == (Seq("even: 2"), ())
            )
        }

        "none" in {
            assert(
                Streams[String].runSeq(Streams[Int].collect(Streams[Int].emit(1, 2, 3)) {
                    case v if false => ???
                }).pure == (Seq.empty, ())
            )
        }

        "stack safety" in {
            assert(
                Streams[Int].runSeq(Streams[Int].collect(Streams[Int].emit(1 to 100000)) {
                    case v if v % 2 == 0 => Streams[Int].emit(v)
                }).pure._1.size == 100000 / 2
            )
        }
    }

    "transform" - {
        "double" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].transform(Streams[Int].emit(1, 2, 3))(_ * 2)
                ).pure == (Seq(2, 4, 6), ())
            )
        }

        "to string" in {
            assert(
                Streams[String].runSeq(
                    Streams[Int].transform(Streams[Int].emit(1, 2, 3))(_.toString)
                ).pure == (Seq("1", "2", "3"), ())
            )
        }

        "one stream to another" in {
            assert(
                Streams[Int].runSeq(
                    Streams[String].transform(
                        Streams[Int].emit(0).andThen(Streams[String].emit("1", "2", "3"))
                    )(_.toInt)
                ).pure == (Seq(0, 1, 2, 3), ())
            )
        }

        "stack safety" in {
            assert(
                Streams[Int].runSeq(
                    Streams[Int].transform(Streams[Int].emit(Seq.fill(100000)(1)))(_ + 1)
                ).pure == (Seq.fill(100000)(2), ())
            )
        }
    }

    "runChannel" - {
        "non-empty stream" in runJVM {
            Channels.init[Int | Streams.Done](3).map { ch =>
                Streams[Int].runChannel(ch)(Streams[Int].emit(1, 2, 3)).map { _ =>
                    ch.take.map { v1 =>
                        assert(v1 == 1)
                        ch.take.map { v2 =>
                            assert(v2 == 2)
                            ch.take.map { v3 =>
                                assert(v3 == 3)
                                ch.take.map { done =>
                                    assert(done == Streams.Done)
                                }
                            }
                        }
                    }
                }
            }
        }

        "empty stream" in run {
            Channels.init[Int | Streams.Done](2).map { ch =>
                Streams[Int].runChannel(ch)(()).map { _ =>
                    ch.take.map { done =>
                        assert(done == Streams.Done)
                    }
                }
            }
        }

        "stack safety" in run {
            Channels.init[Int | Streams.Done](100001).map { ch =>
                Streams[Int].runChannel(ch)(Streams[Int].emit(Seq.fill(100000)(1))).andThen {
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
                Streams[Int].runFold(Streams[Int].emit(1, 2, 3))(0)(_ + _).pure ==
                    (6, ())
            )
        }

        "concat" in {
            assert(
                Streams[String].runFold(
                    Streams[String].emit("a", "b", "c")
                )("")(_ + _).pure == ("abc", ())
            )
        }

        "stack safety" in {
            assert(
                Streams[Int].runFold(
                    Streams[Int].emit(Seq.fill(100000)(1))
                )(0)(_ + _).pure ==
                    (100000, ())
            )
        }
    }

end streamsTest
