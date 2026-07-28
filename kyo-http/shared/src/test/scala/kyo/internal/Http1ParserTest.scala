package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.*
import kyo.internal.util.*
import kyo.net.internal.util.GrowableByteBuffer

class Http1ParserTest extends kyo.BaseHttpTest:

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
        }

        // RFC 9110 section 8.6: Content-Length is 1*DIGIT. A leading '+' is not a digit, so "Content-Length: +13"
        // is invalid and must be refused. Jetty CVE-2023-40167 accepted it, which a peer that rejects it disagrees
        // with about whether the message even has a determinable length.
        "rejects a Content-Length with a leading plus (CVE-2023-40167)" in {
            val req = parseRequest("POST /submit HTTP/1.1\r\nHost: example.com\r\nContent-Length: +13\r\n\r\n")
            assert(
                req == null || req.contentLength < 0,
                s"a non-digit Content-Length must be refused, got contentLength=${if req == null then "n/a" else req.contentLength}"
            )
        }

        "parse chunked request" in {
            val req = parseRequest(
                "POST /upload HTTP/1.1\r\nHost: example.com\r\nTransfer-Encoding: chunked\r\n\r\n"
            )
            assert(req != null, "Request should have been parsed")
            assert(req.method == HttpMethod.POST)
            assert(req.isChunked)
            assert(req.contentLength == -1)
        }

        "parse keep-alive default for HTTP/1.1" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.isKeepAlive)
        }

        "parse Connection: close" in {
            val req = parseRequest(
                "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            )
            assert(req != null)
            assert(!req.isKeepAlive)
        }

        "parse path with query" in {
            val req = parseRequest("GET /path?key=val HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathAsString == "/path")
            assert(req.hasQuery)
            req.queryParam("key") match
                case Present(v) => assert(v == "val")
                case Absent     => fail("Expected query param 'key'")
            ()
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
        }

        "empty body GET has contentLength -1" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.contentLength == -1)
        }

        "case sensitive method - non-standard lowercase" in {
            // HTTP methods are case-sensitive (RFC 9110 section 9.1), so "get" is not the method "GET". This used to be
            // accepted and coerced to GET via an ordinal-0 fallback, which served the request as a method the client did
            // not send. It is now refused; see the request-line validation leaves below.
            val req = parseRequest("get / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "a lowercase method token is not a known method and must be refused, not coerced to GET")
        }

        "parse PUT method" in {
            val req = parseRequest("PUT /resource HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.PUT)
            assert(req.contentLength == 5)
        }

        "parse DELETE method" in {
            val req = parseRequest("DELETE /resource/123 HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.DELETE)
            assert(req.pathSegmentCount == 2)
            assert(req.pathSegmentAsString(0) == "resource")
            assert(req.pathSegmentAsString(1) == "123")
        }

        "parse PATCH method" in {
            val req = parseRequest("PATCH /resource HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.PATCH)
        }

        "parse HEAD method" in {
            val req = parseRequest("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.HEAD)
        }

        "parse OPTIONS method" in {
            val req = parseRequest("OPTIONS * HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.method == HttpMethod.OPTIONS)
        }

        "parse Connection: keep-alive explicitly" in {
            val req = parseRequest(
                "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
            )
            assert(req != null)
            assert(req.isKeepAlive)
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
            ()
        }

        "parse percent-encoded query parameter" in {
            val req = parseRequest("GET /path?name=hello%20world HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            req.queryParam("name") match
                case Present(v) => assert(v == "hello world")
                case Absent     => fail("Expected query param 'name'")
            ()
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
        }

        "path with encoded characters preserved" in {
            val req = parseRequest("GET /path%20with%20spaces HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            // Raw path is preserved (percent-encoding not decoded at path level)
            assert(req.pathAsString == "/path%20with%20spaces")
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
        }

        "parse root path" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathAsString == "/")
            assert(req.pathSegmentCount == 0)
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
        }

        // A bare LF is not a line terminator here, but RFC 9112 section 2.2 lets a recipient treat it as one.
        // Accepting it stores "bar\nX-Evil: 1" as one value, which a proxy re-emits verbatim and a downstream
        // may read as two headers. RFC 9110 section 5.5 makes rejection a recipient MUST.
        "rejects a bare LF inside a header value" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nX-Foo: bar\nX-Evil: 1\r\n\r\n")
            assert(req == null, "a bare LF in a field value must be rejected, not stored as part of the value")
        }

        // The same byte in a name reaches the same place.
        "rejects a bare LF inside a header name" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nX-Fo\no: bar\r\n\r\n")
            assert(req == null, "a bare LF in a field name must be rejected")
        }

        // A field name is a token (RFC 9110 section 5.6.2) and SP is not a tchar. The colon check only looks at
        // the byte before the colon, so "X Foo" passes it and re-emits verbatim through the packed write path.
        "rejects a header name that is not a token" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nX Foo: bar\r\n\r\n")
            assert(req == null, "a field name containing SP is not a token and must be rejected")
        }

        // Catches a token check that only tests the first byte, or that allows the RFC 9110 separators.
        "rejects a header name containing a separator" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nX-Fo@o: bar\r\n\r\n")
            assert(req == null, "'@' is not a tchar and must be rejected in a field name")
        }

        // The over-strictness guard for the token check: every tchar RFC 9110 section 5.6.2 lists must still
        // parse, or the check would reject ordinary headers such as Content-Type or a vendor's X-Foo_Bar.
        "accepts a header name using the full tchar set" in {
            val name = "!#$%&'*+-.^_`|~0Az"
            val req  = parseRequest(s"GET / HTTP/1.1\r\nHost: localhost\r\n$name: ok\r\n\r\n")
            assert(req != null, s"'$name' is a valid token and must parse")
            assert(req.headerCount == 2)
            assert(req.headerName(1) == name)
            assert(req.headerValue(1) == "ok")
        }

        // Path traversal. The splitter divides on the literal byte '/', so an ENCODED separator does not split, and a
        // consumer that decodes a segment afterwards recovers structure the splitter never agreed to. These leaves are
        // at the parser because that is where the request is turned into segments: a check further down cannot restore
        // a distinction the segmentation already destroyed.
        //
        // The stakes are a handler joining a capture onto a base directory, which is what Capture.Rest is documented
        // for. Two spellings both reach it, and one needs no encoding at all.

        // An encoded separator inside a segment is legitimate on BOTH capture kinds, and that is a contract, not an
        // oversight: the client percent-encodes a named capture (RouteUtil appends URLEncoder.encode), so a value of
        // "hello/world" travels as "hello%2Fworld" and must come back intact, and it appends a rest capture raw. So a
        // capture value may contain a path separator, and the parser does not refuse one.
        //
        // The consequence is stated plainly because it decides where the defense can live: a capture is request-supplied
        // DATA, not a safe path fragment, and a handler resolving one against a directory must validate it exactly as it
        // would any other untrusted string. No parser rule can substitute for that while "hello/world" remains a legal
        // capture value.
        "keeps an encoded separator inside a single segment (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /items/hello%2Fworld HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null, "an encoded separator is valid inside a segment")
            assert(req.pathSegmentCount == 2, s"it must not split the segment, got ${req.pathSegmentCount}")
            assert(req.pathSegmentAsStringDecoded(1) == "hello/world", s"got \"${req.pathSegmentAsStringDecoded(1)}\"")
        }

        // Both capture kinds must answer the same way for the same bytes. An earlier attempt re-encoded separators in
        // the rest join only, which forked the contract by route shape: the same segment decoded one way for a named
        // capture and another for a rest capture, so a rule proven on one said nothing about the other.
        "resolves a segment identically for a named capture and a rest capture (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/a%2Fb HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(
                req.pathSegmentAsStringDecoded(1) == req.restPathAsString(1),
                s"named gave \"${req.pathSegmentAsStringDecoded(1)}\", rest gave \"${req.restPathAsString(1)}\""
            )
        }

        // Decoding must be injective: two different request lines must not collapse to one capture value. The
        // re-encoding attempt above broke exactly this, mapping both "a%252Fb" and "a%2Fb" onto "a%2Fb", so a handler
        // could no longer tell a literal "%2F" in a value from an encoded slash.
        "decodes distinct escapes to distinct values (GHSA-crq5-92j2-j7wv)" in {
            val once  = parseRequest("GET /files/a%2Fb HTTP/1.1\r\nHost: localhost\r\n\r\n")
            val twice = parseRequest("GET /files/a%252Fb HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(once != null && twice != null)
            assert(
                once.restPathAsString(1) != twice.restPathAsString(1),
                s"\"a%2Fb\" and \"a%252Fb\" both decoded to \"${once.restPathAsString(1)}\""
            )
            assert(twice.restPathAsString(1) == "a%2Fb", s"got \"${twice.restPathAsString(1)}\"")
        }

        // Routing and reporting must not disagree. Dot segments are resolved before routing, so the stored path is
        // rewritten to match: otherwise the request routes on "admin/secret" while req.url.path, every filter and every
        // log line read "/public/../admin/secret". Two recipients acting on different views of one message is the same
        // disagreement this parser refuses in framing.
        "reports the same path it routed on after resolving dot segments (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /public/../admin/secret HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.restPathAsString(0) == "admin/secret", s"routed on \"${req.restPathAsString(0)}\"")
            assert(req.pathAsString == "/admin/secret", s"but reported \"${req.pathAsString}\"")
        }

        // The empty-segment spelling of the same disagreement. An empty segment is dropped from the segment list just as
        // a dot segment is, so "/a//b" routes on [a, b]; reporting the raw path would again name a different resource
        // than the one served. RFC 3986 makes "//" and "/" different paths, so this is not a cosmetic difference.
        "reports the same path it routed on after dropping empty segments (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /a//b HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentCount == 2, s"expected 2 segments, got ${req.pathSegmentCount}")
            assert(req.pathAsString == "/a/b", s"routed on a/b but reported \"${req.pathAsString}\"")
        }

        // The degenerate case: "//" is all empty segments, so it routes to the root and must say so.
        "reports the root after a path of only separators (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET // HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentCount == 0, s"expected 0 segments, got ${req.pathSegmentCount}")
            assert(req.pathAsString == "/", s"routed to the root but reported \"${req.pathAsString}\"")
        }

        // The over-strictness control for the rewrite: a path with no dot segments must be reported byte-for-byte as
        // sent, since rewriting it would be both wasteful and a change nobody asked for.
        "leaves an ordinary path untouched (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /a/b/c HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathAsString == "/a/b/c", s"got \"${req.pathAsString}\"")
        }

        // The unencoded spelling. It needs no cleverness, it is the plain form of the same attack, and resolving it
        // (RFC 3986 section 5.2.4) is what a URI reference means: "/files/../../etc/passwd" addresses "/etc/passwd".
        // Resolution happens before routing so the route that matches is the route for the path actually addressed,
        // and a ".." with nothing left to pop cannot walk above the root.
        "resolves unencoded dot-dot segments rather than passing them on (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/../../etc/passwd HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null, "a path carrying dot segments is resolvable, not malformed")
            assert(req.pathSegmentCount == 2, s"expected the traversal to resolve to 2 segments, got ${req.pathSegmentCount}")
            assert(req.restPathAsString(0) == "etc/passwd", s"expected \"etc/passwd\", got \"${req.restPathAsString(0)}\"")
        }

        // A path parameter next to a dot segment must not open a traversal. Jetty CVE-2026-8384 left a real ".."
        // beside a ";" UNRESOLVED (a downstream then traversed), and Undertow CVE-2024-1459 STRIPPED the ";" to
        // collapse "/..;/" into "/.." and traversed. The safe position between the two: resolve a genuine ".."
        // segment, and treat "..;" (which RFC 3986 section 3.3 makes an ordinary pchar segment, NOT a dot segment)
        // as the literal directory name it is, so neither spelling escapes.
        "a path parameter beside a dot segment does not open a traversal (CVE-2026-8384, CVE-2024-1459)" in {
            // A real ".." segment, with a ";" on the PRECEDING segment: split on "/", so ".." is its own segment and
            // must resolve, popping "public;".
            val a = parseRequest("GET /public;/../admin/secret HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(a != null, "the request is well-formed")
            assert(a.restPathAsString(0) == "admin/secret", s"the '..' beside a path parameter must resolve, got: ${a.restPathAsString(0)}")

            // "..;" is one segment and is not the dot segment "..", so it is kept literal, not stripped to "..".
            val b = parseRequest("GET /files/..;/etc HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(b != null)
            assert(
                b.pathSegmentAsString(1) == "..;",
                s"\"..;\" must stay a literal segment, not be stripped to a traversal, got: ${b.pathSegmentAsString(1)}"
            )
        }

        // A ';' is an ordinary path character (RFC 3986 section 3.3 pchar), so a segment carrying one is neither split
        // nor stripped: "/a;b/c" is the two segments "a;b" and "c". Stripping the ';' (matrix-parameter handling) is
        // what let Undertow CVE-2020-1757 route differently from what the security layer matched.
        "keeps a path parameter as part of its segment (CVE-2020-1757)" in {
            val req = parseRequest("GET /a;b/c HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentCount == 2, s"expected 2 segments, got ${req.pathSegmentCount}")
            assert(req.pathSegmentAsString(0) == "a;b", s"the ';' must stay part of the segment, got \"${req.pathSegmentAsString(0)}\"")
        }

        // RFC 9112 section 2.2 permits skipping at most one empty line before the request line; it does not permit
        // skipping control bytes. Netty CVE-2026-50020 accepted leading NUL/control bytes before the request line
        // (SKIP_CONTROL_CHARS over all 256 values), so "\x00\x00GET /..." was served as a request a strict front end
        // would frame differently, a smuggling desync.
        "rejects control bytes before the request line (CVE-2026-50020)" in {
            val req = parseRequest("  GET /admin HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "leading control bytes before the request line must be refused, not skipped")
        }

        // RFC 9112 section 2.2: the only line terminator is CRLF. Undertow CVE-2026-28367 accepted three bare CRs as
        // the end-of-headers terminator where a strict front end requires CRLF CRLF, splitting the message. kyo
        // rejects any bare CR in the header block; this pins that the triple-CR terminator does not slip through.
        "rejects a bare-CR run as a header terminator (CVE-2026-28367)" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\r\r\n\r\n")
            assert(req == null, "a bare CR is not a line terminator and must be refused")
        }

        // The same resolution must apply to the ENCODED spelling of the dots, or "%2e%2e" becomes a way to smuggle a
        // dot segment past a check that only looked at the raw bytes.
        "resolves encoded dot-dot segments identically (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/%2e%2e/data HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null, "encoded dots are still dot segments, not malformed input")
            assert(req.pathSegmentCount == 1, s"expected the traversal to resolve to 1 segment, got ${req.pathSegmentCount}")
            assert(req.restPathAsString(0) == "data", s"expected \"data\", got \"${req.restPathAsString(0)}\"")
        }

        // A single dot addresses the segment it sits in, so it contributes nothing.
        "drops single-dot segments (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /a/./b HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.restPathAsString(0) == "a/b", s"expected \"a/b\", got \"${req.restPathAsString(0)}\"")
        }

        // A NUL truncates a path in any consumer that hands it to a C API, so the bytes after it stop being part of
        // the name being checked while remaining part of the name being used.
        "rejects an encoded NUL in a path segment (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/a%00b HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "a NUL byte in a path segment must be rejected")
        }

        // RFC 3986 section 2.1 requires two hex digits after '%'. Beyond being malformed, this is the case that used
        // to raise from request dispatch, where nothing catches it.
        "rejects a malformed percent-escape in a path (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/%zz HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "an escape without two hex digits is malformed and must be rejected, not raised later")
        }

        "rejects a truncated percent-escape at the end of a path (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/abc% HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "a '%' with no hex digits after it is malformed")
        }

        // The over-strictness guard: ordinary percent-encoding must still decode. Without this the rules above could
        // be satisfied by refusing every escape, which would break any path carrying a space or a non-ASCII character.
        "still decodes ordinary percent-escapes in a path segment (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/John%20Doe HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null, "a well-formed escape must parse")
            assert(req.pathSegmentAsStringDecoded(1) == "John Doe", s"got \"${req.pathSegmentAsStringDecoded(1)}\"")
        }

        // '+' is a space in a QUERY (RFC 3986 section 3.4) and an ordinary character in a PATH. Decoding it as a space
        // here would rename the resource being addressed.
        "does not decode '+' as a space in a path segment (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/a+b HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentAsStringDecoded(1) == "a+b", s"'+' is literal in a path, got \"${req.pathSegmentAsStringDecoded(1)}\"")
        }

        // The same value with an escape elsewhere in it. This pins that the '+' rule does not depend on whether the
        // segment happens to contain a '%', which is how the previous decoder disagreed with itself.
        "treats '+' the same whether or not the segment carries an escape (GHSA-crq5-92j2-j7wv)" in {
            val req = parseRequest("GET /files/a+b%20c HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req != null)
            assert(req.pathSegmentAsStringDecoded(1) == "a+b c", s"got \"${req.pathSegmentAsStringDecoded(1)}\"")
        }

        // Upgrade and Expect are LISTS (RFC 9110 sections 7.8 and 10.1.1), so a client naming a fallback still names
        // the first protocol. Tightening the old prefix match to a whole-VALUE match traded a too-loose test for a
        // too-strict one and silently refused every such client; these pin the middle.
        "Upgrade offers websocket even when other protocols follow (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket, h2c\r\n\r\n")
            assert(req != null)
            assert(req.isUpgrade, "\"websocket, h2c\" names websocket as a list element and must signal an upgrade")
        }

        "Expect asks for 100-continue even when other expectations follow (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nExpect: 100-continue, foo\r\n\r\n")
            assert(req != null)
            assert(req.expectContinue, "\"100-continue, foo\" names 100-continue as a list element")
        }

        // RFC 9110 section 5.6.1 permits empty list elements, so a trailing comma names exactly one coding. Refusing it
        // would reject a conformant request in the name of a smuggling defense that does not apply to it.
        "Transfer-Encoding: chunked with a trailing comma is still sole chunked (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked,\r\n\r\n")
            assert(req != null, "an empty list element is legal and must not make the request invalid")
            assert(req.isChunked, "the sole non-empty coding is chunked")
        }

        // The packed layout addresses the raw buffer with 16-bit offsets, so once that buffer passes 65535 bytes an
        // offset wraps and a header name or value resolves to some other window of the buffer entirely: request-supplied
        // bytes, at a request-influenced offset, presented as a header the client never sent, while the headers that
        // were sent silently vanish. That is header smuggling with no malformed byte anywhere in the request.
        //
        // Resolving a dot segment used to make this reachable at the DEFAULT header size, because the resolved path was
        // appended as a third copy on top of the raw path and the segments. The path is now stored once, and the
        // overflow itself is refused rather than wrapped.
        "refuses a request whose offsets cannot be represented" in {
            val long = "b" * 22000
            val req  = parseRequest(s"GET /$long/junk/../x HTTP/1.1\r\nHost: localhost\r\nX-Probe: sentinel\r\n\r\n")
            if req != null then
                assert(req.headerCount == 2, s"expected the two headers sent, got ${req.headerCount}")
                assert(req.headerName(0) == "Host", s"header 0 resolved to \"${req.headerName(0)}\"")
                assert(req.headerValue(0) == "localhost", s"Host resolved to \"${req.headerValue(0)}\"")
                assert(req.headerName(1) == "X-Probe", s"header 1 resolved to \"${req.headerName(1)}\"")
                assert(req.headerValue(1) == "sentinel", s"X-Probe resolved to \"${req.headerValue(1)}\"")
            else succeed("refusing the request outright is the other acceptable answer")
            end if
        }

        // The same length WITHOUT a dot segment, which stores one fewer copy and so must still parse cleanly. This is
        // the control that stops the guard above from being satisfied by refusing every long path.
        "still parses a long path that needs no resolution" in {
            val long = "b" * 22000
            val req  = parseRequest(s"GET /$long/junk/x HTTP/1.1\r\nHost: localhost\r\nX-Probe: sentinel\r\n\r\n")
            assert(req != null, "a long but representable request must parse")
            assert(req.headerValue(0) == "localhost", s"Host resolved to \"${req.headerValue(0)}\"")
            assert(req.headerValue(1) == "sentinel", s"X-Probe resolved to \"${req.headerValue(1)}\"")
        }

        // Request-line validation. A request line that does not parse must be REFUSED, not repaired. Every failure
        // branch below used to fall through silently, leaving the builder at its defaults: method ordinal 0, which is
        // GET, and no path at all, which routes to the root. So a request no conforming server would accept was served,
        // as a DIFFERENT request than the one sent.
        //
        // That is a desync with an access-control edge: a front end applying a rule to "BREW /admin" or to "/admin"
        // forwards something this server then serves as "GET /". One request, two recipients, two different resources.

        // RFC 9110 section 9.1: the method token is case-SENSITIVE. "get" is not "GET", and coercing it to GET means
        // the request served is not the request sent.
        "rejects a lowercase method" in {
            val req = parseRequest("get /admin HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "a method token is case-sensitive, so \"get\" is not a known method")
        }

        // An unrecognized method must be refused rather than silently become GET, which is what the ordinal fallback
        // did. This is the leaf with the clearest access-control consequence.
        "rejects an unknown method" in {
            val req = parseRequest("BREW /admin HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "an unknown method must not be coerced into GET")
        }

        // RFC 9112 section 3: request-line = method SP request-target SP HTTP-version. A tab is not SP.
        "rejects tabs in place of the request-line spaces" in {
            val req = parseRequest("GET\t/admin\tHTTP/1.1\r\nHost: localhost\r\n\r\n")
            assert(req == null, "only SP separates the request-line tokens")
        }

        // RFC 9112 section 2.3: a server should not accept the HTTP/0.9 request line, and accepting it here dropped
        // the target entirely rather than honoring it.
        "rejects a request line with no HTTP version" in {
            val req = parseRequest("GET /admin\r\nHost: localhost\r\n\r\n")
            assert(req == null, "a request line without a version is not a valid HTTP/1.x request line")
        }

        "rejects an empty request line" in {
            val req = parseRequest("\r\nHost: localhost\r\n\r\n")
            assert(req == null, "an empty request line has no method, target or version")
        }

        "rejects a request line carrying only a method" in {
            val req = parseRequest("GET\r\nHost: localhost\r\n\r\n")
            assert(req == null, "a request line needs a target and a version")
        }

        // The over-strictness control: the ordinary request line must still parse, with its target intact. Without
        // this, every leaf above could be satisfied by refusing everything.
        "still parses an ordinary request line" in {
            val req = parseRequest("POST /admin/x?q=1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")
            assert(req != null, "a well-formed request line must parse")
            assert(req.method == HttpMethod.POST, s"method was ${req.method.name}")
            assert(req.pathAsString == "/admin/x", s"path was \"${req.pathAsString}\"")
            assert(req.hasQuery, "the query must survive")
        }

        // Absolute-form request target (RFC 9112 section 3.2.2): a server MUST accept "GET http://host/path HTTP/1.1"
        // and route on its PATH, with the authority taken from the request target (which takes precedence over Host).
        // The scheme "http://authority" prefix is fed straight to the path splitter, which splits on '/', so the
        // authority becomes path segments and the effective path is mangled to "/http:/evil.example/admin". The routed
        // path is then neither "/admin" (what the target names) nor a rejection, so a front end applying an ACL to the
        // real path and kyo route on different paths. A recipient must either parse the authority out and route on
        // "/admin", or reject absolute-form; mangling it is neither.
        "handles an absolute-form request target (RFC 9112 section 3.2.2, CVE-2026-59900)" in {
            val req = parseRequest("GET http://evil.example/admin HTTP/1.1\r\nHost: localhost\r\n\r\n")
            if req == null then succeed("rejecting absolute-form is an acceptable resolution")
            else
                assert(
                    req.pathAsString == "/admin",
                    s"absolute-form must route on its path, not a mangled authority-in-path, got: ${req.pathAsString}"
                )
            end if
        }

        // An absolute-form target with a query: the authority is skipped and the path/query split still applies, so the
        // path is "/search" and the query survives (RFC 9112 section 3.2.2).
        "handles an absolute-form request target with a query" in {
            val req = parseRequest("GET http://evil.example/search?q=1 HTTP/1.1\r\nHost: localhost\r\n\r\n")
            if req == null then succeed("rejecting absolute-form is an acceptable resolution")
            else
                assert(req.pathAsString == "/search", s"path must be /search, got: ${req.pathAsString}")
                assert(req.hasQuery, "the query must survive absolute-form parsing")
            end if
        }

        // An absolute-form target with a bare authority and no path component: the effective path is "/" (RFC 9112
        // section 3.2.2), not a mangled authority.
        "handles an absolute-form request target with no path" in {
            val req = parseRequest("GET http://evil.example HTTP/1.1\r\nHost: localhost\r\n\r\n")
            if req == null then succeed("rejecting absolute-form is an acceptable resolution")
            else
                assert(req.pathAsString == "/", s"a bare authority must yield path /, got: ${req.pathAsString}")
            end if
        }

        // Asterisk-form ("OPTIONS * HTTP/1.1", RFC 9112 section 3.2.4): the target is a single "*" and must not be run
        // through the path splitter (which would treat it as a path segment).
        "handles asterisk-form for OPTIONS" in {
            val req = parseRequest("OPTIONS * HTTP/1.1\r\nHost: localhost\r\n\r\n")
            if req == null then succeed("rejecting asterisk-form is an acceptable resolution")
            else
                assert(
                    req.pathAsString == "" || req.pathAsString == "*",
                    s"asterisk-form must not be mangled into a path segment, got: ${req.pathAsString}"
                )
            end if
        }

        // The Content-Length vs Transfer-Encoding conflict must be refused regardless of HTTP version. Netty
        // CVE-2026-42581 and Tomcat CVE-2021-33037 gated that resolution on HTTP/1.1, so an HTTP/1.0 request carrying
        // both kept its Content-Length while still being framed by chunked, and a Content-Length-first proxy split the
        // body at a different boundary. kyo's rejection is not version-gated; this pins that on the HTTP/1.0 spelling.
        "refuses Content-Length with Transfer-Encoding on HTTP/1.0 (CVE-2021-33037, CVE-2026-42581)" in {
            val req = parseRequest(
                "POST /u HTTP/1.0\r\nHost: localhost\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
            )
            assert(req == null, "both length signals present must be refused whatever the version")
        }

        // Transfer-Encoding framing. Every leaf here is a request-smuggling vector: what matters is not that a value
        // is odd on its own but that this parser and an upstream intermediary can reach DIFFERENT conclusions about
        // where the body ends. The bytes one of them counts as body the other reads as the start of a new request,
        // which is how an attacker prepends a request to another client's connection.

        // The positive control for the leaves below: a plain "chunked" must still frame as chunked, or a stricter
        // token check would have been bought by breaking ordinary chunked requests.
        "Transfer-Encoding: chunked frames as chunked (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n")
            assert(req != null, "a well-formed chunked request must parse")
            assert(req.isChunked, "\"chunked\" must set the chunked framing")
        }

        // "chunkedfoo" is a single token and it is not "chunked"; RFC 9110 section 5.6.2 tokens are delimited, not
        // prefixes. A value-comparison that stops after the 7 bytes of "chunked" reports a match here, so this
        // parser frames the message as chunked while a conforming intermediary does not: the desync.
        //
        // Rejection, not merely "not chunked", is the requirement: this value's final coding is not chunked, which is
        // the same RFC 9112 section 6.3 item 6 condition the identity leaf below rejects. Accepting it as a bodyless
        // request is the UNSAFE reading, since the body then sits in the buffer as the next request. The body is
        // present here so that a parser taking the unsafe path has smuggled bytes to strand.
        "Transfer-Encoding value is matched as a whole token, not by prefix (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunkedfoo\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
            assert(
                req == null,
                "\"chunkedfoo\" is not the token \"chunked\", so the final coding is not chunked and the request must be rejected"
            )
        }

        // RFC 9112 section 6.3 item 6: in a REQUEST whose Transfer-Encoding's final coding is not chunked, the body
        // length cannot be determined, and the server MUST respond 400 and close. Treating it as a bodyless request
        // instead leaves "hello" in the buffer to be parsed as the next request line.
        "rejects Transfer-Encoding whose final coding is not chunked (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: identity\r\n\r\nhello")
            assert(req == null, "a request whose final transfer coding is not chunked has no determinable length and must be rejected")
        }

        // Here chunked IS the final coding, so RFC 9112 section 6.1 makes the message chunked and the gzip coding is
        // one this server does not implement (section 6.1: SHOULD respond 501). Framing as chunked and rejecting are
        // both conformant; treating the message as having no body at all is the third answer and the only unsafe one,
        // since it strands the chunked body in the buffer as attacker-controlled bytes at a request boundary.
        //
        // Of the two conformant answers this asserts REJECTION, deliberately. kyo decodes no coding but chunked, so
        // framing "gzip, chunked" as chunked would hand the handler gzip bytes while telling it nothing about the gzip
        // layer. Asserting the disjunction instead would let a fix pick either, and the server-level leaf covering this
        // same input already requires rejection; two leaves admitting different answers for one input is how a suite
        // stops pinning anything.
        "rejects a message carrying a transfer coding it cannot decode (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: gzip, chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
            assert(
                req == null,
                "gzip is not a coding this server decodes, so the request must be rejected rather than framed or treated as bodyless"
            )
        }

        // Trailing OWS. RFC 9110 section 5.6.3 permits it after a field value, so "chunked " IS the token "chunked"
        // and must still frame. This is the over-strictness guard on the whole-token check: a length comparison that
        // does not trim would reject ordinary conformant traffic, trading a smuggling bug for an interop bug.
        "Transfer-Encoding: chunked with trailing whitespace still frames as chunked (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked \r\n\r\n")
            assert(req != null, "a trailing space is permitted OWS and must not make the request invalid")
            assert(req.isChunked, "\"chunked \" is the token \"chunked\" followed by OWS and must frame as chunked")
        }

        // The same prefix defect reached through Connection rather than Transfer-Encoding. "closefoo" is one token and
        // is not "close"; honoring it tears down a connection the peer expects to keep, and the mirror case below
        // keeps one alive that the peer expects closed, which is where a response can be attributed to the wrong
        // request.
        "Connection value is matched as a whole token, not by prefix (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: closefoo\r\n\r\n")
            assert(req != null, "the request should still parse")
            assert(req.isKeepAlive, "\"closefoo\" is not the token \"close\" and must not disable keep-alive")
        }

        // Connection is a list (RFC 9110 section 7.6.1), so its tokens must be matched as list ELEMENTS. A substring
        // scan reports that "no-upgrade" contains "upgrade", which is the opposite of what the value says: it is a
        // token whose meaning is the refusal. Paired with an Upgrade header this flips a plain request into an
        // upgrade handshake the client never asked for.
        "Connection: no-upgrade does not signal an upgrade (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: no-upgrade\r\nUpgrade: websocket\r\n\r\n")
            assert(req != null, "the request should still parse")
            assert(!req.isUpgrade, "\"no-upgrade\" names a token that is not \"upgrade\" and must not signal an upgrade")
        }

        // The over-strictness guard for the list matching: a real comma-separated Connection value must still be
        // honored, or the fix above would have been bought by breaking every genuine upgrade handshake.
        "Connection: keep-alive, Upgrade signals an upgrade (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, Upgrade\r\nUpgrade: websocket\r\n\r\n")
            assert(req != null, "the request should still parse")
            assert(req.isUpgrade, "\"upgrade\" appearing as a list element must signal an upgrade")
        }

        // The remaining two value sites that were matched by prefix, kept as one leaf each so a regression names the
        // header it broke. A spurious 100-continue makes the server send an interim response to a client not waiting
        // for one; a spurious websocket upgrade hands a plain connection to the frame codec.
        "Expect value is matched as a whole token, not by prefix (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("POST /u HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nExpect: 100-continuefoo\r\n\r\n")
            assert(req != null, "the request should still parse")
            assert(!req.expectContinue, "\"100-continuefoo\" is not the token \"100-continue\"")
        }

        "Upgrade value is matched as a whole token, not by prefix (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocketfoo\r\n\r\n")
            assert(req != null, "the request should still parse")
            assert(!req.isUpgrade, "\"websocketfoo\" is not the token \"websocket\"")
        }

        // RFC 9110 section 5.3 lets a recipient combine repeated field lines into one comma-separated list, making
        // this "chunked, identity", whose final coding is identity and which section 6.3 therefore rejects. A parser
        // that honors whichever line it saw first disagrees with any intermediary that combined them.
        "rejects duplicate Transfer-Encoding header lines (GHSA-jrpm-956j-96jg)" in {
            val req = parseRequest(
                "POST /u HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: identity\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
            )
            assert(
                req == null,
                "repeated Transfer-Encoding lines combine to a list whose final coding is not chunked, and must be rejected"
            )
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
            ()
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
            ()
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
            ()
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

            ()
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
            ()
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
            ()
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
            ()
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
            ()
        }

        "setRequest and readBody" in {
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
        }
    }

    "RouteLookup" - {

        "initial state" in {
            val lookup = new RouteLookup(8)
            assert(lookup.endpointIdx == -1)
            assert(lookup.captureCount == 0)
            assert(lookup.captureSegmentIndices.length == 8)
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
            ()
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
        }
    }

end Http1ParserTest
