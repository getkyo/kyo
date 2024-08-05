package kyo.bench

import io.grpc.{ManagedChannelBuilder, ServerBuilder, StatusException}
import kyo.bench.GrpcUnaryE2EBench.*
import kyo.{size => _, *}
import scalapb.zio_grpc.{Server, ServerLayer, ZManagedChannel}
import kyo.grpc.helloworld.testservice.*
import zio.{UIO, ZIO, ZLayer}
import java.util.concurrent.TimeUnit

class GrpcUnaryE2EBench extends Bench.ForkOnly(reply):

    override def catsBench() =
        ???

    private def createClientAndServer =
        for
            _ <- createServer(port)
            client <- createClient(port)
        yield client

    private def createServer(port: Int) =
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

    private def createClient(port: Int) =
        createChannel(port).map(Greeter.client(_))

    private def createChannel(port: Int) =
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

    override def kyoBenchFiber() =
        Resources.run(createClientAndServer.map(_.sayHello(request)))
    end kyoBenchFiber

    override val zioRuntimeLayer =
        val server =
            ServerLayer.fromService[ZioTestservice.Greeter](ServerBuilder.forPort(port), new ZIOGreeterImpl(size))

        val client =
            ZLayer.scoped {
                val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
                ZioTestservice.GreeterClient.scoped(ZManagedChannel(channel)).orDie
            }

        super.zioRuntimeLayer.merge(server).merge(client)
    end zioRuntimeLayer

    override def zioBench() =
        ZioTestservice.GreeterClient.sayHello(request)
            .orDie
            .asInstanceOf[UIO[HelloReply]]
    end zioBench

end GrpcUnaryE2EBench

object GrpcUnaryE2EBench:

    private val host = "localhost"
    private val port = 50051
    private val size = 100000L

    private val message = Some(Hello("Alice"))
    private val request = HelloRequest(message)
    private val reply   = HelloReply(message)

end GrpcUnaryE2EBench

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
