package kyo.bench.arena.grpc

import io.grpc.*
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.*
import kyo.*
import kyo.bench.arena.ArenaBench2
import kyo.bench.arena.ArenaBench2.*
import kyo.bench.arena.WarmupJITProfile.{CatsForkWarmup, KyoForkWarmup, ZIOForkWarmup}
import GrpcClientBench.*
import GrpcService.*
import kyo.grpc.GrpcRequest
import org.openjdk.jmh.annotations.*
import scalapb.zio_grpc
import zio.ZIO

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

class GrpcClientManyToManyBench extends ArenaBench2[Long](sizeSquared):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Long =
        import state.{*, given}
        forkCats:
            client.manyToMany(fs2.Stream.emits(requests), Metadata()).compile.count

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Long =
        import state.*
        forkKyo:
            client.manyToMany(Stream.init(requests)).into(Sink.count.map(_.toLong))

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Long =
        import state.{*, given}
        forkZIO:
            client.manyToMany(zio.stream.ZStream.fromIterable(requests)).runCount

end GrpcClientManyToManyBench
