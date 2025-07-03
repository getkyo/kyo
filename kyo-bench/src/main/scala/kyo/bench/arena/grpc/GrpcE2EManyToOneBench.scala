package kyo.bench.arena.grpc

import GrpcService.*
import io.grpc.Metadata
import kgrpc.*
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.ArenaBench
import kyo.grpc.Grpc
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import scalapb.zio_grpc.Server
import zio.Chunk
import zio.UIO
import zio.ZIO
import zio.stream.ZStream

class GrpcE2EManyToOneBench extends ArenaBench.ForkOnly(response):

    private var port: Int = uninitialized

    @Setup
    def buildChannel(): Unit =
        port = findFreePort()
    end buildChannel

    override def catsBench(): cats.effect.IO[Response] =
        import cats.effect.*
        import fs2.Stream
        createCatsServer(port, static = false).use: _ =>
            createCatsClient(port).use: client =>
                val requestStream = Stream.emits(requests)
                client.manyToOne(requestStream, Metadata())
    end catsBench

    override def kyoBenchFiber(): Response < Grpc =
        Resource.run:
            for
                _      <- createKyoServer(port, static = false)
                client <- createKyoClient(port)
            yield client.manyToOne(Stream.init(requests))

    override def zioBench(): UIO[Response] =
        ZIO.scoped:
            val run =
                for
                    _      <- createZioServer(port, static = false)
                    client <- createZioClient(port)
                    reply  <- client.manyToOne(ZStream.fromIterable(requests))
                yield reply
            run.orDie

end GrpcE2EManyToOneBench
