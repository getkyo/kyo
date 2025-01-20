package kyo.bench

import WarmupJITProfile.*
import org.openjdk.jmh.annotations.*

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

    given ioRuntime: cats.effect.unsafe.IORuntime =
        if System.getProperty("replaceCatsExecutor", "false") == "true" then
            kyo.KyoSchedulerIORuntime.global
        else
            cats.effect.unsafe.implicits.global
end Bench

object Bench:

    abstract class Base[A](expectedResult: A) extends Bench[A](expectedResult):
        def zioBench(): zio.UIO[A]
        def kyoBenchFiber(): kyo.<[A, kyo.Async & kyo.Abort[Throwable]] = kyoBench()
        def kyoBench(): kyo.<[A, kyo.IO]
        def catsBench(): cats.effect.IO[A]
    end Base

    abstract class Fork[A: kyo.Flat](expectedResult: A) extends Base[A](expectedResult):

        @Benchmark
        def forkKyo(warmup: KyoForkWarmup): A =
            import kyo.*
            import AllowUnsafe.embrace.danger
            IO.Unsafe.evalOrThrow(Async.run(kyoBenchFiber()).flatMap(_.block(Duration.Infinity))).getOrThrow
        end forkKyo

        @Benchmark
        def forkCats(warmup: CatsForkWarmup): A =
            cats.effect.IO.cede.flatMap(_ => catsBench()).unsafeRunSync()

        @Benchmark
        def forkZIO(warmup: ZIOForkWarmup): A = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[A: kyo.Flat](expectedResult: A) extends Fork[A](expectedResult):
        def kyoBench() = ???

    abstract class SyncAndFork[A: kyo.Flat](expectedResult: A) extends Fork[A](expectedResult):

        @Benchmark
        def syncKyo(warmup: KyoSyncWarmup): A =
            import kyo.AllowUnsafe.embrace.danger
            kyo.IO.Unsafe.evalOrThrow(kyoBench())
        end syncKyo

        @Benchmark
        def syncCats(warmup: CatsSyncWarmup): A =
            catsBench().unsafeRunSync()

        @Benchmark
        def syncZIO(warmup: ZIOSyncWarmup): A = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end Bench
