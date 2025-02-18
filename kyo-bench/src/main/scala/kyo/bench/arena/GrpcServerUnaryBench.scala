package kyo.bench.arena

import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kgrpc.helloworld.testservice.*
import kyo.*
import kyo.bench.arena.GrpcService.*
import org.openjdk.jmh.annotations.TearDown
import scalapb.zio_grpc.Server
import zio.ZIO

class GrpcServerUnaryBench extends ArenaBench.ForkOnly(reply):

    private val channel      = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
    private val blockingStub = GreeterGrpc.blockingStub(channel)

    @TearDown
    def shutdownChannel() =
        val shutdown = channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)
        if !shutdown then throw TimeoutException("Channel did not shutdown within 10 seconds.")
    end shutdownChannel

    override def catsBench() =
        ???

    override def kyoBenchFiber() =
        Resource.run {
            for
                _     <- createServer(port)
                reply <- IO(blockingStub.sayHello(request))
            yield reply
        }

    override val zioRuntimeLayer =
        super.zioRuntimeLayer.merge(serverLayer)

    override def zioBench() =
        ZIO.attempt(blockingStub.sayHello(request)).orDie

end GrpcServerUnaryBench
