package kyo.internal

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class CurlClientBackendTest extends AnyFreeSpec with NonImplicitAssertions:

    "CurlEventLoop.curlResultToError" - {

        "maps DNS failure (code 6) to ConnectionError" in {
            val error = CurlEventLoop.curlResultToError(6, "example.com", 443)
            assert(error.isInstanceOf[HttpConnectException])
            assert(error.getMessage.contains("example.com"))
        }

        "maps connection refused (code 7) to ConnectionError" in {
            val error = CurlEventLoop.curlResultToError(7, "localhost", 8080)
            assert(error.isInstanceOf[HttpConnectException])
        }

        "maps timeout (code 28) to ConnectionError" in {
            val error = CurlEventLoop.curlResultToError(28, "slow.com", 80)
            assert(error.isInstanceOf[HttpConnectException])
            assert(error.getCause.getMessage.contains("timed out"))
        }

        "maps SSL error codes to ConnectionError with SSL in cause" in {
            Seq(35, 51, 53, 54, 58, 59, 60).foreach { code =>
                val error = CurlEventLoop.curlResultToError(code, "secure.com", 443)
                assert(error.isInstanceOf[HttpConnectException], s"code $code")
                assert(error.getCause.getMessage.contains("SSL"), s"code $code cause should mention SSL")
            }
        }

        "maps unknown codes to ConnectionError" in {
            val error = CurlEventLoop.curlResultToError(99, "example.com", 80)
            assert(error.isInstanceOf[HttpConnectException])
            assert(error.getCause.getMessage.contains("99"))
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
