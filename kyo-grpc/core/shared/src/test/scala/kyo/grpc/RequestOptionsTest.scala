package kyo.grpc

import io.grpc.Metadata
import kyo.*
import org.scalactic.TripleEquals.*

class RequestOptionsTest extends Test:

    "constructor" - {
        "creates instance with default empty values" in run {
            val options = RequestOptions()

            assert(options.headers === Maybe.empty)
            assert(options.messageCompression === Maybe.empty)
            assert(options.responseCapacity === Maybe.empty)
            succeed
        }

        "creates instance with specified values" in run {
            val headers  = new Metadata()
            val capacity = 16

            val options = RequestOptions(
                headers = Maybe.Present(headers),
                messageCompression = Maybe.Present(true),
                responseCapacity = Maybe.Present(capacity)
            )

            assert(options.headers === Maybe.Present(headers))
            assert(options.messageCompression === Maybe.Present(true))
            assert(options.responseCapacity === Maybe.Present(capacity))
            succeed
        }
    }

    "responseCapacityOrDefault" - {
        "returns specified capacity when present" in run {
            val capacity = 42
            val options  = RequestOptions(responseCapacity = Maybe.Present(capacity))

            assert(options.responseCapacityOrDefault === capacity)
            succeed
        }

        "returns default capacity when absent" in run {
            val options = RequestOptions()

            assert(options.responseCapacityOrDefault === RequestOptions.DefaultResponseCapacity)
            succeed
        }
    }

    "combine" - {
        "merges two empty options" in run {
            val options1 = RequestOptions()
            val options2 = RequestOptions()

            options1.combine(options2).map: result =>
                assert(result.headers === Maybe.empty)
                assert(result.messageCompression === Maybe.empty)
                assert(result.responseCapacity === Maybe.empty)
                succeed
        }

        "prefers second options messageCompression when both present" in run {
            val options1 = RequestOptions(messageCompression = Maybe.Present(true))
            val options2 = RequestOptions(messageCompression = Maybe.Present(false))

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(false))
                succeed
        }

        "keeps first options messageCompression when second is absent" in run {
            val options1 = RequestOptions(messageCompression = Maybe.Present(true))
            val options2 = RequestOptions()

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(true))
                succeed
        }

        "uses second options messageCompression when first is absent" in run {
            val options1 = RequestOptions()
            val options2 = RequestOptions(messageCompression = Maybe.Present(false))

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(false))
                succeed
        }

        "prefers second options responseCapacity when both present" in run {
            val options1 = RequestOptions(responseCapacity = Maybe.Present(10))
            val options2 = RequestOptions(responseCapacity = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.responseCapacity === Maybe.Present(20))
                succeed
        }

        "keeps first options responseCapacity when second is absent" in run {
            val options1 = RequestOptions(responseCapacity = Maybe.Present(10))
            val options2 = RequestOptions()

            options1.combine(options2).map: result =>
                assert(result.responseCapacity === Maybe.Present(10))
                succeed
        }

        "uses second options responseCapacity when first is absent" in run {
            val options1 = RequestOptions()
            val options2 = RequestOptions(responseCapacity = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.responseCapacity === Maybe.Present(20))
                succeed
        }

        "merges headers from both options" in run {
            val metadata1 = new Metadata()
            val key1      = Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)
            metadata1.put(key1, "value1")

            val metadata2 = new Metadata()
            val key2      = Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)
            metadata2.put(key2, "value2")

            val options1 = RequestOptions(headers = Maybe.Present(metadata1))
            val options2 = RequestOptions(headers = Maybe.Present(metadata2))

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

            val options1 = RequestOptions(headers = Maybe.Present(metadata))
            val options2 = RequestOptions()

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

            val options1 = RequestOptions()
            val options2 = RequestOptions(headers = Maybe.Present(metadata))

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

            val options1 = RequestOptions(
                headers = Maybe.Present(metadata1),
                messageCompression = Maybe.Present(true),
                responseCapacity = Maybe.Present(10)
            )

            val options2 = RequestOptions(
                headers = Maybe.Present(metadata2),
                messageCompression = Maybe.Present(false),
                responseCapacity = Maybe.Present(20)
            )

            options1.combine(options2).map: result =>
                assert(result.messageCompression === Maybe.Present(false))
                assert(result.responseCapacity === Maybe.Present(20))
                result.headers match
                    case Maybe.Present(merged) =>
                        assert(merged.get(key1) === "value1")
                        assert(merged.get(key2) === "value2")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected merged headers")
        }
    }

    "run" - {
        "extracts single emitted option" in run {
            val options = RequestOptions(messageCompression = Maybe.Present(true))

            val computation = Emit.value(options)

            RequestOptions.run(computation).map: (result, _) =>
                assert(result.messageCompression === Maybe.Present(true))
                succeed
        }

        "combines multiple emitted options" in run {
            val options1 = RequestOptions(messageCompression = Maybe.Present(true))
            val options2 = RequestOptions(responseCapacity = Maybe.Present(20))

            val computation = for
                _ <- Emit.value(options1)
                _ <- Emit.value(options2)
            yield "result"

            RequestOptions.run(computation).map: (result, value) =>
                assert(result.messageCompression === Maybe.Present(true))
                assert(result.responseCapacity === Maybe.Present(20))
                assert(value === "result")
                succeed
        }

        "handles computation with no emissions" in run {
            val computation = "result"

            RequestOptions.run(computation).map: (result, value) =>
                assert(result === RequestOptions())
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

            val options1 = RequestOptions(headers = Maybe.Present(metadata1))
            val options2 = RequestOptions(headers = Maybe.Present(metadata2))

            val computation = for
                _ <- Emit.value(options1)
                _ <- Emit.value(options2)
            yield ()

            RequestOptions.run(computation).map: (result, _) =>
                result.headers match
                    case Maybe.Present(merged) =>
                        assert(merged.get(key1) === "value1")
                        assert(merged.get(key2) === "value2")
                        succeed
                    case Maybe.Absent =>
                        fail("Expected merged headers")
        }
    }

    "constants" - {
        "DefaultRequestBuffer is 8" in {
            assert(RequestOptions.DefaultRequestBuffer === 8)
            succeed
        }

        "DefaultResponseCapacity is 8" in {
            assert(RequestOptions.DefaultResponseCapacity === 8)
            succeed
        }
    }

end RequestOptionsTest
