package kyo2

class sumsTest extends Test:

    "int" in {
        val v: String < Sum[Int] =
            for
                _ <- Sum.add(1)
                _ <- Sum.add(1)
                _ <- Sum.add(1)
            yield "a"

        assert(Sum.run(v).eval == (Chunk(1, 1, 1), "a"))
    }
    "string" in {
        val v: String < Sum[String] =
            for
                _ <- Sum.add("1")
                _ <- Sum.add("2")
                _ <- Sum.add("3")
            yield "a"
        val res = Sum.run(v)
        assert(res.eval == (Chunk("1", "2", "3"), "a"))
    }
    "int and string" in {
        val v: String < (Sum[Int] & Sum[String]) =
            for
                _ <- Sum.add(3)
                _ <- Sum.add("1")
                _ <- Sum.add(2)
                _ <- Sum.add("2")
                _ <- Sum.add(1)
                _ <- Sum.add("3")
            yield "a"
        val res: (Chunk[String], (Chunk[Int], String)) =
            Sum.run(Sum.run[Int](v)).eval
        assert(res == (Chunk("1", "2", "3"), (Chunk(3, 2, 1), "a")))
    }

    "no values" in {
        val t = Sum.run[Int]("a").eval
        assert(t == (Chunk.empty, "a"))

        val t2 = Sum.run[String](42).eval
        assert(t2 == (Chunk.empty, 42))
    }

    "List" in {
        val v =
            for
                _ <- Sum.add(List(1))
                _ <- Sum.add(List(2))
                _ <- Sum.add(List(3))
            yield "a"
        val res = Sum.run(v)
        assert(res.eval == (Chunk(List(1), List(2), List(3)), "a"))
    }

    "Set" in {
        val v =
            for
                _ <- Sum.add(Set(1))
                _ <- Sum.add(Set(2))
                _ <- Sum.add(Set(3))
            yield "a"
        val res = Sum.run(v)
        assert(res.eval == (Chunk(Set(1), Set(2), Set(3)), "a"))
    }

end sumsTest
