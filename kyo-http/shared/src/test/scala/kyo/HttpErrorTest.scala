package kyo

import java.net.ConnectException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class HttpErrorTest extends Test:

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

        "classifies TimeoutException as Timeout" in {
            val cause  = new TimeoutException("request timed out")
            val result = HttpError.fromThrowable(cause, "localhost", 8080)
            result match
                case e: HttpError.Timeout =>
                    assert(e.message.contains("request timed out"))
                case other => fail(s"Expected Timeout but got $other")
            end match
        }

        "classifies kyo.Timeout as Timeout" in {
            val cause  = new kyo.Timeout(Present(5.seconds))
            val result = HttpError.fromThrowable(cause, "localhost", 8080)
            result match
                case e: HttpError.Timeout =>
                    assert(e.message.contains("timed out"))
                case other => fail(s"Expected Timeout but got $other")
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

        "classifies unknown exception as InvalidResponse" in {
            val cause  = new RuntimeException("something unexpected")
            val result = HttpError.fromThrowable(cause, "localhost", 8080)
            result match
                case e: HttpError.InvalidResponse =>
                    assert(e.message.contains("something unexpected"))
                case other => fail(s"Expected InvalidResponse but got $other")
            end match
        }

        "handles null message in exception" in {
            val cause  = new RuntimeException(null: String)
            val result = HttpError.fromThrowable(cause, "localhost", 8080)
            result match
                case e: HttpError.InvalidResponse =>
                    assert(e.message.contains("RuntimeException"))
                case other => fail(s"Expected InvalidResponse but got $other")
            end match
        }

        "handles null message in ConnectException" in {
            val cause  = new ConnectException(null)
            val result = HttpError.fromThrowable(cause, "host", 80)
            result match
                case _: HttpError.ConnectionFailed => succeed
                case other                         => fail(s"Expected ConnectionFailed but got $other")
        }

        "handles null message in TimeoutException" in {
            val cause  = new TimeoutException(null)
            val result = HttpError.fromThrowable(cause, "host", 80)
            result match
                case e: HttpError.Timeout =>
                    assert(e.message.contains("TimeoutException"))
                case other => fail(s"Expected Timeout but got $other")
            end match
        }
    }

    "HttpError case classes" - {

        "ConnectionFailed message includes host and port" in {
            val error = HttpError.ConnectionFailed("example.com", 9090, new ConnectException("refused"))
            assert(error.getMessage.contains("example.com"))
            assert(error.getMessage.contains("9090"))
        }

        "Timeout message includes details" in {
            val error = HttpError.Timeout("after 30s")
            assert(error.getMessage.contains("after 30s"))
        }

        "SslError message includes details" in {
            val cause = new SSLException("bad cert")
            val error = HttpError.SslError("bad cert", cause)
            assert(error.getMessage.contains("SSL/TLS error"))
            assert(error.getMessage.contains("bad cert"))
        }

        "TooManyRedirects message includes count" in {
            val error = HttpError.TooManyRedirects(10)
            assert(error.getMessage.contains("10"))
        }

        "InvalidResponse message includes details" in {
            val error = HttpError.InvalidResponse("malformed header")
            assert(error.getMessage.contains("malformed header"))
        }

        "StatusError message includes status code" in {
            val error = HttpError.StatusError(HttpResponse.Status(404), "not found")
            assert(error.getMessage.contains("404"))
        }

        "ParseError message includes details" in {
            val cause = new Exception("invalid json")
            val error = HttpError.ParseError("unexpected token", cause)
            assert(error.getMessage.contains("unexpected token"))
        }

        "RetriesExhausted message includes attempts and status" in {
            val error = HttpError.RetriesExhausted(3, HttpResponse.Status(503), "unavailable")
            assert(error.getMessage.contains("3"))
            assert(error.getMessage.contains("503"))
        }
    }

end HttpErrorTest
