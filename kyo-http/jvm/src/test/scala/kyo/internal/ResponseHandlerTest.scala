package kyo.internal

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kyo.*

class ResponseHandlerTest extends Test:

    import AllowUnsafe.embrace.danger

    // The promise stores HttpResponse directly (no pending effects), so we can safely cast
    private def pollResponse(
        promise: Promise.Unsafe[kyo.HttpResponse[HttpBody.Bytes], Abort[HttpError]]
    ): Maybe[Result[HttpError, kyo.HttpResponse[HttpBody.Bytes]]] =
        promise.poll().map(_.map(_.asInstanceOf[kyo.HttpResponse[HttpBody.Bytes]]))

    "ResponseHandler" - {

        "parses successful response" in {
            val promise = Promise.Unsafe.init[kyo.HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
            val channel = new EmbeddedChannel()
            val handler = new ResponseHandler(promise, channel, "localhost", 8080)
            discard(channel.pipeline().addLast(handler))

            val body    = "hello world"
            val content = Unpooled.wrappedBuffer(body.getBytes("UTF-8"))
            val msg     = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
            discard(msg.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain"))
            discard(msg.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length))

            discard(channel.writeInbound(msg))

            pollResponse(promise) match
                case Present(Result.Success(response)) =>
                    assert(response.status == kyo.HttpResponse.Status.OK)
                    assert(response.bodyText == "hello world")
                    assert(response.header("content-type") == Present("text/plain"))
                case other =>
                    fail(s"Expected completed Success but got $other")
            end match
        }

        "handles channel inactive" in {
            val promise = Promise.Unsafe.init[kyo.HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
            val channel = new EmbeddedChannel()
            val handler = new ResponseHandler(promise, channel, "localhost", 8080)
            discard(channel.pipeline().addLast(handler))

            channel.pipeline().fireChannelInactive()

            pollResponse(promise) match
                case Present(r) => assert(r.isFailure, s"Expected failure but got $r")
                case Absent     => fail("Expected promise to be completed")
        }

        "handles exception" in {
            val promise = Promise.Unsafe.init[kyo.HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
            val channel = new EmbeddedChannel()
            val handler = new ResponseHandler(promise, channel, "localhost", 8080)
            discard(channel.pipeline().addLast(handler))

            channel.pipeline().fireExceptionCaught(new RuntimeException("test error"))

            pollResponse(promise) match
                case Present(r) => assert(r.isFailure, s"Expected failure but got $r")
                case Absent     => fail("Expected promise to be completed")
        }

        "parses response with empty body" in {
            val promise = Promise.Unsafe.init[kyo.HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
            val channel = new EmbeddedChannel()
            val handler = new ResponseHandler(promise, channel, "localhost", 8080)
            discard(channel.pipeline().addLast(handler))

            val msg = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)
            discard(channel.writeInbound(msg))

            pollResponse(promise) match
                case Present(Result.Success(response)) =>
                    assert(response.status == kyo.HttpResponse.Status.NoContent)
                    assert(response.bodyText == "")
                case other =>
                    fail(s"Expected completed Success but got $other")
            end match
        }

        "parses response with multiple headers" in {
            val promise = Promise.Unsafe.init[kyo.HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
            val channel = new EmbeddedChannel()
            val handler = new ResponseHandler(promise, channel, "localhost", 8080)
            discard(channel.pipeline().addLast(handler))

            val msg = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer("ok".getBytes))
            discard(msg.headers().set("X-Custom", "value1"))
            discard(msg.headers().set("X-Another", "value2"))
            discard(channel.writeInbound(msg))

            pollResponse(promise) match
                case Present(Result.Success(response)) =>
                    assert(response.header("X-Custom") == Present("value1"))
                    assert(response.header("X-Another") == Present("value2"))
                case other =>
                    fail(s"Expected completed Success but got $other")
            end match
        }
    }

end ResponseHandlerTest
