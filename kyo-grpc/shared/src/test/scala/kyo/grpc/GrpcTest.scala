package kyo.grpc

import org.scalatest.wordspec.AsyncWordSpec

class GrpcTest extends AsyncWordSpec:

    "GrpcServer" should:
        "create a server configuration" in:
            val config = GrpcServer.newServer(port = 8080)
            assert(config.port == 8080)
            assert(config.host == "0.0.0.0")
            succeed

        "create a server with custom host" in:
            val config = GrpcServer.newServer(port = 9090, host = "127.0.0.1")
            assert(config.port == 9090)
            assert(config.host == "127.0.0.1")
            succeed

    "GrpcClient" should:
        "create a client configuration" in:
            import kyo.grpc.client.*
            val config = GrpcClient.newClient("localhost:8080")
            assert(config.target == "localhost:8080")
            succeed

        "run a registered in-process unary call" in:
            import kyo.grpc.client.*
            val cfg = GrpcClient.newClient("inproc")
            val client = GrpcClient(cfg)
            client.registerUnary[String, String]("helloworld.Greeter/SayHello")(name => s"Hello, $name")
            val out = client.call[String, String]("helloworld.Greeter/SayHello", "Kyo").run
            assert(out == "Hello, Kyo")
            succeed

end GrpcTest
