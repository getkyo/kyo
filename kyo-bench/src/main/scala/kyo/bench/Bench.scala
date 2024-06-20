package kyo.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import kyo.*
import org.openjdk.jmh.annotations.*
import zio.UIO
import zio.ZLayer

@State(Scope.Benchmark)
@Fork(
    value = 1,
    jvmArgs = Array(
        "-Dcats.effect.tracing.mode=DISABLED",
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
abstract class Bench[T](val expectedResult: T):
    private var finalizers: List[() => Unit] = Nil

    @TearDown
    def tearDown(): Unit = finalizers.foreach(_())

    def zioRuntimeLayer: ZLayer[Any, Any, Any] =
        if System.getProperty("replaceZIOExecutor", "false") == "true" then
            KyoSchedulerZIORuntime.layer
        else
            ZLayer.empty

    lazy val zioRuntime =
        if zioRuntimeLayer ne ZLayer.empty then
            val (runtime, finalizer) = ZIORuntime.fromLayerWithFinalizer(zioRuntimeLayer)
            finalizers = finalizer :: finalizers
            runtime.unsafe
        else
            zio.Runtime.default.unsafe
    end zioRuntime
end Bench

object Bench:

    abstract class Base[T](expectedResult: T) extends Bench[T](expectedResult):
        def zioBench(): UIO[T]

        def kyoBenchFiber(): T < Fibers = kyoBench()
        def kyoBench(): T < IOs

        def kyoBenchFiber2(): kyo2.<[T, kyo2.Async & kyo2.Abort[Throwable]] = kyoBench2()
        def kyoBench2(): kyo2.<[T, kyo2.IO]                                 = ???

        def catsBench(): IO[T]
    end Base

    abstract class Fork[T: Flat](expectedResult: T) extends Base[T](expectedResult):

        @Benchmark
        def forkKyo(): T = IOs.run(Fibers.init(kyoBenchFiber()).flatMap(_.block(Duration.Infinity)))

        @Benchmark
        def forkKyo2(): T =
            import kyo2.*
            kyo2.IO.run(Async.run(kyoBenchFiber2()).flatMap(_.block(Duration.Infinity))).eval.getOrThrow
        end forkKyo2

        @Benchmark
        def forkCats(): T = IO.cede.flatMap(_ => catsBench()).unsafeRunSync()

        @Benchmark
        def forkZIO(): T = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[T: Flat](expectedResult: T) extends Fork[T](expectedResult):
        def kyoBench() = ???

    abstract class SyncAndFork[T: Flat](expectedResult: T) extends Fork[T](expectedResult):

        @Benchmark
        def syncKyo(): T = IOs.run(kyoBench())

        @Benchmark
        def syncKyo2(): T = kyo2.IO.run(kyoBench2()).eval

        @Benchmark
        def syncCats(): T = catsBench().unsafeRunSync()

        @Benchmark
        def syncZIO(): T = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end Bench
