package kyo

import java.util.concurrent.TimeoutException

class HttpErrorTest extends Test:

    // Tests requiring java.net.ConnectException or javax.net.ssl types
    // are in jvm/.../HttpErrorJvmTest.scala

    "HttpError.fromThrowable" - {

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

        "Timeout message includes details" in {
            val error = HttpError.Timeout("after 30s")
            assert(error.getMessage.contains("after 30s"))
        }

        "SslError message includes details" in {
            val cause = new Exception("bad cert")
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
            val error = HttpError.StatusError(HttpStatus(404), "not found")
            assert(error.getMessage.contains("404"))
        }

        "ParseError message includes details" in {
            val cause = new Exception("invalid json")
            val error = HttpError.ParseError("unexpected token", cause)
            assert(error.getMessage.contains("unexpected token"))
        }

        "RetriesExhausted message includes attempts and status" in {
            val error = HttpError.RetriesExhausted(3, HttpStatus(503), "unavailable")
            assert(error.getMessage.contains("3"))
            assert(error.getMessage.contains("503"))
        }
    }

end HttpErrorTest
