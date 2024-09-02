package kyo.bench

import WarmupJITProfile.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kyo.bench.Bench.*
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import scala.concurrent.duration.*

@State(Scope.Benchmark)
abstract class WarmupJITProfile:
    run()
    def run() =
        if warmupSeconds <= 0 then
            println("JIT profile warmup disabled")
        else
            println("Warming up JIT profile for " + warmupSeconds.seconds)
            val deadline = System.currentTimeMillis() + warmupSeconds.seconds.toMillis
            val exec     = Executors.newFixedThreadPool(warmupThreads)
            try
                val cdl = new CountDownLatch(warmupThreads)
                (0 until warmupThreads).foreach { _ =>
                    exec.execute(() =>
                        while System.currentTimeMillis() < deadline do
                            warmupBenchs.foreach {
                                case bench: SyncAndFork[?] => sync(bench)
                                case bench: Fork[?]        => fork(bench)
                            }
                        end while
                        cdl.countDown()
                    )
                }
                cdl.await()
                println("Wamup done!")
            finally
                exec.shutdown()
            end try
    end run

    def sync[A](bench: SyncAndFork[A]): A

    def fork[A](bench: Fork[A]): A

end WarmupJITProfile

object WarmupJITProfile:

    class CatsWarmup extends WarmupJITProfile:
        def sync[A](bench: SyncAndFork[A]) = bench.syncCats(this)
        def fork[A](bench: Fork[A])        = bench.forkCats(this)

    class KyoWarmup extends WarmupJITProfile:
        def sync[A](bench: SyncAndFork[A]) = bench.syncKyo(this)
        def fork[A](bench: Fork[A])        = bench.forkKyo(this)

    class ZIOWarmup extends WarmupJITProfile:
        def sync[A](bench: SyncAndFork[A]) = bench.syncZIO(this)
        def fork[A](bench: Fork[A])        = bench.forkZIO(this)

    def warmupSeconds = System.getProperty("warmupJITProfileSeconds", "0").toInt

    val warmupThreads = Runtime.getRuntime().availableProcessors()

    def warmupBenchs =
        Seq[Bench[?]](
            new BlockingBench,
            new BroadFlatMapBench,
            new CollectBench,
            new CountdownLatchBench,
            new DeepBindMapBench,
            new EnqueueDequeueBench,
            new FailureBench,
            new ForkChainedBench,
            new ForkJoinBench,
            new LoggingBench,
            new NarrowBindMapBench,
            new PingPongBench,
            new ProducerConsumerBench,
            new RandomBench,
            new SemaphoreBench,
            new StateMapBench,
            new StreamBench
        )
end WarmupJITProfile
