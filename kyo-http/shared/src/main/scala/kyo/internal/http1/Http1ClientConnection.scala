package kyo.internal.http1

import kyo.*
import kyo.internal.codec.*
import kyo.internal.util.*
import kyo.scheduler.IOPromise

/** HTTP/1.1 client connection that sends requests and receives responses using the unsafe channel-backed architecture.
  *
  * Connection-scoped, reusable across sequential requests (keep-alive). Uses a pre-allocated response promise reused via
  * `becomeAvailable()` — no CHM, no ID correlation needed for HTTP/1.1's sequential request/response model.
  *
  * The parser runs on the inbound channel and completes the response promise when headers arrive. The send method serializes request
  * headers via a connection-scoped GrowableByteBuffer and offers them to the outbound channel.
  *
  * Allocation per request: 1 byte[] (serialized request headers) + 1 byte[] (parsed response headers). The GrowableByteBuffer and
  * ParsedResponse builder state are reused.
  */
final private[kyo] class Http1ClientConnection(
    inbound: Channel.Unsafe[Span[Byte]],
    outbound: Channel.Unsafe[Span[Byte]],
    headerBuf: GrowableByteBuffer,
    maxHeaderSize: Int = 65536
)(using AllowUnsafe, Frame):

    /** Reusable response promise that exposes the protected `becomeAvailable` for reuse across requests. */
    private class ResponsePromise extends IOPromise[Nothing, ParsedResponse]:
        def resetForReuse(): Boolean = becomeAvailable()
    end ResponsePromise

    // Pre-allocated response promise — reused across requests
    private val responsePromise           = new ResponsePromise
    private var _lastBodySpan: Span[Byte] = Span.empty[Byte]

    // Pre-allocated chunked decoder state — reset and reused per chunked response
    private val decoderState = new ChunkedBodyDecoder.DecoderState

    // Parser on inbound channel — parses response headers, completes responsePromise
    private val parser = new Http1ResponseParser(
        inbound,
        maxHeaderSize = maxHeaderSize,
        onResponseParsed = (response, bodySpan) =>
            _lastBodySpan = bodySpan
            responsePromise.completeDiscard(Result.succeed(response))
        ,
        onClosed = () =>
            responsePromise.completeDiscard(Result.panic(new java.io.IOException("connection closed")))
    )

    /** Sends an HTTP request and returns a fiber that completes when response headers are parsed.
      *
      * Serializes the request line and headers to bytes via the connection-scoped GrowableByteBuffer, offers them to the outbound channel,
      * then resets the response promise for this request cycle. The parser will complete the promise when the response arrives.
      *
      * For requests with a body, the body bytes are offered as a separate chunk after the headers.
      */
    def send(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders,
        body: Span[Byte]
    )(using AllowUnsafe): Fiber.Unsafe[ParsedResponse, Abort[Nothing]] =
        // Cross opaque boundary: IOPromise[Nothing, ParsedResponse] is the runtime
        // representation of Fiber.Unsafe[ParsedResponse, Abort[Nothing]].
        sendDirect(method, path, headers, body, "", contentLength = -1, chunked = false)
            .asInstanceOf[Fiber.Unsafe[ParsedResponse, Abort[Nothing]]]

    /** Returns the underlying IOPromise directly for internal backend use. IOPromise.onComplete gives Result[Nothing, ParsedResponse] — no
      * `< S` wrapper.
      *
      * @param hostHeader
      *   pre-computed host header value (e.g. "example.com" or "example.com:8080"). Written as a "Host" header unless the caller's
      *   `headers` already contain a "Host" entry. Pass empty string to skip.
      */
    private[kyo] def sendDirect(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders,
        body: Span[Byte],
        hostHeader: String,
        contentLength: Int,
        chunked: Boolean
    )(using AllowUnsafe): IOPromise[Nothing, ParsedResponse] =
        import Http1ClientConnection.*
        // Serialize request headers to bytes
        headerBuf.reset()
        headerBuf.writeAscii(method.name)
        headerBuf.writeByte(' ')
        headerBuf.writeAscii(path)
        headerBuf.writeBytes(HttpVersion, 0, HttpVersion.length)
        // Write Host header first if caller didn't provide one
        if hostHeader.nonEmpty && !headers.contains("Host") then
            headerBuf.writeBytes(HostPrefix, 0, HostPrefix.length)
            headerBuf.writeAscii(hostHeader)
            headerBuf.writeBytes(CrLf, 0, CrLf.length)
        end if
        headers.writeToBuffer(headerBuf)
        // Write Content-Length or Transfer-Encoding if specified
        if contentLength >= 0 then
            headerBuf.writeBytes(ContentLengthPrefix, 0, ContentLengthPrefix.length)
            headerBuf.writeIntAscii(contentLength)
            headerBuf.writeBytes(CrLf, 0, CrLf.length)
        else if chunked then
            headerBuf.writeBytes(TransferEncodingChunked, 0, TransferEncodingChunked.length)
        end if
        headerBuf.writeBytes(CrLf, 0, CrLf.length)

        // Offer serialized headers to outbound channel (1 byte[] alloc)
        offerOrLog(Span.fromUnsafe(headerBuf.toByteArray), "Http1ClientConnection send headers")

        // Offer body if present
        if body.nonEmpty then
            offerOrLog(body, "Http1ClientConnection send body")

        // Reset promise for this request cycle and start parser
        discard(responsePromise.resetForReuse())
        _lastBodySpan = Span.empty[Byte]
        parser.reset()
        parser.start()

        responsePromise
    end sendDirect

    /** Returns the body bytes that were available in the same chunk as the response headers. */
    def lastBodySpan(using AllowUnsafe): Span[Byte] = _lastBodySpan

    /** The inbound channel for reading remaining body bytes (for streaming or large bodies). */
    def bodyChannel: Channel.Unsafe[Span[Byte]] = inbound

    /** Resets and returns the connection-scoped DecoderState for reuse on a new chunked response. */
    def chunkedDecoderState: ChunkedBodyDecoder.DecoderState =
        decoderState.reset()
        decoderState
    end chunkedDecoderState

    /** Offers data to outbound channel with backpressure. Uses offer() for the fast path (channel has space), falls back to putFiber() when
      * the channel is full to avoid silently dropping request data.
      */
    private def offerOrLog(data: Span[Byte], context: String)(using AllowUnsafe): Unit =
        outbound.offer(data) match
            case Result.Success(true)  => () // fast path: channel had space
            case Result.Success(false) =>
                // Channel full — queue via putFiber for backpressure (will complete when space available)
                discard(outbound.putFiber(data))
            case Result.Failure(_: Closed) => () // connection shutting down
            case Result.Panic(t) =>
                Log.live.unsafe.error(s"$context: panic", t)

    /** Close the connection. Fails any pending response promise so waiting fibers unblock. */
    def close()(using AllowUnsafe): Unit =
        responsePromise.completeDiscard(
            Result.panic(new java.io.IOException("connection closed"))
        )

end Http1ClientConnection

private[kyo] object Http1ClientConnection:

    private val HttpVersion             = " HTTP/1.1\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val ContentLengthPrefix     = "Content-Length: ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val TransferEncodingChunked = "Transfer-Encoding: chunked\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val HostPrefix              = "Host: ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val CrLf                    = "\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    /** Creates a new Http1ClientConnection with a fresh GrowableByteBuffer. */
    def init(
        inbound: Channel.Unsafe[Span[Byte]],
        outbound: Channel.Unsafe[Span[Byte]],
        maxHeaderSize: Int = 65536
    )(using AllowUnsafe, Frame): Http1ClientConnection =
        new Http1ClientConnection(inbound, outbound, new GrowableByteBuffer, maxHeaderSize)

end Http1ClientConnection
