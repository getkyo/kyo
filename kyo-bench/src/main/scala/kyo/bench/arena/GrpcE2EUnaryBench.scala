package kyo.bench.arena

import io.grpc.{Grpc, Metadata}
import kgrpc.*
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.GrpcE2EUnaryBench.*
import kyo.bench.arena.GrpcService.*
import kyo.grpc.GrpcRequest
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scalapb.zio_grpc.Server
import zio.ZIO

class GrpcE2EUnaryBench extends ArenaBench.ForkOnly(reply):

    private var port: Int = uninitialized

    @Setup
    def buildChannel() =
        port = findFreePort()
    end buildChannel

    override def catsBench() =
        import cats.effect.*
        createCatsServer(port).use: _ =>
            createCatsClient(port).use: client =>
                client.oneToOne(request, Metadata())
    end catsBench

    override def kyoBenchFiber() =
        Resource.run:
            for
                _      <- createKyoServer(port)
                client <- createKyoClient(port)
            yield client.oneToOne(request)

    override def zioBench() =
        ZIO.scoped:
            val run =
                for
                    _      <- createZioServer(port)
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