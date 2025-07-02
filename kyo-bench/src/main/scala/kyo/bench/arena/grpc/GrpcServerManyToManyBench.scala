package kyo.bench.arena.grpc

import GrpcServerBench.*
import GrpcService.*
import io.grpc.stub.StreamObserver
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.ArenaBench2
import kyo.bench.arena.WarmupJITProfile.CatsForkWarmup
import kyo.bench.arena.WarmupJITProfile.KyoForkWarmup
import kyo.bench.arena.WarmupJITProfile.ZIOForkWarmup
import org.openjdk.jmh.annotations.*
import zio.ZIO

class GrpcServerManyToManyBench extends ArenaBench2(sizeSquared):

    private given Frame = Frame.internal

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Int =
        import state.{*, given}
        forkCats:
            cats.effect.IO.async[Int]: cb =>
                val observer = new StreamObserver[Response]:
                    private var count: Int               = 0
                    def onNext(response: Response): Unit = count += 1
                    def onError(t: Throwable): Unit      = cb(Left(t))
                    def onCompleted(): Unit              = cb(Right(count))

                cats.effect.IO:
                    val requestObserver = stub.manyToMany(observer)
                    requests.foreach(requestObserver.onNext)
                    requestObserver.onCompleted()
                    None
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Int =
        import state.*
        forkKyo:
            Promise.initWith[Throwable, Int]: promise =>
                val observer = new StreamObserver[Response]:
                    private var count: Int               = 0
                    def onNext(response: Response): Unit = count += 1
                    def onError(t: Throwable): Unit =
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.fail(t)))
                    def onCompleted(): Unit =
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.succeed(count)))

                val run = Async.defer:
                    val requestObserver = stub.manyToMany(observer)
                    requests.foreach(requestObserver.onNext)
                    requestObserver.onCompleted()
                run.andThen(promise.get)
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Int =
        import state.{*, given}
        forkZIO:
            ZIO.async[Any, Throwable, Int]: cb =>
                val observer = new StreamObserver[Response]:
                    private var count: Int               = 0
                    def onNext(response: Response): Unit = count += 1
                    def onError(t: Throwable): Unit      = cb(ZIO.fail(t))
                    def onCompleted(): Unit              = cb(ZIO.succeed(count))

                val requestObserver = stub.manyToMany(observer)
                requests.foreach(requestObserver.onNext)
                requestObserver.onCompleted()
    end zioBench

end GrpcServerManyToManyBench
