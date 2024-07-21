package kyo

import io.grpc.*
import io.grpc.examples.helloworld.helloworld.*
import kyo.grpc.*

import java.util.concurrent.TimeUnit

object HelloWorldClient extends KyoApp:

  private val host = "localhost"
  private val port = 9001

  run {
    for
      client <- createClient
      request = HelloRequest(name = "World")
      response <- client.sayHello(request)
    yield "Goodbye!"
  }

  private def createClient =
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

end HelloWorldClient
