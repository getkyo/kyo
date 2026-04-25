package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.util.*

class ParsedRequestTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    // ==================== GrowableByteBuffer ====================

    "GrowableByteBuffer" - {

        "write and read bytes" in {
            val buf = new GrowableByteBuffer()
            buf.writeByte(1)
            buf.writeByte(2)
            buf.writeByte(3)
            val src = Array[Byte](10, 20, 30, 40)
            buf.writeBytes(src, 1, 2)
            val result = buf.toByteArray
            assert(result.length == 5)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
            assert(result(3) == 20)
            assert(result(4) == 30)
            succeed
        }

        "writeAscii" in {
            val buf = new GrowableByteBuffer()
            buf.writeAscii("Hello, World!")
            val result   = buf.toByteArray
            val expected = "Hello, World!".getBytes(StandardCharsets.US_ASCII)
            assert(result.length == expected.length)
            var i = 0
            while i < result.length do
                assert(result(i) == expected(i))
                i += 1
            succeed
        }

        "auto-grow on overflow" in {
            val buf = new GrowableByteBuffer()
            // Write more than the initial 512 bytes
            val data = new Array[Byte](1000)
            var i    = 0
            while i < 1000 do
                data(i) = (i % 256).toByte
                i += 1
            buf.writeBytes(data, 0, 1000)
            assert(buf.size == 1000)
            val result = buf.toByteArray
            assert(result.length == 1000)
            i = 0
            while i < 1000 do
                assert(result(i) == (i % 256).toByte)
                i += 1
            succeed
        }

        "reset reuses buffer" in {
            val buf = new GrowableByteBuffer()
            buf.writeAscii("first")
            assert(buf.size == 5)
            buf.reset()
            assert(buf.size == 0)
            buf.writeAscii("hi")
            assert(buf.size == 2)
            val result = buf.toByteArray
            assert(result.length == 2)
            assert(result(0) == 'h'.toByte)
            assert(result(1) == 'i'.toByte)
            succeed
        }

        "copyTo" in {
            val buf = new GrowableByteBuffer()
            buf.writeAscii("abc")
            val dest = new Array[Byte](10)
            buf.copyTo(dest, 3)
            assert(dest(3) == 'a'.toByte)
            assert(dest(4) == 'b'.toByte)
            assert(dest(5) == 'c'.toByte)
            // Bytes before offset should be zero
            assert(dest(0) == 0)
            assert(dest(1) == 0)
            assert(dest(2) == 0)
            succeed
        }
    }

    // ==================== ParsedRequestBuilder + ParsedRequest ====================

    "ParsedRequestBuilder + ParsedRequest" - {

        "build minimal GET request" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val req = builder.build()
            assert(req.method == HttpMethod.GET)
            assert(req.pathAsString == "/")
            succeed
        }

        "build with content-length" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("POST"))
            val pathBytes = "/data".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setContentLength(100)
            val req = builder.build()
            assert(req.contentLength == 100)
            succeed
        }

        "build with chunked flag" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("POST"))
            val pathBytes = "/stream".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setChunked(true)
            val req = builder.build()
            assert(req.isChunked == true)
            assert(req.isKeepAlive == false)
            succeed
        }

        "build with keep-alive flag" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setKeepAlive(true)
            val req = builder.build()
            assert(req.isKeepAlive == true)
            assert(req.isChunked == false)
            succeed
        }

        "build with query" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes  = "/search".getBytes(StandardCharsets.UTF_8)
            val queryBytes = "key=val".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setQuery(queryBytes, 0, queryBytes.length)
            val req = builder.build()
            assert(req.hasQuery == true)
            assert(req.queryRawString == Present("key=val"))
            succeed
        }

        "path segments" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/api/v1/users".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val seg1 = "api".getBytes(StandardCharsets.UTF_8)
            val seg2 = "v1".getBytes(StandardCharsets.UTF_8)
            val seg3 = "users".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg1, 0, seg1.length)
            builder.addPathSegment(seg2, 0, seg2.length)
            builder.addPathSegment(seg3, 0, seg3.length)
            val req = builder.build()
            assert(req.pathSegmentCount == 3)
            succeed
        }

        "pathSegmentMatches" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/api/v1/users".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val seg1 = "api".getBytes(StandardCharsets.UTF_8)
            val seg2 = "v1".getBytes(StandardCharsets.UTF_8)
            val seg3 = "users".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg1, 0, seg1.length)
            builder.addPathSegment(seg2, 0, seg2.length)
            builder.addPathSegment(seg3, 0, seg3.length)
            val req = builder.build()
            assert(req.pathSegmentMatches(0, ParsedRequest.Segment("api")))
            assert(req.pathSegmentMatches(1, ParsedRequest.Segment("v1")))
            assert(req.pathSegmentMatches(2, ParsedRequest.Segment("users")))
            succeed
        }

        "pathSegmentMatches negative" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/api/v1".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val seg1 = "api".getBytes(StandardCharsets.UTF_8)
            val seg2 = "v1".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg1, 0, seg1.length)
            builder.addPathSegment(seg2, 0, seg2.length)
            val req = builder.build()
            assert(!req.pathSegmentMatches(0, ParsedRequest.Segment("apix")))
            assert(!req.pathSegmentMatches(0, ParsedRequest.Segment("ap")))
            assert(!req.pathSegmentMatches(0, ParsedRequest.Segment("v1")))
            assert(!req.pathSegmentMatches(1, ParsedRequest.Segment("api")))
            assert(!req.pathSegmentMatches(5, ParsedRequest.Segment("api")))
            assert(!req.pathSegmentMatches(-1, ParsedRequest.Segment("api")))
            succeed
        }

        "pathSegmentAsString" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/api/v1/users".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val seg1 = "api".getBytes(StandardCharsets.UTF_8)
            val seg2 = "v1".getBytes(StandardCharsets.UTF_8)
            val seg3 = "users".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg1, 0, seg1.length)
            builder.addPathSegment(seg2, 0, seg2.length)
            builder.addPathSegment(seg3, 0, seg3.length)
            val req = builder.build()
            assert(req.pathSegmentAsString(0) == "api")
            assert(req.pathSegmentAsString(1) == "v1")
            assert(req.pathSegmentAsString(2) == "users")
            succeed
        }

        "headers" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val name1 = "Content-Type".getBytes(StandardCharsets.UTF_8)
            val val1  = "application/json".getBytes(StandardCharsets.UTF_8)
            val name2 = "Host".getBytes(StandardCharsets.UTF_8)
            val val2  = "localhost".getBytes(StandardCharsets.UTF_8)
            builder.addHeader(name1, 0, name1.length, val1, 0, val1.length)
            builder.addHeader(name2, 0, name2.length, val2, 0, val2.length)
            val req = builder.build()
            assert(req.headerCount == 2)
            assert(req.headerName(0) == "Content-Type")
            assert(req.headerValue(0) == "application/json")
            assert(req.headerName(1) == "Host")
            assert(req.headerValue(1) == "localhost")
            succeed
        }

        "queryParam" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes  = "/search".getBytes(StandardCharsets.UTF_8)
            val queryBytes = "a=1&b=2&c=hello%20world".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setQuery(queryBytes, 0, queryBytes.length)
            val req = builder.build()
            assert(req.queryParam("a") == Present("1"))
            assert(req.queryParam("b") == Present("2"))
            assert(req.queryParam("c") == Present("hello world"))
            assert(req.queryParam("missing") == Absent)
            succeed
        }

        "large request" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("POST"))
            val pathBytes = "/api/v1/users/123/posts/456/comments/789/likes/abc/meta".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)

            // 10 path segments
            val segments = Array("api", "v1", "users", "123", "posts", "456", "comments", "789", "likes", "abc")
            segments.foreach { s =>
                val b = s.getBytes(StandardCharsets.UTF_8)
                builder.addPathSegment(b, 0, b.length)
            }

            // Long query
            val queryBytes = "page=1&size=100&sort=created_at&order=desc&filter=active".getBytes(StandardCharsets.UTF_8)
            builder.setQuery(queryBytes, 0, queryBytes.length)

            // 50 headers
            (0 until 50).foreach { i =>
                val name  = s"X-Custom-Header-$i".getBytes(StandardCharsets.UTF_8)
                val value = s"value-$i-with-some-extra-content".getBytes(StandardCharsets.UTF_8)
                builder.addHeader(name, 0, name.length, value, 0, value.length)
            }

            builder.setContentLength(4096)
            builder.setKeepAlive(true)

            val req = builder.build()
            assert(req.method == HttpMethod.POST)
            assert(req.pathSegmentCount == 10)
            assert(req.contentLength == 4096)
            assert(req.isKeepAlive == true)
            assert(req.hasQuery == true)

            // Verify path segments roundtrip
            segments.zipWithIndex.foreach { case (s, i) =>
                assert(req.pathSegmentAsString(i) == s)
                assert(req.pathSegmentMatches(i, ParsedRequest.Segment(s)))
            }

            // Verify headers roundtrip
            assert(req.headerCount == 50)
            (0 until 50).foreach { i =>
                assert(req.headerName(i) == s"X-Custom-Header-$i")
                assert(req.headerValue(i) == s"value-$i-with-some-extra-content")
            }

            // Verify query params
            assert(req.queryParam("page") == Present("1"))
            assert(req.queryParam("size") == Present("100"))
            assert(req.queryParam("sort") == Present("created_at"))
            assert(req.queryParam("order") == Present("desc"))
            assert(req.queryParam("filter") == Present("active"))
            succeed
        }

        "reset and reuse" in {
            val builder = new ParsedRequestBuilder()

            // Build first request
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes1 = "/first".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes1, 0, pathBytes1.length)
            builder.setKeepAlive(true)
            val name1 = "Host".getBytes(StandardCharsets.UTF_8)
            val val1  = "example.com".getBytes(StandardCharsets.UTF_8)
            builder.addHeader(name1, 0, name1.length, val1, 0, val1.length)
            val req1 = builder.build()

            // Reset and build second request
            builder.reset()
            builder.setMethod(ParsedRequest.ordinalFromName("POST"))
            val pathBytes2 = "/second".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes2, 0, pathBytes2.length)
            builder.setChunked(true)
            builder.setContentLength(42)
            val name2 = "Content-Type".getBytes(StandardCharsets.UTF_8)
            val val2  = "text/plain".getBytes(StandardCharsets.UTF_8)
            builder.addHeader(name2, 0, name2.length, val2, 0, val2.length)
            val req2 = builder.build()

            // Verify first request is still valid
            assert(req1.method == HttpMethod.GET)
            assert(req1.pathAsString == "/first")
            assert(req1.isKeepAlive == true)
            assert(req1.isChunked == false)
            assert(req1.headerCount == 1)
            assert(req1.headerName(0) == "Host")

            // Verify second request
            assert(req2.method == HttpMethod.POST)
            assert(req2.pathAsString == "/second")
            assert(req2.isChunked == true)
            assert(req2.isKeepAlive == false)
            assert(req2.contentLength == 42)
            assert(req2.headerCount == 1)
            assert(req2.headerName(0) == "Content-Type")
            assert(req2.headerValue(0) == "text/plain")
            succeed
        }

        "binary safety" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)

            // Header value with all printable ASCII bytes 0x20-0x7E
            val nameBytes = "X-Binary".getBytes(StandardCharsets.UTF_8)
            val valBytes  = new Array[Byte](0x7e - 0x20 + 1)
            var i         = 0
            while i < valBytes.length do
                valBytes(i) = (0x20 + i).toByte
                i += 1
            builder.addHeader(nameBytes, 0, nameBytes.length, valBytes, 0, valBytes.length)
            val req = builder.build()
            assert(req.headerCount == 1)
            val headerVal = req.headerValue(0)
            assert(headerVal.length == valBytes.length)
            i = 0
            while i < valBytes.length do
                assert(headerVal.charAt(i) == (0x20 + i).toChar)
                i += 1
            succeed
        }

        "empty path segments" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            // No addPathSegment calls
            val req = builder.build()
            assert(req.pathSegmentCount == 0)
            assert(req.pathAsString == "/")
            succeed
        }

        "Segment from string" in {
            val seg      = ParsedRequest.Segment("hello")
            val bytes    = seg.bytes
            val expected = "hello".getBytes(StandardCharsets.UTF_8)
            assert(bytes.length == expected.length)
            var i = 0
            while i < bytes.length do
                assert(bytes(i) == expected(i))
                i += 1
            succeed
        }

        "pathSegmentMatches case sensitivity" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/api".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val seg = "api".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg, 0, seg.length)
            val req = builder.build()
            // Case-sensitive: "API" should NOT match "api"
            assert(!req.pathSegmentMatches(0, ParsedRequest.Segment("API")))
            assert(!req.pathSegmentMatches(0, ParsedRequest.Segment("Api")))
            assert(req.pathSegmentMatches(0, ParsedRequest.Segment("api")))
            succeed
        }

        "method ordinal roundtrip" in {
            val allMethods = Array(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
                HttpMethod.HEAD,
                HttpMethod.OPTIONS,
                HttpMethod.TRACE,
                HttpMethod.CONNECT
            )
            allMethods.foreach { m =>
                val builder = new ParsedRequestBuilder()
                val ordinal = ParsedRequest.ordinalFromName(m.name)
                builder.setMethod(ordinal)
                val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
                builder.setPath(pathBytes, 0, pathBytes.length)
                val req = builder.build()
                assert(req.method == m)
            }
            succeed
        }

        "contentLength default" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            // No setContentLength call
            val req = builder.build()
            assert(req.contentLength == -1)
            succeed
        }

        "queryParam URL decoding" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(ParsedRequest.ordinalFromName("GET"))
            val pathBytes = "/search".getBytes(StandardCharsets.UTF_8)
            // %20 = space, + = space
            val queryBytes = "a=hello%20world&b=foo+bar&c=%2F%2B".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setQuery(queryBytes, 0, queryBytes.length)
            val req = builder.build()
            assert(req.queryParam("a") == Present("hello world"))
            assert(req.queryParam("b") == Present("foo bar"))
            assert(req.queryParam("c") == Present("/+"))
            succeed
        }
    }

end ParsedRequestTest
