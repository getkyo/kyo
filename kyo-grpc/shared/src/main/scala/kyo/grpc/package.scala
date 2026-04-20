package kyo.grpc

/** gRPC support for Kyo.
 *
 *  This module provides fiber-native gRPC client and server implementations
 *  using Netty HTTP/2 transport and ScalaPB protobuf serialization.
 *
 *  == Overview ==
 *
 *  The gRPC module offers two main components:
 *  - [[GrpcServer]] for creating and managing gRPC servers
 *  - [[client.GrpcClient]] for making gRPC calls
 *
 *  == Server Example ==
 *
 *  {{{
 *  import kyo.grpc.*
 *
 *  val server = GrpcServer.newServer(port = 8080)
 *  val binding = server.start()
 *  }}}
 *
 *  == Client Example ==
 *
 *  {{{
 *  import kyo.grpc.client.*
 *
 *  val client = GrpcClient.newClient("localhost:8080")
 *  val result = client.call[HelloRequest, HelloReply]("helloworld.Greeter/SayHello", HelloRequest("World"))
 *  }}}
 *
 *  == Design ==
 *
 *  The implementation uses:
 *  - Netty HTTP/2 for transport
 *  - ScalaPB for protobuf serialization
 *  - Kyo fibers for concurrency
 *
 *  This provides better performance than ZIO gRPC interop because it
 *  avoids the fiber-to-fiber context switching overhead.
 */
package object grpc:

    // Common imports for gRPC users
    type Channel = kyo.grpc.client.GrpcClient
    type Server = kyo.grpc.GrpcServer

end grpc
