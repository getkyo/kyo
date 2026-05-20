package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.util.*

class Http1ResponseParserTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    /** Helper: create a channel, offer response bytes, create parser, capture ParsedResponse. */
    private def parseResponse(
        rawResponse: String,
        maxHeaderSize: Int = 65536
    ): (ParsedResponse, Span[Byte]) =
        val channel = Channel.Unsafe.init[Span[Byte]](16)
        val bytes   = rawResponse.getBytes(StandardCharsets.US_ASCII)
        discard(channel.offer(Span.fromUnsafe(bytes)))

        var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
        var body: Span[Byte]       = Span.empty[Byte]
        val parser = new Http1ResponseParser(
            channel,
            maxHeaderSize,
            onResponseParsed = (resp, b) =>
                result = resp
                body = b
        )
        parser.start()
        (result, body)
    end parseResponse

    /** Helper: parse from multiple chunks offered before start. */
    private def parseResponseFromChunks(
        chunks: Seq[Array[Byte]],
        maxHeaderSize: Int = 65536
    ): (ParsedResponse, Span[Byte]) =
        val channel = Channel.Unsafe.init[Span[Byte]](64)
        chunks.foreach(chunk => discard(channel.offer(Span.fromUnsafe(chunk))))

        var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
        var body: Span[Byte]       = Span.empty[Byte]
        val parser = new Http1ResponseParser(
            channel,
            maxHeaderSize,
            onResponseParsed = (resp, b) =>
                result = resp
                body = b
        )
        parser.start()
        (result, body)
    end parseResponseFromChunks

    "Http1ResponseParser" - {

        // Test 1
        "parse valid 200 response with Content-Length and keep-alive" in {
            val (resp, body) = parseResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbody"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.statusCode == 200)
            assert(resp.contentLength == 4)
            assert(!resp.isChunked)
            assert(resp.isKeepAlive)
            assert(body.size == 4)
            assert(new String(body.toArray, StandardCharsets.US_ASCII) == "body")
            succeed
        }

        // Test 2
        "parse response with chunked transfer encoding" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.isChunked)
            assert(resp.contentLength == -1)
            assert(resp.statusCode == 200)
            succeed
        }

        // Test 3
        "parse response with explicit Connection: close" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(!resp.isKeepAlive)
            succeed
        }

        // Test 4
        "HTTP/1.0 response without Connection header defaults to non-keep-alive" in {
            val (resp, _) = parseResponse(
                "HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            // HTTP/1.0 default: keep-alive is false unless explicitly requested
            assert(!resp.isKeepAlive, "HTTP/1.0 without Connection header should not be keep-alive")
            succeed
        }

        // Test 5
        "reject response with invalid status code 999" in {
            val channel = Channel.Unsafe.init[Span[Byte]](16)
            val raw     = "HTTP/1.1 999 Invalid\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(raw.getBytes(StandardCharsets.US_ASCII))))

            var closedCalled           = false
            var parsed: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => parsed = resp,
                onClosed = () => closedCalled = true
            )
            parser.start()

            // 999 is outside 100-599 range, should trigger onClosed
            assert(closedCalled, "Parser should call onClosed for status code 999")
            assert(parsed == null, "Parser should not produce a response for status code 999")
            succeed
        }

        // Test 6
        "reject response with garbage status line" in {
            val channel = Channel.Unsafe.init[Span[Byte]](16)
            // "GARBAGE" has no valid status code — parseStatusCode returns 0
            val raw = "GARBAGE /bad HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(raw.getBytes(StandardCharsets.US_ASCII))))

            var closedCalled           = false
            var parsed: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => parsed = resp,
                onClosed = () => closedCalled = true
            )
            parser.start()

            // Status code 0 is outside 100-599, should trigger onClosed
            assert(closedCalled, "Parser should call onClosed for garbage status line")
            assert(parsed == null, "Parser should not produce a response for garbage status line")
            succeed
        }

        // Test 7
        "parse status line HTTP/1.1 404 Not Found correctly" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.statusCode == 404)
            succeed
        }

        // Test 8
        "parse HTTP/1.0 200 with no reason phrase" in {
            val (resp, _) = parseResponse(
                "HTTP/1.0 200\r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.statusCode == 200)
            succeed
        }

        // Test 9
        "parse Content-Length 12345 correctly" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 12345\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.contentLength == 12345)
            succeed
        }

        // Test 10
        "reject Content-Length with non-digit characters" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 1a2b\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            // parseContentLength returns -1 on non-digit characters
            assert(resp.contentLength == -1, "Content-Length with non-digits should be -1")
            succeed
        }

        // Test 11
        "parse case-insensitive content-length header" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\ncontent-length: 42\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.contentLength == 42, "Case-insensitive content-length should be extracted")
            succeed
        }

        // Test 12
        "parse multiple headers with various spacing" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "X-Request-Id: abc123\r\n" +
                    "Cache-Control:  no-cache\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            val headers = resp.headers
            assert(headers.get("Content-Type") == Present("text/plain"))
            assert(headers.get("X-Request-Id") == Present("abc123"))
            // Leading space after colon should be skipped; value should be "no-cache"
            assert(headers.get("Cache-Control") == Present("no-cache"))
            assert(headers.get("Content-Length") == Present("0"))
            succeed
        }

        // Test 13
        "handle header with empty value" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nX-Empty: \r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            val headers = resp.headers
            // After skipping the space, value is empty
            assert(headers.get("X-Empty") == Present(""), "Header with only space value should be empty string")
            succeed
        }

        // Test 14
        "accumulate partial data across multiple calls" in {
            val fullResponse = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            val bytes        = fullResponse.getBytes(StandardCharsets.US_ASCII)
            val chunkSize    = 8
            val chunks = (0 until bytes.length by chunkSize).map { start =>
                val end = math.min(start + chunkSize, bytes.length)
                bytes.slice(start, end)
            }.toSeq

            val (resp, _) = parseResponseFromChunks(chunks)
            assert(resp != null, "Response should have been parsed from incremental chunks")
            assert(resp.statusCode == 200)
            assert(resp.contentLength == 0)
            succeed
        }

        // Test 15
        "handle EOF during header parse — channel closes gracefully" in {
            val channel      = Channel.Unsafe.init[Span[Byte]](16)
            var closedCalled = false
            val parser = new Http1ResponseParser(
                channel,
                onClosed = () => closedCalled = true
            )
            // Close channel BEFORE start — parser gets Closed immediately
            discard(channel.close())
            parser.start()

            assert(closedCalled, "onClosed should be called when channel is closed during parse")
            succeed
        }

        // Test 16
        "reset and reuse parser for keep-alive" in {
            val channel = Channel.Unsafe.init[Span[Byte]](64)

            val resp1Bytes = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX-Seq: first\r\n\r\nhello"
            val resp2Bytes = "HTTP/1.1 201 Created\r\nContent-Length: 6\r\nX-Seq: second\r\n\r\nworld!"

            discard(channel.offer(Span.fromUnsafe((resp1Bytes + resp2Bytes).getBytes(StandardCharsets.US_ASCII))))

            val responses = new scala.collection.mutable.ArrayBuffer[(ParsedResponse, Span[Byte])]()
            lazy val parser: Http1ResponseParser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, body) =>
                    responses += ((resp, body))
                    if responses.size < 2 then
                        parser.reset()
                        parser.start()
            )
            parser.start()

            assert(responses.size == 2, s"Expected 2 responses but got ${responses.size}")
            val (r1, b1) = responses(0)
            assert(r1.statusCode == 200)
            assert(r1.contentLength == 5)
            assert(r1.headers.get("X-Seq") == Present("first"))
            assert(new String(b1.toArray, StandardCharsets.US_ASCII) == "hello")

            val (r2, b2) = responses(1)
            assert(r2.statusCode == 201)
            assert(r2.contentLength == 6)
            assert(r2.headers.get("X-Seq") == Present("second"))
            assert(new String(b2.toArray, StandardCharsets.US_ASCII) == "world!")
            succeed
        }

        // Test 17
        "detect CRLF_CRLF header terminator correctly" in {
            // Ensure parser correctly identifies \r\n\r\n at various offsets
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nHost: example.com\r\nAccept: */*\r\n\r\n"
            )
            assert(resp != null, "Response should be parsed when \\r\\n\\r\\n terminator is present")
            assert(resp.statusCode == 200)
            assert(resp.headers.get("Host") == Present("example.com"))
            assert(resp.headers.get("Accept") == Present("*/*"))
            succeed
        }

        // Test 18
        "extract leftover body bytes after headers" in {
            val (resp, body) = parseResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 13\r\n\r\nHello, World!"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.contentLength == 13)
            assert(body.size == 13)
            assert(new String(body.toArray, StandardCharsets.US_ASCII) == "Hello, World!")
            succeed
        }

        // Test 19
        "handle response with body smaller than Content-Length in initial chunk" in {
            // Content-Length says 100, but only 5 bytes arrive in the header chunk
            // The parser should clamp body to what's available (5 bytes), not 100
            val (resp, body) = parseResponse(
                "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nhello"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.contentLength == 100)
            // body extracted is min(remaining, contentLength) = min(5, 100) = 5
            assert(body.size == 5)
            assert(new String(body.toArray, StandardCharsets.US_ASCII) == "hello")
            succeed
        }

        // Test 20
        "pack response headers into format compatible with HttpHeaders.fromPacked" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "X-Correlation-Id: corr-789\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Content-Length: 2\r\n" +
                    "\r\n{}"
            )
            assert(resp != null, "Response should have been parsed")
            val headers = resp.headers
            assert(headers.get("Content-Type") == Present("application/json"))
            assert(headers.get("X-Correlation-Id") == Present("corr-789"))
            assert(headers.get("Cache-Control") == Present("no-store"))
            assert(headers.get("Content-Length") == Present("2"))
            // Non-existent header should be Absent
            assert(headers.get("X-Not-Present") == Absent)
            succeed
        }

        // Test 21
        "handle header offset array reallocation with many headers" in {
            val sb = new StringBuilder
            sb.append("HTTP/1.1 200 OK\r\n")
            var i = 0
            // 32+ headers forces hdrOffsets to reallocate (initial size is 128 int slots = 32 headers)
            while i < 35 do
                sb.append(s"X-Header-$i: value-$i\r\n")
                i += 1
            sb.append("\r\n")
            val (resp, _) = parseResponse(sb.toString)
            assert(resp != null, "Response with 35 headers should parse (triggers reallocation)")
            assert(resp.statusCode == 200)
            val headers = resp.headers
            // Spot check first and last
            assert(headers.get("X-Header-0") == Present("value-0"))
            assert(headers.get("X-Header-34") == Present("value-34"))
            succeed
        }

        // Test 22
        "parse response with no headers (only status line)" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.statusCode == 200)
            assert(resp.contentLength == -1)
            assert(!resp.isChunked)
            succeed
        }

        // Test 23
        "ignore header line with no colon — malformed header line skipped" in {
            val (resp, _) = parseResponse(
                "HTTP/1.1 200 OK\r\nMalformedHeaderNoColon\r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed despite malformed header")
            assert(resp.statusCode == 200)
            // Malformed line has no colon, so it's skipped; Content-Length still parsed
            assert(resp.contentLength == 0)
            // Malformed line should not appear in headers (no colon = not stored)
            assert(resp.headers.get("MalformedHeaderNoColon") == Absent)
            succeed
        }

        // Test 24
        "handle response exceeding maxHeaderSize — onClosed called" in {
            val smallMax = 64
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val longResponse =
                "HTTP/1.1 200 OK\r\nX-Big: " + "x" * 200 + "\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(longResponse.getBytes(StandardCharsets.US_ASCII))))

            var closedCalled           = false
            var parsed: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                maxHeaderSize = smallMax,
                onResponseParsed = (resp, _) => parsed = resp,
                onClosed = () => closedCalled = true
            )
            parser.start()

            assert(closedCalled, "Parser should call onClosed when headers exceed maxHeaderSize")
            assert(parsed == null, "Parser should not produce a response when headers exceed maxHeaderSize")
            succeed
        }

        // Test 25
        "parse Content-Length: 0 correctly — contentLength is 0, not -1" in {
            val (resp, body) = parseResponse(
                "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
            )
            assert(resp != null, "Response should have been parsed")
            assert(resp.statusCode == 204)
            assert(resp.contentLength == 0, "Content-Length: 0 should be 0, not -1")
            assert(body.size == 0)
            succeed
        }
    }

end Http1ResponseParserTest
