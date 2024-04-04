package kyoTest

import kyo.*

class seqsTest extends KyoTest:

    "one" in {
        assert(
            Seqs.run(Seqs.get(Seq(1)).map(_ + 1)) ==
                Seq(2)
        )
    }
    "multiple" in {
        assert(
            Seqs.run(Seqs.get(Seq(1, 2, 3)).map(_ + 1)) ==
                Seq(2, 3, 4)
        )
    }
    "nested" in {
        assert(
            Seqs.run(Seqs.get(Seq(1, 2, 3)).map(i =>
                Seqs.get(Seq(i * 10, i * 100))
            )) ==
                Seq(10, 100, 20, 200, 30, 300)
        )
    }
    "drop" in {
        assert(
            Seqs.run(Seqs.get(Seq(1, 2, 3)).map(i =>
                if i < 2 then Seqs.drop
                else Seqs.get(Seq(i * 10, i * 100))
            )) ==
                Seq(20, 200, 30, 300)
        )
    }
    "filter" in {
        assert(
            Seqs.run(Seqs.get(Seq(1, 2, 3)).map(i =>
                Seqs.filter(i >= 2).map(_ => Seqs.get(Seq(i * 10, i * 100)))
            )) ==
                Seq(20, 200, 30, 300)
        )
    }
    "collect" in {
        assert(
            Seqs.collect(Seq(1, 2)).pure ==
                Seq(1, 2)
        )
    }
    "collectUnit" in {
        var count = 0
        val io    = IOs(count += 1)
        IOs.run(Seqs.collectUnit(List.fill(42)(io)))
        assert(count == 42)
    }
    "repeat" in {
        assert(
            Seqs.run(Seqs.repeat(3).andThen(42)) ==
                Seq(42, 42, 42)
        )
    }
    "traverse" in {
        assert(
            Seqs.traverse(Seq(1, 2))(_ + 1).pure ==
                Seq(2, 3)
        )
    }
    "traverseUnit" in {
        var acc = Seq.empty[Int]
        Seqs.traverseUnit(Seq(1, 2))(acc :+= _).pure
        assert(acc == Seq(1, 2))
    }
    "fold" in {
        assert(Seqs.fold(Seq(1, 2, 3))(0)(_ + _).pure == 6)
    }
    "fill" in {
        assert(
            IOs.run(Seqs.fill(100)(IOs(1))) ==
                Seq.fill(100)(1)
        )
    }
end seqsTest
