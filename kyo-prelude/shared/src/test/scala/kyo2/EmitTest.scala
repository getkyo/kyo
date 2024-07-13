package kyo2

import kyo2.Emit.Ack.*

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

end EmitTest
