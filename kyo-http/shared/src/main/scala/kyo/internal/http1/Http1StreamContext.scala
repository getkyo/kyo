package kyo.internal.http1

import kyo.*
import kyo.internal.codec.*
import kyo.internal.server.*
import kyo.internal.util.*
import scala.annotation.tailrec

/** HTTP/1.1 implementation of StreamContext — one instance per connection, reused across requests.
  *
  * Lifecycle per request:
  *   1. Parser calls setRequest(req, bodySpan) when headers are complete.
  *   2. UnsafeServerDispatch routes the request and invokes the handler via IOTask.
  *   3. Handler calls readBody() or streams bodyChannel for the request body.
  *   4. Handler calls respond(status, headers) to get a ResponseWriter, then writes the body.
  *   5. For keep-alive: takeLeftover() recovers any bytes beyond the body, which are injected back into the parser before the next request
  *      cycle.
  *
  * All response writes go to the outbound Channel.Unsafe via offerOrLog. The headerBuf is shared (connection-scoped) and reset on each
  * respond() call.
  */
final private[kyo] class Http1StreamContext(
    val inbound: Channel.Unsafe[Span[Byte]],
    val outbound: Channel.Unsafe[Span[Byte]],
    headerBuf: GrowableByteBuffer
)(using allow: AllowUnsafe, frame: Frame) extends StreamContext:

    private var _request: ParsedRequest = ParsedRequest.empty
    private var _bodySpan: Span[Byte]   = Span.empty[Byte]
    private var _leftover: Span[Byte]   = Span.empty[Byte]

    /** Called by parser when request is ready. */
    def setRequest(req: ParsedRequest, body: Span[Byte]): Unit =
        _request = req
        _bodySpan = body
        _leftover = Span.empty[Byte]
    end setRequest

    def request: ParsedRequest = _request

    /** Returns the initial body bytes passed by the parser (e.g., chunk framing data for chunked requests, or partial body for
      * Content-Length requests). Consumes the span — subsequent calls return empty.
      */
    def takeBodySpan(): Span[Byte] =
        val result = _bodySpan
        _bodySpan = Span.empty[Byte]
        result
    end takeBodySpan

    /** Returns any leftover bytes after the body that belong to the next request. Called by UnsafeServerDispatch before restarting the
      * parser for keep-alive.
      */
    def takeLeftover(): Span[Byte] =
        val result = _leftover
        _leftover = Span.empty[Byte]
        result
    end takeLeftover

    /** Reads the full request body, accumulating from the inbound channel if needed.
      *
      * Fast path: if all body bytes arrived with the headers (contentLength <= bodySpan.size), returns immediately without touching the
      * channel. Slow path: drains the inbound channel until contentLength bytes are accumulated. Any bytes beyond contentLength are
      * preserved as leftover for the next request.
      */
    def readBody()(using Frame): Span[Byte] < (Async & Abort[Closed]) =
        val contentLength = _request.contentLength
        if contentLength <= 0 then
            Span.empty[Byte]
        else if _bodySpan.size >= contentLength then
            // Fast path: all body bytes already available
            val body = _bodySpan.slice(0, contentLength)
            if _bodySpan.size > contentLength then
                _leftover = _bodySpan.slice(contentLength, _bodySpan.size)
            body
        else
            // Slow path: need more bytes from inbound channel
            val bodyBuf = new GrowableByteBuffer
            // Copy initial body bytes
            if !_bodySpan.isEmpty then
                val arr = _bodySpan.toArray
                bodyBuf.writeBytes(arr, 0, arr.length)
            accumulate(bodyBuf, contentLength)
        end if
    end readBody

    /** Accumulates bytes from the inbound channel until contentLength is reached. Uses channel.safe.take to suspend the Kyo fiber without
      * blocking OS threads.
      */
    private def accumulate(bodyBuf: GrowableByteBuffer, contentLength: Int)(using Frame): Span[Byte] < (Async & Abort[Closed]) =
        if bodyBuf.size >= contentLength then
            // We have enough bytes — extract body and preserve leftover
            val allBytes = bodyBuf.toByteArray
            val body     = Span.fromUnsafe(java.util.Arrays.copyOf(allBytes, contentLength))
            if allBytes.length > contentLength then
                val leftoverArr = new Array[Byte](allBytes.length - contentLength)
                java.lang.System.arraycopy(allBytes, contentLength, leftoverArr, 0, leftoverArr.length)
                _leftover = Span.fromUnsafe(leftoverArr)
            end if
            body
        else
            // Need more data — take from channel (suspends fiber, does NOT block OS thread)
            inbound.safe.take.map { span =>
                val arr = span.toArray
                bodyBuf.writeBytes(arr, 0, arr.length)
                accumulate(bodyBuf, contentLength)
            }

    def bodyChannel: Channel.Unsafe[Span[Byte]] = inbound

    def respond(status: HttpStatus, headers: HttpHeaders)(using AllowUnsafe): ResponseWriter =
        headerBuf.reset()
        val code   = status.code
        val cached = if code >= 0 && code < 600 then Http1StreamContext.statusLineCache(code) else null
        if cached != null then
            headerBuf.writeBytes(cached, 0, cached.length)
        else
            headerBuf.writeBytes(Http1StreamContext.HttpVersionPrefix, 0, Http1StreamContext.HttpVersionPrefix.length)
            headerBuf.writeIntAscii(code)
            headerBuf.writeByte(' ')
            headerBuf.writeAscii(Http1StreamContext.reasonPhrase(status))
            headerBuf.writeBytes(Http1StreamContext.CRLF, 0, Http1StreamContext.CRLF.length)
        end if
        // Inject Date header automatically on every response (RFC 9110 section 6.6.1)
        headerBuf.writeBytes(Http1StreamContext.DatePrefix, 0, Http1StreamContext.DatePrefix.length)
        headerBuf.writeAscii(UnsafeServerDispatch.currentDate())
        headerBuf.writeBytes(Http1StreamContext.CRLF, 0, Http1StreamContext.CRLF.length)
        headers.writeToBuffer(headerBuf)
        headerBuf.writeBytes(Http1StreamContext.CRLF, 0, Http1StreamContext.CRLF.length)
        offerOrLog(Span.fromUnsafe(headerBuf.toByteArray), "Http1StreamContext respond")
        http1ResponseWriter
    end respond

    /** Puts data to outbound channel with backpressure. Uses offer() first for the fast path, falls back to putFiber() when the channel is
      * full to avoid silently dropping data.
      */
    private def offerOrLog(data: Span[Byte], context: String)(using AllowUnsafe): Unit =
        outbound.offer(data) match
            case Result.Success(true)  => () // fast path: channel had space
            case Result.Success(false) =>
                // Channel full — queue via putFiber for backpressure (will complete when space available)
                discard(outbound.putFiber(data))
            case Result.Failure(_: Closed) => () // channel closed, connection shutting down
            case Result.Failure(e) =>
                Log.live.unsafe.error(s"$context: unexpected failure", e)
            case Result.Panic(t) =>
                Log.live.unsafe.error(s"$context: panic", t)

    private val http1ResponseWriter: ResponseWriter = new ResponseWriter:
        def writeBody(data: Span[Byte])(using AllowUnsafe): Unit =
            offerOrLog(data, "Http1StreamContext writeBody")

        def writeChunk(data: Span[Byte])(using AllowUnsafe): Unit =
            // Combine chunk header + data + CRLF into a single offer to reduce channel contention.
            // This avoids 3 separate offers (hex size, data, CRLF) per chunk.
            val hexHeader = Http1StreamContext.formatChunkSize(data.size)
            val combined  = new Array[Byte](hexHeader.length + data.size + 2)
            java.lang.System.arraycopy(hexHeader, 0, combined, 0, hexHeader.length)
            discard(data.copyToArray(combined, hexHeader.length))
            combined(combined.length - 2) = '\r'.toByte
            combined(combined.length - 1) = '\n'.toByte
            offerOrLog(Span.fromUnsafe(combined), "Http1StreamContext writeChunk")
        end writeChunk

        def finish()(using AllowUnsafe): Unit =
            offerOrLog(Span.fromUnsafe(Http1StreamContext.LAST_CHUNK), "Http1StreamContext finish")
    end http1ResponseWriter

end Http1StreamContext

private[kyo] object Http1StreamContext:
    val CRLF: Array[Byte]                      = "\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    val LAST_CHUNK: Array[Byte]                = "0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val HttpVersionPrefix: Array[Byte] = "HTTP/1.1 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val DatePrefix: Array[Byte]        = "Date: ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    /** Returns the standard HTTP reason phrase for common status codes. HttpStatus owns the canonical name but reason phrases are HTTP/1.1
      * wire-format specific, so they live here.
      */
    def reasonPhrase(status: HttpStatus): String =
        status.code match
            case 100 => "Continue"
            case 101 => "Switching Protocols"
            case 200 => "OK"
            case 201 => "Created"
            case 202 => "Accepted"
            case 204 => "No Content"
            case 301 => "Moved Permanently"
            case 302 => "Found"
            case 303 => "See Other"
            case 304 => "Not Modified"
            case 307 => "Temporary Redirect"
            case 308 => "Permanent Redirect"
            case 400 => "Bad Request"
            case 401 => "Unauthorized"
            case 403 => "Forbidden"
            case 404 => "Not Found"
            case 405 => "Method Not Allowed"
            case 408 => "Request Timeout"
            case 409 => "Conflict"
            case 410 => "Gone"
            case 411 => "Length Required"
            case 413 => "Payload Too Large"
            case 414 => "URI Too Long"
            case 415 => "Unsupported Media Type"
            case 417 => "Expectation Failed"
            case 418 => "I'm a Teapot"
            case 422 => "Unprocessable Entity"
            case 429 => "Too Many Requests"
            case 500 => "Internal Server Error"
            case 501 => "Not Implemented"
            case 502 => "Bad Gateway"
            case 503 => "Service Unavailable"
            case 504 => "Gateway Timeout"
            case _   => status.name
    end reasonPhrase

    /** Pre-cached status line bytes for common HTTP status codes. Avoids Int.toString and string concat allocations on every response. */
    val statusLineCache: Array[Array[Byte]] =
        val arr = new Array[Array[Byte]](600)
        for code <- Seq(100, 101, 200, 201, 202, 204, 301, 302, 303, 304, 307, 308,
                400, 401, 403, 404, 405, 408, 409, 410, 411, 413, 414, 415,
                417, 418, 422, 429, 500, 501, 502, 503, 504)
        do
            val line = s"HTTP/1.1 $code ${reasonPhrase(HttpStatus(code))}\r\n"
            arr(code) = line.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        end for
        arr
    end statusLineCache

    private val hexDigits: Array[Byte] = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    /** Formats an integer as a hex chunk-size header ("abc\r\n") without String allocation. */
    def formatChunkSize(size: Int): Array[Byte] =
        // Count hex digits needed
        @tailrec def countDigits(n: Int, acc: Int): Int = if n == 0 then acc else countDigits(n >>> 4, acc + 1)
        val digits                                      = countDigits(size >>> 4, 1)
        val result                                      = new Array[Byte](digits + 2)
        @tailrec def fillDigits(n: Int, i: Int): Unit =
            if i >= 0 then
                result(i) = hexDigits(n & 0xf)
                fillDigits(n >>> 4, i - 1)
        fillDigits(size, digits - 1)
        result(digits) = '\r'.toByte
        result(digits + 1) = '\n'.toByte
        result
    end formatChunkSize

    /** Formats a chunk as a single Span: hex-size CRLF data CRLF */
    def formatChunkSpan(data: Span[Byte]): Span[Byte] =
        val hexHeader = formatChunkSize(data.size)
        val combined  = new Array[Byte](hexHeader.length + data.size + 2)
        java.lang.System.arraycopy(hexHeader, 0, combined, 0, hexHeader.length)
        discard(data.copyToArray(combined, hexHeader.length))
        combined(combined.length - 2) = '\r'.toByte
        combined(combined.length - 1) = '\n'.toByte
        Span.fromUnsafe(combined)
    end formatChunkSpan
end Http1StreamContext
