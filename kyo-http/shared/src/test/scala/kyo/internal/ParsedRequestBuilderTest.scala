package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*

class ParsedRequestBuilderTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    // Helper: read big-endian short from byte array
    private def readShort(arr: Array[Byte], offset: Int): Int =
        ((arr(offset) & 0xff) << 8) | (arr(offset + 1) & 0xff)

    // Helper: read big-endian int from byte array
    private def readInt(arr: Array[Byte], offset: Int): Int =
        ((arr(offset) & 0xff) << 24) |
            ((arr(offset + 1) & 0xff) << 16) |
            ((arr(offset + 2) & 0xff) << 8) |
            (arr(offset + 3) & 0xff)

    // Helper: extract the raw byte array from a ParsedRequest via headersAsPacked
    // We'll use a different approach: build and read fields using ParsedRequest accessors

    "ParsedRequestBuilder" - {

        // Test 1: Set method and verify flag encoding
        "setMethod stores ordinal in high byte of flags" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(3) // 3 = PATCH
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val req = builder.build()
            // method should be PATCH (ordinal 3)
            assert(req.method == HttpMethod.PATCH)
            succeed
        }

        // Test 2: Set chunked flag — bit 0
        "setChunked(true) sets bit 0, setChunked(false) clears it" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(0) // GET
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setChunked(true)
            val req1 = builder.build()
            assert(req1.isChunked == true)
            assert(req1.isKeepAlive == false) // bit 1 unaffected

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setChunked(false)
            val req2 = builder.build()
            assert(req2.isChunked == false)
            succeed
        }

        // Test 3: Set keepAlive flag — bit 1
        "setKeepAlive(true) sets bit 1, setKeepAlive(false) clears it" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(0)
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setKeepAlive(true)
            val req1 = builder.build()
            assert(req1.isKeepAlive == true)
            assert(req1.isChunked == false) // bit 0 unaffected

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setKeepAlive(false)
            val req2 = builder.build()
            assert(req2.isKeepAlive == false)
            succeed
        }

        // Test 4: All remaining bit flags at correct bit positions
        "setHasQuery, setExpectContinue, setHasHost, setMultipleHost, setEmptyHost, setUpgrade set correct bits" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)

            // hasQuery = bit 2
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setHasQuery(true)
            val req1 = builder.build()
            assert(req1.hasQuery == true)

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setExpectContinue(true)
            val req2 = builder.build()
            assert(req2.expectContinue == true)

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setHasHost(true)
            val req3 = builder.build()
            assert(req3.hasHost == true)

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setMultipleHost(true)
            val req4 = builder.build()
            assert(req4.hasMultipleHost == true)

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setEmptyHost(true)
            val req5 = builder.build()
            assert(req5.hasEmptyHost == true)

            builder.reset()
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setUpgrade(true)
            val req6 = builder.build()
            assert(req6.isUpgrade == true)

            succeed
        }

        // Test 5: Set path — path offset/length encoded correctly
        "setPath writes path bytes and encodes pathOff/pathLen in output" in {
            val builder   = new ParsedRequestBuilder()
            val pathStr   = "/hello/world"
            val pathBytes = pathStr.getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val req = builder.build()
            // Verify via high-level API
            assert(req.pathAsString == pathStr)
            // Verify pathLen is encoded correctly at positions [8..9]
            // We access via headersAsPacked indirect check: pathSegmentCount is at [14..15]
            // Direct check via ParsedRequest is the cleanest
            assert(req.pathSegmentCount == 0) // no segments added
            succeed
        }

        // Test 6: setQuery writes bytes and sets hasQuery flag automatically
        "setQuery writes query bytes and auto-sets hasQuery flag" in {
            val builder    = new ParsedRequestBuilder()
            val pathBytes  = "/search".getBytes(StandardCharsets.UTF_8)
            val queryStr   = "foo=bar&baz=qux"
            val queryBytes = queryStr.getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            // Do NOT call setHasQuery manually — setQuery must do it
            builder.setQuery(queryBytes, 0, queryBytes.length)
            val req = builder.build()
            assert(req.hasQuery == true)
            assert(req.queryRawString == Present(queryStr))
            succeed
        }

        // Test 7: addPathSegment appends offset/length and increments segmentCount
        "addPathSegment increments segment count and stores segment data" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/api".getBytes(StandardCharsets.UTF_8)
            val segBytes  = "api".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.addPathSegment(segBytes, 0, segBytes.length)
            val req = builder.build()
            assert(req.pathSegmentCount == 1)
            assert(req.pathSegmentAsString(0) == "api")
            succeed
        }

        // Test 8: Multiple path segments accumulate in order
        "addPathSegment multiple times accumulates segments in order" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/a/b/c/d".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val segments = Array("a", "b", "c", "d")
            segments.foreach { s =>
                val sb = s.getBytes(StandardCharsets.UTF_8)
                builder.addPathSegment(sb, 0, sb.length)
            }
            val req = builder.build()
            assert(req.pathSegmentCount == 4)
            assert(req.pathSegmentAsString(0) == "a")
            assert(req.pathSegmentAsString(1) == "b")
            assert(req.pathSegmentAsString(2) == "c")
            assert(req.pathSegmentAsString(3) == "d")
            succeed
        }

        // Test 9: addHeader writes name and value, records offsets, increments headerCount
        "addHeader writes name/value and increments header count" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            val nameBytes = "Content-Type".getBytes(StandardCharsets.UTF_8)
            val valBytes  = "application/json".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.addHeader(nameBytes, 0, nameBytes.length, valBytes, 0, valBytes.length)
            val req = builder.build()
            assert(req.headerCount == 1)
            assert(req.headerName(0) == "Content-Type")
            assert(req.headerValue(0) == "application/json")
            succeed
        }

        // Test 10: Multiple headers accumulate and count matches
        "addHeader multiple times — all headers present in order" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val headers = Array(
                "Host"         -> "example.com",
                "Accept"       -> "text/html",
                "Content-Type" -> "application/json",
                "X-Custom"     -> "my-value"
            )
            headers.foreach { case (n, v) =>
                val nb = n.getBytes(StandardCharsets.UTF_8)
                val vb = v.getBytes(StandardCharsets.UTF_8)
                builder.addHeader(nb, 0, nb.length, vb, 0, vb.length)
            }
            val req = builder.build()
            assert(req.headerCount == 4)
            headers.zipWithIndex.foreach { case ((n, v), i) =>
                assert(req.headerName(i) == n)
                assert(req.headerValue(i) == v)
            }
            succeed
        }

        // Test 11: Segment offset array doubling — addPathSegment 16+ times triggers growth
        // Initial segOffsets has 32 slots (16 entries of 2 ints each); adding 17 requires doubling
        "addPathSegment 17 times triggers internal array growth without data loss" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            // 16 segments fills the initial 32-slot array exactly; 17th triggers growth
            val count = 17
            (0 until count).foreach { i =>
                val segStr   = s"seg$i"
                val segBytes = segStr.getBytes(StandardCharsets.UTF_8)
                builder.addPathSegment(segBytes, 0, segBytes.length)
            }
            val req = builder.build()
            assert(req.pathSegmentCount == count)
            (0 until count).foreach { i =>
                assert(req.pathSegmentAsString(i) == s"seg$i")
            }
            succeed
        }

        // Test 12: Header offset array doubling — addHeader 32+ times triggers growth
        // Initial hdrOffsets has 128 slots (32 headers of 4 ints each); 33rd triggers growth
        "addHeader 33 times triggers internal array growth without data loss" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val count = 33
            (0 until count).foreach { i =>
                val n = s"X-Header-$i".getBytes(StandardCharsets.UTF_8)
                val v = s"value-$i".getBytes(StandardCharsets.UTF_8)
                builder.addHeader(n, 0, n.length, v, 0, v.length)
            }
            val req = builder.build()
            assert(req.headerCount == count)
            (0 until count).foreach { i =>
                assert(req.headerName(i) == s"X-Header-$i")
                assert(req.headerValue(i) == s"value-$i")
            }
            succeed
        }

        // Test 13: Build packs format correctly — verify output array layout matches spec
        "build produces correctly structured byte array" in {
            val builder   = new ParsedRequestBuilder()
            val pathStr   = "/test"
            val pathBytes = pathStr.getBytes(StandardCharsets.UTF_8)
            val nameBytes = "Host".getBytes(StandardCharsets.UTF_8)
            val valBytes  = "localhost".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(1) // POST ordinal
            builder.setContentLength(42)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.addHeader(nameBytes, 0, nameBytes.length, valBytes, 0, valBytes.length)
            val req = builder.build()

            // Layout verification via ParsedRequest high-level API:
            // - method at flags high byte = ordinal 1 = POST
            assert(req.method == HttpMethod.POST)
            // - contentLength at bytes [2..5]
            assert(req.contentLength == 42)
            // - pathAsString reads from [6..9] + rawBytes
            assert(req.pathAsString == pathStr)
            // - headerCount at [16 + segCount*4 .. +1], here segCount=0, so offset 16
            assert(req.headerCount == 1)
            assert(req.headerName(0) == "Host")
            assert(req.headerValue(0) == "localhost")
            succeed
        }

        // Test 14: Build encodes flags big-endian — result[0] = flags >> 8, result[1] = flags & 0xFF
        "build encodes flags big-endian with method in high byte" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            // Method ordinal 5 = HEAD; chunked (bit 0) + keepAlive (bit 1) both set
            builder.setMethod(5)
            builder.setChunked(true)
            builder.setKeepAlive(true)
            builder.setPath(pathBytes, 0, pathBytes.length)
            val req = builder.build()
            // Verify via ParsedRequest API: method reads (flags >> 8) & 0xff = 5 = HEAD
            assert(req.method == HttpMethod.HEAD)
            // Both low bits set
            assert(req.isChunked == true)
            assert(req.isKeepAlive == true)
            // Other bits clear
            assert(req.hasQuery == false)
            assert(req.expectContinue == false)
            assert(req.hasHost == false)
            assert(req.hasMultipleHost == false)
            assert(req.hasEmptyHost == false)
            assert(req.isUpgrade == false)
            succeed
        }

        // Test 15: Build encodes headerCount at correct position: offset 16 + segCount*4
        "build places header count at offset 16 + segCount*4" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            // Add 3 path segments — header count offset moves to 16 + 3*4 = 28
            val seg = "x".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg, 0, 1)
            builder.addPathSegment(seg, 0, 1)
            builder.addPathSegment(seg, 0, 1)
            val n = "X-Test".getBytes(StandardCharsets.UTF_8)
            val v = "ok".getBytes(StandardCharsets.UTF_8)
            builder.addHeader(n, 0, n.length, v, 0, v.length)
            val req = builder.build()
            // Validate via high-level API
            assert(req.pathSegmentCount == 3)
            assert(req.headerCount == 1)
            assert(req.headerName(0) == "X-Test")
            assert(req.headerValue(0) == "ok")
            succeed
        }

        // Test 16: Build empty request — no path, no headers → still valid ParsedRequest
        "build with no path and no headers produces valid minimal ParsedRequest" in {
            val builder = new ParsedRequestBuilder()
            builder.setMethod(0) // GET
            // No setPath, no addHeader
            val req = builder.build()
            // Should not throw; method readable
            assert(req.method == HttpMethod.GET)
            assert(req.pathSegmentCount == 0)
            assert(req.headerCount == 0)
            assert(req.contentLength == -1)
            assert(req.isChunked == false)
            assert(req.hasQuery == false)
            // Empty path should be readable as empty string
            val path = req.pathAsString
            assert(path == "")
            succeed
        }

        // Test 17: reset() clears all state
        "reset clears all state fields to initial values" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/original".getBytes(StandardCharsets.UTF_8)
            val segBytes  = "original".getBytes(StandardCharsets.UTF_8)
            val nameBytes = "Host".getBytes(StandardCharsets.UTF_8)
            val valBytes  = "example.com".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(2) // PUT
            builder.setChunked(true)
            builder.setKeepAlive(true)
            builder.setHasQuery(true)
            builder.setExpectContinue(true)
            builder.setHasHost(true)
            builder.setContentLength(999)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.addPathSegment(segBytes, 0, segBytes.length)
            builder.addHeader(nameBytes, 0, nameBytes.length, valBytes, 0, valBytes.length)

            builder.reset()

            // After reset, build a minimal request and verify all flags are cleared
            val pathBytes2 = "/after-reset".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0) // GET
            builder.setPath(pathBytes2, 0, pathBytes2.length)
            val req = builder.build()

            assert(req.method == HttpMethod.GET)
            assert(req.isChunked == false)
            assert(req.isKeepAlive == false)
            assert(req.hasQuery == false)
            assert(req.expectContinue == false)
            assert(req.hasHost == false)
            assert(req.contentLength == -1)
            assert(req.pathSegmentCount == 0)
            assert(req.headerCount == 0)
            assert(req.pathAsString == "/after-reset")
            succeed
        }

        // Test 18: reset() allows full reuse — reset → set → build works correctly second time
        "reset allows reuse: second build after reset is independent of first" in {
            val builder = new ParsedRequestBuilder()

            // First build
            builder.setMethod(1) // POST
            val path1 = "/first".getBytes(StandardCharsets.UTF_8)
            builder.setPath(path1, 0, path1.length)
            builder.setKeepAlive(true)
            builder.setContentLength(100)
            val n1 = "Authorization".getBytes(StandardCharsets.UTF_8)
            val v1 = "Bearer token123".getBytes(StandardCharsets.UTF_8)
            builder.addHeader(n1, 0, n1.length, v1, 0, v1.length)
            val seg1 = "first".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg1, 0, seg1.length)
            val req1 = builder.build()

            // Reset and second build
            builder.reset()
            builder.setMethod(4) // DELETE
            val path2 = "/second/path".getBytes(StandardCharsets.UTF_8)
            builder.setPath(path2, 0, path2.length)
            builder.setChunked(true)
            builder.setContentLength(0)
            val n2 = "Content-Type".getBytes(StandardCharsets.UTF_8)
            val v2 = "text/plain".getBytes(StandardCharsets.UTF_8)
            builder.addHeader(n2, 0, n2.length, v2, 0, v2.length)
            val seg2 = "second".getBytes(StandardCharsets.UTF_8)
            val seg3 = "path".getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(seg2, 0, seg2.length)
            builder.addPathSegment(seg3, 0, seg3.length)
            val req2 = builder.build()

            // Verify first request is unaffected
            assert(req1.method == HttpMethod.POST)
            assert(req1.pathAsString == "/first")
            assert(req1.isKeepAlive == true)
            assert(req1.isChunked == false)
            assert(req1.contentLength == 100)
            assert(req1.headerCount == 1)
            assert(req1.headerName(0) == "Authorization")
            assert(req1.headerValue(0) == "Bearer token123")
            assert(req1.pathSegmentCount == 1)
            assert(req1.pathSegmentAsString(0) == "first")

            // Verify second request is correct and independent
            assert(req2.method == HttpMethod.DELETE)
            assert(req2.pathAsString == "/second/path")
            assert(req2.isChunked == true)
            assert(req2.isKeepAlive == false)
            assert(req2.contentLength == 0)
            assert(req2.headerCount == 1)
            assert(req2.headerName(0) == "Content-Type")
            assert(req2.headerValue(0) == "text/plain")
            assert(req2.pathSegmentCount == 2)
            assert(req2.pathSegmentAsString(0) == "second")
            assert(req2.pathSegmentAsString(1) == "path")
            succeed
        }

        // Test 19: setContentLength with positive value — encoded big-endian at offset 2
        "setContentLength encodes positive value big-endian at bytes [2..5]" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setContentLength(0x01020304)
            val req = builder.build()
            // Verify via ParsedRequest.contentLength which reads big-endian int at offset 2
            assert(req.contentLength == 0x01020304)
            succeed
        }

        // Test 20: setContentLength(-1) encodes as 0xFFFFFFFF
        "setContentLength(-1) encodes as 0xFFFFFFFF (all ones, absent sentinel)" in {
            val builder   = new ParsedRequestBuilder()
            val pathBytes = "/".getBytes(StandardCharsets.UTF_8)
            builder.setMethod(0)
            builder.setPath(pathBytes, 0, pathBytes.length)
            builder.setContentLength(-1)
            val req = builder.build()
            // -1 as a signed 32-bit int is 0xFFFFFFFF
            assert(req.contentLength == -1)

            // Also verify the default (no setContentLength call) is also -1
            val builder2 = new ParsedRequestBuilder()
            builder2.setMethod(0)
            builder2.setPath(pathBytes, 0, pathBytes.length)
            val req2 = builder2.build()
            assert(req2.contentLength == -1)
            succeed
        }
    }

end ParsedRequestBuilderTest
