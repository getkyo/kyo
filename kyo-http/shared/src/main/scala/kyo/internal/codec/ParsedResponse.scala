package kyo.internal.codec

import kyo.*

/** Parsed HTTP response with status code, packed headers, and pre-extracted connection metadata.
  *
  * Produced by Http1ResponseParser from raw response bytes. The headers field is stored as a packed byte array (HttpHeaders.fromPacked
  * format), enabling zero-alloc lookups until the caller needs individual String values.
  *
  * contentLength, isChunked, and isKeepAlive are extracted during parsing so the client connection can decide the body-reading strategy and
  * connection reuse without touching the header index.
  */
final private[kyo] class ParsedResponse(
    val statusCode: Int,
    val packedHeaders: Array[Byte],
    /** Body size from Content-Length header, -1 if absent. */
    val contentLength: Int,
    /** True when Transfer-Encoding: chunked was detected. */
    val isChunked: Boolean,
    /** True when the connection can be reused (HTTP/1.1 default, or explicit Connection: keep-alive). */
    val isKeepAlive: Boolean
):
    /** Wraps the packed header bytes as an HttpHeaders view (zero-alloc until individual lookup). */
    def headers: HttpHeaders = HttpHeaders.fromPacked(packedHeaders)
end ParsedResponse
