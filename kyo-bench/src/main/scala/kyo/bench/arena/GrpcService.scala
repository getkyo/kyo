package kyo.bench.arena

import cats.effect
import cats.effect.kernel
import fs2.grpc.syntax.all.*
import io.grpc
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.*
import kgrpc.bench.*
import kyo.*
import kyo.grpc.*

import scala.language.implicitConversions
import scalapb.zio_grpc
import scalapb.zio_grpc.ScopedServer
import scalapb.zio_grpc.ServerLayer
import scalapb.zio_grpc.ZManagedChannel
import zio.{Scope, ZIO, ZLayer, stream}

object GrpcService:

    given Frame = Frame.internal

    val host = "localhost"
    val port = 50051
    val size = 100000L

    val message: String  = "Hello"
    val request: Request = Request(message)
    val reply: Response  = Response(message)

    lazy val createCatsServer: cats.effect.Resource[cats.effect.IO, Server] =
        TestServiceFs2Grpc
            .bindServiceResource[cats.effect.IO](new CatsTestServiceImpl(size))
            .flatMap: service =>
                NettyServerBuilder
                    .forPort(port)
                    .addService(service)
                    .resource[cats.effect.IO]
                    .evalMap(server => cats.effect.IO(server.start()))

    lazy val createCatsClient: cats.effect.Resource[cats.effect.IO, TestServiceFs2Grpc[cats.effect.IO, Metadata]] =
        NettyChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .resource[cats.effect.IO]
            .flatMap(TestServiceFs2Grpc.stubResource[cats.effect.IO](_))

    lazy val createKyoServer: grpc.Server < (Resource & IO) =
        kyo.grpc.Server.start(port)(_.addService(KyoTestServiceImpl(size)))

    lazy val createKyoClient: TestService.Client < (Resource & IO) =
        kyo.grpc.Client.channel(host, port)(_.usePlaintext()).map(TestService.client(_))

    lazy val createZioServer: ZIO[Scope, Throwable, zio_grpc.Server] =
        ScopedServer.fromService(ServerBuilder.forPort(port), new ZIOTestServiceImpl(size))

    lazy val createZioClient: ZIO[Scope, Throwable, ZioBench.TestServiceClient] =
        val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
        ZioBench.TestServiceClient.scoped(ZManagedChannel(channel))

end GrpcService

class CatsTestServiceImpl(size: Long) extends TestServiceFs2Grpc[cats.effect.IO, Metadata]:

    import cats.effect.*

    override def oneToOne(request: kgrpc.bench.Request, ctx: Metadata): IO[Response] =
        IO.pure(Response(request.message))

    override def oneToMany(request: Request, ctx: Metadata): fs2.Stream[IO, Response] = ???

    override def manyToOne(request: fs2.Stream[IO, Request], ctx: Metadata): IO[Response] = ???

    override def manyToMany(request: fs2.Stream[IO, Request], ctx: Metadata): fs2.Stream[IO, Response] = ???

end CatsTestServiceImpl

class KyoTestServiceImpl(size: Long) extends TestService:

    override def oneToOne(request: Request): Response < Any =
        Response(request.message)

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] < GrpcResponse = ???

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse = ???

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] < GrpcResponse = ???

end KyoTestServiceImpl

class ZIOTestServiceImpl(size: Long) extends ZioBench.TestService:

    override def oneToOne(request: Request): ZIO[Any, StatusException, Response] =
        ZIO.succeed(Response(request.message))

    override def oneToMany(request: Request): stream.Stream[StatusException, Response] =
        stream.ZStream.repeat(Response(request.message)).take(size)

    override def manyToOne(request: stream.Stream[StatusException, Request]): zio.IO[StatusException, Response] = ???

    override def manyToMany(request: stream.Stream[StatusException, Request]): stream.Stream[StatusException, Response] = ???

end ZIOTestServiceImpl
