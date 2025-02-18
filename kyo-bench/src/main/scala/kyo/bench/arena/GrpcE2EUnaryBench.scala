package kyo.bench.arena

import io.grpc.Grpc
import kgrpc.helloworld.testservice.*
import kyo.*
import kyo.bench.arena.GrpcService.*
import kyo.grpc.GrpcRequest
import scalapb.zio_grpc.Server
import zio.UIO

class GrpcE2EUnaryBench extends ArenaBench.ForkOnly(reply):

    override def catsBench() =
        ???

    override def kyoBenchFiber() =
        Resource.run(
            GrpcRequest.run(
                for
                    _      <- createServer(port)
                    client <- createClient(port)
                yield client.sayHello(request)
            ).map(_.getOrThrow)
        )

    override val zioRuntimeLayer =
        super.zioRuntimeLayer.merge(serverLayer).merge(clientLayer)

    override def zioBench() =
        ZioTestservice.GreeterClient.sayHello(request)
            .orDie
            .asInstanceOf[UIO[HelloReply]]

end GrpcE2EUnaryBench
