package kyo.bench.arena.grpc

import GrpcServerBench.*
import GrpcService.*
import io.grpc.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.TestServiceBlockingStub
import kyo.*
import kyo.bench.arena.ArenaBench2
import kyo.bench.arena.WarmupJITProfile.CatsForkWarmup
import kyo.bench.arena.WarmupJITProfile.KyoForkWarmup
import kyo.bench.arena.WarmupJITProfile.ZIOForkWarmup
import kyo.kernel.ContextEffect
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scalapb.zio_grpc
import zio.ZIO

class GrpcServerOneToManyBench extends ArenaBench2(size):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Int =
        import state.{*, given}
        forkCats:
            cats.effect.IO(consume(blockingStub.oneToMany(request)))
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Int =
        import state.*
        forkKyo:
            Sync.defer(consume(blockingStub.oneToMany(request)))
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Int =
        import state.{*, given}
        forkZIO:
            ZIO.attempt(consume(blockingStub.oneToMany(request))).orDie
    end zioBench

    // Consume the iterator otherwise Netty has a hissy fit about resource leaks.
    private def consume(replies: Iterator[Response]): Int =
        scala.collection.immutable.LazyList.from(replies).foldLeft(0)((acc, _) => acc + 1)

end GrpcServerOneToManyBench
