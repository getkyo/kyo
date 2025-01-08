package kyo

class DirectTest extends Test:

    "one run" in run {
        val io = defer {
            val a = IO("hello").now
            a + " world"
        }
        io.map { result =>
            assert(result == "hello world")
        }
    }

    "two runs" in run {
        val io =
            defer {
                val a = IO("hello").now
                val b = IO("world").now
                a + " " + b
            }
        io.map { result =>
            assert(result == "hello world")
        }
    }

    "two effects" in run {
        val io: String < (Abort[Absent] & IO) =
            defer {
                val a = Abort.get(Some("hello")).now
                val b = IO("world").now
                a + " " + b
            }
        Abort.run(io).map { result =>
            assert(result == Result.success("hello world"))
        }
    }

    "if" in run {
        var calls = Seq.empty[Int]
        val io: Boolean < IO =
            defer {
                if IO { calls :+= 1; true }.now then
                    IO { calls :+= 2; true }.now
                else
                    IO { calls :+= 3; true }.now
            }
        io.map { result =>
            assert(result)
            assert(calls == Seq(1, 2))
        }
    }

    "booleans" - {
        "&&" in run {
            var calls = Seq.empty[Int]
            val io: Boolean < IO =
                defer {
                    (IO { calls :+= 1; true }.now && IO { calls :+= 2; true }.now)
                }
            io.map { result =>
                assert(result)
                assert(calls == Seq(1, 2))
            }
        }
        "||" in run {
            var calls = Seq.empty[Int]
            val io: Boolean < IO =
                defer {
                    (IO { calls :+= 1; true }.now || IO { calls :+= 2; true }.now)
                }
            io.map { result =>
                assert(result)
                assert(calls == Seq(1))
            }
        }
    }

    "while" in run {
        val io =
            defer {
                val c = AtomicInt.init(1).now
                while c.get.now < 100 do
                    c.incrementAndGet.now
                    ()
                c.get.now
            }
        io.map { result =>
            assert(result == 100)
        }
    }

    "options" in {
        def test(opt: Option[Int]) =
            assert(opt == Abort.run(defer(Abort.get(opt).now)).eval.fold(_ => None)(Some(_)))
        test(Some(1))
        test(None)
    }
    "consoles" in run {
        Console.withIn(List("hello"))(defer(Abort.run(Console.readLine).now)).map { result =>
            assert(result.contains("hello"))
        }
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
                val xx = x.now
                xx + (
                    if xx > 0 then y.now.length * x.now
                    else y.now.length
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
                val xx = x.now
                val r =
                    xx + (
                        if xx > 0 then y.now.length * x.now
                        else y.now.length
                    )
                Choice.dropIf(r <= 0).now
                r
            }

        assert(Choice.run(v).eval == Seq(3, 4, 1))
    }
end DirectTest
