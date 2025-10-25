package kyo.grpc

import io.grpc.Metadata
import io.grpc.MethodDescriptor
import kyo.*

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

        "type aliases" - {

            "GrpcRequestCompletion type alias exists" in {
                // Verify the type alias compiles
                val effect: GrpcRequestCompletion = Kyo.unit
                succeed
            }

            "GrpcRequests type alias exists" in {
                // Verify the type alias compiles
                def test(r: GrpcRequests[TestRequest]): Unit = ()
                succeed
            }

            "GrpcRequestsInit type alias exists" in {
                // Verify the type alias compiles
                def test(r: GrpcRequestsInit[TestRequest]): Unit = ()
                succeed
            }
        }

        "request options" - {

            "default options have empty headers" in run {
                val options = RequestOptions()
                assert(options.headers.isEmpty)
                assert(options.messageCompression.isEmpty)
                assert(options.responseCapacity.isEmpty)
                succeed
            }

            "options with headers" in run {
                val headers = Metadata()
                val key     = Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER)
                headers.put(key, "test-value")

                val options = RequestOptions(headers = Maybe(headers))

                assert(options.headers.isDefined)
                assert(options.headers.get.get(key) == "test-value")
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
                val headers1 = Metadata()
                val key1     = Metadata.Key.of("header1", Metadata.ASCII_STRING_MARSHALLER)
                headers1.put(key1, "value1")

                val headers2 = Metadata()
                val key2     = Metadata.Key.of("header2", Metadata.ASCII_STRING_MARSHALLER)
                headers2.put(key2, "value2")

                val options1 = RequestOptions(
                    headers = Maybe(headers1),
                    messageCompression = Maybe(true)
                )

                val options2 = RequestOptions(
                    headers = Maybe(headers2),
                    responseCapacity = Maybe(50)
                )

                for
                    combined <- options1.combine(options2)
                yield
                    assert(combined.headers.isDefined)
                    // options2 doesn't have messageCompression, so options1's value is used
                    assert(combined.messageCompression.isDefined)
                    assert(combined.messageCompression.get == true)
                    assert(combined.responseCapacity.isDefined)
                    assert(combined.responseCapacity.get == 50)
            }
        }
    }

    private case class TestMarshaller[T]() extends MethodDescriptor.Marshaller[T]:
        def stream(value: T): java.io.InputStream =
            new java.io.ByteArrayInputStream(value.toString.getBytes)

        def parse(stream: java.io.InputStream): T =
            throw new UnsupportedOperationException("Not implemented for test")
    end TestMarshaller

end ClientCallTest
