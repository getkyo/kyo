package kyo.bench.arena

import io.grpc.Grpc
import io.grpc.Metadata
import kgrpc.*
import kyo.*
import kyo.bench.arena.GrpcService.*
import kyo.grpc.GrpcRequest
import scalapb.zio_grpc.Server
import zio.ZIO

class GrpcE2EUnaryBench extends ArenaBench.ForkOnly(reply):

    override def catsBench() =
        import cats.effect.*
        createCatsServer.use: _ =>
            createCatsClient.use: client =>
                client.oneToOne(request, Metadata())
    end catsBench

    override def kyoBenchFiber() =
        // TODO: This is ugly.
        Resource.run:
            GrpcRequest.run(
                for
                    _      <- createKyoServer
                    client <- createKyoClient
                yield client.oneToOne(request)
            ).map(_.getOrThrow)

    override def zioBench() =
        ZIO.scoped:
            val run =
                for
                    _      <- createZioServer
                    client <- createZioClient
                    reply  <- client.oneToOne(request)
                yield reply
            run.orDie

end GrpcE2EUnaryBench
