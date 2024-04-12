package kyoTest

import kyo.*

class seqsTest extends KyoTest:

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
