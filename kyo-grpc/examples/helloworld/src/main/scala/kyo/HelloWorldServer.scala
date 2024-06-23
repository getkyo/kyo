package kyo

import io.grpc.*
import io.grpc.examples.helloworld.helloworld.*
import kyo.grpc.*

object GreeterService extends Greeter:
  override def sayHello(request: HelloRequest): HelloReply < GrpcResponses =
    for {
      _ <- Consoles.run(Consoles.println(s"Got request: $request"))
    } yield HelloReply(s"Hello, ${request.name}")

object HelloWorldServer extends KyoGrpServerApp:

  private val port: Int = 9001

  private val services = Seq(
    Greeter.bindService(GreeterService)
  )

  run {
    for {
      _ <- Consoles.println(s"Server is running on port $port. Press Ctrl-C to stop.")
      server <- Resources.acquireRelease(IOs(buildServer(port, services).start())) { (server: Server) =>
        for {
          _ <- Consoles.run(Consoles.print("Shutting down..."))
          _ <- IOs(server.shutdown().awaitTermination())
          _ <- Consoles.run(Consoles.println("Done."))
        } yield ()
      }
      s <- waitForInterrupt
    } yield "Goodbye!"
  }

end HelloWorldServer
