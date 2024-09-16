package kyo.bench

import kgrpc.helloworld.testservice.*
import kyo.*
import kyo.bench.GrpcService.*
import scalapb.zio_grpc.Server
import zio.UIO

class GrpcE2EUnaryBench extends Bench.ForkOnly(reply):

    override def catsBench() =
        ???

    override def kyoBenchFiber() =
        Resource.run {
            val x =
                for
                    _      <- createServer(port)
                    client <- createClient(port)
                yield client.sayHello(request)
            x
        }

    override val zioRuntimeLayer =
        super.zioRuntimeLayer.merge(serverLayer).merge(clientLayer)

    override def zioBench() =
        ZioTestservice.GreeterClient.sayHello(request)
            .orDie
            .asInstanceOf[UIO[HelloReply]]

end GrpcE2EUnaryBench
