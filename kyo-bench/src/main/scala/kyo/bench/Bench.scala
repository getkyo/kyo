package kyo.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import kyo.*
import org.openjdk.jmh.annotations.*
import scala.annotation.nowarn
import scala.concurrent.duration.Duration
import zio.UIO

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
abstract class Bench[T]

object Bench:

    abstract class Base[T] extends Bench[T]:
        def zioBench(): UIO[T]
        def kyoBenchFiber(): T < Fibers = kyoBench()
        def kyoBench(): T < IOs
        def catsBench(): IO[T]
    end Base

    @nowarn
    abstract class Fork[T](using f: Flat[T]) extends Base[T]:
        @Benchmark
        def forkKyo(): T = IOs.run(Fibers.init(kyoBenchFiber()).flatMap(_.block(Duration.Inf)))

        @Benchmark
        def forkCats(): T = IO.cede.flatMap(_ => catsBench()).unsafeRunSync()

        @Benchmark
        def forkZio(): T = zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(zio.ZIO.yieldNow.flatMap(_ => zioBench())).getOrThrow()
        )
    end Fork

    abstract class ForkOnly[T](using f: Flat[T]) extends Fork[T]:
        def kyoBench() = ???

    abstract class SyncAndFork[T](using f: Flat[T]) extends Fork[T]:

        @Benchmark
        def syncKyo(): T = IOs.run(kyoBench())

        @Benchmark
        def syncCats(): T = catsBench().unsafeRunSync()

        @Benchmark
        def syncZio(): T = zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(zioBench()).getOrThrow()
        )
    end SyncAndFork
end Bench
