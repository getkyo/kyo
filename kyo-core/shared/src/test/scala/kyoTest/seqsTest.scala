package kyoTest

import kyo.*

class seqsTest extends KyoTest:
    "collect" in {
        assert(Seqs.collect(Seq.empty).pure == Seq.empty)
        assert(Seqs.collect(Seq(1)).pure == Seq(1))
        assert(Seqs.collect(Seq(1, 2)).pure == Seq(1, 2))
        assert(Seqs.collect(Seq.fill(100)(1)).pure == Seq.fill(100)(1))
        assert(Seqs.collect(List(1, 2, 3)).pure == List(1, 2, 3))
        assert(Seqs.collect(Vector(1, 2, 3)).pure == Vector(1, 2, 3))
    }

    "collectUnit" in {
        var count = 0
        val io    = IOs(count += 1)
        IOs.run(Seqs.collectUnit(Seq.empty))
        assert(count == 0)
        IOs.run(Seqs.collectUnit(Seq(io)))
        assert(count == 1)
        IOs.run(Seqs.collectUnit(List.fill(42)(io)))
        assert(count == 43)
        IOs.run(Seqs.collectUnit(Vector.fill(10)(io)))
        assert(count == 53)
    }

    "map" in {
        assert(Seqs.map(Seq.empty[Int])(_ + 1).pure == Seq.empty)
        assert(Seqs.map(Seq(1))(_ + 1).pure == Seq(2))
        assert(Seqs.map(Seq(1, 2))(_ + 1).pure == Seq(2, 3))
        assert(Seqs.map(Seq.fill(100)(1))(_ + 1).pure == Seq.fill(100)(2))
        assert(Seqs.map(List(1, 2, 3))(_ + 1).pure == List(2, 3, 4))
        assert(Seqs.map(Vector(1, 2, 3))(_ + 1).pure == Vector(2, 3, 4))
    }

    "foreach" in {
        var acc = Seq.empty[Int]
        IOs.run(Seqs.foreach(Seq.empty[Int])(v => IOs(acc :+= v)))
        assert(acc == Seq.empty)
        acc = Seq.empty[Int]
        IOs.run(Seqs.foreach(Seq(1))(v => IOs(acc :+= v)))
        assert(acc == Seq(1))
        acc = Seq.empty[Int]
        IOs.run(Seqs.foreach(Seq(1, 2))(v => IOs(acc :+= v)))
        assert(acc == Seq(1, 2))
        acc = Seq.empty[Int]
        IOs.run(Seqs.foreach(Seq.fill(100)(1))(v => IOs(acc :+= v)))
        assert(acc == Seq.fill(100)(1))
        acc = Seq.empty[Int]
        IOs.run(Seqs.foreach(List(1, 2, 3))(v => IOs(acc :+= v)))
        assert(acc == List(1, 2, 3))
        acc = Seq.empty[Int]
        IOs.run(Seqs.foreach(Vector(1, 2, 3))(v => IOs(acc :+= v)))
        assert(acc == Vector(1, 2, 3))
    }

    "foldLeft" in {
        assert(Seqs.foldLeft(Seq.empty[Int])(0)(_ + _).pure == 0)
        assert(Seqs.foldLeft(Seq(1))(0)(_ + _).pure == 1)
        assert(Seqs.foldLeft(Seq(1, 2, 3))(0)(_ + _).pure == 6)
        assert(Seqs.foldLeft(Seq.fill(100)(1))(0)(_ + _).pure == 100)
        assert(Seqs.foldLeft(List(1, 2, 3))(0)(_ + _).pure == 6)
        assert(Seqs.foldLeft(Vector(1, 2, 3))(0)(_ + _).pure == 6)
    }

    "fill" in {
        assert(IOs.run(Seqs.fill(0)(IOs(1))) == Seq.empty)
        assert(IOs.run(Seqs.fill(1)(IOs(1))) == Seq(1))
        assert(IOs.run(Seqs.fill(3)(IOs(1))) == Seq(1, 1, 1))
        assert(IOs.run(Seqs.fill(100)(IOs(1))) == Seq.fill(100)(1))
    }

    "repeat" in {
        var count = 0
        val io    = IOs(count += 1)

        IOs.run(Seqs.repeat(0)(io))
        assert(count == 0)

        count = 0
        IOs.run(Seqs.repeat(1)(io))
        assert(count == 1)

        count = 0
        IOs.run(Seqs.repeat(100)(io))
        assert(count == 100)

        count = 0
        IOs.run(Seqs.repeat(10000)(io))
        assert(count == 10000)
    }

    "stack safety" - {
        val n = 10000

        "collect" in {
            val largeSeq = Seq.fill(n)(1)
            assert(Seqs.collect(largeSeq).pure == largeSeq)
        }

        "collectUnit" in {
            var count = 0
            val io    = IOs(count += 1)
            IOs.run(Seqs.collectUnit(Seq.fill(n)(io)))
            assert(count == n)
        }

        "map" in {
            val largeSeq = Seq.fill(n)(1)
            assert(Seqs.map(largeSeq)(_ + 1).pure == Seq.fill(n)(2))
        }

        "foreach" in {
            var acc = Seq.empty[Int]
            IOs.run(Seqs.foreach(Seq.fill(n)(1))(v => IOs(acc :+= v)))
            assert(acc == Seq.fill(n)(1))
        }

        "foldLeft" in {
            val largeSeq = Seq.fill(n)(1)
            assert(Seqs.foldLeft(largeSeq)(0)(_ + _).pure == n)
        }

        "fill" in {
            assert(IOs.run(Seqs.fill(n)(IOs(1))) == Seq.fill(n)(1))
        }
    }
end seqsTest
