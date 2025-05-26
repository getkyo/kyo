package kyo.bench.arena.grpc

import io.grpc.{Grpc, Metadata}
import kgrpc.*
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.ArenaBench
import GrpcE2EUnaryBench.*
import GrpcService.*
import kyo.grpc.GrpcRequest
import org.openjdk.jmh.annotations.*
import scalapb.zio_grpc.Server
import zio.{UIO, ZIO}

import scala.compiletime.uninitialized

class GrpcE2EUnaryBench extends ArenaBench.ForkOnly(reply):

    private var port: Int = uninitialized

    @Setup
    def buildChannel(): Unit =
        port = findFreePort()
    end buildChannel

    override def catsBench(): cats.effect.IO[Response] =
        import cats.effect.*
        createCatsServer(port, static = false).use: _ =>
            createCatsClient(port).use: client =>
                client.oneToOne(request, Metadata())
    end catsBench

    override def kyoBenchFiber(): Response < GrpcRequest =
        Resource.run:
            for
                _      <- createKyoServer(port, static = false)
                client <- createKyoClient(port)
            yield client.oneToOne(request)

    override def zioBench(): UIO[Response] =
        ZIO.scoped:
            val run =
                for
                    _      <- createZioServer(port, static = false)
                    client <- createZioClient(port)
                    reply  <- client.oneToOne(request)
                yield reply
            run.orDie

end GrpcE2EUnaryBench

object GrpcE2EUnaryBench:

    val message: String  = "Hello"
    val request: Request = Request(message)
    val reply: Response  = Response(message)

end GrpcE2EUnaryBench
