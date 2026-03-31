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

end GrpcTest
