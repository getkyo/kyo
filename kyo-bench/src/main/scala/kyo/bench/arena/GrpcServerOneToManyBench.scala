package kyo.bench.arena

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kgrpc.bench.*
import kgrpc.bench.TestServiceGrpc.TestServiceBlockingStub
import kyo.*
import kyo.bench.arena.GrpcServerOneToManyBench.*
import kyo.bench.arena.GrpcService.*
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scalapb.zio_grpc.Server
import zio.ZIO

class GrpcServerOneToManyBench extends ArenaBench.ForkOnly(replies):

    private var port: Int = uninitialized
    private var channel: ManagedChannel = uninitialized
    private var blockingStub: TestServiceBlockingStub = uninitialized

    @Setup
    def buildChannel() =
        port = findFreePort()
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
        blockingStub = TestServiceGrpc.blockingStub(channel)
    end buildChannel

    @TearDown
    def shutdownChannel() =
        val shutdown = channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)
        if !shutdown then throw TimeoutException("Channel did not shutdown within 10 seconds.")
    end shutdownChannel

    override def catsBench() =
        import cats.effect.*
        createCatsServer(port).use: _ =>
            cats.effect.IO(blockingStub.oneToMany(request))
    end catsBench

    override def kyoBenchFiber() =
        Resource.run:
            for
                _     <- createKyoServer(port)
                reply <- IO(blockingStub.oneToMany(request))
            yield reply

    override def zioBench() =
        ZIO.scoped:
            val run =
                for
                    _     <- createZioServer(port)
                    reply <- ZIO.attempt(blockingStub.oneToMany(request))
                yield reply
            run.orDie

end GrpcServerOneToManyBench

object GrpcServerOneToManyBench:

    val message: String             = "Hello"
    val request: Request            = Request(message)
    val replies: Iterator[Response] = Iterator(Response(message))

end GrpcServerOneToManyBench
