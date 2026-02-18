package kyo.internal

import kyo.*

/** Captured response headers from a streaming response. */
private[kyo] case class StreamingHeaders(
    status: HttpStatus,
    headers: HttpHeaders
)
