package kyo.bench.arena

import WarmupJITProfile.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kyo.bench.arena.ArenaBench.*
import kyo.discard
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import scala.concurrent.duration.*
import scala.util.control.NonFatal

@State(Scope.Benchmark)
abstract class WarmupJITProfile:
    start()
    def start() =
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
                        try
                            while System.currentTimeMillis() < deadline do
                                warmupBenchs.foreach(run(_))
                            end while
                        catch
                            case ex if NonFatal(ex) =>
                                new Exception("JIT profile warmup failed!", ex).printStackTrace()
                        end try
                        cdl.countDown()
                    )
                }
                cdl.await()
                println("Wamup done!")
            finally
                exec.shutdown()
            end try

    def run[A](bench: ArenaBench[?]): Unit

end WarmupJITProfile

object WarmupJITProfile:

    abstract class SyncWarmup extends WarmupJITProfile:
        def run[A](bench: ArenaBench[?]) =
            bench match
                case bench: SyncAndFork[?] => discard(runSync(bench))
                case _                     => ()
        def runSync[A](bench: SyncAndFork[A]): A
    end SyncWarmup

    abstract class ForkWarmup extends WarmupJITProfile:
        def run[A](bench: ArenaBench[?]) =
            bench match
                case bench: Fork[?] => discard(runFork(bench))
                case _              => ()
        def runFork[A](bench: Fork[A]): A
    end ForkWarmup

    class CatsSyncWarmup extends SyncWarmup:
        def runSync[A](bench: SyncAndFork[A]) = bench.syncCats(this)

    class CatsForkWarmup extends ForkWarmup:
        def runFork[A](bench: Fork[A]) = bench.forkCats(this)

    class KyoSyncWarmup extends SyncWarmup:
        def runSync[A](bench: SyncAndFork[A]) = bench.syncKyo(this)

    class KyoForkWarmup extends ForkWarmup:
        def runFork[A](bench: Fork[A]) = bench.forkKyo(this)

    class ZIOSyncWarmup extends SyncWarmup:
        def runSync[A](bench: SyncAndFork[A]) = bench.syncZIO(this)

    class ZIOForkWarmup extends ForkWarmup:
        def runFork[A](bench: Fork[A]) = bench.forkZIO(this)

    def warmupSeconds = System.getProperty("warmupJITProfileSeconds", "0").toInt

    val warmupThreads = Runtime.getRuntime().availableProcessors()

    def warmupBenchs =
        Seq[ArenaBench[?]](
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
            new StreamBench,
            new StreamIOBench
        )
end WarmupJITProfile
