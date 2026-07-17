package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.*
import kyo.internal.util.*
import kyo.net.internal.util.GrowableByteBuffer

class Http1StreamContextTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    /** Helper: build a minimal ParsedRequest with contentLength. */
    private def makeRequest(contentLength: Int): ParsedRequest =
        val builder = new ParsedRequestBuilder
        builder.setMethod(0) // GET
        builder.setPath("/test".getBytes(StandardCharsets.US_ASCII), 0, 5)
        builder.setContentLength(contentLength)
        builder.setKeepAlive(true)
        builder.build()
    end makeRequest

    /** Helper: create a context with fresh channels and a header buffer. */
    private def makeCtx(): (Http1StreamContext, Channel.Unsafe[Span[Byte]], Channel.Unsafe[Span[Byte]]) =
        val inbound   = Channel.Unsafe.init[Span[Byte]](16)
        val outbound  = Channel.Unsafe.init[Span[Byte]](16)
        val headerBuf = new GrowableByteBuffer()
        val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)
        (ctx, inbound, outbound)
    end makeCtx

    /** Helper: read one span from outbound as raw bytes. */
    private def pollBytes(outbound: Channel.Unsafe[Span[Byte]])(using kyo.test.AssertScope): Array[Byte] =
        outbound.poll() match
            case Result.Success(Present(span)) => span.toArray
            case other                         => fail(s"Expected data in outbound, got: $other")

    /** Helper: read one span from outbound as a String. */
    private def pollString(outbound: Channel.Unsafe[Span[Byte]])(using kyo.test.AssertScope): String =
        new String(pollBytes(outbound), StandardCharsets.US_ASCII)

    "Http1StreamContext" - {

        // 1. Set request and body span
        "setRequest stores request and initial body bytes" in {
            val (ctx, _, _) = makeCtx()
            val req         = makeRequest(5)
            val bodyBytes   = "hello".getBytes(StandardCharsets.US_ASCII)
            ctx.setRequest(req, Span.fromUnsafe(bodyBytes))
            assert(ctx.request == req)
            val span = ctx.takeBodySpan()
            assert(new String(span.toArray, StandardCharsets.US_ASCII) == "hello")
        }

        // 2. Take body span consumes bytes
        "takeBodySpan consumes — subsequent calls return empty" in {
            val (ctx, _, _) = makeCtx()
            val req         = makeRequest(3)
            ctx.setRequest(req, Span.fromUnsafe("abc".getBytes(StandardCharsets.US_ASCII)))
            val first  = ctx.takeBodySpan()
            val second = ctx.takeBodySpan()
            assert(new String(first.toArray, StandardCharsets.US_ASCII) == "abc")
            assert(second.isEmpty)
        }

        // 3. ReadBody fast path — contentLength <= bodySpan.size returns immediately
        "readBody fast path returns body without touching channel" in {
            val (ctx, inbound, _) = makeCtx()
            val req               = makeRequest(5)
            val bodyBytes         = "hello".getBytes(StandardCharsets.US_ASCII)
            ctx.setRequest(req, Span.fromUnsafe(bodyBytes))
            // Close the inbound channel — fast path should not touch it
            discard(inbound.close())
            ctx.readBody().map { body =>
                assert(new String(body.toArray, StandardCharsets.US_ASCII) == "hello")
            }
        }

        // 4. ReadBody with leftover bytes — contentLength < bodySpan.size → leftover preserved
        "readBody preserves leftover bytes when bodySpan exceeds contentLength" in {
            val (ctx, _, _) = makeCtx()
            // contentLength = 5, but bodySpan has 10 bytes → 5 leftover
            val req       = makeRequest(5)
            val bodyBytes = "helloEXTRA".getBytes(StandardCharsets.US_ASCII)
            ctx.setRequest(req, Span.fromUnsafe(bodyBytes))
            ctx.readBody().map { body =>
                assert(new String(body.toArray, StandardCharsets.US_ASCII) == "hello")
                val leftover = ctx.takeLeftover()
                assert(new String(leftover.toArray, StandardCharsets.US_ASCII) == "EXTRA")
            }
        }

        // 5. ReadBody slow path — accumulates from inbound channel until contentLength
        "readBody slow path accumulates bytes from inbound channel" in {
            val (ctx, inbound, _) = makeCtx()
            val req               = makeRequest(10)
            // Seed only 3 bytes as initial bodySpan — not enough
            ctx.setRequest(req, Span.fromUnsafe("abc".getBytes(StandardCharsets.US_ASCII)))
            // Offer remaining bytes to inbound channel
            discard(inbound.offer(Span.fromUnsafe("defghij".getBytes(StandardCharsets.US_ASCII))))
            ctx.readBody().map { body =>
                assert(body.size == 10)
                assert(new String(body.toArray, StandardCharsets.US_ASCII) == "abcdefghij")
            }
        }

        // 6. Take leftover consumes bytes
        "takeLeftover consumes — subsequent calls return empty" in {
            val (ctx, _, _) = makeCtx()
            val req         = makeRequest(3)
            ctx.setRequest(req, Span.fromUnsafe("abcXY".getBytes(StandardCharsets.US_ASCII)))
            ctx.readBody().map { _ =>
                val first  = ctx.takeLeftover()
                val second = ctx.takeLeftover()
                assert(new String(first.toArray, StandardCharsets.US_ASCII) == "XY")
                assert(second.isEmpty)
            }
        }

        // 7. Respond writes status line
        "respond writes HTTP/1.1 status line to outbound channel" in {
            val (ctx, _, outbound) = makeCtx()
            discard(ctx.respond(HttpStatus.OK, HttpHeaders.empty))
            val result = pollString(outbound)
            assert(result.startsWith("HTTP/1.1 200 OK\r\n"), s"Got: $result")
        }

        // 8. Respond caches common status lines
        "respond uses cached status line for common codes" in {
            // Verify that status codes in the cache produce the same bytes as an
            // uncached custom status with the same code — they should be equal.
            val cached = Http1StreamContext.statusLineCache(200)
            assert(cached != null, "Status 200 should be cached")
            val expected = "HTTP/1.1 200 OK\r\n"
            assert(
                new String(cached, StandardCharsets.US_ASCII) == expected,
                s"Cache entry: '${new String(cached, StandardCharsets.US_ASCII)}'"
            )
            ()
        }

        // 9. Respond adds Date header
        "respond injects Date header automatically" in {
            val (ctx, _, outbound) = makeCtx()
            discard(ctx.respond(HttpStatus.OK, HttpHeaders.empty))
            val result = pollString(outbound)
            assert(result.contains("Date: "), s"Expected 'Date: ' in response headers, got: $result")
        }

        // 10. Respond applies headers
        "respond includes caller-provided headers in output" in {
            val (ctx, _, outbound) = makeCtx()
            val headers            = HttpHeaders.empty.add("X-Custom", "test-value").add("Cache-Control", "no-store")
            discard(ctx.respond(HttpStatus.OK, headers))
            val result = pollString(outbound)
            assert(result.contains("X-Custom: test-value\r\n"), s"Got: $result")
            assert(result.contains("Cache-Control: no-store\r\n"), s"Got: $result")
        }

        // 11. WriteBody offers to outbound
        "writeBody offers span directly to outbound channel" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("Content-Length", "5"))
            discard(outbound.poll()) // discard status line
            writer.writeBody(Span.fromUnsafe("world".getBytes(StandardCharsets.US_ASCII)))
            val result = pollString(outbound)
            assert(result == "world", s"Got: $result")
        }

        // 12. WriteBody handles full channel — offer returns false → putFiber
        "writeBody falls back to putFiber when channel is full" in {
            // Create a capacity-1 outbound channel; fill it, then try another write
            val inbound   = Channel.Unsafe.init[Span[Byte]](4)
            val outbound  = Channel.Unsafe.init[Span[Byte]](1)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            // Fill the channel completely with the respond call
            val writer = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("Content-Length", "5"))
            // Channel is now full (capacity 1, 1 item in it from respond)

            // writeBody should not throw/panic even when channel is full — it uses putFiber
            writer.writeBody(Span.fromUnsafe("extra".getBytes(StandardCharsets.US_ASCII)))

            // Drain what we can — at least the headers should be there
            outbound.poll() match
                case Result.Success(Present(_)) => succeed("headers are present in outbound after write")
                case other                      => fail(s"Expected headers in outbound, got: $other")
            end match
        }

        // 13. WriteChunk formats with hex size
        "writeChunk formats chunk with hex size CRLF data CRLF" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("Transfer-Encoding", "chunked"))
            discard(outbound.poll()) // discard status line
            val data = "Hello, World!".getBytes(StandardCharsets.US_ASCII) // 13 bytes = 0xd
            writer.writeChunk(Span.fromUnsafe(data))
            val result = pollString(outbound)
            // 13 = 0xd hex
            assert(result == "d\r\nHello, World!\r\n", s"Got: '$result'")
        }

        // 14. WriteChunk coalesces data — single offer, not multiple
        "writeChunk coalesces hex header, data and CRLF into single channel offer" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("Transfer-Encoding", "chunked"))
            discard(outbound.poll()) // discard status line
            writer.writeChunk(Span.fromUnsafe("abc".getBytes(StandardCharsets.US_ASCII)))
            // There should be exactly ONE offer in the channel (not three separate ones)
            val first  = outbound.poll()
            val second = outbound.poll()
            assert(first.isSuccess && first.getOrThrow.isDefined, "Expected one offer for chunk")
            assert(
                second.isSuccess && second.getOrThrow.isEmpty,
                s"Expected no second offer — chunk should be a single coalesced write, but got a second item"
            )
            ()
        }

        // 15. Finish writes terminal chunk
        "finish writes last-chunk marker 0 CRLF CRLF" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty)
            discard(outbound.poll()) // discard status line
            writer.finish()
            val result = pollString(outbound)
            assert(result == "0\r\n\r\n", s"Got: '$result'")
        }

        // 16. Header buffer reset between responses
        "headerBuf is reset on each respond call — no stale headers from previous response" in {
            val inbound   = Channel.Unsafe.init[Span[Byte]](32)
            val outbound  = Channel.Unsafe.init[Span[Byte]](32)
            val headerBuf = new GrowableByteBuffer()
            val ctx       = new Http1StreamContext(inbound, outbound, headerBuf)

            // First response with a custom header
            val h1 = HttpHeaders.empty.add("X-First", "first-value")
            discard(ctx.respond(HttpStatus.OK, h1))
            val first = pollString(outbound)
            assert(first.contains("X-First: first-value\r\n"), s"First response missing custom header: $first")

            // Second response with a different custom header — must NOT contain X-First
            val h2 = HttpHeaders.empty.add("X-Second", "second-value")
            discard(ctx.respond(HttpStatus.Created, h2))
            val second = pollString(outbound)
            assert(second.contains("HTTP/1.1 201 Created\r\n"), s"Second response wrong status: $second")
            assert(second.contains("X-Second: second-value\r\n"), s"Second response missing X-Second: $second")
            assert(!second.contains("X-First"), s"Second response must not contain X-First header (stale data): $second")
        }

        // 17. Format chunk size for small value
        "formatChunkSize encodes small value 10 as 'a CRLF'" in {
            val result = Http1StreamContext.formatChunkSize(10)
            val str    = new String(result, StandardCharsets.US_ASCII)
            assert(str == "a\r\n", s"Got: '$str'")
        }

        // 18. Format chunk size for large value
        "formatChunkSize encodes large value 0xABCD as 'abcd CRLF'" in {
            val result = Http1StreamContext.formatChunkSize(0xabcd)
            val str    = new String(result, StandardCharsets.US_ASCII)
            assert(str == "abcd\r\n", s"Got: '$str'")
        }

        // 19. Format chunk size zero
        "formatChunkSize encodes zero as '0 CRLF'" in {
            val result = Http1StreamContext.formatChunkSize(0)
            val str    = new String(result, StandardCharsets.US_ASCII)
            assert(str == "0\r\n", s"Got: '$str'")
        }

        // 20. A CRLF in a response header value must not become a second header line.
        // This is the response-splitting vector: a handler echoing a peer-supplied trace id reaches this
        // exact call. A serializer that writes the value verbatim puts "X-Admin: true" on the wire as a
        // header the handler never set, and a recipient reads it as one.
        "respond does not split the response when a header value carries CRLF" in {
            val (ctx, _, outbound) = makeCtx()
            val headers            = HttpHeaders.empty.add("X-Trace", "bar\r\nX-Admin: true")
            discard(ctx.respond(HttpStatus.OK, headers))
            val result = pollString(outbound)
            assert(
                !result.contains("X-Admin"),
                s"the CRLF-bearing value was written verbatim and injected a header line: $result"
            )
            assert(
                result.startsWith("HTTP/1.1 500 Internal Server Error\r\n"),
                s"an unwritable field must fail the response closed, got: $result"
            )
            assert(result.contains("Content-Length: 0\r\n"), s"the 500 must declare an empty body, got: $result")
        }

        // 21. The substituted 500 declares Content-Length: 0, so the handler's body must not follow it:
        // writeBody would append bytes past the declared length and finish() would append the chunked
        // last-chunk marker, either of which desynchronizes the connection for the next request.
        "the 500 substitution discards the handler's body so the framing stays intact" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("X-Trace", "bar\r\nX-Admin: true"))
            discard(pollBytes(outbound)) // the substituted 500 head
            writer.writeBody(Span.fromUnsafe("handler body".getBytes(StandardCharsets.US_ASCII)))
            writer.writeChunk(Span.fromUnsafe("chunk".getBytes(StandardCharsets.US_ASCII)))
            writer.finish()
            outbound.poll() match
                case Result.Success(Absent) => succeed("nothing followed the 500 head")
                case Result.Success(Present(span)) =>
                    fail(s"the discarded response wrote '${new String(span.toArray, StandardCharsets.US_ASCII)}' after the 500 head")
                case other => fail(s"Expected an empty outbound, got: $other")
            end match
        }

        // 22. The over-strictness guard, and the reason the fix is not "reject non-ASCII". A field value may
        // carry obs-text (%x80-FF) per RFC 9110 section 5.5, so "café" is legal HTTP and must reach the wire.
        // A rejection here would hand a peer a remote lever to 500 every response a handler echoes.
        "respond writes a non-ASCII header value as UTF-8 octets" in {
            val (ctx, _, outbound) = makeCtx()
            discard(ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("X-Trace", "café")))
            val bytes = pollBytes(outbound)
            val text  = new String(bytes, StandardCharsets.UTF_8)
            assert(text.startsWith("HTTP/1.1 200 OK\r\n"), s"a legal obs-text value must not fail the response: $text")
            assert(text.contains("X-Trace: café\r\n"), s"Got: $text")
            // Spelled out at the octet level: 'é' is the two-byte sequence C3 A9. A writer that narrowed each
            // char to its low byte would put the single byte E9 here and corrupt the value.
            val expected = "X-Trace: caf".getBytes(StandardCharsets.US_ASCII).toSeq ++ Seq[Byte](0xc3.toByte, 0xa9.toByte)
            assert(bytes.toSeq.containsSlice(expected), "the value must reach the wire as UTF-8 octets, not a narrowed byte")
        }

        // 23. A field name is a token (RFC 9110 section 5.6.2), and a space is not a tchar: "X Trace: v"
        // reads as a name "X" with a value "Trace: v" to a recipient that splits on the first colon.
        "respond fails closed when a header name is not a token" in {
            val (ctx, _, outbound) = makeCtx()
            discard(ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("X Trace", "value")))
            val result = pollString(outbound)
            assert(!result.contains("X Trace"), s"a non-token name must not reach the wire: $result")
            assert(
                result.startsWith("HTTP/1.1 500 Internal Server Error\r\n"),
                s"a non-token name must fail the response closed, got: $result"
            )
        }
    }

end Http1StreamContextTest
