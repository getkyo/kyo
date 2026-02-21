package kyo.grpc

import io.grpc.MethodDescriptor
import kyo.*
import org.scalactic.TripleEquals.*

class ClientCallTest extends Test:

    case class TestRequest(message: String)
    case class TestResponse(result: String)

    "ClientCall" - {

        "method descriptors" - {

            "unary method descriptor" in run {
                val method = MethodDescriptor.newBuilder[TestRequest, TestResponse]()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Service/UnaryMethod")
                    .setRequestMarshaller(TestMarshaller[TestRequest]())
                    .setResponseMarshaller(TestMarshaller[TestResponse]())
                    .build()

                assert(method.getType.equals(MethodDescriptor.MethodType.UNARY))
                assert(method.getFullMethodName == "test.Service/UnaryMethod")
                succeed
            }

            "client streaming method descriptor" in run {
                val method = MethodDescriptor.newBuilder[TestRequest, TestResponse]()
                    .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
                    .setFullMethodName("test.Service/ClientStreamingMethod")
                    .setRequestMarshaller(TestMarshaller[TestRequest]())
                    .setResponseMarshaller(TestMarshaller[TestResponse]())
                    .build()

                assert(method.getType.equals(MethodDescriptor.MethodType.CLIENT_STREAMING))
                assert(method.getFullMethodName == "test.Service/ClientStreamingMethod")
                succeed
            }

            "server streaming method descriptor" in run {
                val method = MethodDescriptor.newBuilder[TestRequest, TestResponse]()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName("test.Service/ServerStreamingMethod")
                    .setRequestMarshaller(TestMarshaller[TestRequest]())
                    .setResponseMarshaller(TestMarshaller[TestResponse]())
                    .build()

                assert(method.getType.equals(MethodDescriptor.MethodType.SERVER_STREAMING))
                assert(method.getFullMethodName == "test.Service/ServerStreamingMethod")
                succeed
            }

            "bidirectional streaming method descriptor" in run {
                val method = MethodDescriptor.newBuilder[TestRequest, TestResponse]()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName("test.Service/BidiStreamingMethod")
                    .setRequestMarshaller(TestMarshaller[TestRequest]())
                    .setResponseMarshaller(TestMarshaller[TestResponse]())
                    .build()

                assert(method.getType.equals(MethodDescriptor.MethodType.BIDI_STREAMING))
                assert(method.getFullMethodName == "test.Service/BidiStreamingMethod")
                succeed
            }
        }

        "request options" - {

            "default options have empty headers" in run {
                val options = RequestOptions()
                assert(options.headers === SafeMetadata.empty)
                assert(options.messageCompression.isEmpty)
                assert(options.responseCapacity.isEmpty)
                succeed
            }

            "options with headers" in run {
                val headers = SafeMetadata.empty.add("test-header", "test-value")

                val options = RequestOptions(headers = headers)

                assert(options.headers.getStrings("test-header") == Seq("test-value"))
                succeed
            }

            "options with message compression" in run {
                val options = RequestOptions(messageCompression = Maybe(true))

                assert(options.messageCompression.isDefined)
                assert(options.messageCompression.get == true)
                succeed
            }

            "options with response capacity" in run {
                val options = RequestOptions(responseCapacity = Maybe(100))

                assert(options.responseCapacity.isDefined)
                assert(options.responseCapacity.get == 100)
                assert(options.responseCapacityOrDefault == 100)
                succeed
            }

            "responseCapacityOrDefault returns default when not set" in run {
                val options = RequestOptions()
                assert(options.responseCapacityOrDefault == RequestOptions.DefaultResponseCapacity)
                succeed
            }

            "combine merges options" in run {
                val h1 = SafeMetadata.empty.add("header1", "value1")
                val h2 = SafeMetadata.empty.add("header2", "value2")

                val options1 = RequestOptions(
                    headers = h1,
                    messageCompression = Maybe(true)
                )

                val options2 = RequestOptions(
                    headers = h2,
                    responseCapacity = Maybe(50)
                )

                options1.combine(options2).map: result =>
                    assert(result.headers.getStrings("header1") == Seq("value1"))
                    assert(result.headers.getStrings("header2") == Seq("value2"))
                    assert(result.messageCompression == Maybe(true))
                    assert(result.responseCapacity == Maybe(50))
                    succeed
            }
        }
    }

end ClientCallTest
