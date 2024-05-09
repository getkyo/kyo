package kyo.bench

import kyo.*
import org.scalatest.Assertions
import org.scalatest.freespec.AsyncFreeSpec

abstract class BenchTest extends AsyncFreeSpec with Assertions:

    enum Target:
        case Cats
        case Kyo
        case Zio
    end Target

    def target: Target
    def runSync[T](b: Bench.SyncAndFork[T]): T
    def runFork[T](b: Bench.Fork[T]): T

    val targets = Seq("cats", "kyo", "zio")

    def detectRuntimeLeak() =
        Thread.getAllStackTraces().forEach { (thread, stack) =>
            for deny <- targets.filter(_ != target.toString().toLowerCase()) do
                if stack.filter(!_.toString.contains("kyo.bench")).mkString.toLowerCase.contains(deny) then
                    fail(s"Detected $deny threads in a $target benchmark: $thread")
        }
        succeed
    end detectRuntimeLeak

    def test[T](b: Bench.SyncAndFork[T], expected: T)(using CanEqual[T, T]): Unit =
        "sync" in {
            assert(runSync(b) == expected)
            detectRuntimeLeak()
        }
        "fork" in {
            assert(runFork(b) == expected)
            detectRuntimeLeak()
        }
    end test

    def test[T](b: Bench.ForkOnly[T], expected: T)(using CanEqual[T, T]): Unit =
        "fork" in {
            assert(runFork(b) == expected)
            detectRuntimeLeak()
        }
    end test

    "BroadFlatMapBench" - {
        test(BroadFlatMapBench(), BigInt(610))
    }

    "ForkChainedBench" - {
        test(ForkChainedBench(), 0)
    }

    "CollectBench" - {
        val b = CollectBench()
        test(b, List.fill(b.count)(1))
    }

    "CollectParBench" - {
        val b = CollectParBench()
        test(b, List.fill(b.count)(1))
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
    }

    "ForkSpawnBench" - {
        test(ForkSpawnBench(), ())
    }

    "MtlBench" in {
        assert(MtlBench().syncKyo().isRight)
    }

    "StreamBench" - {
        test(StreamBench(), 25000000)
    }

    "StreamBufferBench" - {
        test(StreamBufferBench(), 25000000)
    }

    "FailureBench" - {
        given [T]: CanEqual[T, T] = CanEqual.derived
        test(FailureBench(), Left(Ex2))
    }

    "RandomBench" - {
        test(RandomBench(), ())
    }

    "ForkJoinBench" - {
        test(ForkJoinBench(), ())
    }

    "ForkJoinContentionBench" - {
        test(ForkJoinContentionBench(), ())
    }

    "HttpClientBench" - {
        test(HttpClientBench(), "pong")
    }

    "HttpClientContentionBench" - {
        test(HttpClientContentionBench(), Seq.fill(Runtime.getRuntime().availableProcessors())("pong"))
    }
end BenchTest
