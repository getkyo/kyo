package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.Status
import kyo.*
import kyo.grpc.Equalities.given
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory

class ResponseOptionsTest extends Test with AsyncMockFactory:

    "constructor" - {
        "creates instance with default empty values" in run {
            val options = ResponseOptions()

            assert(options.headers === Maybe.empty)
            assert(options.messageCompression === Maybe.empty)
            assert(options.compression === Maybe.empty)
            assert(options.onReadyThreshold === Maybe.empty)
            assert(options.requestBuffer === Maybe.empty)
            succeed
        }

        "creates instance with specified values" in run {
            val headers  = new Metadata()
            val buffer   = 16
            val threshold = 32

            val options = ResponseOptions(
                headers = Maybe.Present(headers),
                messageCompression = Maybe.Present(true),
                compression = Maybe.Present("gzip"),
                onReadyThreshold = Maybe.Present(threshold),
                requestBuffer = Maybe.Present(buffer)
            )

            assert(options.headers === Maybe.Present(headers))
            assert(options.messageCompression === Maybe.Present(true))
            assert(options.compression === Maybe.Present("gzip"))
            assert(options.onReadyThreshold === Maybe.Present(threshold))
            assert(options.requestBuffer === Maybe.Present(buffer))
            succeed
        }
    }

    "requestBufferOrDefault" - {
        "returns specified buffer when present" in run {
            val buffer  = 42
            val options = ResponseOptions(requestBuffer = Maybe.Present(buffer))

            assert(options.requestBufferOrDefault === buffer)
            succeed
        }

        "returns default buffer when absent" in run {
            val options = ResponseOptions()

            assert(options.requestBufferOrDefault === ResponseOptions.DefaultRequestBuffer)
            succeed
        }
    }

    "combine" - {
        "merges two empty options" in run {
            val options1 = ResponseOptions()
            val options2 = ResponseOptions()

            options1.combine(options2).map: result =>
                assert(result.headers === Maybe.empty)
                assert(result.messageCompression === Maybe.empty)
                assert(result.compression === Maybe.empty)
                assert(result.onReadyThreshold === Maybe.empty)
                assert(result.requestBuffer === Maybe.empty)
                succeed
        }

        "prefers second options messageCompression when both present" in run {
            val options1 = ResponseOptions(messageCompression = Maybe.Present(true))
            val options2 = ResponseOptions(messageCompression = Maybe.Present(false))

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(false))
                succeed
        }

        "keeps first options messageCompression when second is absent" in run {
            val options1 = ResponseOptions(messageCompression = Maybe.Present(true))
            val options2 = ResponseOptions()

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(true))
                succeed
        }

        "uses second options messageCompression when first is absent" in run {
            val options1 = ResponseOptions()
            val options2 = ResponseOptions(messageCompression = Maybe.Present(false))

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(false))
                succeed
        }

        "prefers second options compression when both present" in run {
            val options1 = ResponseOptions(compression = Maybe.Present("gzip"))
            val options2 = ResponseOptions(compression = Maybe.Present("snappy"))

            options1.combine(options2).map: result =>
                assert(result.compression === Maybe.Present("snappy"))
                succeed
        }

        "keeps first options compression when second is absent" in run {
            val options1 = ResponseOptions(compression = Maybe.Present("gzip"))
            val options2 = ResponseOptions()

            options1.combine(options2).map: result =>
                assert(result.compression === Maybe.Present("gzip"))
                succeed
        }

        "uses second options compression when first is absent" in run {
            val options1 = ResponseOptions()
            val options2 = ResponseOptions(compression = Maybe.Present("snappy"))

            options1.combine(options2).map: result =>
                assert(result.compression === Maybe.Present("snappy"))
                succeed
        }

        "prefers second options onReadyThreshold when both present" in run {
            val options1 = ResponseOptions(onReadyThreshold = Maybe.Present(10))
            val options2 = ResponseOptions(onReadyThreshold = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.onReadyThreshold === Maybe.Present(20))
                succeed
        }

        "keeps first options onReadyThreshold when second is absent" in run {
            val options1 = ResponseOptions(onReadyThreshold = Maybe.Present(10))
            val options2 = ResponseOptions()

            options1.combine(options2).map: result =>
                assert(result.onReadyThreshold === Maybe.Present(10))
                succeed
        }

        "uses second options onReadyThreshold when first is absent" in run {
            val options1 = ResponseOptions()
            val options2 = ResponseOptions(onReadyThreshold = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.onReadyThreshold === Maybe.Present(20))
                succeed
        }

        "prefers second options requestBuffer when both present" in run {
            val options1 = ResponseOptions(requestBuffer = Maybe.Present(10))
            val options2 = ResponseOptions(requestBuffer = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.requestBuffer === Maybe.Present(20))
                succeed
        }

        "keeps first options requestBuffer when second is absent" in run {
            val options1 = ResponseOptions(requestBuffer = Maybe.Present(10))
            val options2 = ResponseOptions()

            options1.combine(options2).map: result =>
                assert(result.requestBuffer === Maybe.Present(10))
                succeed
        }

        "uses second options requestBuffer when first is absent" in run {
            val options1 = ResponseOptions()
            val options2 = ResponseOptions(requestBuffer = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.requestBuffer === Maybe.Present(20))
                succeed
        }

        "merges headers from both options" in run {
            val metadata1 = new Metadata()
            val key1      = Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)
            metadata1.put(key1, "value1")

            val metadata2 = new Metadata()
            val key2      = Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)
            metadata2.put(key2, "value2")

            val options1 = ResponseOptions(headers = Maybe.Present(metadata1))
            val options2 = ResponseOptions(headers = Maybe.Present(metadata2))

            options1.combine(options2).map: result =>
                result.headers match
                    case Maybe.Present(merged) =>
                        assert(merged.get(key1) === "value1")
                        assert(merged.get(key2) === "value2")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected merged headers")
        }

        "keeps first options headers when second is absent" in run {
            val metadata = new Metadata()
            val key      = Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)
            metadata.put(key, "value1")

            val options1 = ResponseOptions(headers = Maybe.Present(metadata))
            val options2 = ResponseOptions()

            options1.combine(options2).map: result =>
                result.headers match
                    case Maybe.Present(headers) =>
                        assert(headers.get(key) === "value1")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected headers to be present")
        }

        "uses second options headers when first is absent" in run {
            val metadata = new Metadata()
            val key      = Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)
            metadata.put(key, "value2")

            val options1 = ResponseOptions()
            val options2 = ResponseOptions(headers = Maybe.Present(metadata))

            options1.combine(options2).map: result =>
                result.headers match
                    case Maybe.Present(headers) =>
                        assert(headers.get(key) === "value2")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected headers to be present")
        }

        "combines all fields together" in run {
            val metadata1 = new Metadata()
            val key1      = Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)
            metadata1.put(key1, "value1")

            val metadata2 = new Metadata()
            val key2      = Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)
            metadata2.put(key2, "value2")

            val options1 = ResponseOptions(
                headers = Maybe.Present(metadata1),
                messageCompression = Maybe.Present(true),
                compression = Maybe.Present("gzip"),
                onReadyThreshold = Maybe.Present(10),
                requestBuffer = Maybe.Present(5)
            )

            val options2 = ResponseOptions(
                headers = Maybe.Present(metadata2),
                messageCompression = Maybe.Present(false),
                compression = Maybe.Present("snappy"),
                onReadyThreshold = Maybe.Present(20),
                requestBuffer = Maybe.Present(15)
            )

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(false))
                assert(result.compression === Maybe.Present("snappy"))
                assert(result.onReadyThreshold === Maybe.Present(20))
                assert(result.requestBuffer === Maybe.Present(15))
                result.headers match
                    case Maybe.Present(merged) =>
                        assert(merged.get(key1) === "value1")
                        assert(merged.get(key2) === "value2")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected merged headers")
        }
    }

    "sendHeaders" - {
        "sends all options to server call when all fields present" in run {
            val metadata = new Metadata()
            val key      = Metadata.Key.of("test-key", Metadata.ASCII_STRING_MARSHALLER)
            metadata.put(key, "test-value")

            val options = ResponseOptions(
                headers = Maybe.Present(metadata),
                messageCompression = Maybe.Present(true),
                compression = Maybe.Present("gzip"),
                onReadyThreshold = Maybe.Present(42)
            )

            val call = mock[ServerCall[String, String]]

            call.setMessageCompression
                .expects(true)
                .once()

            call.setCompression
                .expects("gzip")
                .once()

            call.setOnReadyThreshold
                .expects(42)
                .once()

            call.sendHeaders
                .expects(argEquals(metadata))
                .once()

            options.sendHeaders(call).map(_ => succeed)
        }

        "sends only headers when other fields absent" in run {
            val metadata = new Metadata()
            val options  = ResponseOptions(headers = Maybe.Present(metadata))

            val call = mock[ServerCall[String, String]]

            call.sendHeaders
                .expects(argEquals(metadata))
                .once()

            options.sendHeaders(call).map(_ => succeed)
        }

        "sends messageCompression when present" in run {
            val options = ResponseOptions(messageCompression = Maybe.Present(false))

            val call = mock[ServerCall[String, String]]

            call.setMessageCompression
                .expects(false)
                .once()

            call.sendHeaders
                .expects(*)
                .once()

            options.sendHeaders(call).map(_ => succeed)
        }

        "sends compression when present" in run {
            val options = ResponseOptions(compression = Maybe.Present("snappy"))

            val call = mock[ServerCall[String, String]]

            call.setCompression
                .expects("snappy")
                .once()

            call.sendHeaders
                .expects(*)
                .once()

            options.sendHeaders(call).map(_ => succeed)
        }

        "sends onReadyThreshold when present" in run {
            val options = ResponseOptions(onReadyThreshold = Maybe.Present(100))

            val call = mock[ServerCall[String, String]]

            call.setOnReadyThreshold
                .expects(100)
                .once()

            call.sendHeaders
                .expects(*)
                .once()

            options.sendHeaders(call).map(_ => succeed)
        }

        "sends headers when all fields absent" in run {
            val options = ResponseOptions()
            val call    = mock[ServerCall[String, String]]

            call.sendHeaders
                .expects(argEquals(Metadata()))
                .once()

            options.sendHeaders(call).map(_ => succeed)
        }
    }

    "run" - {
        "extracts single emitted option" in run {
            val options = ResponseOptions(messageCompression = Maybe.Present(true))

            val computation = Emit.value(options)

            ResponseOptions.run(computation).map: (result, _) =>
                assert(result.messageCompression === Maybe.Present(true))
                succeed
        }

        "combines multiple emitted options" in run {
            val options1 = ResponseOptions(messageCompression = Maybe.Present(true))
            val options2 = ResponseOptions(requestBuffer = Maybe.Present(20))

            val computation = for
                _ <- Emit.value(options1)
                _ <- Emit.value(options2)
            yield "result"

            ResponseOptions.run(computation).map: (result, value) =>
                assert(result.messageCompression === Maybe.Present(true))
                assert(result.requestBuffer === Maybe.Present(20))
                assert(value === "result")
                succeed
        }

        "handles computation with no emissions" in run {
            val computation = "result"

            ResponseOptions.run(computation).map: (result, value) =>
                assert(result === ResponseOptions())
                assert(value === "result")
                succeed
        }

        "merges headers from multiple emissions" in run {
            val metadata1 = new Metadata()
            val key1      = Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)
            metadata1.put(key1, "value1")

            val metadata2 = new Metadata()
            val key2      = Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)
            metadata2.put(key2, "value2")

            val options1 = ResponseOptions(headers = Maybe.Present(metadata1))
            val options2 = ResponseOptions(headers = Maybe.Present(metadata2))

            val computation = for
                _ <- Emit.value(options1)
                _ <- Emit.value(options2)
            yield ()

            ResponseOptions.run(computation).map: (result, _) =>
                result.headers match
                    case Maybe.Present(merged) =>
                        assert(merged.get(key1) === "value1")
                        assert(merged.get(key2) === "value2")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected merged headers")
        }
    }

    "runSend" - {
        "extracts options, sends headers, and returns result" in run {
            val metadata = new Metadata()
            val options  = ResponseOptions(
                headers = Maybe.Present(metadata),
                messageCompression = Maybe.Present(true)
            )

            val computation = Emit.value(options).andThen("test-result")

            val call = mock[ServerCall[String, String]]

            call.setMessageCompression
                .expects(true)
                .once()

            call.sendHeaders
                .expects(*)
                .once()

            ResponseOptions.runSend(call)(computation).map: result =>
                assert(result === "test-result")
                succeed
        }

        "handles multiple emissions and sends combined headers" in run {
            val metadata1 = new Metadata()
            val key1      = Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)
            metadata1.put(key1, "value1")

            val metadata2 = new Metadata()
            val key2      = Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)
            metadata2.put(key2, "value2")

            val options1 = ResponseOptions(headers = Maybe.Present(metadata1))
            val options2 = ResponseOptions(
                headers = Maybe.Present(metadata2),
                compression = Maybe.Present("gzip")
            )

            val computation = for
                _ <- Emit.value(options1)
                _ <- Emit.value(options2)
            yield "final-result"

            val call = mock[ServerCall[String, String]]

            call.setCompression
                .expects("gzip")
                .once()

            call.sendHeaders
                .expects(*)
                .once()

            ResponseOptions.runSend(call)(computation).map: result =>
                assert(result === "final-result")
                succeed
        }

        "handles computation with no emissions" in run {
            val computation = "no-options"
            val call        = mock[ServerCall[String, String]]

            call.sendHeaders
                .expects(*)
                .once()

            ResponseOptions.runSend(call)(computation).map: result =>
                assert(result === "no-options")
                succeed
        }
    }

    "constants" - {
        "DefaultRequestBuffer is 8" in {
            assert(ResponseOptions.DefaultRequestBuffer === 8)
            succeed
        }
    }

end ResponseOptionsTest
