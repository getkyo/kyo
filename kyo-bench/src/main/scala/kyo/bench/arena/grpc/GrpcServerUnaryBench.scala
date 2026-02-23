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

class GrpcServerUnaryBench extends ArenaBench2(response):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Response =
        import state.{*, given}
        forkCats:
            cats.effect.IO(blockingStub.oneToOne(request))
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Response =
        import state.*
        forkKyo:
            Sync.defer(blockingStub.oneToOne(request))
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Response =
        import state.{*, given}
        forkZIO:
            ZIO.attempt(blockingStub.oneToOne(request)).orDie
    end zioBench

end GrpcServerUnaryBench
