package kyo.grpc.internal

import io.grpc.*

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import kyo.*
import kyo.grpc.*
import kyo.grpc.Equalities.given
import org.scalamock.scalatest.AsyncMockFactory
import org.scalamock.stubs.Stubs
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.time.{Seconds, Span}

class UnaryServerCallHandlerTest extends Test with Stubs with Eventually:

    case class TestRequest(message: String)
    case class TestResponse(result: String)

    override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = scaled(Span(5, Seconds)))

    "UnaryServerCallHandler" - {

        "startup" - {
            "requests one message from client" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => TestResponse("response")

                val callHandler = UnaryServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                assert(call.request.calls === List(1))
            }

            "set options and sends headers" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => TestResponse("response")

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

                val init: GrpcHandlerInit[TestRequest, TestResponse] =
                    for
                        actualRequestHeaders <- Env.get[Metadata]
                        _ <- Emit.value(responseOptions)
                    yield
                        assert(actualRequestHeaders eq requestHeaders)
                        handler

                val callHandler = UnaryServerCallHandler(init)

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
            "sends message and closes" in run {
                import org.scalactic.TraversableEqualityConstraints.*

                val request = TestRequest("test")
                val expectedResponse = TestResponse("response")

                val handler: GrpcHandler[TestRequest, TestResponse] = _ => expectedResponse

                val callHandler = UnaryServerCallHandler(handler)

                val call = stub[ServerCall[TestRequest, TestResponse]]
                call.request.returnsWith(())
                call.sendMessage.returnsWith(())
                call.close.returnsWith(())

                val requestHeaders = Metadata()

                val listener = callHandler.startCall(call, requestHeaders)

                // Simulate receiving a message
                listener.onMessage(request)

                eventually {
                    assert(call.sendMessage.times === 1)
                    assert(call.close.times === 1)
                }

                assert(call.sendMessage.calls === List(expectedResponse))
                call.close.calls must contain theSameElementsInOrderAs List((Status.OK, Metadata()))
            }

            "closes with trailers from handler" in run {
                val request = TestRequest("test")
                val expectedResponse = TestResponse("response")
                val responseTrailers = Metadata()
                responseTrailers.put(Metadata.Key.of("custom-header", Metadata.ASCII_STRING_MARSHALLER), "custom-value")

                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => Emit.value(responseTrailers).map(_ => expectedResponse)

                val callHandler = UnaryServerCallHandler(handler)
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

                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => Abort.fail(status.asException())

                val callHandler = UnaryServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)

                eventually {
                    assert(call.close.times === 1)
                }

                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }

            "handles panic correctly" in run {
                val request = TestRequest("test")
                val cause = Exception("Something went wrong")

                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => Abort.panic(cause)

                val callHandler = UnaryServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())
                call.close.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                listener.onMessage(request)

                eventually {
                    assert(call.close.times === 1)
                }

                val status = Status.UNKNOWN.withCause(cause)
                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }

            "fails when client completes without sending request" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => TestResponse("response")

                val callHandler = UnaryServerCallHandler(handler)
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
            "interrupts when sending message" in run {
                val request = TestRequest("test")

                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => TestResponse("response")

                val callHandler = UnaryServerCallHandler(handler)
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
                listener.onCancel()

                eventually {
                    // This fails because of https://github.com/getkyo/kyo/issues/1431.
                    //assert(interrupted.get === true)
                    assert(call.close.times === 1)
                }

                val status = Status.CANCELLED.withDescription("Call was cancelled.")
                call.close.calls must contain theSameElementsInOrderAs List((status, Metadata()))
            }
        }

        "lifecycle" - {
            "onComplete does nothing" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => TestResponse("response")

                val callHandler = UnaryServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Call onComplete - should not throw
                listener.onComplete()

                assert(call.request.times === 1)
            }

            "onReady does nothing" in run {
                val handler: GrpcHandler[TestRequest, TestResponse] =
                    req => TestResponse("response")

                val callHandler = UnaryServerCallHandler(handler)
                val call = stub[ServerCall[TestRequest, TestResponse]]
                val requestHeaders = Metadata()

                call.request.returnsWith(())

                val listener = callHandler.startCall(call, requestHeaders)

                // Call onReady - should not throw
                listener.onReady()

                assert(call.request.times === 1)
            }
        }
    }

end UnaryServerCallHandlerTest
