package kyo

import io.grpc.*
import io.grpc.examples.helloworld.helloworld.*
import kyo.grpc.*

object HelloWorldClient extends KyoApp:

  private val host = "localhost"
  private val port = 9001

  run {
    val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build
    val request = HelloRequest(name = "World")
    for {
      Greeter.client()
    } yield "Goodbye!"
  }

end HelloWorldClient
