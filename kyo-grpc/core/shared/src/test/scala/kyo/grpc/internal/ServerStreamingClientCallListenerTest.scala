package kyo.grpc.internal

import io.grpc.{Channel as _, *}
import kyo.*
import kyo.grpc.*
import kyo.grpc.Equalities.given
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.time.{Seconds, Span}

class ServerStreamingClientCallListenerTest extends Test with Eventually:

    case class TestResponse(result: String)

    override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = scaled(Span(5, Seconds)))

    "ServerStreamingClientCallListener" - {

        "onHeaders" - {
            "completes headers promise" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

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
            "offers messages to channel" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    response1 = TestResponse("first")
                    response2 = TestResponse("second")
                    response3 = TestResponse("third")
                    _ = listener.onMessage(response1)
                    _ = listener.onMessage(response2)
                    _ = listener.onMessage(response3)

                    result1 <- responseChannel.take
                    result2 <- responseChannel.take
                    result3 <- responseChannel.take
                yield
                    assert(result1 === response1)
                    assert(result2 === response2)
                    assert(result3 === response3)
            }

            "handles multiple messages sequentially" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](16)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    responses = (1 to 10).map(i => TestResponse(s"message-$i"))
                    _ = responses.foreach(listener.onMessage)

                    collected <- Kyo.foreach(responses)(_ => responseChannel.take)
                yield
                    assert(collected === responses.toList)
            }
        }

        "onClose" - {
            "closes channel and completes completion promise" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    status = Status.OK
                    trailers = Metadata()
                    key = Metadata.Key.of("trailer-key", Metadata.ASCII_STRING_MARSHALLER)
                    _ = trailers.put(key, "trailer-value")
                    _ = listener.onClose(status, trailers)

                    completionResult <- completionPromise.get
                    channelClosed <- responseChannel.closed
                yield
                    assert(completionResult.status === status)
                    assert(completionResult.trailers.get(key) === "trailer-value")
                    assert(channelClosed === true)
            }

            "closes channel with error status" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    errorStatus = Status.UNAVAILABLE.withDescription("Service unavailable")
                    trailers = Metadata()
                    _ = listener.onClose(errorStatus, trailers)

                    completionResult <- completionPromise.get
                    channelClosed <- responseChannel.closed
                yield
                    assert(completionResult.status === errorStatus)
                    assert(completionResult.status.getDescription === "Service unavailable")
                    assert(channelClosed === true)
            }

            "waits for channel to be empty before closing" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    // Add messages before closing
                    response1 = TestResponse("first")
                    response2 = TestResponse("second")
                    _ = listener.onMessage(response1)
                    _ = listener.onMessage(response2)

                    status = Status.OK
                    trailers = Metadata()
                    _ = listener.onClose(status, trailers)

                    // Should still be able to read messages
                    result1 <- responseChannel.take
                    result2 <- responseChannel.take

                    completionResult <- completionPromise.get
                    channelClosed <- responseChannel.closed
                yield
                    assert(result1 === response1)
                    assert(result2 === response2)
                    assert(completionResult.status === status)
                    assert(channelClosed === true)
            }
        }

        "onReady" - {
            "sets ready signal to true" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    _ = listener.onReady()

                    ready <- readySignal.get
                yield
                    assert(ready === true)
            }

            "can be called multiple times" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

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
            "processes successful server streaming call" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    // Simulate call lifecycle
                    headers = Metadata()
                    _ = headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "application/grpc")
                    _ = listener.onHeaders(headers)

                    _ = listener.onReady()

                    responses = List(
                        TestResponse("response1"),
                        TestResponse("response2"),
                        TestResponse("response3")
                    )
                    _ = responses.foreach(listener.onMessage)

                    trailers = Metadata()
                    _ = listener.onClose(Status.OK, trailers)

                    headersResult <- headersPromise.get
                    collectedResponses <- Kyo.foreach(responses)(_ => responseChannel.take)
                    completionResult <- completionPromise.get
                    readyResult <- readySignal.get
                    channelClosed <- responseChannel.closed
                yield
                    assert(headersResult eq headers)
                    assert(collectedResponses === responses)
                    assert(completionResult.status === Status.OK)
                    assert(readyResult === true)
                    assert(channelClosed === true)
            }

            "processes failed server streaming call" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    // Simulate call lifecycle with error
                    headers = Metadata()
                    _ = listener.onHeaders(headers)

                    // Send some responses before error
                    response1 = TestResponse("response1")
                    _ = listener.onMessage(response1)

                    errorStatus = Status.INTERNAL.withDescription("Server error")
                    trailers = Metadata()
                    _ = listener.onClose(errorStatus, trailers)

                    headersResult <- headersPromise.get
                    receivedResponse <- responseChannel.take
                    completionResult <- completionPromise.get
                    channelClosed <- responseChannel.closed
                yield
                    assert(headersResult eq headers)
                    assert(receivedResponse === response1)
                    assert(completionResult.status === errorStatus)
                    assert(completionResult.status.getDescription === "Server error")
                    assert(channelClosed === true)
            }

            "handles empty stream" in run {
                for
                    headersPromise <- Promise.init[Metadata, Any]
                    responseChannel <- Channel.init[TestResponse](8)
                    completionPromise <- Promise.init[CallClosed, Any]
                    readySignal <- Signal.initRef[Boolean](false)
                    listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                    // Simulate call lifecycle with no messages
                    headers = Metadata()
                    _ = listener.onHeaders(headers)

                    trailers = Metadata()
                    _ = listener.onClose(Status.OK, trailers)

                    headersResult <- headersPromise.get
                    completionResult <- completionPromise.get
                    channelClosed <- responseChannel.closed
                yield
                    assert(headersResult eq headers)
                    assert(completionResult.status === Status.OK)
                    assert(channelClosed === true)
            }
        }
    }

end ServerStreamingClientCallListenerTest
