package kyo.bench

import io.grpc.{ManagedChannelBuilder, ServerBuilder, StatusException}
import kyo.grpc.helloworld.testservice.*
import kyo.{size as _, *}
import scalapb.zio_grpc.{Server, ServerLayer, ZManagedChannel}
import zio.{UIO, ZIO, ZLayer}

import java.util.concurrent.TimeUnit

object GrpcService:

    val host = "localhost"
    val port = 50051
    val size = 100000L

    val message: Some[Hello]  = Some(Hello("Alice"))
    val request: HelloRequest = HelloRequest(message)
    val reply: HelloReply     = HelloReply(message)

    val serverLayer: ZLayer[Any, Throwable, Server] =
        ServerLayer.fromService[ZioTestservice.Greeter](ServerBuilder.forPort(port), new ZIOGreeterImpl(size))

    val clientLayer: ZLayer[Any, Nothing, ZioTestservice.GreeterClient] =
        ZLayer.scoped {
            val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
            ZioTestservice.GreeterClient.scoped(ZManagedChannel(channel)).orDie
        }

    val createClientAndServer =
        for
            _      <- createServer(port)
            client <- createClient(port)
        yield client

    def createServer(port: Int) =
        Resources.acquireRelease(
            IOs(
                ServerBuilder
                    .forPort(port)
                    .addService(Greeter.server(KyoGreeterImpl(size)))
                    .build()
                    .start()
            )
        ) { server =>
            IOs(server.shutdown().awaitTermination())
        }

    def createClient(port: Int) =
        createChannel(port).map(Greeter.client(_))

    def createChannel(port: Int) =
        Resources.acquireRelease(
            IOs(
                ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .build()
            )
        ) { channel =>
            IOs(channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS)).unit
        }

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
