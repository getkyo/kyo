package kyo.bench.arena

import WarmupJITProfile.*
import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*

abstract class ArenaBench[A](val expectedResult: A) extends BaseBench:
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
end ArenaBench

object ArenaBench:

    abstract class Base[A](expectedResult: A) extends ArenaBench[A](expectedResult):
        def zioBench(): zio.UIO[A]
        def kyoBenchFiber(): kyo.<[A, kyo.Async & kyo.Abort[Throwable]] = kyoBench()
        def kyoBench(): kyo.<[A, kyo.Sync]
        def catsBench(): cats.effect.IO[A]
    end Base

    abstract class Fork[A](expectedResult: A) extends Base[A](expectedResult):

        @Benchmark
        def forkKyo(warmup: KyoForkWarmup): A =
            import kyo.*
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(Async.run(kyoBenchFiber()).flatMap(_.block(Duration.Infinity))).getOrThrow
        end forkKyo

        @Benchmark
        def forkCats(warmup: CatsForkWarmup): A =
            cats.effect.IO.cede.flatMap(_ => catsBench()).unsafeRunSync()

        @Benchmark
        def forkZIO(warmup: ZIOForkWarmup): A = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[A](expectedResult: A) extends Fork[A](expectedResult):
        def kyoBench() = ???

    abstract class SyncAndFork[A](expectedResult: A) extends Fork[A](expectedResult):

        @Benchmark
        def syncKyo(warmup: KyoSyncWarmup): A =
            import kyo.AllowUnsafe.embrace.danger
            kyo.Sync.Unsafe.evalOrThrow(kyoBench())
        end syncKyo

        @Benchmark
        def syncCats(warmup: CatsSyncWarmup): A =
            catsBench().unsafeRunSync()

        @Benchmark
        def syncZIO(warmup: ZIOSyncWarmup): A = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end ArenaBench
