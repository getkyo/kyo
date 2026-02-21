package kyo.grpc

import kyo.*
import org.scalactic.TripleEquals.*

class RequestOptionsTest extends Test:

    "constructor" - {
        "creates instance with default empty values" in run {
            val options = RequestOptions()

            assert(options.headers === SafeMetadata.empty)
            assert(options.messageCompression === Maybe.empty)
            assert(options.responseCapacity === Maybe.empty)
            succeed
        }

        "creates instance with specified values" in run {
            val headers = SafeMetadata.empty.add("key", "value")

            val options = RequestOptions(
                headers = headers,
                messageCompression = Maybe.Present(true),
                responseCapacity = Maybe.Present(16)
            )

            assert(options.headers === headers)
            assert(options.messageCompression === Maybe.Present(true))
            assert(options.responseCapacity === Maybe.Present(16))
            succeed
        }
    }

    "responseCapacityOrDefault" - {
        "returns specified capacity when present" in run {
            val options = RequestOptions(responseCapacity = Maybe.Present(42))
            assert(options.responseCapacityOrDefault === 42)
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
                assert(result.headers === SafeMetadata.empty)
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

        "prefers second options responseCapacity when both present" in run {
            val options1 = RequestOptions(responseCapacity = Maybe.Present(10))
            val options2 = RequestOptions(responseCapacity = Maybe.Present(20))

            options1.combine(options2).map: result =>
                assert(result.responseCapacity === Maybe.Present(20))
                succeed
        }

        "merges headers from both options" in run {
            val h1 = SafeMetadata.empty.add("key1", "value1")
            val h2 = SafeMetadata.empty.add("key2", "value2")

            val options1 = RequestOptions(headers = h1)
            val options2 = RequestOptions(headers = h2)

            options1.combine(options2).map: result =>
                assert(result.headers.getStrings("key1") === Seq("value1"))
                assert(result.headers.getStrings("key2") === Seq("value2"))
                succeed
        }
    }

    "run" - {
        "extracts single emitted option" in run {
            val options     = RequestOptions(messageCompression = Maybe.Present(true))
            val computation = Emit.value(options)

            RequestOptions.run(computation).map: (result, _) =>
                assert(result.messageCompression === Maybe.Present(true))
                succeed
        }

        "handles computation with no emissions" in run {
            val computation = "result"

            RequestOptions.run(computation).map: (result, value) =>
                assert(result === RequestOptions())
                assert(value === "result")
                succeed
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
