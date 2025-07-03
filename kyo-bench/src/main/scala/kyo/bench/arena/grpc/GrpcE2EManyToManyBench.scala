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

class GrpcE2EManyToManyBench extends ArenaBench.ForkOnly[Long](sizeSquared):

    private var port: Int = uninitialized

    @Setup
    def buildChannel(): Unit =
        port = findFreePort()
    end buildChannel

    override def catsBench(): cats.effect.IO[Long] =
        import cats.effect.*
        import fs2.Stream
        createCatsServer(port, static = false).use: _ =>
            createCatsClient(port).use: client =>
                val requestStream = Stream.emits(requests)
                client.manyToMany(requestStream, Metadata()).compile.count
    end catsBench

    override def kyoBenchFiber(): Long < Grpc =
        Resource.run:
            for
                _      <- createKyoServer(port, static = false)
                client <- createKyoClient(port)
            yield client.manyToMany(Stream.init(requests)).into(Sink.count.map(_.toLong))

    override def zioBench(): UIO[Long] =
        ZIO.scoped:
            val run =
                for
                    _      <- createZioServer(port, static = false)
                    client <- createZioClient(port)
                    reply  <- client.manyToMany(ZStream.fromIterable(requests)).runCount
                yield reply
            run.orDie

end GrpcE2EManyToManyBench
