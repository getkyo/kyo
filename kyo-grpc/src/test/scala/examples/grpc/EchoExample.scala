package examples.grpc

import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.Marshaller
import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServerServiceDefinition
import java.io.ByteArrayInputStream
import java.io.InputStream
import kyo.*
import kyo.grpc.Grpcs

/** Example demonstrating all four gRPC RPC types with Kyo.
  *
  * This example shows how to:
  *   - Define custom marshallers
  *   - Create method descriptors
  *   - Implement handlers for all RPC types
  *   - Start a server and make client calls
  *   - Manage lifecycle with Kyo's Scope
  */
object EchoExample extends KyoApp:

    // ----- Custom String Marshaller -----

    private val stringMarshaller: Marshaller[String] = new Marshaller[String]:
        override def stream(value: String): InputStream =
            new ByteArrayInputStream(value.getBytes("UTF-8"))
        override def parse(stream: InputStream): String =
            new String(stream.readAllBytes(), "UTF-8")

    // ----- Method Descriptors -----

    private val unaryMethod: MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.UNARY)
            .setFullMethodName("echo.EchoService/UnaryEcho")
            .build()

    private val serverStreamingMethod: MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.SERVER_STREAMING)
            .setFullMethodName("echo.EchoService/ServerStreamingEcho")
            .build()

    private val clientStreamingMethod: MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.CLIENT_STREAMING)
            .setFullMethodName("echo.EchoService/ClientStreamingEcho")
            .build()

    private val bidiStreamingMethod: MethodDescriptor[String, String] =
        MethodDescriptor.newBuilder(stringMarshaller, stringMarshaller)
            .setType(MethodType.BIDI_STREAMING)
            .setFullMethodName("echo.EchoService/BidiStreamingEcho")
            .build()

    // ----- Service Definition -----

    private def serviceDefinition =
        val unaryHandler = Grpcs.unaryHandler[String, String](
            unaryMethod,
            request => Async.defer(s"echo: $request")
        )

        val serverStreamingHandler = Grpcs.serverStreamingHandler[String, String](
            serverStreamingMethod,
            request =>
                Stream.init(Seq(
                    s"chunk-1: $request",
                    s"chunk-2: $request",
                    s"chunk-3: $request"
                ))
        )

        val clientStreamingHandler = Grpcs.clientStreamingHandler[String, String](
            clientStreamingMethod,
            stream => stream.fold("")((acc, s) => acc + s + ";")
        )

        val bidiStreamingHandler = Grpcs.bidiStreamingHandler[String, String](
            bidiStreamingMethod,
            stream => stream.map(s => s"bidi-echo: $s")
        )

        ServerServiceDefinition.builder("echo.EchoService")
            .addMethod(unaryMethod, unaryHandler.getServerCallHandler)
            .addMethod(serverStreamingMethod, serverStreamingHandler.getServerCallHandler)
            .addMethod(clientStreamingMethod, clientStreamingHandler.getServerCallHandler)
            .addMethod(bidiStreamingMethod, bidiStreamingHandler.getServerCallHandler)
            .build()
    end serviceDefinition

    // ----- Main -----

    run {
        Scope.run {
            for
                server <- Grpcs.server(0, Seq(serviceDefinition))
                port = server.getPort
                _ <- Console.printLine(s"gRPC Echo server running on port $port")

                channel <- Grpcs.channel(s"localhost:$port")

                // Unary call
                unaryResult <- Grpcs.unaryCall(channel, unaryMethod, "hello")
                _           <- Console.printLine(s"Unary: $unaryResult")
            yield ()
        }
    }
end EchoExample
