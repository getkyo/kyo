package kgrpc

import java.util.concurrent.TimeUnit
import kgrpc.helloworld.*
import kyo.*
import kyo.grpc.*

object HelloWorldClient extends KyoApp:

    private val host = "localhost"
    private val port = 50051

    run {
        for
            client <- createClient
            request = HelloRequest(name = "World")
            _        <- Console.printLine(s"Sending request: $request")
            response <- client.sayHello(request)
            _        <- Console.printLine(s"Got response: $response")
        yield ()
    }

    private def createClient =
        Client.channel(host, port)(_.usePlaintext()).map(Greeter.client(_))

end HelloWorldClient
