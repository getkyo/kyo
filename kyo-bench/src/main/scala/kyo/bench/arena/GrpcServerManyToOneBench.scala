package kyo.bench.arena

import io.grpc.stub.StreamObserver
import java.util.NoSuchElementException
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.GrpcServerBench.*
import kyo.bench.arena.GrpcServerManyToOneBench.*
import kyo.bench.arena.WarmupJITProfile.{CatsForkWarmup, KyoForkWarmup, ZIOForkWarmup}
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
                    private var response: Maybe[Response]   = Maybe.empty
                    def onNext(response: Response): Unit =
                        if this.response.isDefined then throw IllegalStateException("Response already set.")
                        this.response = Maybe(response)
                    def onError(t: Throwable): Unit      =
                        cb(Left(t))
                    def onCompleted(): Unit              =
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
                        discard(promise.unsafe.complete(response.fold(Result.fail(NoSuchElementException("No response")))(Result.succeed(_))))

                val run = Async:
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
                    def onError(t: Throwable): Unit      = cb(ZIO.fail(t))
                    def onCompleted(): Unit              = cb(response.fold(ZIO.fail(NoSuchElementException("No response")))(ZIO.succeed(_)))

                val requestObserver = stub.manyToOne(observer)
                requests.foreach(requestObserver.onNext)
                requestObserver.onCompleted()
    end zioBench

end GrpcServerManyToOneBench

object GrpcServerManyToOneBench:

    val message: String          = "Hello"
    val requests: Chunk[Request] = Chunk.fill(10)(Request(message))
    val response: Response       = Response(message)

end GrpcServerManyToOneBench
