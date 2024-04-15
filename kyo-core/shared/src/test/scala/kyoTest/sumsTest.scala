package kyoTest

import kyo.*

class sumsTest extends KyoTest:

    "int" in {
        val v: String < Sums[Int] =
            for
                _ <- Sums[Int].add(1)
                _ <- Sums[Int].add(1)
                _ <- Sums[Int].add(1)
            yield "a"

        assert(Sums[Int].run(v).pure == (Chunks.init(1, 1, 1), "a"))
    }
    "string" in {
        val v: String < Sums[String] =
            for
                _ <- Sums[String].add("1")
                _ <- Sums[String].add("2")
                _ <- Sums[String].add("3")
            yield "a"
        val res = Sums[String].run(v)
        assert(res.pure == (Chunks.init("1", "2", "3"), "a"))
    }
    "int and string" in {
        val v: String < (Sums[Int] & Sums[String]) =
            for
                _ <- Sums[Int].add(3)
                _ <- Sums[String].add("1")
                _ <- Sums[Int].add(2)
                _ <- Sums[String].add("2")
                _ <- Sums[Int].add(1)
                _ <- Sums[String].add("3")
            yield "a"
        val res: (Chunk[String], (Chunk[Int], String)) =
            Sums[String].run(Sums[Int].run(v)).pure
        assert(res == (Chunks.init("1", "2", "3"), (Chunks.init(3, 2, 1), "a")))
    }

    "no values" in {
        val t = Sums[Int].run("a").pure
        assert(t == (Chunks.init, "a"))

        val t2 = Sums[String].run(42).pure
        assert(t2 == (Chunks.init, 42))
    }

    "List" in {
        val v =
            for
                _ <- Sums[List[Int]].add(List(1))
                _ <- Sums[List[Int]].add(List(2))
                _ <- Sums[List[Int]].add(List(3))
            yield "a"
        val res = Sums[List[Int]].run(v)
        assert(res.pure == (Chunks.init(List(1), List(2), List(3)), "a"))
    }

    "Set" in {
        val v =
            for
                _ <- Sums[Set[Int]].add(Set(1))
                _ <- Sums[Set[Int]].add(Set(2))
                _ <- Sums[Set[Int]].add(Set(3))
            yield "a"
        val res = Sums[Set[Int]].run(v)
        assert(res.pure == (Chunks.init(Set(1), Set(2), Set(3)), "a"))
    }
end sumsTest
