package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.*
import kyo.internal.util.*

class Http1ParserTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    /** Helper: create a channel, offer data, create parser, capture parsed request. */
    private def parseRequest(rawRequest: String, maxHeaderSize: Int = 65536): ParsedRequest =
        val channel = Channel.Unsafe.init[Span[Byte]](16)
        val bytes   = rawRequest.getBytes(StandardCharsets.US_ASCII)
        discard(channel.offer(Span.fromUnsafe(bytes)))

        val builder               = new ParsedRequestBuilder
        var result: ParsedRequest = null.asInstanceOf[ParsedRequest]
        val parser                = new Http1Parser(channel, builder, maxHeaderSize, onRequestParsed = (req, _) => result = req)
        parser.start()
        result
    end parseRequest

    /** Helper: parse from multiple chunks offered before start. */
    private def parseRequestFromChunks(chunks: Seq[Array[Byte]], maxHeaderSize: Int = 65536): ParsedRequest =
        val channel = Channel.Unsafe.init[Span[Byte]](64)
        chunks.foreach(chunk => discard(channel.offer(Span.fromUnsafe(chunk))))

        val builder               = new ParsedRequestBuilder
        var result: ParsedRequest = null.asInstanceOf[ParsedRequest]
        val parser                = new Http1Parser(channel, builder, maxHeaderSize, onRequestParsed = (req, _) => result = req)
        parser.start()
        result
    end parseRequestFromChunks

    "Http1Parser" - {

        "parse simple GET request" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null, "Request should have been parsed")
            assert(req.method == HttpMethod.GET)
            assert(req.pathAsString == "/")
            assert(req.pathSegmentCount == 0)
            assert(!req.hasQuery)
            assert(!req.isChunked)
            assert(req.isKeepAlive)
            assert(req.contentLength == -1)
            assert(req.headerCount == 1)
            assert(req.headerName(0) == "Host")
            assert(req.headerValue(0) == "localhost")
            succeed
        }

        "parse POST with Content-Length" in {
            val req = parseRequest(
                "POST /submit HTTP/1.1\r\nHost: example.com\r\nContent-Length: 13\r\n\r\n"
            )
            assert(req != null, "Request should have been parsed")
            assert(req.method == HttpMethod.POST)
            assert(req.pathAsString == "/submit")
            assert(req.contentLength == 13)
            assert(!req.isChunked)
            assert(req.isKeepAlive)
            succeed
        }

        "parse chunked request" in {
            val req = parseRequest(
                "POST /upload HTTP/1.1\r\nHost: example.com\r\nTransfer-Encoding: chunked\r\n\r\n"
            )
            assert(req != null, "Request should have been parsed")
            assert(req.method == HttpMethod.POST)
            assert(req.isChunked)
            assert(req.contentLength == -1)
            succeed
        }

        "parse keep-alive default for HTTP/1.1" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.isKeepAlive)
            succeed
        }

        "parse Connection: close" in {
            val req = parseRequest(
                "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            )
            assert(req != null)
            assert(!req.isKeepAlive)
            succeed
        }

        "parse path with query" in {
            val req = parseRequest("GET /path?key=val HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathAsString == "/path")
            assert(req.hasQuery)
            req.queryParam("key") match
                case Present(v) => assert(v == "val")
                case Absent     => fail("Expected query param 'key'")
            succeed
        }

        "parse multiple headers" in {
            val req = parseRequest(
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: text/html\r\n" +
                    "Accept-Language: en-US\r\n" +
                    "User-Agent: TestBot/1.0\r\n" +
                    "X-Custom: custom-value\r\n" +
                    "\r\n"
            )
            assert(req != null)
            assert(req.headerCount == 5)
            assert(req.headerName(0) == "Host")
            assert(req.headerValue(0) == "localhost")
            assert(req.headerName(1) == "Accept")
            assert(req.headerValue(1) == "text/html")
            assert(req.headerName(2) == "Accept-Language")
            assert(req.headerValue(2) == "en-US")
            assert(req.headerName(3) == "User-Agent")
            assert(req.headerValue(3) == "TestBot/1.0")
            assert(req.headerName(4) == "X-Custom")
            assert(req.headerValue(4) == "custom-value")
            succeed
        }

        "parse path segments" in {
            val req = parseRequest("GET /api/v1/users HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentCount == 3)
            assert(req.pathSegmentMatches(0, ParsedRequest.Segment("api")))
            assert(req.pathSegmentMatches(1, ParsedRequest.Segment("v1")))
            assert(req.pathSegmentMatches(2, ParsedRequest.Segment("users")))
            assert(req.pathSegmentAsString(0) == "api")
            assert(req.pathSegmentAsString(1) == "v1")
            assert(req.pathSegmentAsString(2) == "users")
            succeed
        }

        "incremental data - small chunks" in {
            // Feed the request in small chunks (10 bytes at a time)
            val fullRequest = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            val bytes       = fullRequest.getBytes(StandardCharsets.US_ASCII)
            val chunkSize   = 10
            val chunks = (0 until bytes.length by chunkSize).map { start =>
                val end = math.min(start + chunkSize, bytes.length)
                bytes.slice(start, end)
            }.toSeq

            val req = parseRequestFromChunks(chunks)
            assert(req != null, "Request should have been parsed from incremental chunks")
            assert(req.method == HttpMethod.GET)
            assert(req.pathAsString == "/hello")
            assert(req.headerCount == 1)
            assert(req.headerName(0) == "Host")
            assert(req.headerValue(0) == "localhost")
            succeed
        }

        "multiple requests on same parser" in {
            val channel = Channel.Unsafe.init[Span[Byte]](64)
            val builder = new ParsedRequestBuilder

            val requests = new scala.collection.mutable.ArrayBuffer[ParsedRequest]()
            // Note: parser is referenced in its own callback (re-entrant).
            // This is correct: onRequestParsed fires inside parse(), which calls
            // onRequestParsed, which calls parser.start() → needMoreBytes() (tail position).
            lazy val parser: Http1Parser = new Http1Parser(
                channel,
                builder,
                onRequestParsed = (req, _) =>
                    requests += req
                    // After handling the first request, reset and start again for the next
                    if requests.size < 2 then
                        parser.reset()
                        parser.start()
            )

            // Offer two requests (pipelining: both in channel before parser starts)
            val req1 = "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
            val req2 = "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe((req1 + req2).getBytes(StandardCharsets.US_ASCII))))

            parser.start()

            assert(requests.size == 2, s"Expected 2 requests but got ${requests.size}")
            assert(requests(0).pathAsString == "/first")
            assert(requests(1).pathAsString == "/second")
            succeed
        }

        "header exceeds max size" in {
            // Create a request with headers larger than max
            val smallMax   = 64
            val longHeader = "GET / HTTP/1.1\r\nHost: " + "x" * 200 + "\r\n\r\n"

            val channel = Channel.Unsafe.init[Span[Byte]](16)
            discard(channel.offer(Span.fromUnsafe(longHeader.getBytes(StandardCharsets.US_ASCII))))

            val builder = new ParsedRequestBuilder

            var closedCalled             = false
            var parsedReq: ParsedRequest = null.asInstanceOf[ParsedRequest]
            val parser = new Http1Parser(
                channel,
                builder,
                smallMax,
                onRequestParsed = (req, _) => parsedReq = req,
                onClosed = () => closedCalled = true
            )

            parser.start()

            // Either the parser rejects it via onClosed, or it handles gracefully
            // The parser buffer is only 64 bytes, but the request is 200+ bytes
            // When data exceeds maxHeaderSize, onClosed should be called
            assert(closedCalled, "Parser should have called onClosed for oversized headers")
            assert(parsedReq == null, "Parser should not have produced a request for oversized headers")
            succeed
        }

        "binary header values preserved" in {
            // Header with special characters (non-alphanumeric but valid HTTP header chars)
            val req = parseRequest(
                "GET / HTTP/1.1\r\nHost: localhost\r\nX-Special: a=b&c=d;e/f\r\n\r\n"
            )
            assert(req != null)
            assert(req.headerCount == 2)
            assert(req.headerName(1) == "X-Special")
            assert(req.headerValue(1) == "a=b&c=d;e/f")
            succeed
        }

        "empty body GET has contentLength -1" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.contentLength == -1)
            succeed
        }

        "case sensitive method - non-standard lowercase" in {
            // HTTP methods are case-sensitive per RFC 9110
            // "get" is not a standard method, so it maps to GET ordinal 0 as fallback
            val req = parseRequest("get / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            // ordinalFromName returns 0 (GET) as default for unknown methods
            assert(req.method == HttpMethod.GET)
            succeed
        }

        "parse PUT method" in {
            val req = parseRequest("PUT /resource HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.PUT)
            assert(req.contentLength == 5)
            succeed
        }

        "parse DELETE method" in {
            val req = parseRequest("DELETE /resource/123 HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.DELETE)
            assert(req.pathSegmentCount == 2)
            assert(req.pathSegmentAsString(0) == "resource")
            assert(req.pathSegmentAsString(1) == "123")
            succeed
        }

        "parse PATCH method" in {
            val req = parseRequest("PATCH /resource HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.PATCH)
            succeed
        }

        "parse HEAD method" in {
            val req = parseRequest("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.HEAD)
            succeed
        }

        "parse OPTIONS method" in {
            val req = parseRequest("OPTIONS * HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.OPTIONS)
            succeed
        }

        "parse Connection: keep-alive explicitly" in {
            val req = parseRequest(
                "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
            )
            assert(req != null)
            assert(req.isKeepAlive)
            succeed
        }

        "parse query with multiple parameters" in {
            val req = parseRequest("GET /search?q=hello&page=2&limit=10 HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.hasQuery)
            req.queryParam("q") match
                case Present(v) => assert(v == "hello")
                case Absent     => fail("Expected query param 'q'")
            req.queryParam("page") match
                case Present(v) => assert(v == "2")
                case Absent     => fail("Expected query param 'page'")
            req.queryParam("limit") match
                case Present(v) => assert(v == "10")
                case Absent     => fail("Expected query param 'limit'")
            succeed
        }

        "parse percent-encoded query parameter" in {
            val req = parseRequest("GET /path?name=hello%20world HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            req.queryParam("name") match
                case Present(v) => assert(v == "hello world")
                case Absent     => fail("Expected query param 'name'")
            succeed
        }

        "parse deep path segments" in {
            val req = parseRequest("GET /a/b/c/d/e/f HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentCount == 6)
            assert(req.pathSegmentAsString(0) == "a")
            assert(req.pathSegmentAsString(1) == "b")
            assert(req.pathSegmentAsString(2) == "c")
            assert(req.pathSegmentAsString(3) == "d")
            assert(req.pathSegmentAsString(4) == "e")
            assert(req.pathSegmentAsString(5) == "f")
            succeed
        }

        "large headers - 50 headers" in {
            val sb = new StringBuilder
            sb.append("GET / HTTP/1.1\r\n")
            var i = 0
            while i < 50 do
                sb.append(s"X-Header-$i: value-$i\r\n")
                i += 1
            sb.append("\r\n")
            val req = parseRequest(sb.toString)
            assert(req != null, "Request with 50 headers should parse")
            assert(req.method == HttpMethod.GET)
            assert(req.headerCount == 50)
            assert(req.headerName(0) == "X-Header-0")
            assert(req.headerValue(0) == "value-0")
            assert(req.headerName(24) == "X-Header-24")
            assert(req.headerValue(24) == "value-24")
            assert(req.headerName(49) == "X-Header-49")
            assert(req.headerValue(49) == "value-49")
            succeed
        }

        "path with encoded characters preserved" in {
            val req = parseRequest("GET /path%20with%20spaces HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            // Raw path is preserved (percent-encoding not decoded at path level)
            assert(req.pathAsString == "/path%20with%20spaces")
            succeed
        }

        "parse request with body leftover in same chunk" in {
            // Headers + body arrive in one chunk; parser delivers the request,
            // leftover body bytes remain in the parser buffer for subsequent reading
            val raw = "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\nHello World"
            val req = parseRequest(raw)
            assert(req != null)
            assert(req.method == HttpMethod.POST)
            assert(req.contentLength == 11)
            assert(req.pathAsString == "/echo")
            succeed
        }

        "parse root path" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathAsString == "/")
            assert(req.pathSegmentCount == 0)
            succeed
        }

        "channel closed triggers onClosed" in {
            val channel = Channel.Unsafe.init[Span[Byte]](16)
            val builder = new ParsedRequestBuilder

            var closedCalled = false
            val parser = new Http1Parser(
                channel,
                builder,
                onClosed = () => closedCalled = true
            )

            // Close the channel before starting the parser
            discard(channel.close())
            parser.start()

            assert(closedCalled, "onClosed should have been called when channel is closed")
            succeed
        }
    }

    "Http1StreamContext" - {

        "respond writes status line with empty headers" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            discard(ctx.respond(HttpStatus.OK, HttpHeaders.empty))

            val result = outbound.poll()
            assert(result.isSuccess)
            val maybe = result.getOrThrow
            maybe match
                case Present(span) =>
                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                    // Date header is always injected (RFC 9110 section 6.6.1)
                    assert(str.startsWith("HTTP/1.1 200 OK\r\nDate: "), s"Expected status line with Date header, got: $str")
                    assert(str.endsWith("\r\n\r\n"), s"Expected CRLFCRLF terminator, got: $str")
                case _ => fail("Expected data in outbound channel")
            end match
            succeed
        }

        "respond writes headers" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            val headers = HttpHeaders.empty.add("Content-Type", "text/plain")
            discard(ctx.respond(HttpStatus.OK, headers))

            val result = outbound.poll()
            assert(result.isSuccess)
            val maybe = result.getOrThrow
            maybe match
                case Present(span) =>
                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                    assert(str.startsWith("HTTP/1.1 200 OK\r\n"), s"Expected status line, got: $str")
                    assert(str.contains("Content-Type: text/plain\r\n"), s"Expected Content-Type header, got: $str")
                    assert(str.endsWith("\r\n\r\n"), s"Expected header terminator, got: $str")
                case _ => fail("Expected data in outbound channel")
            end match
            succeed
        }

        "respond writes status line and headers" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            val headers = HttpHeaders.empty.add("Content-Type", "text/plain").add("X-Test", "value")
            discard(ctx.respond(HttpStatus.OK, headers))

            // Read what was written to outbound
            val result = outbound.poll()
            assert(result.isSuccess)
            val maybe = result.getOrThrow
            maybe match
                case Present(span) =>
                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                    assert(str.startsWith("HTTP/1.1 200 OK\r\n"), s"Expected status line, got: $str")
                    assert(str.contains("Content-Type: text/plain\r\n"), s"Expected Content-Type header, got: $str")
                    assert(str.contains("X-Test: value\r\n"), s"Expected X-Test header, got: $str")
                    assert(str.endsWith("\r\n\r\n"), s"Expected header terminator, got: $str")
                case _ => fail("Expected data in outbound channel")
            end match
            succeed
        }

        "writeChunk sends chunked encoding format" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            val headers = HttpHeaders.empty.add("Transfer-Encoding", "chunked")
            val writer  = ctx.respond(HttpStatus.OK, headers)

            // Discard the status line + headers
            discard(outbound.poll())

            // Write a chunk
            val chunkData = "Hello, World!".getBytes(StandardCharsets.UTF_8)
            writer.writeChunk(Span.fromUnsafe(chunkData))

            // Read the combined chunk (hex header + data + CRLF in a single offer)
            val combined = outbound.poll().getOrThrow

            combined match
                case Present(span) =>
                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                    // 13 bytes = 0xd, so expected: "d\r\nHello, World!\r\n"
                    assert(str == "d\r\nHello, World!\r\n", s"Expected 'd\\r\\nHello, World!\\r\\n' but got '$str'")
                case _ => fail("Expected combined chunk data")
            end match

            succeed
        }

        "finish sends last chunk marker" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            val writer = ctx.respond(HttpStatus.OK, HttpHeaders.empty)
            discard(outbound.poll()) // discard status line

            writer.finish()

            val result = outbound.poll().getOrThrow
            result match
                case Present(span) =>
                    val data = new String(span.toArray, StandardCharsets.US_ASCII)
                    assert(data == "0\r\n\r\n", s"Expected last chunk marker, got: '$data'")
                case _ => fail("Expected last chunk marker in outbound")
            end match
            succeed
        }

        "writeBody sends data directly" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            val writer = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("Content-Length", "5"))
            discard(outbound.poll()) // discard status line

            val body = "hello".getBytes(StandardCharsets.UTF_8)
            writer.writeBody(Span.fromUnsafe(body))

            val result = outbound.poll().getOrThrow
            result match
                case Present(span) =>
                    val data = new String(span.toArray, StandardCharsets.UTF_8)
                    assert(data == "hello")
                case _ => fail("Expected body data in outbound")
            end match
            succeed
        }

        "respond with 404 Not Found" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            discard(ctx.respond(HttpStatus.NotFound, HttpHeaders.empty))

            val result = outbound.poll().getOrThrow
            result match
                case Present(span) =>
                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                    assert(str.startsWith("HTTP/1.1 404 Not Found\r\n"), s"Got: $str")
                case _ => fail("Expected status line in outbound")
            end match
            succeed
        }

        "respond with 500 Internal Server Error" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            discard(ctx.respond(HttpStatus.InternalServerError, HttpHeaders.empty))

            val result = outbound.poll().getOrThrow
            result match
                case Present(span) =>
                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                    assert(str.startsWith("HTTP/1.1 500 Internal Server Error\r\n"), s"Got: $str")
                case _ => fail("Expected status line in outbound")
            end match
            succeed
        }

        "setRequest and readBody" in run {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            val builder   = new ParsedRequestBuilder
            val bodyBytes = "request body".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0) // GET
            builder.setPath("test".getBytes, 0, 4)
            builder.setContentLength(bodyBytes.length)
            val req = builder.build()

            ctx.setRequest(req, Span.fromUnsafe(bodyBytes))

            assert(ctx.request == req)
            ctx.readBody().map { body =>
                val bodyStr = new String(body.toArray, StandardCharsets.UTF_8)
                assert(bodyStr == "request body")
            }
        }

        "bodyChannel returns inbound" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](16)
            val outbound  = Channel.Unsafe.init[Span[Byte]](16)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            assert(ctx.bodyChannel eq inbound)
            succeed
        }
    }

    "RouteLookup" - {

        "initial state" in {
            val lookup = new RouteLookup(8)
            assert(lookup.endpointIdx == -1)
            assert(lookup.captureCount == 0)
            assert(lookup.captureSegmentIndices.length == 8)
            succeed
        }

        "reset clears state" in {
            val lookup = new RouteLookup(4)
            lookup.endpointIdx = 5
            lookup.captureCount = 2
            lookup.captureSegmentIndices(0) = 10
            lookup.captureSegmentIndices(1) = 20

            lookup.reset()

            assert(lookup.endpointIdx == -1)
            assert(lookup.captureCount == 0)
            // captureSegmentIndices values are not cleared by reset (just count is zeroed)
            // which is correct behavior since captureCount determines valid entries
            succeed
        }

        "stores capture indices" in {
            val lookup = new RouteLookup(4)
            lookup.endpointIdx = 3
            lookup.captureCount = 2
            lookup.captureSegmentIndices(0) = 1
            lookup.captureSegmentIndices(1) = 3

            assert(lookup.endpointIdx == 3)
            assert(lookup.captureCount == 2)
            assert(lookup.captureSegmentIndices(0) == 1)
            assert(lookup.captureSegmentIndices(1) == 3)
            succeed
        }

        "reset and reuse" in {
            val lookup = new RouteLookup(4)

            // First use
            lookup.endpointIdx = 1
            lookup.captureCount = 1
            lookup.captureSegmentIndices(0) = 5

            lookup.reset()

            // Second use
            lookup.endpointIdx = 2
            lookup.captureCount = 2
            lookup.captureSegmentIndices(0) = 7
            lookup.captureSegmentIndices(1) = 9

            assert(lookup.endpointIdx == 2)
            assert(lookup.captureCount == 2)
            assert(lookup.captureSegmentIndices(0) == 7)
            assert(lookup.captureSegmentIndices(1) == 9)
            succeed
        }
    }

end Http1ParserTest
