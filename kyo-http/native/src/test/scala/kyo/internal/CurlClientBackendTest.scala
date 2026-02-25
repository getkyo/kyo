package kyo.internal

import kyo.HttpError as Http2Error
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class CurlClientBackendTest extends AnyFreeSpec with NonImplicitAssertions:

    "CurlEventLoop2.curlResultToError" - {

        "maps DNS failure (code 6) to ConnectionError" in {
            val error = CurlEventLoop2.curlResultToError(6, "example.com", 443)
            assert(error.isInstanceOf[Http2Error.ConnectionError])
            assert(error.getMessage.contains("example.com"))
        }

        "maps connection refused (code 7) to ConnectionError" in {
            val error = CurlEventLoop2.curlResultToError(7, "localhost", 8080)
            assert(error.isInstanceOf[Http2Error.ConnectionError])
        }

        "maps timeout (code 28) to TimeoutError" in {
            val error = CurlEventLoop2.curlResultToError(28, "slow.com", 80)
            assert(error.isInstanceOf[Http2Error.TimeoutError])
        }

        "maps SSL error codes to ConnectionError with SSL in message" in {
            Seq(35, 51, 53, 54, 58, 59, 60).foreach { code =>
                val error = CurlEventLoop2.curlResultToError(code, "secure.com", 443)
                assert(error.isInstanceOf[Http2Error.ConnectionError], s"code $code")
                assert(error.getMessage.contains("SSL"), s"code $code message should mention SSL")
            }
        }

        "maps unknown codes to ConnectionError" in {
            val error = CurlEventLoop2.curlResultToError(99, "example.com", 80)
            assert(error.isInstanceOf[Http2Error.ConnectionError])
            assert(error.getMessage.contains("99"))
        }
    }

    "HttpPlatformBackend" - {

        "client is CurlClientBackend" in {
            assert(HttpPlatformBackend.client.isInstanceOf[CurlClientBackend])
        }

        "server is H2oServerBackend" in {
            assert(HttpPlatformBackend.server.isInstanceOf[H2oServerBackend])
        }
    }

end CurlClientBackendTest
