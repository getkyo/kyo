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

class GrpcServerOneToManyBench extends ArenaBench2(10):

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
            IO(consume(blockingStub.oneToMany(request)))
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

object GrpcServerOneToManyBench:

    val message: String    = "Hello"
    val request: Request   = Request(message)
    val response: Response = Response(message)

end GrpcServerOneToManyBench
