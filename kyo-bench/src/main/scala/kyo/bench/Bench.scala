package kyo.bench

import WarmupJITProfile.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.openjdk.jmh.annotations.*
import scala.concurrent.duration.*

@State(Scope.Benchmark)
@Fork(
    value = 1,
    jvmArgs = Array(
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:-DoJVMTIVirtualThreadTransitions",
        "-Dcom.sun.management.jmxremote",
        "-Dcom.sun.management.jmxremote.port=1099",
        "-Dcom.sun.management.jmxremote.authenticate=false",
        "-Dcom.sun.management.jmxremote.ssl=false"
    ),
    jvmArgsPrepend = Array(
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
)
@BenchmarkMode(Array(Mode.Throughput))
abstract class Bench[A](val expectedResult: A):
    private var finalizers: List[() => Unit] = Nil

    @TearDown
    def tearDown(): Unit = finalizers.foreach(_())

    def zioRuntimeLayer: zio.ZLayer[Any, Any, Any] =
        if System.getProperty("replaceZIOExecutor", "false") == "true" then
            kyo.KyoSchedulerZIORuntime.layer
        else
            zio.ZLayer.empty

    lazy val zioRuntime =
        if zioRuntimeLayer ne zio.ZLayer.empty then
            val (runtime, finalizer) = ZIORuntime.fromLayerWithFinalizer(zioRuntimeLayer)
            finalizers = finalizer :: finalizers
            runtime.unsafe
        else
            zio.Runtime.default.unsafe
    end zioRuntime
end Bench

object Bench:

    abstract class Base[A](expectedResult: A) extends Bench[A](expectedResult):
        def zioBench(): zio.UIO[A]
        def kyoBenchFiber(): kyo.<[A, kyo.Async] = kyoBench()
        def kyoBench(): kyo.<[A, kyo.IO]
        def catsBench(): cats.effect.IO[A]
    end Base

    abstract class Fork[A: kyo.Flat](expectedResult: A) extends Base[A](expectedResult):

        @Benchmark
        def forkKyo(warmup: KyoForkWarmup): A =
            import kyo.*
            IO.run(Async.run(kyoBenchFiber()).flatMap(_.block(Duration.Infinity))).eval.getOrThrow
        end forkKyo

        @Benchmark
        def forkCats(warmup: CatsForkWarmup): A =
            import cats.effect.unsafe.implicits.global
            cats.effect.IO.cede.flatMap(_ => catsBench()).unsafeRunSync()
        end forkCats

        @Benchmark
        def forkZIO(warmup: ZIOForkWarmup): A = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[A: kyo.Flat](expectedResult: A) extends Fork[A](expectedResult):
        def kyoBench() = ???

    abstract class SyncAndFork[A: kyo.Flat](expectedResult: A) extends Fork[A](expectedResult):

        @Benchmark
        def syncKyo(warmup: KyoSyncWarmup): A = kyo.IO.run(kyoBench()).eval

        @Benchmark
        def syncCats(warmup: CatsSyncWarmup): A =
            import cats.effect.unsafe.implicits.global
            catsBench().unsafeRunSync()
        end syncCats

        @Benchmark
        def syncZIO(warmup: ZIOSyncWarmup): A = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end Bench
