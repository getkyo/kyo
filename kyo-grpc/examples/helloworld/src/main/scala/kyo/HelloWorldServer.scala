package kyo

import io.grpc.*
import io.grpc.examples.helloworld.helloworld.*
import kyo.grpc.*

object GreeterService extends Greeter:

  override def sayHello(request: HelloRequest): HelloReply < GrpcResponses =
    for
      _ <- Consoles.run(Consoles.println(s"Got request: $request"))
    yield HelloReply(s"Hello, ${request.name}")

object HelloWorldServer extends KyoApp:

  private val port = 50051

  run {
    for
      _ <- Consoles.println(s"Server is running on port $port. Press Ctrl-C to stop.")
      server <- Server.start(port)(_.addService(GreeterService), { server =>
        for
          _ <- Consoles.run(Consoles.print("Shutting down..."))
          _ <- Server.shutdown(server)
          _ <- Consoles.run(Consoles.println("Done."))
        yield ()
      })
      _ <- Server.waitForInterrupt
    yield "Goodbye!"
  }

end HelloWorldServer
