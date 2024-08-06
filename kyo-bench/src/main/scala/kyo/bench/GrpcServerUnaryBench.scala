package kyo.bench

import io.grpc.ManagedChannelBuilder
import kyo.bench.GrpcService.*
import kyo.grpc.helloworld.testservice.*
import kyo.{size as _, *}
import scalapb.zio_grpc.Server
import zio.ZIO

class GrpcServerUnaryBench extends Bench.ForkOnly(reply):

    private val channel      = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
    private val blockingStub = GreeterGrpc.blockingStub(channel)

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
