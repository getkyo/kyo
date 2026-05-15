package kyo.grpc

import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.Marshaller
import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusException
import java.io.ByteArrayInputStream
import java.io.InputStream
import kyo.*
import org.scalatest.freespec.AnyFreeSpec

class GrpcTest extends AnyFreeSpec:

    private val stringMarshaller: Marshaller[String] = new Marshaller[String]:
        override def stream(value: String): InputStream =
            new ByteArrayInputStream(value.getBytes("UTF-8"))
        override def parse(stream: InputStream): String =
            new String(stream.readAllBytes(), "UTF-8")

    private def unaryMethod(name: String): MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.UNARY)
            .setFullMethodName(s"test.TestService/$name")
            .build()

    private def serverStreamingMethod(name: String): MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.SERVER_STREAMING)
            .setFullMethodName(s"test.TestService/$name")
            .build()

    private def clientStreamingMethod(name: String): MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.CLIENT_STREAMING)
            .setFullMethodName(s"test.TestService/$name")
            .build()

    private def bidiStreamingMethod(name: String): MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.BIDI_STREAMING)
            .setFullMethodName(s"test.TestService/$name")
            .build()

    // ===== Unary Tests =====

    "unary echo round-trip" in {
        import AllowUnsafe.embrace.danger

        val method = unaryMethod("Echo")
        val handler = Grpcs.unaryHandler[String, String](
            method,
            request => Async.defer(s"echo: $request")
        )

        val ssd = ServerServiceDefinition.builder("test.TestService")
            .addMethod(method, handler.getServerCallHandler)
            .build()

        val result = Sync.Unsafe.evalOrThrow {
            KyoApp.runAndBlock(Duration.Infinity) {
                Scope.run {
                    for
                        server <- Grpcs.server(0, Seq(ssd))
                        port = server.getPort
                        channel <- Grpcs.channel(s"localhost:$port")
                        result  <- Grpcs.unaryCall(channel, method, "hello")
                    yield result
                }
            }
        }

        assert(result == "echo: hello")
    }

    "unary handler propagates StatusException" in {
        import AllowUnsafe.embrace.danger

        val method = unaryMethod("FailingUnary")
        val handler = Grpcs.unaryHandler[String, String](
            method,
            _ => Abort.fail(Status.INVALID_ARGUMENT.withDescription("bad input").asException())
        )

        val ssd = ServerServiceDefinition.builder("test.TestService")
            .addMethod(method, handler.getServerCallHandler)
            .build()

        val thrown = intercept[io.grpc.StatusRuntimeException] {
            Sync.Unsafe.evalOrThrow {
                KyoApp.runAndBlock(Duration.Infinity) {
                    Scope.run {
                        for
                            server <- Grpcs.server(0, Seq(ssd))
                            port = server.getPort
                            channel <- Grpcs.channel(s"localhost:$port")
                            result  <- Grpcs.unaryCall(channel, method, "trigger")
                        yield result
                    }
                }
            }
        }

        assert(thrown.getStatus.getCode eq io.grpc.Status.Code.INVALID_ARGUMENT)
        assert(thrown.getStatus.getDescription == "bad input")
    }

    // ===== Server Streaming Tests =====

    "server streaming handler is created with correct method descriptor" in {
        val method = serverStreamingMethod("ServerStreamEcho")
        val handler = Grpcs.serverStreamingHandler[String, String](
            method,
            request => Stream.init(Seq(s"1: $request", s"2: $request", s"3: $request"))
        )

        assert(handler.getMethodDescriptor.getFullMethodName == method.getFullMethodName)
        assert(handler.getMethodDescriptor.getType eq MethodType.SERVER_STREAMING)
    }

    // ===== Client Streaming Tests =====

    "client streaming handler is created with correct method descriptor" in {
        val method = clientStreamingMethod("ClientStreamEcho")
        val handler = Grpcs.clientStreamingHandler[String, String](
            method,
            stream => stream.fold("")((acc, s) => acc + s + ";")
        )

        assert(handler.getMethodDescriptor.getFullMethodName == method.getFullMethodName)
        assert(handler.getMethodDescriptor.getType eq MethodType.CLIENT_STREAMING)
    }

    // ===== Bidirectional Streaming Tests =====

    "bidi streaming handler is created with correct method descriptor" in {
        val method = bidiStreamingMethod("BidiStreamEcho")
        val handler = Grpcs.bidiStreamingHandler[String, String](
            method,
            stream => stream.map(s => s"bidi: $s")
        )

        assert(handler.getMethodDescriptor.getFullMethodName == method.getFullMethodName)
        assert(handler.getMethodDescriptor.getType eq MethodType.BIDI_STREAMING)
    }

    // ===== Server/Channel Lifecycle Tests =====

    "server with port 0 gets assigned a real port" in {
        import AllowUnsafe.embrace.danger

        val method  = unaryMethod("Echo")
        val handler = Grpcs.unaryHandler[String, String](method, request => Async.defer(request))

        val ssd = ServerServiceDefinition.builder("test.TestService")
            .addMethod(method, handler.getServerCallHandler)
            .build()

        val port = Sync.Unsafe.evalOrThrow {
            KyoApp.runAndBlock(Duration.Infinity) {
                Scope.run {
                    for
                        server <- Grpcs.server(0, Seq(ssd))
                        port = server.getPort
                    yield port
                }
            }
        }

        assert(port > 0)
    }

    "multiple methods on same service" in {
        import AllowUnsafe.embrace.danger

        val method1 = unaryMethod("Echo1")
        val method2 = unaryMethod("Echo2")

        val handler1 = Grpcs.unaryHandler[String, String](method1, request => Async.defer(s"svc1: $request"))
        val handler2 = Grpcs.unaryHandler[String, String](method2, request => Async.defer(s"svc2: $request"))

        val ssd = ServerServiceDefinition.builder("test.TestService")
            .addMethod(method1, handler1.getServerCallHandler)
            .addMethod(method2, handler2.getServerCallHandler)
            .build()

        val result = Sync.Unsafe.evalOrThrow {
            KyoApp.runAndBlock(Duration.Infinity) {
                Scope.run {
                    for
                        server <- Grpcs.server(0, Seq(ssd))
                        port = server.getPort
                        channel <- Grpcs.channel(s"localhost:$port")
                        result1 <- Grpcs.unaryCall(channel, method1, "hello")
                        result2 <- Grpcs.unaryCall(channel, method2, "hello")
                    yield (result1, result2)
                }
            }
        }

        assert(result == (("svc1: hello", "svc2: hello")))
    }

end GrpcTest
