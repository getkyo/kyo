package kyo.bench.arena

import cats.effect
import cats.effect.kernel
import fs2.grpc.syntax.all.*
import io.grpc
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.*
import kgrpc.helloworld.testservice.*
import kyo.*
import scala.language.implicitConversions
import scalapb.zio_grpc
import scalapb.zio_grpc.ServerLayer
import scalapb.zio_grpc.ZManagedChannel
import zio.ZIO
import zio.ZLayer

object GrpcService:

    given Frame = Frame.internal

    val host = "localhost"
    val port = 50051
    val size = 100000L

    val message: Some[Hello]  = Some(Hello("Alice"))
    val request: HelloRequest = HelloRequest(message)
    val reply: HelloReply     = HelloReply(message)

    lazy val createCatsServer: cats.effect.Resource[cats.effect.IO, Server] =
        GreeterFs2Grpc
            .bindServiceResource[cats.effect.IO](new CatsGreeterImpl(size))
            .flatMap: service =>
                NettyServerBuilder
                    .forPort(port)
                    .addService(service)
                    .resource[cats.effect.IO]
                    .evalMap(server => cats.effect.IO(server.start()))

    lazy val createCatsClient: cats.effect.Resource[cats.effect.IO, GreeterFs2Grpc[cats.effect.IO, Metadata]] =
        NettyChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .resource[cats.effect.IO]
            .flatMap(GreeterFs2Grpc.stubResource[cats.effect.IO](_))

    lazy val createKyoServer: grpc.Server < (Resource & IO) =
        kyo.grpc.Server.start(port)(_.addService(KyoGreeterImpl(size)))

    lazy val createKyoClient: Greeter.Client < (Resource & IO) =
        kyo.grpc.Client.channel(host, port)(_.usePlaintext()).map(Greeter.client(_))

    lazy val createZioServer: ZLayer[Any, Throwable, zio_grpc.Server] =
        ServerLayer.fromService[ZioTestservice.Greeter](ServerBuilder.forPort(port), new ZIOGreeterImpl(size))

    lazy val createZioClient: ZLayer[Any, Nothing, ZioTestservice.GreeterClient] =
        ZLayer.scoped {
            val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
            ZioTestservice.GreeterClient.scoped(ZManagedChannel(channel)).orDie
        }

end GrpcService

class CatsGreeterImpl(size: Long) extends GreeterFs2Grpc[cats.effect.IO, Metadata]:

    import cats.effect.*

    def sayHello(request: kgrpc.helloworld.testservice.HelloRequest, ctx: Metadata): IO[HelloReply] =
        IO.pure(HelloReply(request.request))

    //    def sayHelloStreaming(request: HelloRequest): ZStream[Any, StatusException, HelloReply] =
    //        ZStream.repeat(HelloReply(request.request)).take(size)

end CatsGreeterImpl

class KyoGreeterImpl(size: Long) extends Greeter:

    override def sayHello(request: HelloRequest): HelloReply < Any =
        HelloReply(request.request)

end KyoGreeterImpl

class ZIOGreeterImpl(size: Long) extends ZioTestservice.Greeter:

    def sayHello(request: HelloRequest): ZIO[Any, StatusException, HelloReply] =
        ZIO.succeed(HelloReply(request.request))

    //    def sayHelloStreaming(request: HelloRequest): ZStream[Any, StatusException, HelloReply] =
    //        ZStream.repeat(HelloReply(request.request)).take(size)

end ZIOGreeterImpl
