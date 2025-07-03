package kyo.bench.arena.grpc

import GrpcClientBench.*
import GrpcService.*
import io.grpc.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.*
import kyo.*
import kyo.bench.arena.ArenaBench2
import kyo.bench.arena.ArenaBench2.*
import kyo.bench.arena.WarmupJITProfile.CatsForkWarmup
import kyo.bench.arena.WarmupJITProfile.KyoForkWarmup
import kyo.bench.arena.WarmupJITProfile.ZIOForkWarmup
import kyo.grpc.Grpc
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scalapb.zio_grpc
import zio.ZIO

class GrpcClientOneToManyBench extends ArenaBench2[Long](size):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Long =
        import state.{*, given}
        forkCats:
            client.oneToMany(request, Metadata()).compile.count
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Long =
        import state.*
        forkKyo:
            client.oneToMany(request).into(Sink.count.map(_.toLong))
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Long =
        import state.{*, given}
        forkZIO:
            client.oneToMany(request).runCount
    end zioBench

end GrpcClientOneToManyBench
