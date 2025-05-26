package kyo.bench.arena.grpc

import io.grpc.*
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.TestServiceBlockingStub
import kyo.*
import kyo.bench.arena.ArenaBench2
import GrpcServerBench.*
import GrpcServerUnaryBench.*
import GrpcService.*
import kyo.bench.arena.WarmupJITProfile.{CatsForkWarmup, KyoForkWarmup, ZIOForkWarmup}
import kyo.kernel.ContextEffect
import org.openjdk.jmh.annotations.*
import scalapb.zio_grpc
import zio.ZIO

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

class GrpcServerUnaryBench extends ArenaBench2(reply):

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
            IO(blockingStub.oneToOne(request))
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Response =
        import state.{*, given}
        forkZIO:
            ZIO.attempt(blockingStub.oneToOne(request)).orDie
    end zioBench

end GrpcServerUnaryBench

object GrpcServerUnaryBench:

    val message: String  = "Hello"
    val request: Request = Request(message)
    val reply: Response  = Response(message)

end GrpcServerUnaryBench
