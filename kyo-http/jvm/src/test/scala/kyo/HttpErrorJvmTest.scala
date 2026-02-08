package kyo

import java.net.ConnectException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Tests requiring JVM-only types (ConnectException, SSL exceptions). */
class HttpErrorJvmTest extends Test:

    "HttpError.fromThrowable" - {

        "classifies ConnectException as ConnectionFailed" in {
            val cause  = new ConnectException("Connection refused")
            val result = HttpError.fromThrowable(cause, "localhost", 8080)
            result match
                case e: HttpError.ConnectionFailed =>
                    assert(e.host == "localhost")
                    assert(e.port == 8080)
                    assert(e.cause eq cause)
                case other => fail(s"Expected ConnectionFailed but got $other")
            end match
        }

        "classifies SSLException as SslError" in {
            val cause  = new SSLException("SSL handshake failed")
            val result = HttpError.fromThrowable(cause, "localhost", 443)
            result match
                case e: HttpError.SslError =>
                    assert(e.message.contains("SSL handshake failed"))
                    assert(e.cause eq cause)
                case other => fail(s"Expected SslError but got $other")
            end match
        }

        "classifies SSLHandshakeException as SslError" in {
            val cause  = new SSLHandshakeException("certificate validation failed")
            val result = HttpError.fromThrowable(cause, "api.example.com", 443)
            result match
                case e: HttpError.SslError =>
                    assert(e.message.contains("certificate validation failed"))
                case other => fail(s"Expected SslError but got $other")
            end match
        }

        "handles null message in ConnectException" in {
            val cause  = new ConnectException(null)
            val result = HttpError.fromThrowable(cause, "host", 80)
            result match
                case e: HttpError.ConnectionFailed =>
                    assert(e.host == "host")
                    assert(e.port == 80)
                    assert(e.cause eq cause)
                case other => fail(s"Expected ConnectionFailed but got $other")
            end match
        }
    }

    "HttpError case classes" - {

        "ConnectionFailed message includes host and port" in {
            val error = HttpError.ConnectionFailed("example.com", 9090, new ConnectException("refused"))
            assert(error.getMessage.contains("example.com"))
            assert(error.getMessage.contains("9090"))
        }
    }

end HttpErrorJvmTest
