package kyo.internal.codec

import kyo.*

/** Parsed HTTP response with status code, headers, and pre-extracted connection metadata.
  *
  * Produced by Http1ResponseParser from raw response bytes. The headers are built with HttpHeaders.parsed, so a lookup decodes only the
  * value it returns.
  *
  * contentLength, isChunked, and isKeepAlive are extracted during parsing so the client connection can decide the body-reading strategy and
  * connection reuse without touching the header index.
  */
final private[kyo] class ParsedResponse(
    val statusCode: Int,
    val headers: HttpHeaders,
    /** Body size from Content-Length header, -1 if absent. */
    val contentLength: Int,
    /** True when Transfer-Encoding: chunked was detected. */
    val isChunked: Boolean,
    /** True when the connection can be reused (HTTP/1.1 default, or explicit Connection: keep-alive). */
    val isKeepAlive: Boolean
)
