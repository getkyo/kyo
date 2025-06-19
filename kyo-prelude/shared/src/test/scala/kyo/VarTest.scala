package kyo

class VarTest extends Test:

    "get" in {
        val r = Var.run(1)(Var.get[Int].map(_ + 1)).eval
        assert(r == 2)
    }

    "use" in {
        val r = Var.run(1)(Var.use[Int](_ + 1)).eval
        assert(r == 2)
    }

    "setUnit, get" in {
        val r = Var.run(1)(Var.setDiscard(2).andThen(Var.get[Int])).eval
        assert(r == 2)
    }

    "set, get" in {
        val r = Var.run(1)(Var.set(2)).eval
        assert(r == 1)
    }

    "get, setUnit, get" in {
        val r = Var.run(1)(
            Var.get[Int].map(i => Var.setDiscard[Int](i + 1)).andThen(Var.get[Int])
        ).eval
        assert(r == 2)
    }

    "get, set" in {
        val r = Var.run(1)(
            Var.get[Int].map(i => Var.set[Int](i + 1))
        ).eval
        assert(r == 1)
    }

    "update" in {
        val r = Var.run(1)(Var.update[Int](_ + 1)).eval
        assert(r == 2)
    }

    "updateUnit" in {
        val r = Var.run(1)(Var.updateDiscard[Int](_ + 1).andThen(Var.get[Int])).eval
        assert(r == 2)
    }

    "runTuple" in {
        val r = Var.runTuple(1)(Var.update[Int](_ + 1).andThen(Var.get[Int]).map(_ + 1)).eval
        assert(r == (2, 3))
    }

    "setWith" in {
        val result = Var.run(1) {
            Var.setWith(2)(Var.use[Int](_ * 2))
        }.eval
        assert(result == 4)
    }

    "scope" - {
        "should not affect the outer state" in {
            val result = Var.run(42)(
                Var.run(24)(Var.get[Int]).map(_ => Var.get[Int])
            )
            assert(result.eval == 42)
        }

        "should allow updating the local state" in {
            val result = Var.run(42)(
                Var.run(24)(Var.update[Int](_ + 1).map(_ => Var.get[Int]))
            )
            assert(result.eval == 25)
        }
    }

    "nested let" in {
        Var.run(1) {
            Var.run(2) {
                Var.get[Int].map { innerValue =>
                    assert(innerValue == 2)
                }
            }.andThen(Var.get[Int])
                .map { outerValue =>
                    assert(outerValue == 1)
                }
        }.eval
    }

    "string value" in {
        val result = Var.run("a")(Var.setDiscard("b").andThen(Var.get[String])).eval
        assert(result == "b")
    }

    "side effect" in {
        var calls = 0
        val result =
            Var.run(1) {
                for
                    u <- Var.update[Int] { value =>
                        calls += 1
                        value + 1
                    }
                    result <- Var.get[Int]
                yield (u, result)
            }.eval
        assert(result == (2, 2) && calls == 1)
    }

    "inference" in {
        val a: Int < Var[Int]                    = Var.get[Int]
        val b: Unit < (Var[Int] & Var[String])   = a.map(i => Var.setDiscard(i.toString()))
        val c: String < (Var[Int] & Var[String]) = b.andThen(Var.set("c"))
        val d: String < Var[String]              = Var.run(1)(c)
        val e: String < Any                      = Var.run("t")(d)
        assert(e.eval == "1")
    }

    "isolate" - {
        "merge" - {
            "combines values from isolated and outer scopes" in run {
                val result = Var.runTuple(42) {
                    Var.isolate.merge[Int](_ + _).run {
                        for
                            outer <- Var.get[Int]
                            _     <- Var.set(10)
                            inner <- Var.get[Int]
                        yield (outer, inner)
                    }
                }
                assert(result.eval == (52, (42, 10)))
            }

            "proper state restoration after nested isolations" in run {
                val result = Var.runTuple(1) {
                    for
                        start <- Var.get[Int]
                        v1 <- Var.isolate.merge[Int](_ + _).run {
                            Var.update[Int](_ + 1).andThen(Var.get[Int])
                        }
                        middle <- Var.get[Int]
                        v2 <- Var.isolate.merge[Int](_ + _).run {
                            Var.update[Int](_ + 2).andThen(Var.get[Int])
                        }
                        end <- Var.get[Int]
                    yield (start, v1, middle, v2, end)
                }
                assert(result.eval == (8, (1, 2, 3, 5, 8)))
            }

            "with multishot" in run {
                val result: (Int, Chunk[Int]) < Any = Var.runTuple(1) {
                    Choice.run:
                        Choice.eval(1, 2, 3).map: i =>
                            Var.update[Int](_ + i)
                }

                assert(result.eval == (1 + 1 + 2 + 3, Chunk(2, 4, 7)))

                val resultIsolate: (Int, Chunk[Int]) < Any = Var.runTuple(1) {
                    Choice.run:
                        Var.isolate.merge[Int](_ + _).run:
                            Choice.eval(1, 2, 3).map: i =>
                                Var.update[Int](_ + i)
                }

                assert(resultIsolate.eval == (1 + 2 + 3 + 4, Chunk(2, 3, 4)))
            }

        }

        "update" - {
            "replaces outer value with inner value" in run {
                val result = Var.runTuple(42) {
                    for
                        before <- Var.get[Int]
                        _ <- Var.isolate.update[Int].run {
                            Var.set(10)
                        }
                        after <- Var.get[Int]
                    yield (before, after)
                }
                assert(result.eval == (10, (42, 10)))
            }

            "nested updates apply in order" in run {
                val result = Var.runTuple(1) {
                    for
                        start <- Var.get[Int]
                        _ <- Var.isolate.update[Int].run {
                            Var.update[Int](_ + 1).andThen {
                                Var.isolate.update[Int].run {
                                    Var.update[Int](_ * 2)
                                }
                            }
                        }
                        end <- Var.get[Int]
                    yield (start, end)
                }
                assert(result.eval == (4, (1, 4)))
            }

            "value changes preserved after effects" in run {
                val result = Var.runTuple(5) {
                    Env.run(2) {
                        for
                            start <- Var.get[Int]
                            _ <- Var.isolate.update[Int].run {
                                Env.use[Int] { multiplier =>
                                    Var.update[Int](_ * multiplier)
                                }
                            }
                            end <- Var.get[Int]
                        yield (start, end)
                    }
                }
                assert(result.eval == (10, (5, 10)))
            }
        }

        "discard" - {
            "inner modifications don't affect outer scope" in run {
                val result = Var.runTuple(42) {
                    for
                        before <- Var.get[Int]
                        _ <- Var.isolate.discard[Int].run {
                            Var.set(10)
                        }
                        after <- Var.get[Int]
                    yield (before, after)
                }
                assert(result.eval == (42, (42, 42)))
            }

            "nested discards maintain isolation" in run {
                val result = Var.runTuple(1) {
                    for
                        start <- Var.get[Int]
                        _ <- Var.isolate.discard[Int].run {
                            Var.update[Int](_ + 1).andThen {
                                Var.isolate.discard[Int].run {
                                    Var.update[Int](_ * 2)
                                }
                            }
                        }
                        end <- Var.get[Int]
                    yield (start, end)
                }
                assert(result.eval == (1, (1, 1)))
            }

            "effects execute but state changes discarded" in run {
                var sideEffect = 0
                val result = Var.runTuple(5) {
                    for
                        start <- Var.get[Int]
                        _ <- Var.isolate.discard[Int].run {
                            Var.update[Int] { x =>
                                sideEffect += 1
                                x * 2
                            }
                        }
                        end <- Var.get[Int]
                    yield (start, end, sideEffect)
                }
                assert(result.eval == (5, (5, 5, 1)))
            }

        }

        "composition" - {
            "can combine multiple isolates" in run {
                val i1 = Var.isolate.merge[Int](_ + _)
                val i2 = Var.isolate.update[Int]

                val combined = i1.andThen(i2)

                val result = Var.runTuple(1) {
                    for
                        start <- Var.get[Int]
                        _ <- combined.run {
                            Var.update[Int](_ + 1)
                        }
                        end <- Var.get[Int]
                    yield (start, end)
                }
                assert(result.eval == (2, (1, 2)))
            }

            "preserves individual isolation behaviors when composed" in run {
                val i1 = Var.isolate.discard[Int]
                val i2 = Var.isolate.update[Int]

                val result = Var.runTuple(1) {
                    for
                        start <- Var.get[Int]
                        _ <- i1.run {
                            Var.set(10)
                        }
                        middle <- Var.get[Int]
                        _ <- i2.run {
                            Var.set(20)
                        }
                        end <- Var.get[Int]
                    yield (start, middle, end)
                }
                assert(result.eval == (20, (1, 1, 20)))
            }

            "with Emit isolate" in run {
                val varIsolate  = Var.isolate.discard[Int]
                val emitIsolate = Emit.isolate.merge[Int]

                val combined = varIsolate.andThen(emitIsolate)

                val result = Var.runTuple(1) {
                    Emit.run {
                        combined.run {
                            for
                                _  <- Var.update[Int](_ + 1)
                                v  <- Var.get[Int]
                                _  <- Emit.value(v)
                                _  <- Var.update[Int](_ * 2)
                                v2 <- Var.get[Int]
                                _  <- Emit.value(v2)
                            yield ()
                        }
                    }
                }
                assert(result.eval == (1, (Chunk(2, 4), ())))
            }
        }
    }

    "updateWith" in {
        val result = Var.run(1) {
            Var.updateWith[Int](_ + 1)(v => v * 2)
        }.eval
        assert(result == 4)
    }

    "effect nesting" - {

        "state capture in nested computations" in {
            val nested =
                Kyo.lift {
                    Var.use[Int] { outer =>
                        Var.setWith(outer * 2) {
                            Kyo.lift(Var.get[Int])
                        }
                    }
                }

            val result = Var.run(5)(nested.flatten.flatten)
            assert(result.eval == 10)

            val stateTrack =
                Var.use[Int] { start =>
                    Kyo.lift {
                        Var.set(start + 1).andThen {
                            Var.get[Int]
                        }
                    }.flatten.map { inner =>
                        (start, inner)
                    }
                }

            assert(Var.run(5)(stateTrack).eval == (5, 6))
        }

        "nested state transformations" in {
            val nestedUpdates =
                Var.updateWith[Int](_ + 1) { _ =>
                    Kyo.lift {
                        Var.update[Int](_ * 2).andThen {
                            Var.update[Int](_ - 1).andThen {
                                Var.get[Int]
                            }
                        }
                    }
                }

            assert(Var.run(3)(nestedUpdates.flatten).eval == 7)
        }

        "ordering" in {
            val sequence = Kyo.lift {
                Var.setWith(List.empty[String]) {
                    Kyo.lift {
                        Var.updateWith[List[String]](_ :+ "first") { _ =>
                            Var.update[List[String]](_ :+ "second")
                        }
                    }.flatten.andThen {
                        Var.updateWith[List[String]](_ :+ "third") { _ =>
                            Var.get[List[String]]
                        }
                    }
                }
            }

            assert(Var.run(Nil)(sequence.flatten).eval == List("first", "second", "third"))
        }
    }
end VarTest
