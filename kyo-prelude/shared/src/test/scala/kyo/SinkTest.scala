package kyo

class SinkTest extends Test:

    "sinks" - {
        "discard" in run {
            val sink = Sink.discard[Int]
            val stream = Stream:
                Var.get[Int].map: init =>
                    Loop(init): i =>
                        if i > 5 then Loop.done
                        else
                            Emit.valueWith(Chunk(i)):
                                Var.update[Int](_ + 1).map: next =>
                                    Loop.continue(next)
            val result = Var.runTuple(0)(sink.drain(stream)).eval
            assert(result == (6, ()))
        }

        "collect" in run {
            val sink   = Sink.collect[Int]
            val stream = Stream.init(0 to 5)
            val result = sink.drain(stream).eval
            assert(result == (0 to 5))
        }

        "count" in run {
            val sink   = Sink.count[Int]
            val stream = Stream.init(0 to 5)
            val result = sink.drain(stream).eval
            assert(result == 6)
        }

        "foreach" in run {
            val sink   = Sink.foreach((i: Int) => Var.update[List[Int]](i :: _).unit)
            val stream = Stream.init(0 to 5)
            val result = Var.runTuple(Nil)(sink.drain(stream)).eval
            assert(result == ((5 to 0 by -1), ()))
        }

        "foreachChunk" in run {
            val sink   = Sink.foreachChunk((ch: Chunk[Int]) => Var.update[List[Chunk[Int]]](ch :: _).unit)
            val stream = Stream.init(0 to 1).concat(Stream.init(2 to 3)).concat(Stream.init(4 to 5))
            val result = Var.runTuple(Nil)(sink.drain(stream)).eval
            assert(result == (List(Chunk(4, 5), Chunk(2, 3), Chunk(0, 1)), ()))
        }

        "fold" in run {
            val sink   = Sink.fold(0)((a: Int, v: String) => a + v.length)
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = sink.drain(stream).eval
            assert(result == 6)
        }

        "foldKyo" in run {
            val sink   = Sink.foldKyo(0)((a: Int, v: String) => Var.update[String](_ + v).andThen(a + v.length))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple("")(sink.drain(stream)).eval
            assert(result == ("abesee", 6))
        }
    }

    "contramap" - {
        "pure" in run {
            val s1     = Sink.fold[Int, Int](0)(_ + _)
            val s2     = s1.contramap((_: String).length)
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = s2.drain(stream).eval
            assert(result == 6)
        }

        "effectful" in run {
            val s1 = Sink.fold[Int, Int](0)(_ + _)
            val s2 = s1.contramap: (str: String) =>
                Var.update[Int](_ + 1).andThen(str.length)
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple(0)(s2.drain(stream)).eval
            assert(result == (3, 6))
        }
    }

    "contramapPure" in run {
        val s1     = Sink.fold[Int, Int](0)(_ + _)
        val s2     = s1.contramapPure((_: String).length)
        val stream = Stream.init(Seq("a", "be", "see"))
        val result = s2.drain(stream).eval
        assert(result == 6)
    }

    "contramapChunk" - {
        "pure" in run {
            val s1     = Sink.fold[Int, Int](0)(_ + _)
            val s2     = s1.contramapChunk((_: Chunk[String]).map(_.length))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = s2.drain(stream).eval
            assert(result == 6)
        }

        "effectful" in run {
            val s1 = Sink.fold[Int, Int](0)(_ + _)
            val s2 = s1.contramapChunk: (ch: Chunk[String]) =>
                Var.update[Int](_ + 1).andThen(ch.map(_.length))
            val stream = Stream.init(Seq("a", "be", "see"))
            val result = Var.runTuple(0)(s2.drain(stream)).eval
            assert(result == (1, 6))
        }
    }

    "contramapChunkPure" in run {
        val s1     = Sink.fold[Int, Int](0)(_ + _)
        val s2     = s1.contramapChunkPure((_: Chunk[String]).map(_.length))
        val stream = Stream.init(Seq("a", "be", "see"))
        val result = s2.drain(stream).eval
        assert(result == 6)
    }

    "map" - {
        "pure" in run {
            val s1     = Sink.fold[Int, Int](0)(_ + _)
            val s2     = s1.map(_.toString)
            val stream = Stream.init(1 to 4)
            val result = s2.drain(stream).eval
            assert(result == "10")
        }

        "effectful" in run {
            val s1 = Sink.fold[Int, Int](0)(_ + _)
            val s2 = s1.map: i =>
                Var.update[Int](_ + 1).andThen(i.toString)
            val stream = Stream.init(1 to 4)
            val result = Var.runTuple(0)(s2.drain(stream)).eval
            assert(result == (1, "10"))
        }
    }

    "zip" - {
        "x2" in {
            val s1     = Sink.foldKyo[Int, Int, Var[Int]](0)((a, v) => Var.get[Int].map(i => a + v + i))
            val s2     = Sink.foldKyo[Int, Int, Var[Int]](0)((a, v) => Var.get[Int].map(i => a + v - i))
            val zipped = s1.zip(s2)
            val stream = Stream.init(0 to 5)
            val res1   = Var.run(3)(s1.drain(stream)).eval
            val res2   = Var.run(3)(s2.drain(stream)).eval
            val result = Var.run(3)(zipped.drain(stream)).eval
            assert(result == (res1, res2))
        }

        "x10" in {
            val s1     = Sink.fold[Int, Int](0)(_ + _)
            val s2     = s1.map(_ + 1)
            val s3     = s2.map(_ + 1)
            val s4     = s3.map(_ + 1)
            val s5     = s4.map(_ + 1)
            val s6     = s5.map(_ + 1)
            val s7     = s6.map(_ + 1)
            val s8     = s7.map(_ + 1)
            val s9     = s8.map(_ + 1)
            val s10    = s9.map(_ + 1)
            val zipped = Sink.zip(s1, s2, s3, s4, s5, s6, s7, s8, s9, s10)
            val stream = Stream.init(0 to 5)
            val result = zipped.drain(stream).eval
            assert(result == (15, 16, 17, 18, 19, 20, 21, 22, 23, 24))
        }

        "early terminating sink" in {
            val s1 = Sink:
                Loop(0, 3): (acc, i) =>
                    Poll.one[Chunk[Int]].map:
                        case Absent => Loop.done(acc)
                        case Present(chunk) =>
                            val newAcc = acc + chunk.take(i).reduce(_ + _)
                            if chunk.size >= i then Loop.done(newAcc)
                            else Loop.continue(newAcc, i - chunk.size)
            val s2     = Sink.fold(0)((acc: Int, v: Int) => acc + v)
            val zipped = s1.zip(s2)
            val stream = Stream.init(0 to 5).rechunk(1)
            val result = zipped.drain(stream).eval
            assert(result == (3, 15))
        }
    }

end SinkTest
