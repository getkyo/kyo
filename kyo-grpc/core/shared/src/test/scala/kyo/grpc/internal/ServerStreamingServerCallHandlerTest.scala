package kyo.grpc.internal

import io.grpc.{Grpc as _, *}
import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.grpc.*
import kyo.grpc.Equalities.given
import org.scalamock.scalatest.AsyncMockFactory
import org.scalamock.stubs.Stubs
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.time.{Seconds, Span}

class ServerStreamingServerCallHandlerTest extends Test with Stubs with Eventually:

    case class TestRequest(message: String)
    case class TestResponse(result: String)

    override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = scaled(Span(5, Seconds)))

    "ServerStreamingServerCallHandler" - {

        "startup" - {
            "requests one message from client" in run {
                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.empty[TestResponse]

                val callHandler = ServerStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                assert(call.request.calls === List(1))
            }

            "set options and sends headers" in run {
                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.empty[TestResponse]

                val requestHeaders = Metadata()

                val responseHeaders = Metadata()
                responseHeaders.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")

                val responseOptions = ResponseOptions(
                    headers = Maybe.Present(responseHeaders),
                    messageCompression = Maybe.Present(true),
                    compression = Maybe.Present("gzip"),
                    onReadyThreshold = Maybe.Present(16),
                    requestBuffer = Maybe.Present(4)
                )

                val init: GrpcHandlerInit[TestRequest, Stream[TestResponse, Grpc]] =
                    for
                        actualRequestHeaders <- Env.get[Metadata]
                        _ <- Emit.value(responseOptions)
                    yield
                        assert(actualRequestHeaders eq requestHeaders)
                        handler

                val callHandler = ServerStreamingServerCallHandler(init)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.setMessageCompression.returnsWith(())
                call.setCompression.returnsWith(())
                call.setOnReadyThreshold.returnsWith(())
                call.sendHeaders.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                assert(call.setMessageCompression.calls === responseOptions.messageCompression.toList)
                assert(call.setCompression.calls === responseOptions.compression.toList)
                assert(call.setOnReadyThreshold.calls === responseOptions.onReadyThreshold.toList)
                assert(call.sendHeaders.calls === responseOptions.headers.toList)
            }
        }

        "success" - {
            "sends multiple response messages" in run {
                import org.scalactic.TraversableEqualityConstraints.*

                val request = TestRequest("test")
                val responses = List(
                    TestResponse("response1"),
                    TestResponse("response2"),
                    TestResponse("response3")
                )

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.init(responses)

                val callHandler = ServerStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                (() => call.isReady()).returnsWith(true)
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Simulate receiving a message
                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 3)
                    assert(call.close.times === 1)
                }

                assert(call.sendMessage.calls === responses)
                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }

            "sends empty stream" in run {
                import org.scalactic.TraversableEqualityConstraints.*

                val request = TestRequest("test")

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.empty[TestResponse]

                val callHandler = ServerStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 0)
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }

            "processes responses incrementally" in run {
                val request = TestRequest("test")
                val sentMessages = new AtomicInteger(0)

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req =>
                        Stream.init(List(
                            TestResponse("response1"),
                            TestResponse("response2"),
                            TestResponse("response3")
                        )).tap(_ => Sync.defer(sentMessages.incrementAndGet()))

                val callHandler = ServerStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                (() => call.isReady()).returnsWith(true)
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 3)
                    assert(sentMessages.get === 3)
                }
            }

            "closes with trailers from handler" in run {
                val request = TestRequest("test")
                val responseTrailers = Metadata()
                responseTrailers.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req =>
                        for
                            _ <- Emit.value(responseTrailers)
                        yield Stream.init(List(TestResponse("response")))

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, responseTrailers))
            }
        }

        "errors" - {
            "handles abort failure correctly" in run {
                val request = TestRequest("test")
                val status = Status.INVALID_ARGUMENT.withDescription("Bad request")

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Abort.fail(status.asException())

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }

            "handles panic correctly" in run {
                val request = TestRequest("test")
                val cause = Exception("Something went wrong")

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Abort.panic(cause)

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                val status = Status.UNKNOWN.withCause(cause)
                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }

            "handles error during stream generation" in run {
                val request = TestRequest("test")

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req =>
                        Stream.init(List(
                            TestResponse("response1"),
                            TestResponse("response2")
                        )).map(resp =>
                            if resp.result == "response2" then
                                throw Exception("Stream error")
                            else
                                resp
                        )

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                val (status, _) = call.close.calls.head
                assert(status.getCode === Status.Code.UNKNOWN)
            }

            "fails when client completes without sending request" in run {
                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.empty[TestResponse]

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Complete without sending a message
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                val status = Status.INVALID_ARGUMENT.withDescription("Client completed before sending a request.")
                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }
        }

        "cancellation" - {
            "interrupts while sending messages" in run {
                val request = TestRequest("test")
                val responses = List.fill(10)(TestResponse("response"))

                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.init(responses)

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())

                val interrupted = new JAtomicBoolean(false)
                call.sendMessage.returnsWith {
                    try {
                        Thread.sleep(patienceConfig.timeout.toMillis + 1000)
                    } catch {
                        case e: InterruptedException =>
                            interrupted.set(true)
                            throw e
                    }
                }

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)
                listener.onHalfClose()
                listener.onCancel()

                eventually {
                    assert(call.close.times === 1)
                }

                val status = Status.CANCELLED.withDescription("Call was cancelled.")
                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }
        }

        // TODO: Re-enable these tests after implementing Signal-based flow control
        // "flow control" - {
        //     ...
        // }

        "lifecycle" - {
            "onComplete does nothing" in run {
                val handler: GrpcHandler[TestRequest, Stream[TestResponse, Grpc]] =
                    req => Stream.empty[TestResponse]

                val callHandler = ServerStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Call onComplete - should not throw
                listener.onComplete()

                assert(call.request.times === 1)
            }
        }
    }

end ServerStreamingServerCallHandlerTest
