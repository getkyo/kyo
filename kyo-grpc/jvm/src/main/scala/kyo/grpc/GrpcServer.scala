package kyo.grpc

import io.grpc.*
import io.grpc.netty.NettyServerBuilder
import io.netty.channel.{EventLoopGroup, NioEventLoopGroup, ServerChannel}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.grpc.GrpcServerConfig
import scala.jdk.CollectionConverters.*

/** Fiber-native gRPC server implementation using Netty HTTP/2 transport.
 * 
 * This implementation provides high-performance gRPC server support by:
 * - Using Netty's direct executor to avoid thread pool overhead
 * - Sharing the scheduler across all requests (no per-request runtime creation)
 * - Integrating directly with Kyo's fibers for concurrency
 */
class GrpcServer(
    services: List[ServerServiceDefinition],
    config: GrpcServerConfig
):
    private var server: Server = _
    private val isShuttingDown = AtomicBoolean(false)

    /** Start the gRPC server and return the binding */
    def start(): ServerBinding < Async =
        import AllowUnsafe.embrace.danger
        Sync.defer {
            val eventLoopGroup = NioEventLoopGroup(1)
            
            val builder = NettyServerBuilder
                .forPort(config.port)
                .directExecutor()
                .maxConcurrentCallsPerConnection(config.maxConcurrentCallsPerConnection)
                .keepAliveTime(config.keepAliveTime.toNanos, TimeUnit.NANOSECONDS)
                .permitKeepAliveTime(config.permitKeepAliveTime.toNanos, TimeUnit.NANOSECONDS)
                .withChildHandler {
                    case ch: SocketChannel => 
                        // HTTP/2 and gRPC framing handled by NettyServerBuilder automatically
                        ()
                }

            services.foreach(builder.addService)

            server = builder.build()
            server.start()
            
            val address = server.getBindAddr match
                case addr: InetSocketAddress => addr
                case _ => new InetSocketAddress(config.port)

            val binding = ServerBinding(
                address.getHostString,
                address.getPort, 
                () => stopWithEventLoop(eventLoopGroup)
            )
            binding.pure[Async]
        }
    end start

    private def stopWithEventLoop(eventLoopGroup: NioEventLoopGroup): Unit < Async =
        if isShuttingDown.getAndSet(true) then
            ().pure[Async]
        else
            Sync.defer {
                if server != null then
                    server.shutdown().awaitTermination(30, TimeUnit.SECONDS)
                    if !server.isTerminated then
                        server.shutdownNow()
                    server = null
                eventLoopGroup.shutdownGracefully(0, 30, TimeUnit.SECONDS)
                ().pure[Async]
            }

    /** Stop the gRPC server */
    def stop(): Unit < Async =
        if isShuttingDown.getAndSet(true) then
            ().pure[Async]
        else
            Sync.defer {
                if server != null then
                    server.shutdown().awaitTermination(30, TimeUnit.SECONDS)
                    if !server.isTerminated then
                        server.shutdownNow()
                    server = null
                ().pure[Async]
            }

    /** Get the port the server is bound to */
    def port: Int < Async =
        if server != null then
            server.getPort.pure[Async]
        else
            (-1).pure[Async]

end GrpcServer

object GrpcServer:

    /** Create a new GrpcServer builder */
    def apply(config: GrpcServerConfig = GrpcServerConfig()): GrpcServerBuilder =
        new GrpcServerBuilder(config)

    /** Builder for configuring a GrpcServer */
    class GrpcServerBuilder(config: GrpcServerConfig):
        private val services = scala.collection.mutable.ListBuffer[ServerServiceDefinition]()

        /** Add a gRPC service definition to the server */
        def addService(service: ServerServiceDefinition): GrpcServerBuilder =
            services += service
            this

        /** Build the server instance */
        def build(): GrpcServer =
            new GrpcServer(services.toList, config)

    end GrpcServerBuilder

end GrpcServer

/** Represents a bound server with its address and stop function */
class ServerBinding(
    val host: String,
    val port: Int,
    private val stopFn: () => Unit < Async
):
    def stop(): Unit < Async = stopFn()
    
    /** Stop the server using aFrame */
    def close()(using Frame): Unit < Async = stop()
    
    override def toString = s"ServerBinding($host:$port)"
end ServerBinding
