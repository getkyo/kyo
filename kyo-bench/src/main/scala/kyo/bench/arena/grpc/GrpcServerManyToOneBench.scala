package kyo.bench.arena.grpc

import GrpcServerBench.*
import GrpcService.*
import io.grpc.stub.StreamObserver
import java.util.NoSuchElementException
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.ArenaBench2
import kyo.bench.arena.WarmupJITProfile.CatsForkWarmup
import kyo.bench.arena.WarmupJITProfile.KyoForkWarmup
import kyo.bench.arena.WarmupJITProfile.ZIOForkWarmup
import org.openjdk.jmh.annotations.*
import zio.ZIO

class GrpcServerManyToOneBench extends ArenaBench2(response):

    private given Frame = Frame.internal

    @Benchmark
    def catsBench(warmup: CatsForkWarmup, state: CatsState): Response =
        import state.{*, given}
        forkCats:
            cats.effect.IO.async[Response]: cb =>
                val observer = new StreamObserver[Response]:
                    private var response: Maybe[Response] = Maybe.empty
                    def onNext(response: Response): Unit =
                        if this.response.isDefined then throw IllegalStateException("Response already set.")
                        this.response = Maybe(response)
                    def onError(t: Throwable): Unit =
                        cb(Left(t))
                    def onCompleted(): Unit =
                        cb(response.fold(Left(NoSuchElementException("No response")))(Right(_)))

                cats.effect.IO:
                    val requestObserver = stub.manyToOne(observer)
                    requests.foreach(requestObserver.onNext)
                    requestObserver.onCompleted()
                    None
    end catsBench

    @Benchmark
    def kyoBench(warmup: KyoForkWarmup, state: KyoState): Response =
        import state.*
        forkKyo:
            Promise.initWith[Throwable, Response]: promise =>
                val observer = new StreamObserver[Response]:
                    private var response: Maybe[Response] = Maybe.empty
                    def onNext(response: Response): Unit =
                        if this.response.isDefined then throw IllegalStateException("Response already set.")
                        this.response = Maybe(response)
                    def onError(t: Throwable): Unit =
                        import AllowUnsafe.embrace.danger
                        discard(promise.unsafe.complete(Result.fail(t)))
                    def onCompleted(): Unit =
                        import AllowUnsafe.embrace.danger
                        discard(
                            promise.unsafe.complete(response.fold(Result.fail(NoSuchElementException("No response")))(Result.succeed(_)))
                        )
                    end onCompleted

                val run = Async.defer:
                    val requestObserver = stub.manyToOne(observer)
                    requests.foreach(requestObserver.onNext)
                    requestObserver.onCompleted()
                run.andThen(promise.get)
    end kyoBench

    @Benchmark
    def zioBench(warmup: ZIOForkWarmup, state: ZIOState): Response =
        import state.{*, given}
        forkZIO:
            ZIO.async[Any, Throwable, Response]: cb =>
                val observer = new StreamObserver[Response]:
                    private var response: Option[Response] = None
                    def onNext(response: Response): Unit =
                        if this.response.isDefined then throw IllegalStateException("Response already set.")
                        this.response = Some(response)
                    def onError(t: Throwable): Unit = cb(ZIO.fail(t))
                    def onCompleted(): Unit         = cb(response.fold(ZIO.fail(NoSuchElementException("No response")))(ZIO.succeed(_)))

                val requestObserver = stub.manyToOne(observer)
                requests.foreach(requestObserver.onNext)
                requestObserver.onCompleted()
    end zioBench

end GrpcServerManyToOneBench
