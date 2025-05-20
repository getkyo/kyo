package kyo.bench.arena

import io.grpc.*
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.TestServiceBlockingStub
import kyo.*
import kyo.bench.arena.GrpcServerBench.*
import kyo.bench.arena.GrpcServerOneToManyBench.*
import kyo.bench.arena.GrpcService.*
import kyo.bench.arena.WarmupJITProfile.{CatsForkWarmup, KyoForkWarmup, ZIOForkWarmup}
import kyo.kernel.ContextEffect
import org.openjdk.jmh.annotations.*
import scalapb.zio_grpc
import zio.ZIO

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.compiletime.uninitialized

class GrpcServerOneToManyBench extends ArenaBench2(replies):

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Iterator[Response] =
        import state.{*, given}
        forkCats:
            cats.effect.IO(blockingStub.oneToMany(request))
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Iterator[Response] =
        import state.*
        forkKyo:
            IO(blockingStub.oneToMany(request))
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Iterator[Response] =
        import state.{*, given}
        forkZIO:
            ZIO.attempt(blockingStub.oneToMany(request)).orDie
    end zioBench

end GrpcServerOneToManyBench

object GrpcServerOneToManyBench:

    val message: String  = "Hello"
    val request: Request = Request(message)
    // TODO: Add more.
    val replies: Iterator[Response]  = Iterator(Response(message))

end GrpcServerOneToManyBench
