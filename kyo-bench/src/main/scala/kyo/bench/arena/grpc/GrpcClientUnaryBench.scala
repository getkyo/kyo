package kyo.bench.arena.grpc

import io.grpc.*
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.*
import kyo.*
import kyo.bench.arena.ArenaBench2
import kyo.bench.arena.ArenaBench2.*
import kyo.bench.arena.WarmupJITProfile.{CatsForkWarmup, KyoForkWarmup, ZIOForkWarmup}
import GrpcClientBench.*
import GrpcClientUnaryBench.*
import GrpcService.*
import kyo.grpc.GrpcRequest
import org.openjdk.jmh.annotations.*
import scalapb.zio_grpc
import zio.ZIO

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

class GrpcClientUnaryBench extends ArenaBench2(reply):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Response =
        import state.{*, given}
        forkCats:
            client.oneToOne(request, Metadata())
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Response =
        import state.*
        forkKyo:
            client.oneToOne(request)
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Response =
        import state.{*, given}
        forkZIO:
            client.oneToOne(request)
    end zioBench

end GrpcClientUnaryBench

object GrpcClientUnaryBench:

    val message: String  = "Hello"
    val request: Request = Request(message)
    val reply: Response  = Response(message)

end GrpcClientUnaryBench
