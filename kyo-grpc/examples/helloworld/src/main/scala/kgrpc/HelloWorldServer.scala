package kgrpc

import kgrpc.helloworld.*
import kyo.*
import kyo.grpc.*

object GreeterService extends Greeter:

  override def sayHello(request: HelloRequest): HelloReply < GrpcResponse =
    for
      _ <- Console.println(s"Got request: $request")
    yield HelloReply(s"Hello, ${request.name}")

object HelloWorldServer extends KyoApp:

  private val port = 50051

  run {
    for
      _ <- Console.println(s"Server is running on port $port. Press Ctrl-C to stop.")
      server <- Server.start(port)(_.addService(GreeterService), { server =>
        for
          _ <- Console.print("Shutting down...")
          _ <- Server.shutdown(server)
          _ <- Console.println("Done.")
        yield ()
      })
      _ <- Server.waitForInterrupt
    yield ()
  }

end HelloWorldServer
