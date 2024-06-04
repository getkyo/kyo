package kyo

import io.grpc.stub.{ServerCalls, StreamObserver}
import io.grpc.*
import io.grpc.examples.helloworld.helloworld.Greeter
import io.grpc.examples.helloworld.helloworld.GreeterGrpc.{METHOD_SAY_HELLO, SERVICE}
// TODO: Why does it double up on the helloworld package name?
import io.grpc.examples.helloworld.helloworld.{HelloReply, HelloRequest}
import kyo.grpc.*

import scala.util.Try

object GreeterService extends Greeter:
  override def sayHello(request: HelloRequest): HelloReply < GrpcResponses =
    for {
      _ <- Consoles.run(Consoles.println(s"Got request: $request"))
    } yield HelloReply(s"Hello, ${request.name}")

object HelloWorldServer extends KyoApp:

  private def buildGreeterService(serviceImpl: Greeter): ServerServiceDefinition =
    ServerServiceDefinition.builder(SERVICE)
      .addMethod(
        METHOD_SAY_HELLO,
        // TODO: When to use a different type of call?
        ServerCalls.asyncUnaryCall((request: HelloRequest, observer: StreamObserver[HelloReply]) => {
          val fiber = GrpcResponses.init(serviceImpl.sayHello(request))
          val pendingIOs = fiber.onComplete { reply =>
            IOs.attempt(reply).map(scalapb.grpc.Grpc.completeObserver(observer))
          }
          IOs.run(pendingIOs)
        }))
      .build()

  private def buildServer(port: Int, services: Seq[ServerServiceDefinition]): Server =
    services.foldLeft(ServerBuilder.forPort(port))(_.addService(_)).build()

  private val port: Int = 9001

  private val services = Seq(
    buildGreeterService(GreeterService)
  )

  run {
    for {
      // TODO: Get the shutdown working properly.
      _ <- Consoles.println(s"Server is running on port $port. Press Ctrl-C to stop.")
      server <- Resources.acquireRelease(IOs(buildServer(port, services).start())) { (server: Server) =>
        IOs.run(Consoles.run(Consoles.print("Shutting down...")))
        // TODO: Add a timeout.
        server.shutdown().awaitTermination()
        IOs.run(Consoles.run(Consoles.println("Done.")))
      }
      _ <- Fibers.sleep(Duration.Infinity)
    } yield {
      "Goodbye!"
    }
  }
