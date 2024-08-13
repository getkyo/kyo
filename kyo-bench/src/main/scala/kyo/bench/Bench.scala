package kyo.bench

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
abstract class Bench[T](val expectedResult: T):
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

    abstract class Base[T](expectedResult: T) extends Bench[T](expectedResult):
        def zioBench(): zio.UIO[T]
        def kyoBenchFiber(): kyo.<[T, kyo.Async] = kyoBench()
        def kyoBench(): kyo.<[T, kyo.IO]
        def catsBench(): cats.effect.IO[T]
    end Base

    abstract class Fork[T: kyo.Flat](expectedResult: T) extends Base[T](expectedResult):

        @Benchmark
        def forkKyo(): T =
            import kyo.*
            IO.run(Async.run(kyoBenchFiber()).flatMap(_.block(Duration.Infinity))).eval.getOrThrow
        end forkKyo

        @Benchmark
        def forkCats(): T =
            import cats.effect.unsafe.implicits.global
            cats.effect.IO.cede.flatMap(_ => catsBench()).unsafeRunSync()
        end forkCats

        @Benchmark
        def forkZIO(): T = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[T: kyo.Flat](expectedResult: T) extends Fork[T](expectedResult):
        def kyoBench() = ???

    abstract class SyncAndFork[T: kyo.Flat](expectedResult: T) extends Fork[T](expectedResult):

        @Benchmark
        def syncKyo(): T = kyo.IO.run(kyoBench()).eval

        @Benchmark
        def syncCats(): T =
            import cats.effect.unsafe.implicits.global
            catsBench().unsafeRunSync()
        end syncCats

        @Benchmark
        def syncZIO(): T = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end Bench
