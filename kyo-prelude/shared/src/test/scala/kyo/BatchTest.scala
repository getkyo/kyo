import kyo.*
import scala.collection.mutable.ArrayBuffer

class BatchTest extends Test:

    val n = 10000

    class TestSource[A, B, S](f: Seq[A] => Seq[B] < S):
        private val callsBuffer = ArrayBuffer[Seq[A]]()
        private val source = Batch.sourceSeq[A, B, S] { seq =>
            callsBuffer += seq
            f(seq)
        }

        def apply(a: A): B < Batch[S] = source(a)

        def calls: Seq[Seq[A]] = callsBuffer.toSeq
    end TestSource

    "one eval, one source" in run {
        val source = TestSource[Int, Int, Any](seq => seq.map(_ + 1))
        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3))
                b <- source(a)
            yield (a, b)

        Batch.run(result).map { seq =>
            assert(seq == Seq((1, 2), (2, 3), (3, 4)))
            assert(source.calls == Seq(Seq(1, 2, 3)))
        }
    }

    "multiple evals, one source" in run {
        val source = TestSource[Int, String, Any](seq => seq.map(_.toString))
        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3))
                b <- Batch.eval(Seq(4, 5, 6))
                c <- source(a)
                d <- source(b)
            yield (a, b, c, d)

        Batch.run(result).map { seq =>
            assert(seq == Seq(
                (1, 4, "1", "4"),
                (1, 5, "1", "5"),
                (1, 6, "1", "6"),
                (2, 4, "2", "4"),
                (2, 5, "2", "5"),
                (2, 6, "2", "6"),
                (3, 4, "3", "4"),
                (3, 5, "3", "5"),
                (3, 6, "3", "6")
            ))
            assert(source.calls == Seq(Seq(1, 2, 3), Seq(4, 5, 6)))
        }
    }

    "one eval, multiple sources" in run {
        val source1 = TestSource[Int, String, Any](seq => seq.map(_.toString))
        val source2 = TestSource[Int, Int, Any](seq => seq.map(_ * 2))
        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3))
                b <- source1(a)
                c <- source2(a)
            yield (a, b, c)

        Batch.run(result).map { seq =>
            assert(seq == Seq(
                (1, "1", 2),
                (2, "2", 4),
                (3, "3", 6)
            ))
            assert(source1.calls == Seq(Seq(1, 2, 3)))
            assert(source2.calls == Seq(Seq(1, 2, 3)))
        }
    }

    "multiple eval, multiple sources" in run {
        val source1 = TestSource[Int, String, Any](seq => seq.map(_.toString))
        val source2 = TestSource[Int, Int, Any](seq => seq.map(_ * 2))
        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3))
                b <- Batch.eval(Seq(4, 5, 6))
                c <- source1(a)
                d <- source2(b)
            yield (a, b, c, d)

        Batch.run(result).map { seq =>
            assert(seq == Seq(
                (1, 4, "1", 8),
                (1, 5, "1", 10),
                (1, 6, "1", 12),
                (2, 4, "2", 8),
                (2, 5, "2", 10),
                (2, 6, "2", 12),
                (3, 4, "3", 8),
                (3, 5, "3", 10),
                (3, 6, "3", 12)
            ))
            assert(source1.calls == Seq(Seq(1, 2, 3)))
            assert(source2.calls == Seq(Seq(4, 5, 6)))
        }
    }

    "complex interleaved" in run {
        val source1 = TestSource[Int, String, Any](seq => seq.map(_.toString))
        val source2 = TestSource[String, Int, Any](seq => seq.map(_.length))
        val source3 = TestSource[Int, Double, Any](seq => seq.map(_ * 1.5))

        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3))
                b <- source1(a)
                c <- Batch.eval(Seq("x", "yy", "zzz"))
                d <- source2(c)
                e <- source3(a)
                f <- source2(b)
            yield (a, b, c, d, e, f)

        Batch.run(result).map { seq =>
            assert(seq == Seq(
                (1, "1", "x", 1, 1.5, 1),
                (1, "1", "yy", 2, 1.5, 1),
                (1, "1", "zzz", 3, 1.5, 1),
                (2, "2", "x", 1, 3.0, 1),
                (2, "2", "yy", 2, 3.0, 1),
                (2, "2", "zzz", 3, 3.0, 1),
                (3, "3", "x", 1, 4.5, 1),
                (3, "3", "yy", 2, 4.5, 1),
                (3, "3", "zzz", 3, 4.5, 1)
            ))
            assert(source1.calls == Seq(Seq(1, 2, 3)))
            assert(source2.calls == Seq(Seq("x", "yy", "zzz"), Seq("1", "2", "3")))
            assert(source3.calls == Seq(Seq(1, 2, 3)))
        }
    }

    "empty eval" in run {
        val source = TestSource[Int, String, Any](seq => seq.map(_.toString))
        val result =
            for
                a <- Batch.eval(Seq.empty[Int])
                b <- source(a)
            yield (a, b)

        Batch.run(result).map { seq =>
            assert(seq.isEmpty)
            assert(source.calls.isEmpty)
        }
    }

    "large batch" in run {
        val largeSeq = (1 to n).toSeq
        val source   = TestSource[Int, Int, Any](seq => seq.map(_ * 2))
        val result =
            for
                a <- Batch.eval(largeSeq)
                b <- source(a)
            yield (a, b)

        Batch.run(result).map { seq =>
            assert(seq.size == n)
            assert(seq.forall { case (a, b) => b == a * 2 })
            assert(source.calls == Seq(largeSeq))
        }
    }

    "favors batching" - {
        "simple case" in run {
            val complexSource = TestSource[Int, Int, Env[Int]] { seq =>
                Kyo.foreach(seq)(x => Env.use[Int](_ => x * 2))
            }
            val simpleSource = TestSource[Int, Int, Any](seq => seq.map(_ + 1))

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- complexSource(a)
                    c <- Batch.eval(Seq(4, 5, 6))
                    d <- simpleSource(c)
                yield (a, b, c, d)

            Env.run(10) {
                Batch.run(result).map { seq =>
                    assert(seq == Seq(
                        (1, 2, 4, 5),
                        (1, 2, 5, 6),
                        (1, 2, 6, 7),
                        (2, 4, 4, 5),
                        (2, 4, 5, 6),
                        (2, 4, 6, 7),
                        (3, 6, 4, 5),
                        (3, 6, 5, 6),
                        (3, 6, 6, 7)
                    ))
                    assert(complexSource.calls == Seq(Seq(1, 2, 3)))
                    assert(simpleSource.calls == Seq(Seq(4, 5, 6)))
                }
            }
        }

        "multiple complex sources" in run {
            val complexSource1 = TestSource[Int, Int, Env[Int]] { seq =>
                Kyo.foreach(seq)(x => Env.use[Int](env => x * env))
            }
            val complexSource2 = TestSource[Int, Int, Var[Int]] { seq =>
                Kyo.foreach(seq)(x => Var.use[Int](state => x + state))
            }

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- complexSource1(a)
                    c <- Batch.eval(Seq(4, 5, 6))
                    d <- complexSource2(c)
                yield (a, b, c, d)

            Env.run(10) {
                Var.run(5) {
                    Batch.run(result).map { seq =>
                        assert(seq == Seq(
                            (1, 10, 4, 9),
                            (1, 10, 5, 10),
                            (1, 10, 6, 11),
                            (2, 20, 4, 9),
                            (2, 20, 5, 10),
                            (2, 20, 6, 11),
                            (3, 30, 4, 9),
                            (3, 30, 5, 10),
                            (3, 30, 6, 11)
                        ))
                        assert(complexSource1.calls == Seq(Seq(1, 2, 3)))
                        assert(complexSource2.calls == Seq(Seq(4, 5, 6)))
                    }
                }
            }
        }

        "interleaved complex and simple sources" in run {
            val complexSource = TestSource[Int, Int, Env[Int]] { seq =>
                Kyo.foreach(seq)(x => Env.use[Int](env => x * env))
            }
            val simpleSource = TestSource[Int, Int, Any](seq => seq.map(_ + 1))

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- complexSource(a)
                    c <- simpleSource(a)
                    d <- Batch.eval(Seq(4, 5, 6))
                    e <- simpleSource(d)
                    f <- complexSource(d)
                yield (a, b, c, d, e, f)

            Env.run(10) {
                Batch.run(result).map { seq =>
                    assert(seq == Seq(
                        (1, 10, 2, 4, 5, 40),
                        (1, 10, 2, 5, 6, 50),
                        (1, 10, 2, 6, 7, 60),
                        (2, 20, 3, 4, 5, 40),
                        (2, 20, 3, 5, 6, 50),
                        (2, 20, 3, 6, 7, 60),
                        (3, 30, 4, 4, 5, 40),
                        (3, 30, 4, 5, 6, 50),
                        (3, 30, 4, 6, 7, 60)
                    ))
                    assert(complexSource.calls == Seq(Seq(1, 2, 3), Seq(4, 5, 6)))
                    assert(simpleSource.calls == Seq(Seq(1, 2, 3), Seq(4, 5, 6)))
                }
            }
        }
    }

    "error handling" in run {
        val errorSource = TestSource[Int, Int, Any] { seq =>
            if seq.contains(3) then throw new RuntimeException("Error in source")
            else seq.map(_ * 2)
        }

        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3, 4))
                b <- errorSource(a)
            yield (a, b)

        assertThrows[RuntimeException] {
            Batch.run(result).eval
        }
        assert(errorSource.calls == Seq(Seq(1, 2, 3, 4)))
    }

    "interleaved eval and source with dependencies" in run {
        val source1 = TestSource[Int, String, Any](seq => seq.map(_.toString))
        val source2 = TestSource[String, Int, Any](seq => seq.map(_.length))

        val result =
            for
                a <- Batch.eval(Seq(1, 2, 3))
                b <- source1(a)
                c <- Batch.eval(Seq("x", "yy", "zzz"))
                d <- source2(b)
                e <- source2(c)
            yield (a, b, c, d, e)

        Batch.run(result).map { seq =>
            assert(seq == Seq(
                (1, "1", "x", 1, 1),
                (1, "1", "yy", 1, 2),
                (1, "1", "zzz", 1, 3),
                (2, "2", "x", 1, 1),
                (2, "2", "yy", 1, 2),
                (2, "2", "zzz", 1, 3),
                (3, "3", "x", 1, 1),
                (3, "3", "yy", 1, 2),
                (3, "3", "zzz", 1, 3)
            ))
            assert(source1.calls == Seq(Seq(1, 2, 3)))
            assert(source2.calls == Seq(Seq("1", "2", "3"), Seq("x", "yy", "zzz")))
        }
    }

    "effects outside sources" - {
        "simple effect with source" in run {
            var counter = 0
            val source  = TestSource[Int, String, Any](seq => seq.map(_.toString))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    _ = counter += 1
                    b <- source(a)
                    c <- Batch.eval(Seq(4, 5))
                yield (a, b, c)

            Batch.run(result).map { seq =>
                assert(seq == Seq(
                    (1, "1", 4),
                    (1, "1", 5),
                    (2, "2", 4),
                    (2, "2", 5),
                    (3, "3", 4),
                    (3, "3", 5)
                ))
                assert(counter == 3)
                assert(source.calls == Seq(Seq(1, 2, 3)))
            }
        }

        "effect with Env and source" in run {
            val source = TestSource[Int, Int, Any](seq => seq.map(_ * 2))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- Env.use[Int](env => a * env)
                    c <- source(b)
                    d <- Batch.eval(Seq(4, 5))
                yield (a, b, c, d)

            Env.run(10) {
                Batch.run(result).map { seq =>
                    assert(seq == Seq(
                        (1, 10, 20, 4),
                        (1, 10, 20, 5),
                        (2, 20, 40, 4),
                        (2, 20, 40, 5),
                        (3, 30, 60, 4),
                        (3, 30, 60, 5)
                    ))
                    assert(source.calls == Seq(Seq(10, 20, 30)))
                }
            }
        }

        "effect with Var and source" in run {
            val source = TestSource[Int, String, Any](seq => seq.map(x => s"value: $x"))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    _ <- Var.update[Int](_ + a)
                    b <- Var.get[Int]
                    c <- source(b)
                    d <- Batch.eval(Seq(4, 5))
                yield (a, b, c, d)

            Var.run(0) {
                Batch.run(result).map { seq =>
                    assert(seq == Seq(
                        (1, 1, "value: 1", 4),
                        (1, 1, "value: 1", 5),
                        (2, 3, "value: 3", 4),
                        (2, 3, "value: 3", 5),
                        (3, 6, "value: 6", 4),
                        (3, 6, "value: 6", 5)
                    ))
                    assert(source.calls == Seq(Seq(1, 3, 6)))
                }
            }
        }

        "complex effect interleaved with sources" in run {
            var sideEffect = 0
            val source1    = TestSource[Int, Int, Any](seq => seq.map(_ * 2))
            val source2    = TestSource[Int, String, Any](seq => seq.map(x => s"result: $x"))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- Env.use[Int](env => a * env)
                    c <- source1(b)
                    _ = sideEffect += 1
                    d <- Var.update[Int](_ + c)
                    e <- Var.get[Int]
                    f <- source2(e)
                    g <- Batch.eval(Seq(4, 5))
                yield (a, b, c, e, f, g)

            Env.run(10) {
                Var.run(0) {
                    Batch.run(result).map { seq =>
                        assert(seq == Seq(
                            (1, 10, 20, 20, "result: 20", 4),
                            (1, 10, 20, 20, "result: 20", 5),
                            (2, 20, 40, 60, "result: 60", 4),
                            (2, 20, 40, 60, "result: 60", 5),
                            (3, 30, 60, 120, "result: 120", 4),
                            (3, 30, 60, 120, "result: 120", 5)
                        ))
                        assert(sideEffect == 3)
                        assert(source1.calls == Seq(Seq(10, 20, 30)))
                        assert(source2.calls == Seq(Seq(20, 60, 120)))
                    }
                }
            }
        }
    }

    "source with distinct output size" - {
        "fewer items" in run {
            val source = TestSource[Int, Int, Any](seq => seq.filter(_ % 2 == 0))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3, 4, 5))
                    b <- source(a)
                yield (a, b)

            Abort.run[IllegalStateException](Batch.run(result))
                .map { result =>
                    assert(result.isPanic)
                    assert(source.calls == Seq(Seq(1, 2, 3, 4, 5)))
                }
        }

        "more items" in run {
            val source = TestSource[Int, Int, Any](seq => seq.flatMap(x => Seq(x, x * 10)))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- source(a)
                yield (a, b)

            Abort.run[IllegalStateException](Batch.run(result))
                .map { result =>
                    assert(result.isPanic)
                    assert(source.calls == Seq(Seq(1, 2, 3)))
                }
        }

        "empty output" in run {
            val source = TestSource[Int, Int, Any](_ => Seq.empty)
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- source(a)
                yield (a, b)

            Abort.run[IllegalStateException](Batch.run(result))
                .map { result =>
                    assert(result.isPanic)
                    assert(source.calls == Seq(Seq(1, 2, 3)))
                }
        }
    }

    "Batch.source" - {
        "with individual effect suspensions" in run {
            var counter = 0
            val source = Batch.source[Int, String, Env[Int]] { seq =>
                val map = seq.map(i =>
                    i -> Env.use[Int](env =>
                        counter += 1
                        (i * env).toString
                    )
                ).toMap
                (i: Int) => map(i)
            }

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- source(a)
                yield (a, b)

            Env.run(10) {
                Batch.run(result).map { seq =>
                    assert(seq == Seq((1, "10"), (2, "20"), (3, "30")))
                    assert(counter == 3) // Each value caused a separate effect suspension
                }
            }
        }

        "with conditional effect suspensions" in run {
            var evenCounter = 0
            var oddCounter  = 0
            val source = Batch.source[Int, String, Env[Int] & Var[Int]] { seq =>
                val map = seq.map { i =>
                    i -> {
                        if i % 2 == 0 then
                            Env.use[Int] { env =>
                                evenCounter += 1
                                (i * env).toString
                            }
                        else
                            Var.update[Int](_ + i).map { _ =>
                                oddCounter += 1
                                i.toString
                            }
                    }
                }.toMap
                (i: Int) => map(i)
            }

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3, 4))
                    b <- source(a)
                yield (a, b)

            Env.run(10) {
                Var.runTuple(0) {
                    Batch.run(result).map { seq =>
                        assert(seq == Seq((1, "1"), (2, "20"), (3, "3"), (4, "40")))
                        assert(evenCounter == 2)
                        assert(oddCounter == 2)
                    }
                }.map { case (finalVarValue, _) =>
                    assert(finalVarValue == 4) // 1 + 3
                }
            }
        }
    }

    "Batch.sourceMap" - {
        "basic usage" in run {
            val source = Batch.sourceMap[Int, String, Any] { seq =>
                seq.map(i => i -> i.toString).toMap
            }

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- source(a)
                yield (a, b)

            Batch.run(result).map { seq =>
                assert(seq == Seq((1, "1"), (2, "2"), (3, "3")))
            }
        }

        "with effects" in run {
            val source = Batch.sourceMap[Int, String, Env[Int] & Var[Int]] { seq =>
                Env.use[Int] { env =>
                    Var.update[Int](_ + seq.sum).map { _ =>
                        seq.map(i => i -> (i * env).toString).toMap
                    }
                }
            }

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- source(a)
                yield (a, b)

            Env.run(10) {
                Var.runTuple(0) {
                    Batch.run(result).map { seq =>
                        assert(seq == Seq((1, "10"), (2, "20"), (3, "30")))
                    }
                }.map { case (finalVarValue, _) =>
                    assert(finalVarValue == 6) // 1 + 2 + 3
                }
            }
        }

        "error handling" in run {
            val source = Batch.sourceMap[Int, String, Any] { seq =>
                if seq.contains(3) then throw new RuntimeException("Error in source")
                else seq.map(i => i -> i.toString).toMap
            }

            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3, 4))
                    b <- source(a)
                yield (a, b)

            assertThrows[RuntimeException] {
                Batch.run(result).eval
            }
        }
    }

    "Batch.foreach" - {
        "simple usage" in run {
            val result = Batch.foreach(Seq(1, 2, 3))(x => x * 2)
            Batch.run(result).map { seq =>
                assert(seq == Seq(2, 4, 6))
            }
        }

        "with effects" in run {
            val result = Batch.foreach(Seq(1, 2, 3)) { x =>
                Env.use[Int](env => x * env)
            }
            Env.run(10) {
                Batch.run(result).map { seq =>
                    assert(seq == Seq(10, 20, 30))
                }
            }
        }

        "empty sequence" in run {
            val result = Batch.foreach(Seq.empty[Int])(x => x * 2)
            Batch.run(result).map { seq =>
                assert(seq.isEmpty)
            }
        }

        "with error handling" in run {
            val result = Batch.foreach(Seq(1, 2, 3, 4, 5)) { x =>
                if x == 3 then throw new RuntimeException("Error at 3")
                else x * 2
            }
            assertThrows[RuntimeException] {
                Batch.run(result).eval
            }
        }

        "with multiple effects" in run {
            var sideEffect = 0
            val result = Batch.foreach(Seq(1, 2, 3)) { x =>
                for
                    a <- Env.use[Int](env => x * env)
                    _ = sideEffect += 1
                    b <- Var.update[Int](_ + a)
                yield b
            }
            Env.run(10) {
                Var.runTuple(0) {
                    Batch.run(result).map { seq =>
                        assert(seq == Seq(10, 30, 60))
                        assert(sideEffect == 3)
                    }
                }.map { case (finalVarValue, _) =>
                    assert(finalVarValue == 60)
                }
            }
        }

        "interleaved with other Batch operations" in run {
            val source = TestSource[Int, String, Any](seq => seq.map(_.toString))
            val result =
                for
                    a <- Batch.eval(Seq(1, 2, 3))
                    b <- Batch.foreach(Seq(10, 20, 30))(x => x + a)
                    c <- source(b)
                yield (a, b, c)

            Batch.run(result).map { seq =>
                assert(seq == Seq(
                    (1, 11, "11"),
                    (1, 21, "21"),
                    (1, 31, "31"),
                    (2, 12, "12"),
                    (2, 22, "22"),
                    (2, 32, "32"),
                    (3, 13, "13"),
                    (3, 23, "23"),
                    (3, 33, "33")
                ))
                assert(source.calls == Seq(Seq(11, 21, 31, 12, 22, 32, 13, 23, 33)))
            }
        }
    }

end BatchTest
