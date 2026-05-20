package kyo.internal.server

import kyo.*
import kyo.internal.codec.*

/** Per-connection request/response handle shared between the HTTP parser and dispatch layer.
  *
  * One instance is created per accepted connection and reused across all requests on that connection (keep-alive). The parser populates it
  * with the next request; the dispatch layer reads the request, reads the body, and writes the response. Http1StreamContext is the concrete
  * implementation.
  */
private[kyo] trait StreamContext:
    /** The currently parsed request. Valid between parser callback and request completion. */
    def request: ParsedRequest

    /** Read the full request body, suspending the fiber until all bytes arrive.
      *
      * For Content-Length requests: accumulates exactly contentLength bytes from the inbound channel. For no-body requests (GET, HEAD):
      * returns immediately with an empty span.
      */
    def readBody()(using Frame): Span[Byte] < (Async & Abort[Closed])

    /** Direct access to the inbound channel for streaming body reads (chunked decoding). */
    def bodyChannel: Channel.Unsafe[Span[Byte]]

    /** Begin writing a response. Flushes the status line and headers immediately, then returns a ResponseWriter for streaming the body.
      */
    def respond(status: HttpStatus, headers: HttpHeaders)(using AllowUnsafe): ResponseWriter
end StreamContext
