package kyo.internal.server

import kyo.*

/** Writes the body of an HTTP response after the status line and headers have been sent.
  *
  * Obtained from StreamContext.respond. Once respond() is called, the wire already has the status + headers; the caller must eventually
  * call writeBody or finish to close the HTTP message.
  */
private[kyo] trait ResponseWriter:
    /** Write the entire non-chunked body in a single offer to the outbound channel. */
    def writeBody(data: Span[Byte])(using AllowUnsafe): Unit

    /** Write one chunk in chunked transfer encoding (hex-size CRLF data CRLF). */
    def writeChunk(data: Span[Byte])(using AllowUnsafe): Unit

    /** Finish the response. For chunked encoding, writes the last-chunk marker (0\r\n\r\n). Must be called even for empty responses to
      * terminate the HTTP message.
      */
    def finish()(using AllowUnsafe): Unit
end ResponseWriter
