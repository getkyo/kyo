package kyo.grpc

/** gRPC server for Kyo.
 *
 *  This module provides a fiber-native gRPC server implementation for Kyo.
 *  It uses Netty HTTP/2 for transport and ScalaPB for protobuf serialization.
 *
 *  @example
 *  {{{
 *  import kyo.grpc.*
 *  import kyo.grpc.server.GrpcServer
 *
 *  val server = GrpcServer.newServer(port = 8080)
 *  val bound = server.start().map { binding =>
 *    println(s"gRPC server started on ${binding.address}")
 *    binding
 *  }
 *  }}}
 */
object GrpcServer:

    /** Creates a new gRPC server configuration.
     *
     *  @param port The port to listen on
     *  @param host The host to bind to
     *  @return A new server configuration
     */
    def newServer(port: Int, host: String = "0.0.0.0"): GrpcServerConfig =
        GrpcServerConfig(port, host)

    /** Starts the gRPC server.
     *
     *  @param config The server configuration
     *  @return A fiber that resolves to a server binding when the server starts
     */
    def start(config: GrpcServerConfig): ServerBinding =
        start(config, Seq.empty)

    /** Starts the gRPC server with registered services.
     *
     *  @param config The server configuration
     *  @param services The services to register
     *  @return A fiber that resolves to a server binding when the server starts
     */
    def start(config: GrpcServerConfig, services: Seq[GrpcService]): ServerBinding =
        new GrpcServerImpl(config, services).start()

end GrpcServer

/** Configuration for a gRPC server. */
case class GrpcServerConfig(
    port: Int,
    host: String = "0.0.0.0",
    maxConcurrentCallsPerConnection: Int = 100,
    keepAliveTimeMs: Long = 60000,
    keepAliveTimeoutMs: Long = 20000
)

/** A running gRPC server binding. */
class ServerBinding(
    val address: String,
    private val stop: () => Unit
):

    /** Stops the server gracefully.
     *
     *  @return A kyo effect that completes when the server is stopped
     */
    def stop(): Unit = stop()

end ServerBinding

/** A registered gRPC service. */
trait GrpcService:

    /** The fully qualified name of the service (e.g., "helloworld.Greeter"). */
    def fullServiceName: String

    /** The service descriptor. */
    def descriptor: ProtobufServiceDescriptor

end GrpcService

/** A protobuf service descriptor. */
trait ProtobufServiceDescriptor:

    /** The name of the service. */
    def name: String

    /** The methods of the service. */
    def methods: List[ProtobufMethodDescriptor]

end ProtobufServiceDescriptor

/** A protobuf method descriptor. */
trait ProtobufMethodDescriptor:

    /** The name of the method. */
    def name: String

    /** The input type. */
    def inputType: String

    /** The output type. */
    def outputType: String

    /** Whether this is a client-streaming method. */
    def isClientStreaming: Boolean

    /** Whether this is a server-streaming method. */
    def isServerStreaming: Boolean

end ProtobufMethodDescriptor

/** Internal gRPC server implementation using Netty. */
private class GrpcServerImpl(
    config: GrpcServerConfig,
    services: Seq[GrpcService]
):

    // TODO: Implement actual Netty HTTP/2 + gRPC framing server
    // This is a stub implementation - the full implementation requires:
    // 1. Netty ChannelPipeline setup with HTTP/2 and gRPC framing
    // 2. Netty ServerBootstrap with appropriate handlers
    // 3. Kyo fiber integration for request handling
    // 4. ScalaPB message serialization/deserialization

    @volatile private var running = false

    def start(): ServerBinding =
        running = true
        new ServerBinding(s"${config.host}:${config.port}", () => running = false)

    def isRunning: Boolean = running

end GrpcServerImpl
