package kyo.grpc.client

/** gRPC client for Kyo.
 *
 *  This module provides a fiber-native gRPC client implementation for Kyo.
 *
 *  @example
 *  {{{
 *  import kyo.grpc.client.*
 *
 *  val client = GrpcClient.newClient("localhost:8080")
 *  val result = client.call[HelloRequest, HelloReply]("helloworld.Greeter/SayHello", HelloRequest(name = "World"))
 *  }}}
 */
object GrpcClient:

    /** Creates a new gRPC client for the given target.
     *
     *  @param target The target address (e.g., "localhost:8080")
     *  @return A new client configuration
     */
    def newClient(target: String): GrpcClientConfig =
        GrpcClientConfig(target)

end GrpcClient

/** Configuration for a gRPC client. */
case class GrpcClientConfig(
    target: String,
    maxConcurrentCalls: Int = 100,
    keepAliveTimeMs: Long = 60000,
    idleTimeoutMs: Long = Long.MaxValue
)

/** A gRPC client for making calls. */
class GrpcClient(config: GrpcClientConfig):

    /** Makes a unary call to a gRPC method.
     *
     *  @param method The full method name (e.g., "helloworld.Greeter/SayHello")
     *  @param request The request message
     *  @tparam Req The request type
     *  @tparam Res The response type
     *  @return A fiber containing the response
     */
    def call[Req, Res](method: String, request: Req): Call[Req, Res] =
        new Call(method, request)

end GrpcClient

/** A pending gRPC call. */
class Call[Req, Res](method: String, request: Req):

    /** Executes the call and returns the response.
     *
     *  @return The response message
     */
    def run: Any = ???

end Call
