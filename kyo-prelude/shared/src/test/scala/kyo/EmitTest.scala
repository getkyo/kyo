package kyo

class EmitTest extends Test:

    "int" in {
        val v: String < Emit[Int] =
            for
                _ <- Emit.value(1)
                _ <- Emit.value(1)
                _ <- Emit.value(1)
            yield "a"

        assert(Emit.run(v).eval == (Chunk(1, 1, 1), "a"))
    }
    "string" in {
        val v: String < Emit[String] =
            for
                _ <- Emit.value("1")
                _ <- Emit.value("2")
                _ <- Emit.value("3")
            yield "a"
        val res = Emit.run(v)
        assert(res.eval == (Chunk("1", "2", "3"), "a"))
    }
    "int and string" in {
        val v: String < (Emit[Int] & Emit[String]) =
            for
                _ <- Emit.value(3)
                _ <- Emit.value("1")
                _ <- Emit.value(2)
                _ <- Emit.value("2")
                _ <- Emit.value(1)
                _ <- Emit.value("3")
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
                _ <- Emit.value(List(1))
                _ <- Emit.value(List(2))
                _ <- Emit.value(List(3))
            yield "a"
        val res = Emit.run(v)
        assert(res.eval == (Chunk(List(1), List(2), List(3)), "a"))
    }

    "Set" in {
        val v =
            for
                _ <- Emit.value(Set(1))
                _ <- Emit.value(Set(2))
                _ <- Emit.value(Set(3))
            yield "a"
        val res = Emit.run(v)
        assert(res.eval == (Chunk(Set(1), Set(2), Set(3)), "a"))
    }

    "runForeach" - {
        "with pure function" in {
            var seen = List.empty[Int]
            def emits(i: Int): Unit < Emit[Int] =
                if i == 5 then ()
                else Emit.valueWith(i)(emits(i + 1))
            end emits

            Emit.runForeach(emits(0)) { v =>
                seen :+= v
            }.eval
            assert(seen == List(0, 1, 2, 3, 4))
        }

        "with effects" in {
            var seen = List.empty[Int]
            def emits(i: Int): Unit < Emit[Int] =
                if i == 5 then ()
                else Emit.valueWith(i)(emits(i + 1))

            val result =
                Var.runTuple(0) {
                    Emit.runForeach(emits(0)) { v =>
                        for
                            count <- Var.get[Int]
                            _     <- Var.set(count + 1)
                            _ = seen :+= v
                        yield ()
                    }
                }.eval
            assert(seen == List(0, 1, 2, 3, 4))
            assert(result == (5, ()))
        }

        "early termination" in {
            var seen = List.empty[Int]
            def emits(i: Int): Unit < Emit[Int] =
                if i == 5 then ()
                else Emit.valueWith(i)(emits(i + 1))
            end emits

            val result = Abort.run[String] {
                Emit.runForeach(emits(0)) { v =>
                    seen :+= v
                    if v < 3 then ()
                    else Abort.fail("Reached 3")
                }
            }.eval
            assert(seen == List(0, 1, 2, 3))
            assert(result == Result.fail("Reached 3"))
        }
    }

    "runWhile" - {
        "with pure function" in {
            var seen = List.empty[Int]
            def emits(i: Int): Unit < Emit[Int] =
                if i == 5 then ()
                else Emit.valueWith(i)(emits(i + 1))
            end emits

            Emit.runWhile(emits(0)) { v =>
                seen :+= v
                if v < 3 then true
                else false
            }.eval
            assert(seen == List(0, 1, 2, 3))
        }

        "with effects" in {
            var seen = List.empty[Int]
            def emits(i: Int): Unit < Emit[Int] =
                if i == 5 then ()
                else Emit.valueWith(i)(emits(i + 1))

            val result =
                Env.run(3) {
                    Var.runTuple(0) {
                        Emit.runWhile(emits(0)) { v =>
                            for
                                threshold <- Env.get[Int]
                                count     <- Var.get[Int]
                                _         <- Var.set(count + 1)
                                _ = seen :+= v
                            yield if v <= threshold then true else false
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
                else Emit.valueWith(i)(emits(i + 1))
            end emits

            val result = Abort.run[String] {
                Emit.runWhile(emits(0)) { v =>
                    seen :+= v
                    if v < 3 then true
                    else Abort.fail("Reached 3")
                }
            }.eval
            assert(seen == List(0, 1, 2, 3))
            assert(result == Result.fail("Reached 3"))
        }
    }

    "runFold" - {
        "basic folding" in {
            val v =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "a"

            val result = Emit.runFold(0)((acc, v: Int) => acc + v)(v).eval
            assert(result == (6, "a"))
        }

        "with effects" in {
            val v =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "a"

            val result = Env.run(10) {
                Emit.runFold(0)((acc, v: Int) =>
                    for
                        threshold <- Env.get[Int]
                        newAcc = if v < threshold then acc + v else acc
                    yield newAcc
                )(v)
            }.eval
            assert(result == (6, "a"))
        }
    }

    "runDiscard" - {
        "discards all values" in {
            val v =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "a"

            val result = Emit.runDiscard(v).eval
            assert(result == "a")
        }

        "with effects" in {
            var count = 0
            val v =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield count

            val result = Emit.runDiscard(v).eval
            assert(result == 0)
            assert(count == 0) // Ensures values were truly discarded
        }
    }

    "runFirst" - {
        "basic operation" in run {
            val v =
                for
                    _ <- Emit.value(1)
                    _ <- Emit.value(2)
                    _ <- Emit.value(3)
                yield "done"

            for
                (v1, cont1)    <- Emit.runFirst(v)
                (v2, cont2)    <- Emit.runFirst(cont1())
                (v3, cont3)    <- Emit.runFirst(cont2())
                (v4, cont4)    <- Emit.runFirst(cont3())
                (rest, result) <- Emit.run(cont4())
            yield
                assert(v1.contains(1) && v2.contains(2) && v3.contains(3))
                assert(v4.isEmpty)
                assert(rest.isEmpty)
                assert(result == "done")
            end for
        }

        "empty emission" in run {
            val v: String < Emit[Int] = "done"

            for
                (v1, cont1)    <- Emit.runFirst(v)
                (rest, result) <- Emit.run(cont1())
            yield
                assert(v1.isEmpty)
                assert(rest.isEmpty)
                assert(result == "done")
            end for
        }

        "with effects" in run {
            val v =
                for
                    _ <- Emit.value(1)
                    _ <- Var.update[Int](_ + 1)
                    _ <- Emit.value(2)
                    _ <- Var.update[Int](_ + 1)
                yield "done"

            for
                result <- Var.runTuple(0) {
                    for
                        (v1, cont1)    <- Emit.runFirst(v)
                        (v2, cont2)    <- Emit.runFirst(cont1())
                        (v3, cont3)    <- Emit.runFirst(cont2())
                        (rest, result) <- Emit.run(cont3())
                    yield (v1, v2, v3, rest, result)
                }
            yield
                val (counter, (v1, v2, v3, rest, finalResult)) = result
                assert(v1.contains(1))
                assert(v2.contains(2))
                assert(v3.isEmpty)
                assert(rest.isEmpty)
                assert(finalResult == "done")
                assert(counter == 2)
            end for
        }
    }

    "generic type parameters" - {
        "single generic emit" in {
            def emitGeneric[T: Tag](value: T): Unit < Emit[T] =
                Emit.value(value).unit

            val result = Emit.run(emitGeneric(42)).eval
            assert(result == (Chunk(42), ()))
        }

        "multiple generic emits" in {
            def emitMultipleGeneric[T: Tag, U: Tag](t: T, u: U): Unit < (Emit[T] & Emit[U]) =
                for
                    _ <- Emit.value(t)
                    _ <- Emit.value(u)
                yield ()

            val result = Emit.run[Int](Emit.run[String](emitMultipleGeneric(42, "hello"))).eval
            assert(result == (Chunk(42), (Chunk("hello"), ())))
        }

        "nested generic emits" in {
            def nestedEmit[T: Tag, U: Tag](t: T, u: U): Unit < (Emit[T] & Emit[U] & Emit[(T, U)]) =
                for
                    _ <- Emit.value(t)
                    _ <- Emit.value(u)
                    _ <- Emit.value((t, u))
                yield ()

            val result = Emit.run[Int](Emit.run[String](Emit.run[(Int, String)](nestedEmit(42, "world")))).eval
            assert(result == (Chunk(42), (Chunk("world"), (Chunk((42, "world")), ()))))
        }

        "multiple generic emits with different types" in {
            def multiEmit[T: Tag, U: Tag, V: Tag](t: T, u: U, v: V): Unit < (Emit[T] & Emit[U] & Emit[V]) =
                for
                    _ <- Emit.value(t)
                    _ <- Emit.value(u)
                    _ <- Emit.value(v)
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
                    _ <- Emit.value[A](C)
                    _ <- Emit.value[B](C)
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
                        _ <- Emit.value[X](d)
                        _ <- Emit.value[Y](d)
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
                        _ <- Emit.value[P](e)
                        _ <- Emit.value[Q](e)
                        _ <- Emit.value[R](e)
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

    "isolate" - {
        "merge" - {
            "combines emitted values from isolated and outer scopes" in run {
                val result = Emit.run {
                    for
                        _ <- Emit.value(1)
                        isolated <- Emit.isolate.merge[Int].run {
                            for
                                _ <- Emit.value(2)
                                _ <- Emit.value(3)
                            yield "inner"
                        }
                        _ <- Emit.value(4)
                    yield (isolated)
                }
                assert(result.eval == (Chunk(1, 2, 3, 4), "inner"))
            }

            "proper state restoration after nested isolations" in run {
                val result = Emit.run {
                    for
                        _ <- Emit.value("start")
                        v1 <- Emit.isolate.merge[String].run {
                            for
                                _ <- Emit.value("inner1")
                                v2 <- Emit.isolate.merge[String].run {
                                    Emit.value("nested").map(_ => "nested-result")
                                }
                            yield v2
                        }
                        _ <- Emit.value("end")
                    yield v1
                }
                assert(result.eval == (Chunk("start", "inner1", "nested", "end"), "nested-result"))
            }
        }

        "discard" - {
            "inner emissions don't affect outer scope" in run {
                val result = Emit.run {
                    for
                        _ <- Emit.value(1)
                        isolated <- Emit.isolate.discard[Int].run {
                            for
                                _ <- Emit.value(2)
                                _ <- Emit.value(3)
                            yield "inner"
                        }
                        _ <- Emit.value(4)
                    yield isolated
                }
                assert(result.eval == (Chunk(1, 4), "inner"))
            }

            "nested discards maintain isolation" in run {
                val result = Emit.run {
                    for
                        _ <- Emit.value("outer")
                        v1 <- Emit.isolate.discard[String].run {
                            for
                                _ <- Emit.value("discarded1")
                                v2 <- Emit.isolate.discard[String].run {
                                    Emit.value("discarded2").map(_ => "nested-result")
                                }
                            yield v2
                        }
                        _ <- Emit.value("final")
                    yield v1
                }
                assert(result.eval == (Chunk("outer", "final"), "nested-result"))
            }
        }

        "composition" - {
            "can combine with Var isolate" in run {
                val emitIsolate = Emit.isolate.merge[Int]
                val varIsolate  = Var.isolate.discard[Int]

                val combined = emitIsolate.andThen(varIsolate)

                val result = Emit.run {
                    Var.runTuple(0) {
                        combined.run {
                            for
                                _ <- Emit.value(1)
                                _ <- Var.update[Int](_ + 1)
                                v <- Var.get[Int]
                                _ <- Emit.value(v)
                            yield "done"
                        }
                    }
                }
                assert(result.eval == (Chunk(1, 1), (0, "done")))
            }

            "can combine with Memo isolate" in run {
                var count = 0
                val f = Memo[Int, Int, Any] { x =>
                    count += 1
                    x * 2
                }

                val emitIsolate = Emit.isolate.merge[Int]
                val memoIsolate = Memo.isolate

                val combined = emitIsolate.andThen(memoIsolate)

                val result = Emit.run {
                    Memo.run {
                        combined.run {
                            for
                                a <- f(1)
                                _ <- Emit.value(a)
                                b <- f(1)
                                _ <- Emit.value(b)
                            yield (a, b)
                        }
                    }
                }
                assert(result.eval == (Chunk(2, 2), (2, 2)))
                assert(count == 1)
            }

            "preserves individual isolation behaviors when composed" in run {
                val emitDiscard = Emit.isolate.discard[Int]
                val emitMerge   = Emit.isolate.merge[Int]

                val result = Emit.run {
                    for
                        _ <- Emit.value(1)
                        _ <- emitDiscard.run {
                            Emit.value(2)
                        }
                        _ <- Emit.value(3)
                        _ <- emitMerge.run {
                            Emit.value(4)
                        }
                        _ <- Emit.value(5)
                    yield "done"
                }
                assert(result.eval == (Chunk(1, 3, 4, 5), "done"))
            }
        }
    }

    "valueWhen" - {
        "emits value when condition is true" in {
            val v: String < Emit[Int] =
                for
                    _ <- Emit.valueWhen(true)(1)
                    _ <- Emit.valueWhen(true)(2)
                    _ <- Emit.valueWhen(true)(3)
                yield "a"

            assert(Emit.run(v).eval == (Chunk(1, 2, 3), "a"))
        }

        "doesn't emit value when condition is false" in {
            val v: String < Emit[Int] =
                for
                    _ <- Emit.valueWhen(false)(1)
                    _ <- Emit.valueWhen(false)(2)
                    _ <- Emit.valueWhen(false)(3)
                yield "a"

            assert(Emit.run(v).eval == (Chunk.empty, "a"))
        }

        "mixed conditions" in {
            val v: String < Emit[Int] =
                for
                    _ <- Emit.valueWhen(true)(1)
                    _ <- Emit.valueWhen(false)(2)
                    _ <- Emit.valueWhen(true)(3)
                yield "a"

            assert(Emit.run(v).eval == (Chunk(1, 3), "a"))
        }

        "with effects in condition" in {
            val result = Var.runTuple(0) {
                Emit.run {
                    for
                        counter <- Var.get[Int]
                        _       <- Emit.valueWhen(Var.get[Int].map(_ == 0))(1)
                        _       <- Var.update[Int](_ + 1)
                        _       <- Emit.valueWhen(Var.get[Int].map(_ == 1))(2)
                        _       <- Var.update[Int](_ + 1)
                        _       <- Emit.valueWhen(Var.get[Int].map(_ == 3))(3)
                    yield "done"
                }
            }.eval

            assert(result == (2, (Chunk(1, 2), "done")))
        }

        "with complex conditions" in run {
            val isEven     = (n: Int) => n % 2 == 0
            val isPositive = (n: Int) => n > 0

            val result = Emit.run {
                for
                    _ <- Emit.valueWhen(isEven(1) && isPositive(1))("one")
                    _ <- Emit.valueWhen(isEven(2) && isPositive(2))("two")
                    _ <- Emit.valueWhen(isEven(3) || isPositive(3))("three")
                    _ <- Emit.valueWhen(isEven(-4) || isPositive(-4))("four")
                    _ <- Emit.valueWhen(isEven(-5) && isPositive(-5))("five")
                yield "done"
            }

            assert(result.eval == (Chunk("two", "three", "four"), "done"))
        }
    }

    "partial handling" - {
        enum T:
            case T1(int: Int)
            case T2(str: String)
        object T:
            given CanEqual[T, T] = CanEqual.derived

        val emit =
            for
                _ <- Emit.value[T.T1](T.T1(0))
                _ <- Emit.value[T.T2](T.T2("zero"))
                _ <- Emit.value[T.T1](T.T1(1))
                _ <- Emit.value[T.T2](T.T2("one"))
                _ <- Emit.value[T.T1](T.T1(2))
                _ <- Emit.value[T.T2](T.T2("two"))
            yield ()

        "run" in run {
            assert(Emit.runDiscard(Emit.run[T.T1](emit)).eval == (Chunk(T.T1(0), T.T1(1), T.T1(2)), ()))
            assert(Emit.run(Emit.runDiscard[T.T1](emit)).eval == (Chunk(T.T2("zero"), T.T2("one"), T.T2("two")), ()))
        }

        "runForeach" in run {
            var chunk = Chunk.empty[T.T1]
            Emit.runDiscard(Emit.runForeach[T.T1](emit)(t1 => chunk = chunk.appended(t1))).eval
            assert(chunk == Chunk(T.T1(0), T.T1(1), T.T1(2)))
        }

        "runWhile" in run {
            var chunk = Chunk.empty[T.T1]
            Emit.runDiscard(Emit.runWhile[T.T1](emit)(t1 =>
                chunk = chunk.appended(t1)
                t1.int < 1
            )).eval
            assert(chunk == Chunk(T.T1(0), T.T1(1)))
        }

        "runFirst" in run {
            val ranFirst = Emit.runDiscard:
                Emit.runFirst[T.T2](emit).map:
                    case (mv2, cont) =>
                        Emit.runFirst[T.T1](cont()).map:
                            case (mv1, cont) =>
                                Emit.runDiscard(cont())
                                    .andThen((mv2, mv1))

            assert(ranFirst.eval == (Present(T.T2("zero")), Present(T.T1(1))))
        }

    }
end EmitTest
