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

class GrpcClientManyToOneBench extends ArenaBench2(response):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Response =
        import state.{*, given}
        forkCats:
            client.manyToOne(fs2.Stream.emits(requests), Metadata())

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Response =
        import state.*
        forkKyo:
            client.manyToOne(Stream.init(requests))

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Response =
        import state.{*, given}
        forkZIO:
            client.manyToOne(zio.stream.ZStream.fromIterable(requests))

end GrpcClientManyToOneBench
