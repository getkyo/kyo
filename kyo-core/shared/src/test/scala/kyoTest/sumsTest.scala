package kyoTest

import kyo.*

class sumsTest extends KyoTest:

    "int" in {
        val v: String < Sums[Int] =
            for
                _ <- Sums.add(1)
                _ <- Sums.add(1)
                _ <- Sums.add(1)
            yield "a"

        assert(Sums.run[Int](v).pure == (Chunks.init(1, 1, 1), "a"))
    }
    "string" in {
        val v: String < Sums[String] =
            for
                _ <- Sums.add("1")
                _ <- Sums.add("2")
                _ <- Sums.add("3")
            yield "a"
        val res = Sums.run[String](v)
        assert(res.pure == (Chunks.init("1", "2", "3"), "a"))
    }
    "int and string" in {
        val v: String < (Sums[Int] & Sums[String]) =
            for
                _ <- Sums.add(3)
                _ <- Sums.add("1")
                _ <- Sums.add(2)
                _ <- Sums.add("2")
                _ <- Sums.add(1)
                _ <- Sums.add("3")
            yield "a"
        val res: (Chunk[String], (Chunk[Int], String)) =
            Sums.run[String](Sums.run[Int](v)).pure
        assert(res == (Chunks.init("1", "2", "3"), (Chunks.init(3, 2, 1), "a")))
    }

    "no values" in {
        val t = Sums.run[Int]("a").pure
        assert(t == (Chunks.init, "a"))

        val t2 = Sums.run[String](42).pure
        assert(t2 == (Chunks.init, 42))
    }

    "List" in {
        val v =
            for
                _ <- Sums.add(List(1))
                _ <- Sums.add(List(2))
                _ <- Sums.add(List(3))
            yield "a"
        val res = Sums.run[List[Int]](v)
        assert(res.pure == (Chunks.init(List(1), List(2), List(3)), "a"))
    }

    "Set" in {
        val v =
            for
                _ <- Sums.add(Set(1))
                _ <- Sums.add(Set(2))
                _ <- Sums.add(Set(3))
            yield "a"
        val res = Sums.run[Set[Int]](v)
        assert(res.pure == (Chunks.init(Set(1), Set(2), Set(3)), "a"))
    }

    "run requires type param" in {
        assertDoesNotCompile("""
        Sums.run(Sums.add(1))
        """)
    }
end sumsTest
