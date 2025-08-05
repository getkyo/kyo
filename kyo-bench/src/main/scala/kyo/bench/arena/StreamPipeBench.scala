package kyo.bench.arena

import WarmupJITProfile.*
import kyo.AllowUnsafe
import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*

class StreamPipeBench extends BaseBench:
    val seq = (0 until 10000).toVector

    import AllowUnsafe.embrace.danger

    @Benchmark
    def mapPureStreamBench() =
        import kyo.*
        Stream.init(seq)
            .mapPure(_ + 1)
            .fold(0)(_ + _)
            .eval
    end mapPureStreamBench

    @Benchmark
    def mapPureStreamAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .mapAbstractPure(_ + 1)
            .fold(0)(_ + _)
            .eval
    end mapPureStreamAbstractBench

    @Benchmark
    def mapPurePipeBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.mapPure((_: Int) + 1))
            .fold(0)(_ + _)
            .eval
    end mapPurePipeBench

    @Benchmark
    def mapPurePipeAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.mapAbstractPure((_: Int) + 1))
            .fold(0)(_ + _)
            .eval
    end mapPurePipeAbstractBench

    @Benchmark
    def mapKyoStreamBench() =
        import kyo.*
        Stream.init(seq)
            .map(v => Sync(v + 1))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end mapKyoStreamBench

    @Benchmark
    def mapKyoStreamAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .mapAbstract(v => Sync(v + 1))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end mapKyoStreamAbstractBench

    @Benchmark
    def mapKyoPipeBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.map((v: Int) => Sync(v + 1)))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end mapKyoPipeBench

    @Benchmark
    def mapKyoPipeAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.mapAbstract((v: Int) => Sync(v + 1)))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end mapKyoPipeAbstractBench

    @Benchmark
    def filterPureStreamBench() =
        import kyo.*
        Stream.init(seq)
            .filterPure(_ % 2 == 0)
            .fold(0)(_ + _)
            .eval
    end filterPureStreamBench

    @Benchmark
    def filterPureStreamAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .filterAbstractPure(_ % 2 == 0)
            .fold(0)(_ + _)
            .eval
    end filterPureStreamAbstractBench

    @Benchmark
    def filterPurePipeBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.filterPure((_: Int) % 2 == 0))
            .fold(0)(_ + _)
            .eval
    end filterPurePipeBench

    @Benchmark
    def filterPurePipeAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.filterAbstractPure((_: Int) % 2 == 0))
            .fold(0)(_ + _)
            .eval
    end filterPurePipeAbstractBench

    @Benchmark
    def filterKyoStreamBench() =
        import kyo.*
        Stream.init(seq)
            .filter(v => Sync(v % 2 == 0))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end filterKyoStreamBench

    @Benchmark
    def filterKyoStreamAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .filterAbstract(v => Sync(v % 2 == 0))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end filterKyoStreamAbstractBench

    @Benchmark
    def filterKyoPipeBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.filter((v: Int) => Sync(v % 2 == 0)))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end filterKyoPipeBench

    @Benchmark
    def filterKyoPipeAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.filterAbstract((v: Int) => Sync(v % 2 == 0)))
            .fold(0)(_ + _)
            .handle(Sync.Unsafe.evalOrThrow)
    end filterKyoPipeAbstractBench

    @Benchmark
    def filterMapVarStreamBench() =
        import kyo.*
        Stream.init(seq)
            .filter(v => Var.get[Int].map(i => ((i + v) % 2 > -1)))
            .map(v => Var.update[Int](_ + 1).map(i => v + i))
            .fold(0)(_ + _)
            .handle(Var.run(0), Sync.Unsafe.evalOrThrow)
    end filterMapVarStreamBench

    @Benchmark
    def filterMapVarStreamAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .filterAbstract(v => Var.get[Int].map(i => ((i + v) % 2 > -1)))
            .mapAbstract(v => Var.update[Int](_ + 1).map(i => v + i))
            .fold(0)(_ + _)
            .handle(Var.run(0), Sync.Unsafe.evalOrThrow)
    end filterMapVarStreamAbstractBench

    @Benchmark
    def filterMapVarPipeBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.filter((v: Int) => Var.get[Int].map(i => ((i + v) % 2 > -1))))
            .into(Pipe.map((v: Int) => Var.update[Int](_ + 1).map(i => v + i)))
            .fold(0)(_ + _)
            .handle(Var.run(0), Sync.Unsafe.evalOrThrow)
    end filterMapVarPipeBench

    @Benchmark
    def filterMapVarPipeAbstractBench() =
        import kyo.*
        Stream.init(seq)
            .into(Pipe.filterAbstract((v: Int) => Var.get[Int].map(i => ((i + v) % 2 > -1))))
            .into(Pipe.mapAbstract((v: Int) => Var.update[Int](_ + 1).map(i => v + i)))
            .fold(0)(_ + _)
            .handle(Var.run(0), Sync.Unsafe.evalOrThrow)
    end filterMapVarPipeAbstractBench
end StreamPipeBench
