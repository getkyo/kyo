package kyo.bench

import kyoTest.KyoTest

class BenchTest extends KyoTest {

  def test[T](b: Bench.SyncAndFork[T], expected: T): Unit = {
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

  def test[T](b: Bench.ForkOnly[T], expected: T): Unit = {
    "cats" - {
      "fork" in {
        assert(b.forkCats() == expected)
      }
    }
    "kyo" - {
      "fork" in {
        assert(b.forkKyo() == expected)
      }
    }
    "zio" - {
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

  "CountdownLatchBench" - {
    test(CountdownLatchBench(), 0)
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

  "ProducerConsumerBench" - {
    test(ProducerConsumerBench(), ())
  }

  "SemaphoreBench" - {
    test(SemaphoreBench(), ())
  }

  "SuspensionBench" - {
    test(SuspensionBench(), ())
  }
}
