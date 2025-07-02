package kyo.bench.arena.grpc

import GrpcService.*
import io.grpc.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.*
import kyo.*
import kyo.bench.arena.ArenaBench2.*
import kyo.kernel.ContextEffect
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scalapb.zio_grpc
import zio.ZIO

object GrpcServerBench:

    @State(Scope.Benchmark)
    abstract class BaseState:

        protected var port: Int     = uninitialized
        var channel: ManagedChannel = uninitialized

        var blockingStub: TestServiceBlockingStub = uninitialized
        var stub: TestServiceStub                 = uninitialized

        protected var finalizers: List[() => Unit] = Nil

        protected def addFinalizer(finalizer: => Unit): Unit =
            finalizers = (() => finalizer) :: finalizers

        def setup(): Unit =
            port = findFreePort()
            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
            blockingStub = TestServiceGrpc.blockingStub(channel)
            stub = TestServiceGrpc.stub(channel)
        end setup

        @TearDown
        def tearDown(): Unit =
            channel.shutdownNow()
            finalizers.foreach(_())
            val isShutdown = channel.awaitTermination(10, TimeUnit.SECONDS)
            if !isShutdown then throw TimeoutException("Channel did not shutdown within 10 seconds.")
        end tearDown

    end BaseState

    @State(Scope.Benchmark)
    class CatsState extends BaseState:

        var ioRuntime: cats.effect.unsafe.IORuntime = uninitialized
        given cats.effect.unsafe.IORuntime          = ioRuntime

        @Setup
        def setup(runtime: CatsRuntime): Unit =
            super.setup()

            ioRuntime = runtime.ioRuntime

            val (_: Server, finalizer) = createCatsServer(port, static = true).allocated.unsafeRunSync()

            addFinalizer:
                finalizer.unsafeRunSync()
        end setup

    end CatsState

    @State(Scope.Benchmark)
    class KyoState extends BaseState:

        @Setup
        override def setup(): Unit =
            super.setup()

            import AllowUnsafe.embrace.danger

            val finalizer = Resource.Finalizer.Awaitable.Unsafe.init(1)
            val kyoServer = createKyoServer(port, static = true)
            val result    = ContextEffect.handle(Tag[Resource], finalizer, _ => finalizer)(kyoServer)
            val _: Server = Abort.run(Sync.Unsafe.run(result)).eval.getOrThrow

            addFinalizer:
                Abort.run(Sync.Unsafe.run(finalizer.close(Absent))).eval.getOrThrow
        end setup

    end KyoState

    @State(Scope.Benchmark)
    class ZIOState extends BaseState:

        var zioRuntime: zio.Runtime[Any] = uninitialized
        given zio.Runtime[Any]           = zioRuntime

        @Setup
        def setup(runtime: ZIORuntime): Unit =
            super.setup()

            zioRuntime = runtime.zioRuntime
            given zio.Unsafe = zio.Unsafe

            val scope              = zio.Scope.unsafe.make
            val zioServer          = scope.extend(createZioServer(port, static = true))
            val _: zio_grpc.Server = zioRuntime.unsafe.run(zioServer).getOrThrow()

            addFinalizer:
                zioRuntime.unsafe.run(scope.close(zio.Exit.unit)).getOrThrow()
        end setup

    end ZIOState

end GrpcServerBench
