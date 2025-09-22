package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.grpc.*
import org.scalamock.scalatest.AsyncMockFactory

class UnaryServerCallHandlerTest extends kyo.grpc.Test with AsyncMockFactory:

    case class TestRequest(message: String)
    case class TestResponse(result: String)

    "UnaryServerCallHandler" - {

        "successful call" - {
            "returns OK status when handler succeeds" in run {
                val request = TestRequest("test")
                val expectedResponse = TestResponse("response")
                
                val handler: GrpcHandler[TestRequest, TestResponse] = _ => expectedResponse

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                // Mock expectations for successful flow
                call.request
                    .expects(1)
                    .once()

                call.sendMessage
                    .expects(expectedResponse)
                    .once()

                call.close
                    .expects(*, *)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                // Simulate receiving a message
                listener.onMessage(request)
                listener.onHalfClose()

                assertionSuccess
            }

            "handles metadata emission correctly" in run {
                val request = TestRequest("test")
                val expectedResponse = TestResponse("response")
                val testMetadata = Metadata()
                testMetadata.put(Metadata.Key.of("test-key", Metadata.ASCII_STRING_MARSHALLER), "test-value")
                
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => 
                        for
                            _ <- Emit.value(testMetadata)
                            result = expectedResponse
                        yield result

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                // Mock expectations
                call.request
                    .expects(1)
                    .once()
                    
                call.sendMessage
                    .expects(expectedResponse)
                    .once()
                    
                call.close
                    .expects(*, *)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                listener.onMessage(request)
                listener.onHalfClose()

                assertionSuccess
            }
        }

        "error handling" - {
            "handles abort failure correctly" in run {
                val request = TestRequest("test")
                val status = Status.INVALID_ARGUMENT.withDescription("Bad request")
                
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => Abort.fail(status.asException())

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                // Mock expectations for error flow
                call.request
                    .expects(1)
                    .once()
                    
                call.close
                    .expects(*, *)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                listener.onMessage(request)
                listener.onHalfClose()

                assertionSuccess
            }

            "handles panic correctly" in run {
                val request = TestRequest("test")
                val errorMessage = "Something went wrong"
                
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => Abort.panic(Exception(errorMessage))

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                // Mock expectations for panic flow
                call.request
                    .expects(1)
                    .once()
                    
                call.close
                    .expects(*, *)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                listener.onMessage(request)
                listener.onHalfClose()

                assertionSuccess
            }
        }

        "request handling" - {
            "fails when client sends multiple requests" in run {
                val request1 = TestRequest("first")
                val request2 = TestRequest("second")
                
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => TestResponse("response")

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                call.request
                    .expects(1)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                // First message should be accepted
                listener.onMessage(request1)
                
                // Second message should throw an exception
                intercept[StatusException] {
                    listener.onMessage(request2)
                }

                listener.onHalfClose()

                assertionSuccess
            }

            "fails when client completes without sending request" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => TestResponse("response")

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                // Mock expectations for error flow when no request sent
                call.request
                    .expects(1)
                    .once()
                    
                call.close
                    .expects(*, *)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                // Complete without sending a message
                listener.onHalfClose()

                assertionSuccess
            }
        }

        "cancellation" - {
            "handles call cancellation correctly" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => TestResponse("response")

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                call.request
                    .expects(1)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                // Cancel the call - should handle gracefully
                listener.onCancel()

                assertionSuccess
            }
        }

        "lifecycle" - {
            "handles onComplete correctly" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => TestResponse("response")

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                call.request
                    .expects(1)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                // Call onComplete - should not throw
                listener.onComplete()

                assertionSuccess
            }

            "handles onReady correctly" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => TestResponse("response")

                val init: GrpcHandlerInit[TestRequest, TestResponse] = handler

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                call.request
                    .expects(1)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                // Call onReady - should not throw
                listener.onReady()

                assertionSuccess
            }
        }

        "response options handling" - {
            "processes response options correctly" in run {
                val request = TestRequest("test")
                val expectedResponse = TestResponse("response")
                val responseHeaders = Metadata()
                responseHeaders.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")
                
                val handler: GrpcHandler[TestRequest, TestResponse] = 
                    req => expectedResponse

                val init: GrpcHandlerInit[TestRequest, TestResponse] = 
                    Emit.value(ResponseOptions(headers = Maybe.Present(responseHeaders))).map(_ => handler)

                val callHandler = UnaryServerCallHandler(init)
                val call = mock[ServerCall[TestRequest, TestResponse]]
                val headers = Metadata()

                // Mock expectations - should send headers and then response
                call.request
                    .expects(1)
                    .once()
                    
                call.sendHeaders
                    .expects(*)
                    .once()
                    
                call.sendMessage
                    .expects(expectedResponse)
                    .once()
                    
                call.close
                    .expects(*, *)
                    .once()

                val listener = callHandler.startCall(call, headers)
                
                listener.onMessage(request)
                listener.onHalfClose()

                assertionSuccess
            }
        }
    }

end UnaryServerCallHandlerTest