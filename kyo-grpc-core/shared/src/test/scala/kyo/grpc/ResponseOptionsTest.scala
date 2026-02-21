package kyo.grpc

import io.grpc.ServerCall
import io.grpc.Status
import kyo.*
import org.scalactic.TripleEquals.*

class ResponseOptionsTest extends Test:

    "constructor" - {
        "creates instance with default empty values" in run {
            val options = ResponseOptions()

            assert(options.headers === SafeMetadata.empty)
            assert(options.messageCompression === Maybe.empty)
            assert(options.compression === Maybe.empty)
            assert(options.onReadyThreshold === Maybe.empty)
            assert(options.requestBuffer === Maybe.empty)
            succeed
        }

        "creates instance with specified values" in run {
            val headers = SafeMetadata.empty.add("key", "value")

            val options = ResponseOptions(
                headers = headers,
                messageCompression = Maybe.Present(true),
                compression = Maybe.Present("gzip"),
                onReadyThreshold = Maybe.Present(32),
                requestBuffer = Maybe.Present(16)
            )

            assert(options.headers === headers)
            assert(options.messageCompression === Maybe.Present(true))
            assert(options.compression === Maybe.Present("gzip"))
            assert(options.onReadyThreshold === Maybe.Present(32))
            assert(options.requestBuffer === Maybe.Present(16))
            succeed
        }
    }

    "requestBufferOrDefault" - {
        "returns specified buffer when present" in run {
            val options = ResponseOptions(requestBuffer = Maybe.Present(42))
            assert(options.requestBufferOrDefault === 42)
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
                assert(result.headers === SafeMetadata.empty)
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

        "prefers second options compression when both present" in run {
            val options1 = ResponseOptions(compression = Maybe.Present("gzip"))
            val options2 = ResponseOptions(compression = Maybe.Present("snappy"))

            options1.combine(options2).map: result =>
                assert(result.compression === Maybe.Present("snappy"))
                succeed
        }

        "merges headers from both options" in run {
            val h1 = SafeMetadata.empty.add("key1", "value1")
            val h2 = SafeMetadata.empty.add("key2", "value2")

            val options1 = ResponseOptions(headers = h1)
            val options2 = ResponseOptions(headers = h2)

            options1.combine(options2).map: result =>
                assert(result.headers.getStrings("key1") === Seq("value1"))
                assert(result.headers.getStrings("key2") === Seq("value2"))
                succeed
        }
    }

    "run" - {
        "extracts single emitted option" in run {
            val options     = ResponseOptions(messageCompression = Maybe.Present(true))
            val computation = Emit.value(options)

            ResponseOptions.run(computation).map: (result, _) =>
                assert(result.messageCompression === Maybe.Present(true))
                succeed
        }

        "handles computation with no emissions" in run {
            val computation = "result"

            ResponseOptions.run(computation).map: (result, value) =>
                assert(result === ResponseOptions())
                assert(value === "result")
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
