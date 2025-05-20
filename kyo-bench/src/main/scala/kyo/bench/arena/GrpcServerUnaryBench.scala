package kyo.bench.arena

import io.grpc.*

import java.util.concurrent.{TimeUnit, TimeoutException}
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.TestServiceBlockingStub
import kyo.*
import kyo.bench.arena.GrpcServerBench.*
import kyo.bench.arena.GrpcServerUnaryBench.*
import kyo.bench.arena.GrpcService.*
import kyo.bench.arena.WarmupJITProfile.{CatsForkWarmup, KyoForkWarmup, ZIOForkWarmup}
import kyo.kernel.ContextEffect
import org.openjdk.jmh.annotations.*

import scala.compiletime.uninitialized
import scalapb.zio_grpc
import zio.ZIO

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
