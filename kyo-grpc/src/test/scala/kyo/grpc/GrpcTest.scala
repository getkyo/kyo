package kyo.grpc

import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.Marshaller
import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServerServiceDefinition
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

    private val echoMethod: MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.UNARY)
            .setFullMethodName("test.TestService/Echo")
            .build()

    "unary echo round-trip" in {
        import AllowUnsafe.embrace.danger

        val handler = Grpcs.unaryHandler[String, String](
            echoMethod,
            request => Async.defer(s"echo: $request")
        )

        val ssd = ServerServiceDefinition.builder("test.TestService")
            .addMethod(echoMethod, handler.getServerCallHandler)
            .build()

        val result = Sync.Unsafe.evalOrThrow {
            KyoApp.runAndBlock(Duration.Infinity) {
                Scope.run {
                    for
                        server <- Grpcs.server(0, Seq(ssd))
                        port = server.getPort
                        channel <- Grpcs.channel(s"localhost:$port")
                        result  <- Grpcs.unaryCall(channel, echoMethod, "hello")
                    yield result
                }
            }
        }

        assert(result == "echo: hello")
    }

end GrpcTest
