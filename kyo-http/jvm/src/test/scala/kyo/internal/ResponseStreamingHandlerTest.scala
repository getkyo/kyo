package kyo.internal

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kyo.*

class ResponseStreamingHandlerTest extends Test:

    import AllowUnsafe.embrace.danger

    // The promise stores StreamingHeaders directly (no pending effects), so we can safely cast
    private def pollHeaders(
        promise: Promise.Unsafe[StreamingHeaders, Abort[HttpError]]
    ): Maybe[Result[HttpError, StreamingHeaders]] =
        promise.poll().map(_.map(_.asInstanceOf[StreamingHeaders]))

    "ResponseStreamingHandler" - {

        "completes header promise with status and headers" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            discard(response.headers().set("Content-Type", "text/plain"))
            discard(channel.writeInbound(response))

            pollHeaders(headerPromise) match
                case Present(Result.Success(headers)) =>
                    assert(headers.status == kyo.HttpStatus.OK)
                    assert(headers.headers.exists((k, v) => k == "Content-Type" && v == "text/plain"))
                case other =>
                    fail(s"Expected completed Success but got $other")
            end match
        }

        "streams body chunks to byte channel" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            // Send headers first
            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            discard(channel.writeInbound(response))

            // Send body chunks
            val chunk1 = new DefaultHttpContent(Unpooled.wrappedBuffer("hello ".getBytes("UTF-8")))
            val chunk2 = new DefaultLastHttpContent(Unpooled.wrappedBuffer("world".getBytes("UTF-8")))
            discard(channel.writeInbound(chunk1))
            discard(channel.writeInbound(chunk2))

            // Read from byte channel
            byteChannel.poll() match
                case Result.Success(Present(s)) =>
                    assert(new String(s.toArray, "UTF-8") == "hello ")
                case other =>
                    fail(s"Expected first chunk but got $other")
            end match

            byteChannel.poll() match
                case Result.Success(Present(s)) =>
                    assert(new String(s.toArray, "UTF-8") == "world")
                case other =>
                    fail(s"Expected second chunk but got $other")
            end match
        }

        "delivers error status through headers" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            discard(channel.writeInbound(response))

            pollHeaders(headerPromise) match
                case Present(Result.Success(headers)) =>
                    assert(headers.status == kyo.HttpStatus.InternalServerError)
                case other =>
                    fail(s"Expected completed Success with error status but got $other")
            end match
        }

        "error status delivers body chunks" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST)
            discard(channel.writeInbound(response))

            // Send error body
            val body = new DefaultLastHttpContent(Unpooled.wrappedBuffer("{\"error\":\"invalid\"}".getBytes("UTF-8")))
            discard(channel.writeInbound(body))

            // The error body should be available through the byte channel
            pollHeaders(headerPromise) match
                case Present(Result.Success(headers)) =>
                    assert(headers.status == kyo.HttpStatus.BadRequest)
                    byteChannel.poll() match
                        case Result.Success(Present(s)) =>
                            assert(new String(s.toArray, "UTF-8") == "{\"error\":\"invalid\"}")
                        case other =>
                            fail(s"Expected error body chunk but got $other")
                    end match
                case other =>
                    fail(s"Expected headers with error status but got $other")
            end match
        }

        "closes byte channel on last content" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            discard(channel.writeInbound(response))

            val lastContent = LastHttpContent.EMPTY_LAST_CONTENT
            discard(channel.writeInbound(lastContent))

            // Channel should be closed after last content
            assert(byteChannel.closed())
        }

        "handles channel inactive" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            channel.pipeline().fireChannelInactive()

            pollHeaders(headerPromise) match
                case Present(r) => assert(r.isFailure, s"Expected failure for channel inactive but got $r")
                case Absent     => fail("Expected promise to be completed")
        }

        "handles exception" in {
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val handler       = new ResponseStreamingHandler(headerPromise, byteChannel, "localhost", 8080)
            val channel       = new EmbeddedChannel(handler)

            channel.pipeline().fireExceptionCaught(new RuntimeException("connection reset"))

            pollHeaders(headerPromise) match
                case Present(r) => assert(r.isFailure, s"Expected failure for exception but got $r")
                case Absent     => fail("Expected promise to be completed")
        }
    }

end ResponseStreamingHandlerTest
