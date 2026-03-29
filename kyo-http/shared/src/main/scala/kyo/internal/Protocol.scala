package kyo.internal

import kyo.*

/** HTTP version-independent protocol interface.
  *
  * Each HTTP version implements this differently:
  *   - HTTP/1.1 — text request/status lines, text headers, chunked transfer encoding
  *   - HTTP/2 — binary frames, HPACK header compression, multiplexed streams
  *   - HTTP/3 — binary frames, QPACK header compression, over QUIC
  *
  * The server and client backends are parameterized by Protocol. Adding a new HTTP version requires only a new Protocol implementation —
  * zero changes to server, client, WebSocket, or transport code.
  */
trait Protocol:

    /** Read a request from the stream (server side). */
    def readRequest(stream: TransportStream, maxSize: Int)(using
        Frame
    )
        : (HttpMethod, String, HttpHeaders, HttpBody) < (Async & Abort[HttpException])

    /** Write a response head — status line + headers (server side). */
    def writeResponseHead(stream: TransportStream, status: HttpStatus, headers: HttpHeaders)(using
        Frame
    )
        : Unit < Async

    /** Write a request head — method + path + headers (client side). */
    def writeRequestHead(stream: TransportStream, method: HttpMethod, path: String, headers: HttpHeaders)(using
        Frame
    )
        : Unit < Async

    /** Read a response from the stream (client side). */
    def readResponse(stream: TransportStream, maxSize: Int)(using
        Frame
    )
        : (HttpStatus, HttpHeaders, HttpBody) < (Async & Abort[HttpException])

    /** Write a fixed-length body. */
    def writeBody(stream: TransportStream, data: Span[Byte])(using
        Frame
    )
        : Unit < Async

    /** Write a streaming body (chunked for HTTP/1.1, DATA frames for HTTP/2). */
    def writeStreamingBody(stream: TransportStream, body: Stream[Span[Byte], Async])(using
        Frame
    )
        : Unit < Async

    /** Whether the request indicates keep-alive. HTTP/1.1: checks Connection header. HTTP/2+: always true. */
    def isKeepAlive(headers: HttpHeaders): Boolean

end Protocol
