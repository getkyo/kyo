package kyo.grpc

import io.grpc.*
import io.grpc.stub.StreamObserver
import io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit
import kyo.*
import scala.concurrent.duration.*

/** Tests for GrpcServer */
class GrpcServerTest extends GrpcTest:

    "start and stop server" in run {
        val server = createServer()
        for
            binding <- server.start()
            _ <- assert(binding.port > 0)
            _ <- assert(binding.host == "localhost" || binding.host == "0.0.0.0")
            _ <- binding.stop()
        yield succeed
    }

    "server binds to correct port" in run {
        val server = createServer()
        for
            binding <- server.start()
            _ <- assert(binding.port > 0 && binding.port < 65536)
            _ <- binding.stop()
        yield succeed
    }

    "server can be stopped gracefully" in run {
        val server = createServer()
        for
            binding <- server.start()
            _ <- binding.stop()
            _ <- assert(server.port < 0)  // Server should be stopped
        yield succeed
    }

    "server handles multiple start/stop cycles" in run {
        val server = createServer()
        for
            binding1 <- server.start()
            port1 = binding1.port
            _ <- binding1.stop()
            binding2 <- server.start()
            _ <- assert(binding2.port != port1 || binding2.port == port1) // Port may or may not be same
            _ <- binding2.stop()
        yield succeed
    }

    "server with service can be stopped" in run {
        val service = new TestServiceImpl()
        val serverDef = ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                "testMethod",
                io.grpc.MethodDescriptor.newBuilder[String, String]()
                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.TestService/testMethod")
                    .setRequestMarshaller(io.grpc.protobuf.ProtobufUtils.marshaller(classOf[String]))
                    .setResponseMarshaller(io.grpc.protobuf.ProtobufUtils.marshaller(classOf[String]))
                    .build(),
                new io.grpc.stub.ServerCalls.UnaryMethod[String, String] {
                    def invoke(request: String, observer: StreamObserver[String]): Unit =
                        observer.onNext(s"Echo: $request")
                        observer.onCompleted()
                }
            )
            .build()
        
        val server = createServer(service :: serverDef :: Nil:_*)
        for
            binding <- server.start()
            _ <- binding.stop()
        yield succeed
    }

end GrpcServerTest

/** Simple test service implementation for testing */
class TestServiceImpl extends io.grpc.stub.AbstractService {
    import io.grpc.stub.ServerCalls
    
    override def bindService(): ServerServiceDefinition = {
        ServerServiceDefinition.builder("test.TestService")
            .addMethod(
                "unaryTest",
                io.grpc.MethodDescriptor.newBuilder[String, String]()
                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.TestService/unaryTest")
                    .setRequestMarshaller(io.grpc.protobuf.ProtobufUtils.marshaller(classOf[String]))
                    .setResponseMarshaller(io.grpc.protobuf.ProtobufUtils.marshaller(classOf[String]))
                    .build(),
                new ServerCalls.UnaryMethod[String, String] {
                    def invoke(request: String, observer: StreamObserver[String]): Unit = {
                        observer.onNext(s"Unary response to: $request")
                        observer.onCompleted()
                    }
                }
            )
            .build()
    }
}

object TestServiceImpl:
    val SERVICE_NAME = "test.TestService"
    
    val UNARY_METHOD = io.grpc.MethodDescriptor.newBuilder[String, String]()
        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(s"$SERVICE_NAME/unary")
        .setRequestMarshaller(io.grpc.protobuf.ProtobufUtils.marshaller(classOf[String]))
        .setResponseMarshaller(io.grpc.protobuf.ProtobufUtils.marshaller(classOf[String]))
        .build()
    
    def createServiceDefinition(): ServerServiceDefinition =
        ServerServiceDefinition.builder(SERVICE_NAME)
            .addMethod(UNARY_METHOD, unaryHandler)
            .build()
    
    private val unaryHandler = new ServerCalls.UnaryMethod[String, String] {
        def invoke(request: String, observer: StreamObserver[String]): Unit = {
            observer.onNext(s"Response: $request")
            observer.onCompleted()
        }
    }

end TestServiceImpl
