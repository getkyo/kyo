package kyo.bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import kyo.*
import org.openjdk.jmh.annotations.*
import zio.UIO

@State(Scope.Benchmark)
@Fork(
    value = 1,
    jvmArgs = Array(
        // "-XX:+UnlockDiagnosticVMOptions",
        // "-Dgraal.PrintCompilation=true",
        // "-Dgraal.Log",
        // "-Dgraal.MethodFilter=kyo.scheduler.IOFiber.*",
        // "-XX:+TraceDeoptimization",
        // "-XX:+LogCompilation",
        // "-Dgraal.Dump=", //, "-Dgraal.MethodFilter=kyo2.*",
        // "-XX:+PrintCompilation", "-XX:+PrintInlining",
        // "-XX:+TraceTypeProfile",
        "-Dcats.effect.tracing.mode=DISABLED",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:-DoJVMTIVirtualThreadTransitions"
    )
    // jvmArgsPrepend = Array(
    //     "--enable-preview",
    //     "--add-modules=jdk.incubator.concurrent"
    // )
    // jvm = "/Users/flavio.brasil/Downloads/graalvm-ce-java17-22.3.0/Contents/Home/bin/java"
)
@BenchmarkMode(Array(Mode.Throughput))
sealed abstract class Bench[T](using f: Flat[T]):
    def zioBench(): UIO[T]
    def kyoBenchFiber(): T < Fibers = kyoBench()
    def kyoBench(): T < IOs
    def catsBench(): IO[T]
end Bench

object Bench:

    abstract class Fork[T](using f: Flat[T]) extends Bench[T]:
        @Benchmark
        def forkKyo(): T = IOs.run(Fibers.init(kyoBenchFiber()).flatMap(_.block))

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
