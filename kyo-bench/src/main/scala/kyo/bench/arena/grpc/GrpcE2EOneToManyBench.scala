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
import zio.UIO
import zio.ZIO

class GrpcE2EOneToManyBench extends ArenaBench.ForkOnly[Long](size):

    private var port: Int = uninitialized

    @Setup
    def buildChannel(): Unit =
        port = findFreePort()
    end buildChannel

    override def catsBench(): cats.effect.IO[Long] =
        import cats.effect.*
        createCatsServer(port, static = false).use: _ =>
            createCatsClient(port).use: client =>
                client.oneToMany(request, Metadata()).compile.count
    end catsBench

    override def kyoBenchFiber(): Long < Grpc =
        Resource.run:
            for
                _      <- createKyoServer(port, static = false)
                client <- createKyoClient(port)
            yield client.oneToMany(request).into(Sink.count.map(_.toLong))

    override def zioBench(): UIO[Long] =
        ZIO.scoped:
            val run =
                for
                    _      <- createZioServer(port, static = false)
                    client <- createZioClient(port)
                    reply  <- client.oneToMany(request).runCount
                yield reply
            run.orDie

end GrpcE2EOneToManyBench
