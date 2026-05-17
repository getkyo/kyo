package kgrpc

import kgrpc.helloworld.*
import kyo.*
import kyo.grpc.*

object GreeterService extends Greeter:

    override def sayHello(request: HelloRequest): HelloReply < Grpc =
        for
            _ <- Console.printLine(s"Got request: $request")
        yield HelloReply(s"Hello, ${request.name}!")
end GreeterService

object HelloWorldServer extends KyoApp:

    private val port = 50051

    run {
        for
            _ <- Console.printLine(s"Server is running on port $port. Press Ctrl-C to stop.")
            server <- Server.start(port)(
                _.addService(GreeterService),
                { (server, duration) =>
                    for
                        _ <- Console.print("Shutting down...")
                        _ <- Server.shutdown(server, duration)
                        _ <- Console.printLine("Done.")
                    yield ()
                }
            )
            _ <- Async.never
        yield ()
    }

end HelloWorldServer
