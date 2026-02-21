package kyo.grpc.internal

import io.grpc.{Channel as _, *}
import kyo.*
import kyo.grpc.*
import org.scalactic.TripleEquals.*

class ServerStreamingClientCallListenerTest extends Test:

    case class TestResponse(result: String)

    "ServerStreamingClientCallListener" - {

        "onHeaders completes headers promise with SafeMetadata" in run {
            for
                headersPromise    <- Promise.init[SafeMetadata, Any]
                responseChannel   <- Channel.init[TestResponse](8)
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                headers = new Metadata()
                key     = Metadata.Key.of("test-header", Metadata.ASCII_STRING_MARSHALLER)
                _       = headers.put(key, "test-value")
                _       = listener.onHeaders(headers)

                result <- headersPromise.get
            yield
                assert(result.getStrings("test-header") === Seq("test-value"))
        }

        "onMessage offers messages to channel" in run {
            for
                headersPromise    <- Promise.init[SafeMetadata, Any]
                responseChannel   <- Channel.init[TestResponse](8)
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                response1 = TestResponse("first")
                response2 = TestResponse("second")
                _         = listener.onMessage(response1)
                _         = listener.onMessage(response2)

                result1 <- responseChannel.take
                result2 <- responseChannel.take
            yield
                assert(result1 === response1)
                assert(result2 === response2)
        }

        "onClose closes channel and completes completion promise" in run {
            for
                headersPromise    <- Promise.init[SafeMetadata, Any]
                responseChannel   <- Channel.init[TestResponse](8)
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                status   = Status.OK
                trailers = new Metadata()
                _        = listener.onClose(status, trailers)

                completionResult <- completionPromise.get
                channelClosed    <- responseChannel.closed
            yield
                assert(completionResult.status === status)
                assert(channelClosed === true)
        }

        "onReady sets ready signal to true" in run {
            for
                headersPromise    <- Promise.init[SafeMetadata, Any]
                responseChannel   <- Channel.init[TestResponse](8)
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = ServerStreamingClientCallListener(headersPromise, responseChannel, completionPromise, readySignal)

                _ = listener.onReady()

                ready <- readySignal.get
            yield
                assert(ready === true)
        }
    }

end ServerStreamingClientCallListenerTest
