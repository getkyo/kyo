package kyo.bench

import kyo.bench.GrpcService.*
import kyo.grpc.helloworld.testservice.*
import kyo.{size as _, *}
import scalapb.zio_grpc.Server
import zio.UIO

class GrpcE2EUnaryBench extends Bench.ForkOnly(reply):

    override def catsBench() =
        ???

    override def kyoBenchFiber() =
        Resources.run(createClientAndServer.map(_.sayHello(request)))

    override val zioRuntimeLayer =
        super.zioRuntimeLayer.merge(serverLayer).merge(clientLayer)

    override def zioBench() =
        ZioTestservice.GreeterClient.sayHello(request)
            .orDie
            .asInstanceOf[UIO[HelloReply]]

end GrpcE2EUnaryBench
