package kyo.bench.arena.grpc

import GrpcClientBench.*
import GrpcService.*
import io.grpc.*
import io.grpc.stub.StreamObserver
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kgrpc.bench.TestServiceFs2Grpc
import kgrpc.bench.ZioBench
import kyo.*
import kyo.bench.arena.ArenaBench2.*
import kyo.grpc.Grpc
import kyo.kernel.ContextEffect
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scala.concurrent.Future
import scalapb.zio_grpc
import vanilla.kgrpc.bench.*
import vanilla.kgrpc.bench.TestServiceGrpc.*
import zio.ZIO

object GrpcClientBench:

    private val executionContext = kyo.scheduler.Scheduler.get.asExecutionContext

    class TestServiceImpl extends TestService:

        private val response  = Response("response")
        private val responses = Chunk.fill(size)(response)

        def oneToOne(request: Request): Future[Response] =
            Future.successful(response)

        def oneToMany(request: Request, responseObserver: StreamObserver[Response]): Unit =
            responses.foreach(responseObserver.onNext)
            responseObserver.onCompleted()
        end oneToMany

        def manyToOne(responseObserver: StreamObserver[Response]): StreamObserver[Request] =
            new StreamObserver[Request]:
                def onNext(request: Request): Unit = ()

                def onError(t: Throwable): Unit =
                    responseObserver.onError(t)

                def onCompleted(): Unit =
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                end onCompleted

        def manyToMany(responseObserver: StreamObserver[Response]): StreamObserver[Request] =
            new StreamObserver[Request]:
                def onNext(request: Request): Unit =
                    responses.foreach(responseObserver.onNext)

                def onError(t: Throwable): Unit =
                    responseObserver.onError(t)

                def onCompleted(): Unit =
                    responseObserver.onCompleted()

    end TestServiceImpl

    @State(Scope.Benchmark)
    abstract class BaseState:

        protected var port: Int                    = uninitialized
        var server: Server                         = uninitialized
        protected var finalizers: List[() => Unit] = Nil

        protected def addFinalizer(finalizer: => Unit): Unit =
            finalizers = (() => finalizer) :: finalizers

        def setup(): Unit =
            port = findFreePort()
            server = ServerBuilder
                .forPort(port)
                .addService(TestService.bindService(new TestServiceImpl, executionContext))
                .build
                .start
        end setup

        @TearDown
        def tearDown(): Unit =
            server.shutdownNow()
            finalizers.foreach(_())
            val isShutdown = server.awaitTermination(10, TimeUnit.SECONDS)
            if !isShutdown then throw TimeoutException("Server did not shutdown within 10 seconds.")
        end tearDown

    end BaseState

    @State(Scope.Benchmark)
    class CatsState extends BaseState:

        var ioRuntime: cats.effect.unsafe.IORuntime              = uninitialized
        given cats.effect.unsafe.IORuntime                       = ioRuntime
        var client: TestServiceFs2Grpc[cats.effect.IO, Metadata] = uninitialized

        @Setup
        def setup(runtime: CatsRuntime): Unit =
            super.setup()

            ioRuntime = runtime.ioRuntime

            val (client, finalizer) = createCatsClient(port).allocated.unsafeRunSync()
            this.client = client

            addFinalizer:
                finalizer.unsafeRunSync()
        end setup

    end CatsState

    @State(Scope.Benchmark)
    class KyoState extends BaseState:

        var client: kgrpc.bench.TestService.Client = uninitialized

        @Setup
        override def setup(): Unit =
            super.setup()

            import AllowUnsafe.embrace.danger

            val finalizer    = Resource.Finalizer.Awaitable.Unsafe.init(1)
            val clientEffect = createKyoClient(port)
            val clientResult = ContextEffect.handle(Tag[Resource], finalizer, _ => finalizer)(clientEffect)

            client = Abort.run(Sync.Unsafe.run(clientResult)).eval.getOrThrow

            addFinalizer:
                Abort.run(Sync.Unsafe.run(finalizer.close(Absent))).eval.getOrThrow
        end setup

    end KyoState

    @State(Scope.Benchmark)
    class ZIOState extends BaseState:

        var zioRuntime: zio.Runtime[Any]       = uninitialized
        given zio.Runtime[Any]                 = zioRuntime
        var client: ZioBench.TestServiceClient = uninitialized

        @Setup
        def setup(runtime: ZIORuntime): Unit =
            super.setup()

            zioRuntime = runtime.zioRuntime
            given zio.Unsafe = zio.Unsafe

            val clientScope     = zio.Scope.unsafe.make
            val zioClientEffect = clientScope.extend(createZioClient(port))
            client = zioRuntime.unsafe.run(zioClientEffect).getOrThrow()
            addFinalizer:
                zioRuntime.unsafe.run(clientScope.close(zio.Exit.unit)).getOrThrow()
        end setup

    end ZIOState

end GrpcClientBench
