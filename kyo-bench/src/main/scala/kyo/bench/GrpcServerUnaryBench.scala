package kyo.bench

import io.grpc.ManagedChannelBuilder
import kyo.bench.GrpcService.*
import kyo.grpc.helloworld.testservice.*
import kyo.{size as _, *}
import org.openjdk.jmh.annotations.TearDown
import scalapb.zio_grpc.Server
import zio.ZIO

import java.util.concurrent.{TimeUnit, TimeoutException}

class GrpcServerUnaryBench extends Bench.ForkOnly(reply):

    private val channel      = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
    private val blockingStub = GreeterGrpc.blockingStub(channel)

    @TearDown
    def shutdownChannel() =
        val shutdown = channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)
        if !shutdown then throw TimeoutException("Channel did not shutdown within 10 seconds.")

    override def catsBench() =
        ???

    override def kyoBenchFiber() =
        Resources.run {
            for
                _     <- createServer(port)
                reply <- IOs(blockingStub.sayHello(request))
            yield reply
        }

    override val zioRuntimeLayer =
        super.zioRuntimeLayer.merge(serverLayer)

    override def zioBench() =
        ZIO.attempt(blockingStub.sayHello(request)).orDie

end GrpcServerUnaryBench
