package kyo.internal

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class RawHeaderParserTest extends AnyFreeSpec with NonImplicitAssertions:

    "RawHeaderParser" - {

        "parses simple headers" in {
            val raw     = "Content-Type: text/html\r\nContent-Length: 42\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Content-Type" -> "text/html", "Content-Length" -> "42"))
        }

        "skips status lines" in {
            val raw     = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Content-Type" -> "text/html"))
        }

        "handles empty input" in {
            val headers = RawHeaderParser.parseHeaders("")
            assert(headers.isEmpty)
        }

        "handles input with only status line" in {
            val headers = RawHeaderParser.parseHeaders("HTTP/1.1 200 OK\r\n")
            assert(headers.isEmpty)
        }

        "trims whitespace from names and values" in {
            val raw     = "  Content-Type  :  text/html  \r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Content-Type" -> "text/html"))
        }

        "handles header values containing colons" in {
            val raw     = "Location: http://example.com:8080/path\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Location" -> "http://example.com:8080/path"))
        }

        "skips lines without colon" in {
            val raw     = "InvalidLine\r\nContent-Type: text/html\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Content-Type" -> "text/html"))
        }

        "skips empty lines between headers" in {
            val raw     = "Content-Type: text/html\r\n\r\nX-Custom: value\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Content-Type" -> "text/html", "X-Custom" -> "value"))
        }

        "parses multiple headers" in {
            val raw = "Content-Type: application/json\r\n" +
                "Content-Length: 100\r\n" +
                "X-Request-Id: abc-123\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Set-Cookie: session=xyz\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 5)
            assert(headers(0) == ("Content-Type"   -> "application/json"))
            assert(headers(1) == ("Content-Length" -> "100"))
            assert(headers(2) == ("X-Request-Id"   -> "abc-123"))
            assert(headers(3) == ("Cache-Control"  -> "no-cache"))
            assert(headers(4) == ("Set-Cookie"     -> "session=xyz"))
        }

        "handles header with empty value" in {
            val raw     = "X-Empty:\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("X-Empty" -> ""))
        }

        "handles mixed status lines and headers" in {
            // Curl may deliver multiple status lines for redirects
            val raw = "HTTP/1.1 301 Moved\r\n" +
                "Location: /new\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Location" -> "/new", "Content-Type" -> "text/html"))
        }

        "handles duplicate header names" in {
            val raw     = "Set-Cookie: a=1\r\nSet-Cookie: b=2\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Set-Cookie" -> "a=1", "Set-Cookie" -> "b=2"))
        }

        "handles header value with leading/trailing spaces" in {
            val raw     = "Authorization:   Bearer token123   \r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Authorization" -> "Bearer token123"))
        }

        "handles no trailing CRLF" in {
            val raw     = "Content-Type: text/plain"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("Content-Type" -> "text/plain"))
        }

        "skips line starting with colon" in {
            // colon at index 0 → colonIdx is 0, not > 0
            val raw     = ": no-name\r\nX-Valid: yes\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers == Seq("X-Valid" -> "yes"))
        }
    }

end RawHeaderParserTest
