package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.grpc.*
import kyo.grpc.Equalities.given
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.time.{Seconds, Span}

class UnaryClientCallListenerTest extends Test with Eventually:

    case class TestResponse(result: String)

    override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = scaled(Span(5, Seconds)))

    "UnaryClientCallListener" - {

        "onHeaders" - {
            "completes headers promise" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    headers = Metadata()
                    key = Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER)
                    _ = headers.put(key, "test-value")
                    _ = listener.onHeaders(headers)

                    result <- headersPromise.get
                yield
                    assert(result eq headers)
                    assert(result.get(key) === "test-value")
            }
        }

        "onMessage" - {
            "completes response promise with first message" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    response = TestResponse("success")
                    _ = listener.onMessage(response)

                    result <- Abort.run[StatusException](responsePromise.get)
                yield
                    assert(result.isSuccess)
                    assert(result.getOrThrow === response)
            }

            "throws exception when server sends more than one response" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    response1 = TestResponse("first")
                    response2 = TestResponse("second")
                    _ = listener.onMessage(response1)

                    exception <- Abort.run[Throwable]:
                        Abort.catching[StatusException]:
                            IO.Unsafe(listener.onMessage(response2))
                yield
                    exception.fold(
                        _ => fail("Expected exception but got success"),
                        ex =>
                            ex match
                                case se: StatusException =>
                                    assert(se.getStatus.getCode === Status.Code.INVALID_ARGUMENT)
                                    assert(se.getStatus.getDescription === "Server sent more than one response.")
                                case _ =>
                                    fail(s"Expected StatusException but got ${ex.getClass}")
                        ,
                        _ => fail("Expected exception but got panic")
                    )
            }
        }

        "onClose" - {
            "completes response promise with error when no message received" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    status = Status.CANCELLED.withDescription("Client cancelled")
                    trailers = Metadata()
                    _ = listener.onClose(status, trailers)

                    completionResult <- completionPromise.get
                    responseResult <- Abort.run[StatusException](responsePromise.get)
                yield
                    assert(completionResult.status === status)
                    assert(completionResult.trailers eq trailers)
                    assert(responseResult.isFailure)
                    val failure = responseResult.failure.get
                    assert(failure.getStatus === status)
            }

            "completes completion promise after message received" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    response = TestResponse("success")
                    _ = listener.onMessage(response)

                    status = Status.OK
                    trailers = Metadata()
                    key = Metadata.Key.of("trailer-key", Metadata.ASCII_STRING_MARSHALLER)
                    _ = trailers.put(key, "trailer-value")
                    _ = listener.onClose(status, trailers)

                    completionResult <- completionPromise.get
                yield
                    assert(completionResult.status === status)
                    assert(completionResult.trailers.get(key) === "trailer-value")
            }
        }

        "onReady" - {
            "sets ready signal to true" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    _ = listener.onReady()

                    ready <- readySignal.get
                yield
                    assert(ready === true)
            }

            "can be called multiple times" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    _ = listener.onReady()
                    ready1 <- readySignal.get
                    _ = listener.onReady()
                    ready2 <- readySignal.get
                yield
                    assert(ready1 === true)
                    assert(ready2 === true)
            }
        }

        "full lifecycle" - {
            "processes successful unary call" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    // Simulate call lifecycle
                    headers = Metadata()
                    _ = headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "application/grpc")
                    _ = listener.onHeaders(headers)

                    _ = listener.onReady()

                    response = TestResponse("final result")
                    _ = listener.onMessage(response)

                    trailers = Metadata()
                    _ = listener.onClose(Status.OK, trailers)

                    headersResult <- headersPromise.get
                    responseResult <- Abort.run[StatusException](responsePromise.get)
                    completionResult <- completionPromise.get
                    readyResult <- readySignal.get
                yield
                    assert(headersResult eq headers)
                    assert(responseResult === Result.succeed(response))
                    assert(completionResult.status === Status.OK)
                    assert(readyResult === true)
            }

            "processes failed unary call" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responsePromise <- Promise.init[TestResponse, Abort[StatusException]]
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)

                    // Simulate call lifecycle with error
                    headers = Metadata()
                    _ = listener.onHeaders(headers)

                    errorStatus = Status.UNAVAILABLE.withDescription("Service unavailable")
                    trailers = Metadata()
                    _ = listener.onClose(errorStatus, trailers)

                    headersResult <- headersPromise.get
                    responseResult <- Abort.run[StatusException](responsePromise.get)
                    completionResult <- completionPromise.get
                yield
                    assert(headersResult eq headers)
                    assert(responseResult.isFailure)
                    val failure = responseResult.failure.get
                    assert(failure.getStatus === errorStatus)
                    assert(completionResult.status === errorStatus)
            }
        }
    }

end UnaryClientCallListenerTest
