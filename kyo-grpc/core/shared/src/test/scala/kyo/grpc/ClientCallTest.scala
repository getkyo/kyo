package kyo.grpc

import com.google.protobuf.wrappers.StringValue
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.MethodDescriptor
import kyo.*
import org.scalamock.scalatest.AsyncMockFactory2
import scalapb.grpc.Marshaller

class ClientCallTest extends Test with AsyncMockFactory2:

    "unary" in run {

        val method = MethodDescriptor
            .newBuilder[StringValue, StringValue]
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("helloworld.Greeter", "SayHello"))
            .setRequestMarshaller(Marshaller.forMessage[StringValue])
            .setResponseMarshaller(Marshaller.forMessage[StringValue])
            .build()

        val options = CallOptions.DEFAULT

        val channel = mock[Channel]

        val call = mock[io.grpc.ClientCall[StringValue, StringValue]]
        channel.newCall[StringValue, StringValue]
            .expects(method, options)
            .returns(call)
            .once()

        call.start
            .expects(*, *)
            .once()

        call.request
            // 2 because it pulls twice on start.
            .expects(2)
            .once()

        call.request
            // 2 because it pulls twice on start.
            .expects(2)
            .once()

        val request = StringValue.of("Bob")

        ClientCall.unary(channel, method, options, request).map { response =>
            assert(response.value == "Bob")
        }
    }

end ClientCallTest
