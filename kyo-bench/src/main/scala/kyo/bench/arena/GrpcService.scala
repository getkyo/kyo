package kyo.bench.arena

import cats.effect
import fs2.grpc.syntax.all.*
import io.grpc
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.*
import kgrpc.bench.*
import kyo.*
import kyo.grpc.*
import java.net.ServerSocket

import scala.language.implicitConversions
import scalapb.zio_grpc
import scalapb.zio_grpc.ScopedServer
import scalapb.zio_grpc.ZManagedChannel
import zio.{Scope, ZIO, stream}

object GrpcService:

    given Frame = Frame.internal

    val host = "localhost"
    val size = 100_000

    def createCatsServer(port: Int): cats.effect.Resource[cats.effect.IO, Server] =
        TestServiceFs2Grpc
            .bindServiceResource[cats.effect.IO](new CatsTestServiceImpl(size))
            .flatMap: service =>
                NettyServerBuilder
                    .forPort(port)
                    .addService(service)
                    .resource[cats.effect.IO]
                    .evalMap(server => cats.effect.IO(server.start()))

    def createCatsClient(port: Int): cats.effect.Resource[cats.effect.IO, TestServiceFs2Grpc[cats.effect.IO, Metadata]] =
        NettyChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .resource[cats.effect.IO]
            .flatMap(TestServiceFs2Grpc.stubResource[cats.effect.IO](_))

    def createKyoServer(port: Int): grpc.Server < (Resource & IO) =
        kyo.grpc.Server.start(port)(_.addService(KyoTestServiceImpl(size)))

    def createKyoClient(port: Int): TestService.Client < (Resource & IO) =
        kyo.grpc.Client.channel(host, port)(_.usePlaintext()).map(TestService.client(_))

    def createZioServer(port: Int): ZIO[Scope, Throwable, zio_grpc.Server] = {
        for {
            _ <- ZIO.attempt(scala.Console.err.print("Starting ZIO server on port " + port))
                x <-ScopedServer.fromService(ServerBuilder.forPort(port), new ZIOTestServiceImpl(size))
        } yield x
        end for
    }

    def createZioClient(port: Int): ZIO[Scope, Throwable, ZioBench.TestServiceClient] =
        val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
        ZioBench.TestServiceClient.scoped(ZManagedChannel(channel))

//    def findFreePortCats =
//        for
//            socket <- cats.effect.IO(ServerSocket(0))
//            port <- cats.effect.IO(socket.getLocalPort).guarantee(cats.effect.IO(socket.close()))
//        yield port
//
//    def findFreePortKyo =
//        for
//            socket <- IO(new ServerSocket(0))
//            port <- IO.ensure(IO(socket.close()))(socket.getLocalPort)
//        yield port
//
//    def findFreePortZio =
//        for
//            socket <- ZIO.attempt(ServerSocket(0))
//            port <- ZIO.attempt(socket.getLocalPort).ensuring(ZIO.succeed(socket.close()))
//        yield port

    def findFreePort(): Int =
        val socket = ServerSocket(0)
        try
            socket.getLocalPort
        finally
            socket.close()
    end findFreePort

end GrpcService

class CatsTestServiceImpl(size: Int) extends TestServiceFs2Grpc[cats.effect.IO, Metadata]:

    import cats.effect.*

    override def oneToOne(request: kgrpc.bench.Request, ctx: Metadata): IO[Response] =
        IO.pure(Response(request.message))

    override def oneToMany(request: Request, ctx: Metadata): fs2.Stream[IO, Response] =
        fs2.Stream.chunk(fs2.Chunk.constant(Response(request.message), size))

    override def manyToOne(requests: fs2.Stream[IO, Request], ctx: Metadata): IO[Response] =
        requests
            .compile
            .last
            .map(maybe => Response(maybe.fold("")(_.message)))

    override def manyToMany(requests: fs2.Stream[IO, Request], ctx: Metadata): fs2.Stream[IO, Response] =
        requests.flatMap(oneToMany(_, ctx))

end CatsTestServiceImpl

class KyoTestServiceImpl(size: Int)(using Frame) extends TestService:

    override def oneToOne(request: Request): Response < Any =
        Response(request.message)

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] < GrpcResponse =
        Stream.init(Chunk.fill(size)(Response(request.message)))

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse =
        //Sink.last.drain(requests).map(maybe => Response(maybe.fold("")(_.message)))
        ???

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] < GrpcResponse =
        ???

end KyoTestServiceImpl

class ZIOTestServiceImpl(size: Int) extends ZioBench.TestService:

    override def oneToOne(request: Request): ZIO[Any, StatusException, Response] =
        ZIO.succeed(Response(request.message))

    override def oneToMany(request: Request): stream.Stream[StatusException, Response] =
        stream.ZStream.fromChunk(zio.Chunk.fill(size)(Response(request.message)))

    override def manyToOne(requests: stream.Stream[StatusException, Request]): zio.IO[StatusException, Response] =
        requests.runLast.map(maybe => Response(maybe.fold("")(_.message)))

    override def manyToMany(requests: stream.Stream[StatusException, Request]): stream.Stream[StatusException, Response] =
        requests.flatMap(oneToMany)

end ZIOTestServiceImpl
