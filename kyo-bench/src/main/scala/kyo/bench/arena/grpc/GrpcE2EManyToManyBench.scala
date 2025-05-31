package kyo.bench.arena.grpc

import io.grpc.{Grpc, Metadata}
import kgrpc.*
import kgrpc.bench.*
import kyo.*
import kyo.bench.arena.ArenaBench
import GrpcService.*
import kyo.grpc.GrpcRequest
import org.openjdk.jmh.annotations.*
import scalapb.zio_grpc.Server
import zio.{Chunk, UIO, ZIO}
import zio.stream.ZStream

import scala.compiletime.uninitialized

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

    override def kyoBenchFiber(): Long < GrpcRequest =
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
