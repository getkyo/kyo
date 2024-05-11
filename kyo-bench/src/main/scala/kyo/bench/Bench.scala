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
        "-XX:-DoJVMTIVirtualThreadTransitions"
    ),
    jvmArgsPrepend = Array(
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
)
@BenchmarkMode(Array(Mode.Throughput))
abstract class Bench[T](val expectedResult: T):

    val runtimeLayer: ZLayer[Any, Any, ?] = ZLayer.empty

    lazy val zioRuntime = RuntimeLayers.makeRuntime(runtimeLayer).unsafe

end Bench

object Bench:

    abstract class Base[T](expectedResult: T) extends Bench[T](expectedResult):
        def zioBench(): UIO[T]
        def kyoBenchFiber(): T < Fibers = kyoBench()
        def kyoBench(): T < IOs
        def catsBench(): IO[T]
    end Base

    abstract class Fork[T: Flat](expectedResult: T) extends Base[T](expectedResult):

        @Benchmark
        def forkKyo(): T = IOs.run(Fibers.init(kyoBenchFiber()).flatMap(_.block(Duration.Infinity)))

        @Benchmark
        def forkCats(): T = IO.cede.flatMap(_ => catsBench()).unsafeRunSync()

        @Benchmark
        def forkZio(): T = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[T: Flat](expectedResult: T) extends Fork[T](expectedResult):
        def kyoBench() = ???

    abstract class SyncAndFork[T: Flat](expectedResult: T) extends Fork[T](expectedResult):

        @Benchmark
        def syncKyo(): T = IOs.run(kyoBench())

        @Benchmark
        def syncCats(): T = catsBench().unsafeRunSync()

        @Benchmark
        def syncZio(): T = zio.Unsafe.unsafe(implicit u =>
            zioRuntime.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end Bench
