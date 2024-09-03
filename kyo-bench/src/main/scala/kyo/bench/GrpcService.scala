package kyo.bench

import io.grpc
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.StatusException

import kyo.{size as _, *}
import kyo.grpc.helloworld.testservice.*

import scala.language.implicitConversions
import scalapb.zio_grpc
import scalapb.zio_grpc.Server
import scalapb.zio_grpc.ServerLayer
import scalapb.zio_grpc.ZManagedChannel
import zio.ZIO
import zio.ZLayer

object GrpcService:

    val host = "localhost"
    val port = 50051
    val size = 100000L

    val message: Some[Hello]  = Some(Hello("Alice"))
    val request: HelloRequest = HelloRequest(message)
    val reply: HelloReply     = HelloReply(message)

    val serverLayer: ZLayer[Any, Throwable, zio_grpc.Server] =
        ServerLayer.fromService[ZioTestservice.Greeter](ServerBuilder.forPort(port), new ZIOGreeterImpl(size))

    val clientLayer: ZLayer[Any, Nothing, ZioTestservice.GreeterClient] =
        ZLayer.scoped {
            val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
            ZioTestservice.GreeterClient.scoped(ZManagedChannel(channel)).orDie
        }

    def createServer(port: Int): grpc.Server < Resources =
        kyo.grpc.Server.start(port)(_.addService(KyoGreeterImpl(size)))

    def createClient(port: Int): Greeter.Client < Resources =
        kyo.grpc.Client.channel(host, port)(_.usePlaintext()).map(Greeter.client(_))

end GrpcService

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
