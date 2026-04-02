package kyo.grpc

import io.grpc.*
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.{AbstractBlockingStub, StreamObserver}
import java.util.concurrent.TimeUnit
import kyo.*
import scala.concurrent.duration.*

/** Tests for GrpcClient */
class GrpcClientTest extends GrpcTest:

    "client can connect to server" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding <- server.start()
            client <- GrpcClient(GrpcClientConfig("localhost", binding.port)).build()
            _ <- assert(!client.isShutdown)
            _ <- client.shutdown()
            _ <- binding.stop()
        yield succeed
    }

    "client shutdown terminates channel" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding <- server.start()
            client <- GrpcClient(GrpcClientConfig("localhost", binding.port)).build()
            _ <- client.shutdown()
            _ <- assert(client.isShutdown)
            _ <- binding.stop()
        yield succeed
    }

    "client with deadline can be created" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding <- server.start()
            client <- GrpcClient(
                GrpcClientConfig(
                    host = "localhost",
                    port = binding.port,
                    deadline = Some(10.seconds)
                )
            ).build()
            _ <- assert(!client.isShutdown)
            _ <- client.shutdown()
            _ <- binding.stop()
        yield succeed
    }

    "client can create blocking stub" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding <- server.start()
            client <- GrpcClient(GrpcClientConfig("localhost", binding.port)).build()
            stub = client.blockingStub { ch =>
                new AbstractBlockingStub {
                    override def build(ch: Channel): AbstractBlockingStub = this
                }
            }
            _ <- assert(stub != null)
            _ <- client.shutdown()
            _ <- binding.stop()
        yield succeed
    }

    "client handles connection failure gracefully" in run {
        val config = GrpcClientConfig("localhost", 12345)  // Non-existent port
        for
            client <- GrpcClient(config).build()
            _ <- assert(!client.isShutdown)
            _ <- client.shutdown().handle(Abort.run)
        yield succeed
    }

end GrpcClientTest

/** Integration tests for server and client together */
class GrpcServerClientIntegrationTest extends GrpcTest:

    "server and client work together" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding <- server.start()
            client <- GrpcClient(GrpcClientConfig("localhost", binding.port)).build()
            _ <- assert(binding.port > 0)
            _ <- assert(!client.isShutdown)
            _ <- client.shutdown()
            _ <- binding.stop()
        yield succeed
    }

    "multiple clients can connect to same server" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding <- server.start()
            client1 <- GrpcClient(GrpcClientConfig("localhost", binding.port)).build()
            client2 <- GrpcClient(GrpcClientConfig("localhost", binding.port)).build()
            _ <- assert(!client1.isShutdown)
            _ <- assert(!client2.isShutdown)
            _ <- client1.shutdown()
            _ <- client2.shutdown()
            _ <- binding.stop()
        yield succeed
    }

    "server can restart after stop" in run {
        val serviceDef = TestServiceImpl.createServiceDefinition()
        val server = createServer(serviceDef)
        
        for
            binding1 <- server.start()
            _ <- binding1.stop()
            binding2 <- server.start()
            _ <- binding2.stop()
        yield succeed
    }

end GrpcServerClientIntegrationTest
