package kyo

import kyo.Emit.Ack.*

class EmitTest extends Test:

    "int" in {
        val v: String < Emit[Int] =
            for
                _ <- Emit(1)
                _ <- Emit(1)
                _ <- Emit(1)
            yield "a"

        assert(Emit.run(v).eval == (Chunk(1, 1, 1), "a"))
    }
    "string" in {
        val v: String < Emit[String] =
            for
                _ <- Emit("1")
                _ <- Emit("2")
                _ <- Emit("3")
            yield "a"
        val res = Emit.run(v)
        assert(res.eval == (Chunk("1", "2", "3"), "a"))
    }
    "int and string" in {
        val v: String < (Emit[Int] & Emit[String]) =
            for
                _ <- Emit(3)
                _ <- Emit("1")
                _ <- Emit(2)
                _ <- Emit("2")
                _ <- Emit(1)
                _ <- Emit("3")
            yield "a"
        val res: (Chunk[String], (Chunk[Int], String)) =
            Emit.run(Emit.run[Int](v)).eval
        assert(res == (Chunk("1", "2", "3"), (Chunk(3, 2, 1), "a")))
    }

    "no values" in {
        val t = Emit.run[Int]("a").eval
        assert(t == (Chunk.empty, "a"))

        val t2 = Emit.run[String](42).eval
        assert(t2 == (Chunk.empty, 42))
    }

    "List" in {
        val v =
            for
                _ <- Emit(List(1))
                _ <- Emit(List(2))
                _ <- Emit(List(3))
            yield "a"
        val res = Emit.run(v)
        assert(res.eval == (Chunk(List(1), List(2), List(3)), "a"))
    }

    "Set" in {
        val v =
            for
                _ <- Emit(Set(1))
                _ <- Emit(Set(2))
                _ <- Emit(Set(3))
            yield "a"
        val res = Emit.run(v)
        assert(res.eval == (Chunk(Set(1), Set(2), Set(3)), "a"))
    }

    "Ack" - {
        "apply" - {
            "negative" in {
                val ack = Emit.Ack(-1)
                assert(ack == Emit.Ack.Stop)
            }
            "zero" in {
                val ack = Emit.Ack(0)
                assert(ack == Emit.Ack.Stop)
            }
            "positive" in {
                Emit.Ack(1) match
                    case Continue(n) => assert(n == 1)
                    case Stop        => fail()
            }
        }
        "maxItems" - {
            "stop with negative" in {
                val ack = Emit.Ack.Stop.maxItems(-1)
                assert(ack == Emit.Ack.Stop)
            }
            "stop with zero" in {
                val ack = Emit.Ack.Stop.maxItems(0)
                assert(ack == Emit.Ack.Stop)
            }
            "stop with positive" in {
                val ack = Emit.Ack.Stop.maxItems(1)
                assert(ack == Emit.Ack.Stop)
            }
            "continue with negative" in {
                val ack = Emit.Ack(2).maxItems(-1)
                assert(ack == Emit.Ack.Stop)
            }
            "continue with zero" in {
                val ack = Emit.Ack(2).maxItems(0)
                assert(ack == Emit.Ack.Stop)
            }
            "continue with less" in {
                val ack = Emit.Ack(2).maxItems(1)
                assert(ack == Emit.Ack(1))
            }
            "continue with more" in {
                val ack = Emit.Ack(2).maxItems(3)
                assert(ack == Emit.Ack(2))
            }
        }
        "Continue" - {
            "unapply" - {
                "stop" in {
                    val res = Emit.Ack.Continue.unapply(Emit.Ack.Stop)
                    assert(res.isEmpty)
                }
                "continue" in {
                    val res = Emit.Ack.Continue.unapply(Emit.Ack(2))
                    assert(res.get == 2)
                }
            }
        }
    }

    "runAck" - {
        "runAck" - {
            "with pure function" in {
                var seen = List.empty[Int]
                def emits(i: Int): Unit < Emit[Int] =
                    if i == 5 then ()
                    else
                        Emit.andMap(i) {
                            case Stop        => ()
                            case Continue(_) => emits(i + 1)
                        }

                Emit.runAck(emits(0)) { v =>
                    seen :+= v
                    if v < 3 then Emit.Ack.Continue()
                    else Emit.Ack.Stop
                }.eval
                assert(seen == List(0, 1, 2, 3))
            }

            "with effects" in {
                var seen = List.empty[Int]
                def emits(i: Int): Unit < Emit[Int] =
                    if i == 5 then ()
                    else
                        Emit.andMap(i) {
                            case Stop        => ()
                            case Continue(_) => emits(i + 1)
                        }

                val result =
                    Env.run(3) {
                        Var.runTuple(0) {
                            Emit.runAck(emits(0)) { v =>
                                for
                                    threshold <- Env.get[Int]
                                    count     <- Var.get[Int]
                                    _         <- Var.set(count + 1)
                                    _ = seen :+= v
                                yield if v <= threshold then Emit.Ack.Continue() else Emit.Ack.Stop
                            }
                        }
                    }.eval
                assert(seen == List(0, 1, 2, 3, 4))
                assert(result == (5, ()))
            }

            "early termination" in {
                var seen = List.empty[Int]
                def emits(i: Int): Unit < Emit[Int] =
                    if i == 5 then ()
                    else
                        Emit.andMap(i) {
                            case Stop        => ()
                            case Continue(_) => emits(i + 1)
                        }

                val result = Abort.run[String] {
                    Emit.runAck(emits(0)) { v =>
                        seen :+= v
                        if v < 3 then Emit.Ack.Continue()
                        else Abort.fail("Reached 3")
                    }
                }.eval
                assert(seen == List(0, 1, 2, 3))
                assert(result == Result.fail("Reached 3"))
            }
        }
    }

    "generic type parameters" - {
        "single generic emit" in {
            def emitGeneric[T: Tag](value: T): Unit < Emit[T] =
                Emit(value).unit

            val result = Emit.run(emitGeneric(42)).eval
            assert(result == (Chunk(42), ()))
        }

        "multiple generic emits" in {
            def emitMultipleGeneric[T: Tag, U: Tag](t: T, u: U): Unit < (Emit[T] & Emit[U]) =
                for
                    _ <- Emit(t)
                    _ <- Emit(u)
                yield ()

            val result = Emit.run[Int](Emit.run[String](emitMultipleGeneric(42, "hello"))).eval
            assert(result == (Chunk(42), (Chunk("hello"), ())))
        }

        "nested generic emits" in {
            def nestedEmit[T: Tag, U: Tag](t: T, u: U): Unit < (Emit[T] & Emit[U] & Emit[(T, U)]) =
                for
                    _ <- Emit(t)
                    _ <- Emit(u)
                    _ <- Emit((t, u))
                yield ()

            val result = Emit.run[Int](Emit.run[String](Emit.run[(Int, String)](nestedEmit(42, "world")))).eval
            assert(result == (Chunk(42), (Chunk("world"), (Chunk((42, "world")), ()))))
        }

        "multiple generic emits with different types" in {
            def multiEmit[T: Tag, U: Tag, V: Tag](t: T, u: U, v: V): Unit < (Emit[T] & Emit[U] & Emit[V]) =
                for
                    _ <- Emit(t)
                    _ <- Emit(u)
                    _ <- Emit(v)
                yield ()

            val result = Emit.run[Int](Emit.run[String](Emit.run[Boolean](multiEmit(42, "test", true)))).eval
            assert(result == (Chunk(42), (Chunk("test"), (Chunk(true), ()))))
        }
    }

    "different emit tags with same object instance" - {
        trait A derives CanEqual
        trait B derives CanEqual
        object C extends A with B

        "two emitters with same object" in {
            def emitC: Unit < (Emit[A] & Emit[B]) =
                for
                    _ <- Emit[A](C)
                    _ <- Emit[B](C)
                yield ()

            val result = Emit.run[A] {
                Emit.run[B] {
                    emitC
                }
            }.eval

            assert(result._1 == Chunk(C: A))
            assert(result._2._1 == Chunk(C))
            assert(result._2._2 == ())
        }

        "multiple emissions with different tags" in {
            trait X derives CanEqual
            trait Y derives CanEqual
            case class D(value: Int) extends X with Y

            def emitMultiple(values: List[D]): Unit < (Emit[X] & Emit[Y]) =
                Kyo.foreachDiscard(values) { d =>
                    for
                        _ <- Emit[X](d)
                        _ <- Emit[Y](d)
                    yield ()
                }

            val data = List(D(1), D(2), D(3))

            val result = Emit.run[X] {
                Emit.run[Y] {
                    emitMultiple(data)
                }
            }.eval

            assert(result._1 == Chunk(D(1), D(2), D(3)))
            assert(result._2._1 == Chunk(D(1), D(2), D(3)))
            assert(result._2._2 == ())
        }

        "complex scenario with multiple tags" in {
            trait P derives CanEqual
            trait Q derives CanEqual
            trait R derives CanEqual
            case class E(value: String) extends P with Q with R

            def complexEmit(values: List[E]): Unit < (Emit[P] & Emit[Q] & Emit[R]) =
                Kyo.foreachDiscard(values) { e =>
                    for
                        _ <- Emit[P](e)
                        _ <- Emit[Q](e)
                        _ <- Emit[R](e)
                    yield ()
                }

            val data = List(E("one"), E("two"), E("three"))

            val result = Emit.run[P] {
                Emit.run[Q] {
                    Emit.run[R] {
                        complexEmit(data)
                    }
                }
            }.eval

            assert(result._1 == Chunk(E("one"), E("two"), E("three")))
            assert(result._2._1 == Chunk(E("one"), E("two"), E("three")))
            assert(result._2._2._1 == Chunk(E("one"), E("two"), E("three")))
            assert(result._2._2._2 == ())
        }
    }
end EmitTest
