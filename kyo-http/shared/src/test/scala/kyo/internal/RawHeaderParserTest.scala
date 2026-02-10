package kyo.internal

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class RawHeaderParserTest extends AnyFreeSpec with NonImplicitAssertions:

    "RawHeaderParser" - {

        "parses simple headers" in {
            val raw     = "Content-Type: text/html\r\nContent-Length: 42\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 2)
            assert(headers.get("Content-Type") == Present("text/html"))
            assert(headers.get("Content-Length") == Present("42"))
        }

        "skips status lines" in {
            val raw     = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("Content-Type") == Present("text/html"))
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
            assert(headers.size == 1)
            assert(headers.get("Content-Type") == Present("text/html"))
        }

        "handles header values containing colons" in {
            val raw     = "Location: http://example.com:8080/path\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("Location") == Present("http://example.com:8080/path"))
        }

        "skips lines without colon" in {
            val raw     = "InvalidLine\r\nContent-Type: text/html\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("Content-Type") == Present("text/html"))
        }

        "skips empty lines between headers" in {
            val raw     = "Content-Type: text/html\r\n\r\nX-Custom: value\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 2)
            assert(headers.get("Content-Type") == Present("text/html"))
            assert(headers.get("X-Custom") == Present("value"))
        }

        "parses multiple headers" in {
            val raw = "Content-Type: application/json\r\n" +
                "Content-Length: 100\r\n" +
                "X-Request-Id: abc-123\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Set-Cookie: session=xyz\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 5)
            assert(headers.get("Content-Type") == Present("application/json"))
            assert(headers.get("Content-Length") == Present("100"))
            assert(headers.get("X-Request-Id") == Present("abc-123"))
            assert(headers.get("Cache-Control") == Present("no-cache"))
            assert(headers.get("Set-Cookie") == Present("session=xyz"))
        }

        "handles header with empty value" in {
            val raw     = "X-Empty:\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("X-Empty") == Present(""))
        }

        "handles mixed status lines and headers" in {
            // Curl may deliver multiple status lines for redirects
            val raw = "HTTP/1.1 301 Moved\r\n" +
                "Location: /new\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 2)
            assert(headers.get("Location") == Present("/new"))
            assert(headers.get("Content-Type") == Present("text/html"))
        }

        "handles duplicate header names" in {
            val raw     = "Set-Cookie: a=1\r\nSet-Cookie: b=2\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 2)
            assert(headers.get("Set-Cookie") == Present("a=1"))
            assert(headers.exists((k, v) => k == "Set-Cookie" && v == "b=2"))
        }

        "handles header value with leading/trailing spaces" in {
            val raw     = "Authorization:   Bearer token123   \r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("Authorization") == Present("Bearer token123"))
        }

        "handles no trailing CRLF" in {
            val raw     = "Content-Type: text/plain"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("Content-Type") == Present("text/plain"))
        }

        "skips line starting with colon" in {
            // colon at index 0 → colonIdx is 0, not > 0
            val raw     = ": no-name\r\nX-Valid: yes\r\n"
            val headers = RawHeaderParser.parseHeaders(raw)
            assert(headers.size == 1)
            assert(headers.get("X-Valid") == Present("yes"))
        }
    }

end RawHeaderParserTest
