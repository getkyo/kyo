package kyo

import kyo.internal.CurlEventLoop
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

/** Tests for native-specific curl backend code.
  *
  * Note: These tests may not link until zio-schema Scala Native support is resolved (SecureRandom dependency). They are included to
  * document expected behavior and will run automatically once the linking issue is fixed.
  */
class CurlBackendTest extends AnyFreeSpec with NonImplicitAssertions:

    "CurlEventLoop.curlResultToError" - {

        "maps code 6 (DNS resolution failed) to ConnectionFailed" in {
            val error = CurlEventLoop.curlResultToError(6, "example.com", 443)
            assert(error.isInstanceOf[HttpError.ConnectionFailed])
            val cf = error.asInstanceOf[HttpError.ConnectionFailed]
            assert(cf.host == "example.com")
            assert(cf.port == 443)
        }

        "maps code 7 (connection refused) to ConnectionFailed" in {
            val error = CurlEventLoop.curlResultToError(7, "localhost", 8080)
            assert(error.isInstanceOf[HttpError.ConnectionFailed])
        }

        "maps code 28 (timeout) to Timeout" in {
            val error = CurlEventLoop.curlResultToError(28, "slow.com", 80)
            assert(error.isInstanceOf[HttpError.Timeout])
            assert(error.asInstanceOf[HttpError.Timeout].message.contains("curl timeout"))
        }

        "maps SSL error codes to SslError" in {
            val sslCodes = Seq(35, 51, 53, 54, 58, 59, 60)
            sslCodes.foreach { code =>
                val error = CurlEventLoop.curlResultToError(code, "secure.com", 443)
                assert(error.isInstanceOf[HttpError.SslError], s"code $code should map to SslError")
            }
        }

        "maps unknown codes to InvalidResponse" in {
            val error = CurlEventLoop.curlResultToError(99, "example.com", 80)
            assert(error.isInstanceOf[HttpError.InvalidResponse])
            assert(error.asInstanceOf[HttpError.InvalidResponse].message.contains("curl error 99"))
        }
    }

    "CurlBackend" - {

        "server throws UnsupportedOperationException" in {
            assertThrows[UnsupportedOperationException] {
                CurlBackend.server(0, "0.0.0.0", 65536, 128, true, false, 0, null)
            }
        }
    }

    "HttpPlatformBackend" - {

        "client is CurlBackend" in {
            assert(HttpPlatformBackend.client eq CurlBackend)
        }
    }

end CurlBackendTest
