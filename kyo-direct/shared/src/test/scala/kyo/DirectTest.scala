package kyo

class DirectTest extends Test:

    // "match" in {
    //   val a = IO(1)
    //   val b = IO(2)
    //   val c =
    //     defer {
    //       await(a) match {
    //         case i if (await(b) > 0) => i
    //         case 2                   => 99
    //       }
    //     }
    //   assert(IO.run(c) == 1)
    // }

    "one run" in {
        val io = defer {
            val a = await(IO("hello"))
            a + " world"
        }
        assert(IO.run(io).eval == "hello world")
    }

    "two runs" in {
        val io =
            defer {
                val a = await(IO("hello"))
                val b = await(IO("world"))
                a + " " + b
            }
        assert(IO.run(io).eval == "hello world")
    }

    "two effects" in {
        val io: String < (IO & Abort[Maybe.Empty]) =
            defer {
                val a = await(Abort.get(Some("hello")))
                val b = await(IO("world"))
                a + " " + b
            }
        assert(IO.run(Abort.run(io)).eval == Result.succeed("hello world"))
    }

    "if" in {
        var calls = Seq.empty[Int]
        val io: Boolean < IO =
            defer {
                if await(IO { calls :+= 1; true }) then
                    await(IO { calls :+= 2; true })
                else
                    await(IO { calls :+= 3; true })
            }
        assert(IO.run(io).eval)
        assert(calls == Seq(1, 2))
    }

    "booleans" - {
        "&&" in {
            var calls = Seq.empty[Int]
            val io: Boolean < IO =
                defer {
                    (await(IO { calls :+= 1; true }) && await(IO { calls :+= 2; true }))
                }
            assert(IO.run(io).eval)
            assert(calls == Seq(1, 2))
        }
        "||" in {
            var calls = Seq.empty[Int]
            val io: Boolean < IO =
                defer {
                    (await(IO { calls :+= 1; true }) || await(IO { calls :+= 2; true }))
                }
            assert(IO.run(io).eval)
            assert(calls == Seq(1))
        }
    }

    "while" in {
        val io =
            defer {
                val c = await(AtomicInt.init(1))
                while await(c.get) < 100 do
                    await(c.incrementAndGet)
                    ()
                await(c.get)
            }
        assert(IO.run(io).eval == 100)
    }

    "options" in {
        def test(opt: Option[Int]) =
            assert(opt == Abort.run(defer(await(Abort.get(opt)))).eval.fold(_ => None)(Some(_)))
        test(Some(1))
        test(None)
    }
    "consoles" in {
        object console extends Console:

            def printErr(s: String)(using Frame): Unit < IO = ???

            def println(s: String)(using Frame): Unit < IO = ???

            def print(s: String)(using Frame): Unit < IO = ???

            def readln(using Frame): String < IO = "hello"

            def printlnErr(s: String)(using Frame): Unit < IO = ???
        end console
        val io: String < IO = Console.let(console)(defer(await(Console.readln)))
        assert(IO.run(io).eval == "hello")
    }

    "kyo computations must be within a run block" in {
        assertDoesNotCompile("defer(IO(1))")
        assertDoesNotCompile("""
            defer {
                val a = IO(1)
                10
            }
        """)
        assertDoesNotCompile("""
            defer {
                val a = {
                val b = IO(1)
                10
                }
                10
            }
        """)
    }

    "Choice" in {

        val x = Choice.get(Seq(1, -2, -3))
        val y = Choice.get(Seq("ab", "cde"))

        val v: Int < Choice =
            defer {
                val xx = await(x)
                xx + (
                    if xx > 0 then await(y).length * await(x)
                    else await(y).length
                )
            }

        val a: Seq[Int] = Choice.run(v).eval
        assert(a == Seq(3, -3, -5, 4, -5, -8, 0, 1, -1, 0))
    }

    "Choice + filter" in {

        val x = Choice.get(Seq(1, -2, -3))
        val y = Choice.get(Seq("ab", "cde"))

        val v: Int < Choice =
            defer {
                val xx = await(x)
                val r =
                    xx + (
                        if xx > 0 then await(y).length * await(x)
                        else await(y).length
                    )
                await(Choice.dropIf(r <= 0))
                r
            }

        assert(Choice.run(v).eval == Seq(3, 4, 1))
    }
end DirectTest
