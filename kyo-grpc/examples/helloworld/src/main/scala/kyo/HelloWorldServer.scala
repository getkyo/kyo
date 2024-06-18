package kyo

import io.grpc.*
import io.grpc.examples.helloworld.helloworld.{Greeter, HelloReply, HelloRequest}
import kyo.grpc.*

object GreeterService extends Greeter:
  override def sayHello(request: HelloRequest): HelloReply < GrpcResponses =
    for {
      _ <- Consoles.run(Consoles.println(s"Got request: $request"))
    } yield HelloReply(s"Hello, ${request.name}")

object HelloWorldServer extends KyoApp:

  private def buildServer(port: Int, services: Seq[ServerServiceDefinition]): Server =
    services.foldLeft(ServerBuilder.forPort(port))(_.addService(_)).build()

  private val port: Int = 9001

  private val services = Seq(
    Greeter.bindService(GreeterService)
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

end HelloWorldServer
