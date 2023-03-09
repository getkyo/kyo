package kyo.bench

import kyoTest.KyoTest

class BenchTest extends KyoTest {

  def test[T](b: Bench[T], expected: T): Unit = {
    "cats" - {
      "sync" in {
        assert(b.syncCats() == expected)
      }
      "fork" in {
        assert(b.forkCats() == expected)
      }
    }
    "kyo" - {
      "sync" in {
        assert(b.syncKyo() == expected)
      }
      "fork" in {
        assert(b.forkKyo() == expected)
      }
    }
    "zio" - {
      "sync" in {
        assert(b.syncZio() == expected)
      }
      "fork" in {
        assert(b.forkZio() == expected)
      }
    }
  }

  "BroadFlatMapBench" - {
    test(BroadFlatMapBench(), BigInt(610))
  }

  "ChainedForkBench" - {
    test(ChainedForkBench(), 0)
  }

  "CollectAllBench" - {
    test(CollectAllBench(), 1000L)
  }

  "DeepBindBench" - {
    test(DeepBindBench(), ())
  }

  "EnqueueDequeueBench" - {
    test(EnqueueDequeueBench(), ())
  }

  "ForkManyBench" - {
    test(ForkManyBench(), 0)
  }

  "NarrowBindBench" - {
    test(NarrowBindBench(), 10000)
  }

  "NarrowBindMapBench" - {
    test(NarrowBindMapBench(), 10000)
  }

  "PingPongBench" - {
    test(PingPongBench(), ())
  }
}
