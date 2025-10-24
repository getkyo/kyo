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

class BidiStreamingServerCallHandlerTest extends Test with Stubs with Eventually:

    case class TestRequest(message: String)
    case class TestResponse(result: String)

    override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = scaled(Span(5, Seconds)))

    "BidiStreamingServerCallHandler" - {

        "startup" - {
            "requests one message from client initially" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    requests => requests.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Requests 1 initially, then (bufferSize - 1) = 7 more to fill the buffer
                assert(call.request.calls === List(1, 7))
            }

            "set options and sends headers" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    requests => requests.map(req => TestResponse(s"echo: ${req.message}"))

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

                val init: GrpcHandlerInit[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    for
                        actualRequestHeaders <- Env.get[Metadata]
                        _ <- Emit.value(responseOptions)
                    yield
                        assert(actualRequestHeaders eq requestHeaders)
                        handler

                val callHandler = BidiStreamingServerCallHandler(init)

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

            "requests additional messages based on buffer size" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    requests => requests.map(req => TestResponse(s"echo: ${req.message}"))

                val requestHeaders = Metadata()

                val responseOptions = ResponseOptions(
                    requestBuffer = Maybe.Present(5)
                )

                val init: GrpcHandlerInit[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    for
                        _ <- Emit.value(responseOptions)
                    yield handler

                val callHandler = BidiStreamingServerCallHandler(init)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Should request 1 initially, then 4 more to fill buffer
                assert(call.request.calls === List(1, 4))
            }
        }

        "success" - {
            "echoes multiple request-response pairs" in run {
                import org.scalactic.TraversableEqualityConstraints.*

                val requests = List(
                    TestRequest("msg1"),
                    TestRequest("msg2"),
                    TestRequest("msg3")
                )

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => stream.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                (() => call.isReady()).returnsWith(true)
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Send multiple messages
                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 3)
                    assert(call.close.times === 1)
                }

                val expectedResponses = requests.map(req => TestResponse(s"echo: ${req.message}"))
                assert(call.sendMessage.calls === expectedResponses)
                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }

            "processes stream with transformations" in run {
                val requests = List(
                    TestRequest("1"),
                    TestRequest("2"),
                    TestRequest("3")
                )

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream =>
                        stream
                            .map(req => req.message.toInt)
                            .map(num => num * 2)
                            .map(num => TestResponse(num.toString))

                val callHandler = BidiStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                (() => call.isReady()).returnsWith(true)
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 3)
                }

                val expectedResponses = List(
                    TestResponse("2"),
                    TestResponse("4"),
                    TestResponse("6")
                )
                assert(call.sendMessage.calls === expectedResponses)
            }

            "handles filter operations" in run {
                val requests = List(
                    TestRequest("keep1"),
                    TestRequest("filter"),
                    TestRequest("keep2"),
                    TestRequest("filter"),
                    TestRequest("keep3")
                )

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream =>
                        stream
                            .filter(req => req.message.startsWith("keep"))
                            .map(req => TestResponse(s"kept: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                (() => call.isReady()).returnsWith(true)
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 3)
                }

                val expectedResponses = List(
                    TestResponse("kept: keep1"),
                    TestResponse("kept: keep2"),
                    TestResponse("kept: keep3")
                )
                assert(call.sendMessage.calls === expectedResponses)
            }

            "processes requests incrementally" in run {
                val requests = List(
                    TestRequest("msg1"),
                    TestRequest("msg2"),
                    TestRequest("msg3")
                )

                val processedMessages = new AtomicInteger(0)

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream =>
                        stream
                            .tap(_ => Sync.defer(processedMessages.incrementAndGet()))
                            .map(req => TestResponse(s"processed: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                (() => call.isReady()).returnsWith(true)
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 3)
                    assert(processedMessages.get === 3)
                }
            }

            "requests more messages after processing chunks" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => stream.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Send multiple messages in sequence
                listener.onMessage(TestRequest("msg1"))
                listener.onMessage(TestRequest("msg2"))

                eventually {
                    // Should request more as chunks are processed
                    assert(call.request.times >= 2)
                }

                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }
            }

            "closes with trailers from handler" in run {
                val requests = List(TestRequest("test"))
                val responseTrailers = Metadata()
                responseTrailers.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream =>
                        for
                            _ <- Emit.value(responseTrailers)
                        yield stream.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, responseTrailers))
            }

            "handles empty request stream" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => stream.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Close without sending any messages
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 0)
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }

            "handles empty response stream" in run {
                val requests = List(TestRequest("test"))

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => stream.filter(_ => false).map(req => TestResponse("never"))

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 0)
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }
        }

        "errors" - {
            "handles abort failure correctly" in run {
                val status = Status.INVALID_ARGUMENT.withDescription("Bad request")

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => Abort.fail(status.asException())

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(TestRequest("test"))
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }

            "handles panic correctly" in run {
                val cause = Exception("Something went wrong")

                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => Abort.panic(cause)

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(TestRequest("test"))
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                val status = Status.UNKNOWN.withCause(cause)
                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }

            "handles error during stream processing" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream =>
                        stream.map(req =>
                            if req.message == "error" then
                                throw Exception("Processing error")
                            else
                                TestResponse(s"echo: ${req.message}")
                        )

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(TestRequest("ok"))
                listener.onMessage(TestRequest("error"))
                listener.onHalfClose()

                eventually {
                    assert(call.close.times === 1)
                }

                val (status, _) = call.close.calls.head
                assert(status.getCode === Status.Code.UNKNOWN)
            }
        }

        "cancellation" - {
            "interrupts stream processing" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => stream.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)
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

                listener.onMessage(TestRequest("test"))
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
                val handler: GrpcHandler[Stream[TestRequest, Grpc], Stream[TestResponse, Grpc]] =
                    stream => stream.map(req => TestResponse(s"echo: ${req.message}"))

                val callHandler = BidiStreamingServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Call onComplete - should not throw
                listener.onComplete()

                assert(call.request.times === 2) // Initial 1 + buffer fill
            }
        }
    }

end BidiStreamingServerCallHandlerTest
