package kyoTest

import kyo.*

class directTest extends KyoTest:

    // "match" in {
    //   val a = Defers(1)
    //   val b = Defers(2)
    //   val c =
    //     defer {
    //       await(a) match {
    //         case i if (await(b) > 0) => i
    //         case 2                   => 99
    //       }
    //     }
    //   assert(Defers.run(c) == 1)
    // }

    "one run" in {
        val io = defer {
            val a = await(Defers("hello"))
            a + " world"
        }
        assert(Defers.run(io).pure == "hello world")
    }

    "two runs" in {
        val io =
            defer {
                val a = await(Defers("hello"))
                val b = await(Defers("world"))
                a + " " + b
            }
        assert(Defers.run(io).pure == "hello world")
    }

    "two effects" in {
        val io: String < (Defers & Options) =
            defer {
                val a = await(Options.get(Some("hello")))
                val b = await(Defers("world"))
                a + " " + b
            }
        assert(Defers.run(Options.run(io)).pure == Some("hello world"))
    }

    "if" in {
        var calls = Seq.empty[Int]
        val io: Boolean < Defers =
            defer {
                if await(Defers { calls :+= 1; true }) then
                    await(Defers { calls :+= 2; true })
                else
                    await(Defers { calls :+= 3; true })
            }
        assert(Defers.run(io).pure)
        assert(calls == Seq(1, 2))
    }

    "booleans" - {
        "&&" in {
            var calls = Seq.empty[Int]
            val io: Boolean < Defers =
                defer {
                    (await(Defers { calls :+= 1; true }) && await(Defers { calls :+= 2; true }))
                }
            assert(Defers.run(io).pure)
            assert(calls == Seq(1, 2))
        }
        "||" in {
            var calls = Seq.empty[Int]
            val io: Boolean < Defers =
                defer {
                    (await(Defers { calls :+= 1; true }) || await(Defers { calls :+= 2; true }))
                }
            assert(Defers.run(io).pure)
            assert(calls == Seq(1))
        }
    }

    // "while" in {
    //     val io =
    //         defer {
    //             val c = await(Atomics.initInt(1))
    //             while await(c.get) < 100 do
    //                 await(c.incrementAndGet)
    //                 ()
    //             await(c.get)
    //         }
    //     assert(Defers.run(io).pure == 100)
    // }

    "options" in {
        def test(opt: Option[Int]) =
            assert(opt == Options.run(defer(await(Options.get(opt)))).pure)
        test(Some(1))
        test(None)
    }
    // "tries" in {
    //     def test(t: Try[Int]) =
    //         assert(t == Defers.run(Defers.attempt(defer(await(Defers.fromTry(t))))))
    //     test(Try(1))
    //     test(Try(throw new Exception("a")))
    // }
    // "consoles" in {
    //     object console extends Console:

    //         def printErr(s: String): Unit < Defers = ???

    //         def println(s: String): Unit < Defers = ???

    //         def print(s: String): Unit < Defers = ???

    //         def readln: String < Defers = "hello"

    //         def printlnErr(s: String): Unit < Defers = ???
    //     end console
    //     val io: String < Defers = Consoles.run(console)(defer(await(Consoles.readln)))
    //     assert(Defers.run(io) == "hello")
    // }

    "kyo computations must be within a run block" in {
        assertDoesNotCompile("defer(Defers(1))")
        assertDoesNotCompile("""
            defer {
                val a = Defers(1)
                10
            }
        """)
        assertDoesNotCompile("""
            defer {
                val a = {
                val b = Defers(1)
                10
                }
                10
            }
        """)
    }

    "choices" in {

        val x = Choices.get(Seq(1, -2, -3))
        val y = Choices.get(Seq("ab", "cde"))

        val v: Int < Choices =
            defer {
                val xx = await(x)
                xx + (
                    if xx > 0 then await(y).length * await(x)
                    else await(y).length
                )
            }

        val a: Seq[Int] = Choices.run(v).pure
        assert(a == Seq(3, -3, -5, 4, -5, -8, 0, 1, -1, 0))
    }

    "choices + filter" in {

        val x = Choices.get(Seq(1, -2, -3))
        val y = Choices.get(Seq("ab", "cde"))

        val v: Int < Choices =
            defer {
                val xx = await(x)
                val r =
                    xx + (
                        if xx > 0 then await(y).length * await(x)
                        else await(y).length
                    )
                await(Choices.filter(r > 0))
                r
            }

        assert(Choices.run(v).pure == Seq(3, 4, 1))
    }
end directTest
