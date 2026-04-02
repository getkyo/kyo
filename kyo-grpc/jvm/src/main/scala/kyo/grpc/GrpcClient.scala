package kyo.grpc

import io.grpc.*
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.ProtocolNegotiators
import io.grpc.stub.{AbstractBlockingStub, AbstractStub, StreamObserver}
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.grpc.GrpcClientConfig
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

/** Fiber-native gRPC client implementation using Netty HTTP/2 transport.
 * 
 * This implementation provides high-performance gRPC client support by:
 * - Using Netty's direct executor to avoid thread pool overhead
 * - Sharing the scheduler across all calls (no per-request runtime creation)
 * - Integrating directly with Kyo's fibers for concurrency
 */
class GrpcClient(
    private val channel: ManagedChannel,
    config: GrpcClientConfig
):
    private val deadline = config.deadline.map(d => 
        java.util.concurrent.Deadline.after(d.toNanos, TimeUnit.NANOSECONDS)
    )

    /** Get a blocking stub for the given service */
    def stub[T <: AbstractStub[T]](factory: Channel => T): T =
        val stub = factory(channel)
        deadline.foreach(stub.withDeadlineAfter)
        stub

    /** Create a new client channel to the given host and port */
    def this(config: GrpcClientConfig) =
        this(
            NettyChannelBuilder
                .forAddress(config.host, config.port)
                .directExecutor()
                .maxInboundMessageSize(Int.MaxValue)
                .keepAliveTime(config.deadline.map(_.toNanos).getOrElse(Long.MaxValue), TimeUnit.NANOSECONDS)
                .usePlaintext()
                .build(),
            config
        )

    /** Shutdown the client channel */
    def shutdown(): Unit < Async =
        Sync.defer {
            channel.shutdown().awaitTermination(30, TimeUnit.SECONDS)
            ().pure[Async]
        }

    /** Check if channel is shutdown */
    def isShutdown: Boolean = channel.isShutdown

    /** Check if channel is terminated */
    def isTerminated: Boolean = channel.isTerminated

    /** Get the channel for low-level operations */
    def channel: Channel = channel

end GrpcClient

object GrpcClient:

    /** Create a new GrpcClient builder */
    def apply(config: GrpcClientConfig = GrpcClientConfig()): GrpcClientBuilder =
        new GrpcClientBuilder(config)

    /** Builder for configuring a GrpcClient */
    class GrpcClientBuilder(config: GrpcClientConfig):
        private var host: String = config.host
        private var port: Int = config.port
        private var deadline: Option[Duration] = config.deadline

        /** Set the server host */
        def host(h: String): GrpcClientBuilder =
            host = h
            this

        /** Set the server port */
        def port(p: Int): GrpcClientBuilder =
            port = p
            this

        /** Set the deadline for calls */
        def deadline(d: Duration): GrpcClientBuilder =
            deadline = Some(d)
            this

        /** Build the GrpcClient with the current configuration */
        def build()(using Frame): GrpcClient < Async =
            val actualConfig = GrpcClientConfig(host, port, deadline)
            new GrpcClient(actualConfig).pure[Async]

    end GrpcClientBuilder

    /** Create a client that connects to the given address */
    def connect(host: String, port: Int)(using Frame): GrpcClient < Async =
        GrpcClient(GrpcClientConfig(host, port)).build()

    /** Create a client with configuration */
    def connect(config: GrpcClientConfig)(using Frame): GrpcClient < Async =
        GrpcClient(config).build()

end GrpcClient

/** Extension methods for making gRPC calls more ergonomic */
extension [F[_], T <: AbstractStub[T]](stub: T)
    
    /** Add a deadline to the stub */
    def withDeadline(deadline: Duration): T =
        stub.withDeadlineAfter(deadline.toNanos, TimeUnit.NANOSECONDS)

end extension

/** Helper for creating gRPC channels with various configurations */
object GrpcChannels:
    
    /** Create a Netty channel builder for the given host and port */
    def nettyChannelBuilder(host: String, port: Int): NettyChannelBuilder =
        NettyChannelBuilder
            .forAddress(host, port)
            .directExecutor()
            .usePlaintext()

    /** Create a secure Netty channel builder */
    def nettySecureChannelBuilder(host: String, port: Int): NettyChannelBuilder =
        NettyChannelBuilder
            .forAddress(host, port)
            .directExecutor()
            .useTransportSecurity()
