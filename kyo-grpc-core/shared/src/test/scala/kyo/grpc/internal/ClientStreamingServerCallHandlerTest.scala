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
import org.scalatest.time.Seconds
import org.scalatest.time.Span

class ClientStreamingServerCallHandlerTest extends Test with Stubs with Eventually:

    case class TestRequest(message: String)
    case class TestResponse(result: String)

    implicit override def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = scaled(Span(5, Seconds)))

    "ClientStreamingServerCallHandler" - {

        "startup" - {
            "requests one message from client initially" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    requests => requests.run.map(_ => TestResponse("response"))

                val callHandler = ClientStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Requests 1 initially, then (bufferSize - 1) = 7 more to fill the buffer
                assert(call.request.calls === List(1, 7))
            }

            "set options and sends headers" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    requests => requests.run.map(_ => TestResponse("response"))

                val requestHeaders = Metadata()

                val responseHeaders = Metadata()
                responseHeaders.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")

                val responseOptions = ResponseOptions(
                    headers = SafeMetadata.fromJava(responseHeaders),
                    messageCompression = Maybe.Present(true),
                    compression = Maybe.Present("gzip"),
                    onReadyThreshold = Maybe.Present(16),
                    requestBuffer = Maybe.Present(4)
                )

                val init: GrpcHandlerInit[Stream[TestRequest, Grpc], TestResponse] =
                    for
                        actualRequestHeaders <- Env.get[SafeMetadata]
                        _                    <- Emit.value(responseOptions)
                    yield
                        assert(actualRequestHeaders === SafeMetadata.fromJava(requestHeaders))
                        handler

                val callHandler = ClientStreamingServerCallHandler(init)

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
                assert(call.sendHeaders.calls.map(_.toString) === List(responseOptions.headers.toJava.toString))
            }

            "requests additional messages based on buffer size" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    requests => requests.run.map(_ => TestResponse("response"))

                val requestHeaders = Metadata()

                val responseOptions = ResponseOptions(
                    requestBuffer = Maybe.Present(5)
                )

                val init: GrpcHandlerInit[Stream[TestRequest, Grpc], TestResponse] =
                    for
                        _ <- Emit.value(responseOptions)
                    yield handler

                val callHandler = ClientStreamingServerCallHandler(init)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Should request 1 initially, then 4 more to fill buffer
                assert(call.request.calls === List(1, 4))
            }
        }

        "success" - {
            "receives multiple messages and sends single response" in run {
                import org.scalactic.TraversableEqualityConstraints.*

                val requests = List(
                    TestRequest("msg1"),
                    TestRequest("msg2"),
                    TestRequest("msg3")
                )

                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream =>
                        stream.run.map(chunk =>
                            TestResponse(s"received ${chunk.size} messages")
                        )

                val callHandler = ClientStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Send multiple messages
                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 1)
                    assert(call.close.times === 1)
                }

                assert(call.sendMessage.calls.head.result === "received 3 messages")
                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }

            "processes stream incrementally" in run {
                val requests = List(
                    TestRequest("msg1"),
                    TestRequest("msg2"),
                    TestRequest("msg3")
                )

                val processedMessages = new AtomicInteger(0)

                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream =>
                        stream
                            .tap(_ => Sync.defer(processedMessages.incrementAndGet()))
                            .run
                            .map(_ => TestResponse(s"processed ${processedMessages.get} messages"))

                val callHandler = ClientStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                requests.foreach(listener.onMessage)
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 1)
                    assert(processedMessages.get === 3)
                }
            }

            "requests more messages after processing chunks" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => stream.run.map(_ => TestResponse("done"))

                val callHandler = ClientStreamingServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Send multiple messages in chunks
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
                val requests         = List(TestRequest("test"))
                val responseTrailers = Metadata()
                responseTrailers.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")

                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream =>
                        for
                            _     <- Emit.value(SafeMetadata.fromJava(responseTrailers))
                            chunk <- stream.run
                        yield TestResponse("response")

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
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
        }

        "errors" - {
            "handles abort failure correctly" in run {
                val status = Status.INVALID_ARGUMENT.withDescription("Bad request")

                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => Abort.fail(status.asException())

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
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

                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => Abort.panic(cause)

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
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
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream =>
                        stream
                            .map(req =>
                                if req.message == "error" then
                                    throw Exception("Processing error")
                                else
                                    req
                            )
                            .run
                            .map(_ => TestResponse("response"))

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
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
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => stream.run.map(_ => TestResponse("response"))

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.close.returnsWith(())

                val interrupted = new JAtomicBoolean(false)
                call.sendMessage.returnsWith {
                    try
                        Thread.sleep(patienceConfig.timeout.toMillis + 5000)
                    catch
                        case e: InterruptedException =>
                            interrupted.set(true)
                            throw e
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

        "lifecycle" - {
            "onComplete does nothing" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => stream.run.map(_ => TestResponse("response"))

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Call onComplete - should not throw
                listener.onComplete()

                assert(call.request.times === 2) // Initial 1 + buffer fill
            }

            "onReady does nothing" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => stream.run.map(_ => TestResponse("response"))

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                (() => call.isReady()).returnsWith(true)

                val listener = callHandler.startCall(call, requestHeaders)

                // Call onReady - should not throw
                listener.onReady()

                assert(call.request.times === 2) // Initial 1 + buffer fill
            }

            "handles empty stream" in run {
                val handler: GrpcHandler[Stream[TestRequest, Grpc], TestResponse] =
                    stream => stream.run.map(_ => TestResponse("no messages"))

                val callHandler    = ClientStreamingServerCallHandler(handler)
                val call           = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.sendHeaders.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Close without sending any messages
                listener.onHalfClose()

                eventually {
                    assert(call.sendMessage.times === 1)
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }
        }
    }

end ClientStreamingServerCallHandlerTest
