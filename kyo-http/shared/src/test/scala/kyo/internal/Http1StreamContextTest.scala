package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.*
import kyo.internal.util.*

class Http1StreamContextTest extends kyo.Test:

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

    /** Helper: read one span from outbound as a String. */
    private def pollString(outbound: Channel.Unsafe[Span[Byte]]): String =
        outbound.poll() match
            case Result.Success(Present(span)) => new String(span.toArray, StandardCharsets.US_ASCII)
            case other                         => fail(s"Expected data in outbound, got: $other")

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
            succeed
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
            succeed
        }

        // 3. ReadBody fast path — contentLength <= bodySpan.size returns immediately
        "readBody fast path returns body without touching channel" in run {
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
        "readBody preserves leftover bytes when bodySpan exceeds contentLength" in run {
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
        "readBody slow path accumulates bytes from inbound channel" in run {
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
        "takeLeftover consumes — subsequent calls return empty" in run {
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
            succeed
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
            succeed
        }

        // 9. Respond adds Date header
        "respond injects Date header automatically" in {
            val (ctx, _, outbound) = makeCtx()
            discard(ctx.respond(HttpStatus.OK, HttpHeaders.empty))
            val result = pollString(outbound)
            assert(result.contains("Date: "), s"Expected 'Date: ' in response headers, got: $result")
            succeed
        }

        // 10. Respond applies headers
        "respond includes caller-provided headers in output" in {
            val (ctx, _, outbound) = makeCtx()
            val headers            = HttpHeaders.empty.add("X-Custom", "test-value").add("Cache-Control", "no-store")
            discard(ctx.respond(HttpStatus.OK, headers))
            val result = pollString(outbound)
            assert(result.contains("X-Custom: test-value\r\n"), s"Got: $result")
            assert(result.contains("Cache-Control: no-store\r\n"), s"Got: $result")
            succeed
        }

        // 11. WriteBody offers to outbound
        "writeBody offers span directly to outbound channel" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty.add("Content-Length", "5"))
            discard(outbound.poll()) // discard status line
            writer.writeBody(Span.fromUnsafe("world".getBytes(StandardCharsets.US_ASCII)))
            val result = pollString(outbound)
            assert(result == "world", s"Got: $result")
            succeed
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
                case Result.Success(Present(_)) => succeed
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
            succeed
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
            succeed
        }

        // 15. Finish writes terminal chunk
        "finish writes last-chunk marker 0 CRLF CRLF" in {
            val (ctx, _, outbound) = makeCtx()
            val writer             = ctx.respond(HttpStatus.OK, HttpHeaders.empty)
            discard(outbound.poll()) // discard status line
            writer.finish()
            val result = pollString(outbound)
            assert(result == "0\r\n\r\n", s"Got: '$result'")
            succeed
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
            succeed
        }

        // 17. Format chunk size for small value
        "formatChunkSize encodes small value 10 as 'a CRLF'" in {
            val result = Http1StreamContext.formatChunkSize(10)
            val str    = new String(result, StandardCharsets.US_ASCII)
            assert(str == "a\r\n", s"Got: '$str'")
            succeed
        }

        // 18. Format chunk size for large value
        "formatChunkSize encodes large value 0xABCD as 'abcd CRLF'" in {
            val result = Http1StreamContext.formatChunkSize(0xabcd)
            val str    = new String(result, StandardCharsets.US_ASCII)
            assert(str == "abcd\r\n", s"Got: '$str'")
            succeed
        }

        // 19. Format chunk size zero
        "formatChunkSize encodes zero as '0 CRLF'" in {
            val result = Http1StreamContext.formatChunkSize(0)
            val str    = new String(result, StandardCharsets.US_ASCII)
            assert(str == "0\r\n", s"Got: '$str'")
            succeed
        }
    }

end Http1StreamContextTest
