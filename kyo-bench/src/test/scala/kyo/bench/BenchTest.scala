package kyo.bench

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.Assertions

class BenchTest extends AsyncFreeSpec with Assertions {

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

  "SemaphoreContentionBench" - {
    test(SemaphoreContentionBench(), ())
  }

  "SuspensionBench" - {
    test(SuspensionBench(), ())
  }

  "SchedulingBench" - {
    test(SchedulingBench(), 1001000)
  }

  "RendezvousBench" - {
    val b        = RendezvousBench()
    val expected = b.depth * (b.depth + 1) / 2
    test(b, expected)
    // "ox" - {
    //   "fork" in {
    //     assert(b.forkOx() == expected)
    //   }
    // }
  }
}
