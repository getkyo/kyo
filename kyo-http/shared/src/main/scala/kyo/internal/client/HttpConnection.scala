package kyo.internal.client

import kyo.internal.http1.*

/** A live HTTP/1.1 connection: the transport-level socket plus the protocol handler.
  *
  * Declared top-level to avoid path-dependent types that would arise from a nested class inside HttpClientBackend. Held by the
  * ConnectionPool and passed to HttpClientBackend methods for send/close/isAlive operations.
  */
final private[kyo] class HttpConnection[Handle](
    val transport: kyo.internal.transport.Connection[Handle],
    val http1: Http1ClientConnection,
    val targetHost: String,
    val targetPort: Int,
    val targetSsl: Boolean,
    val hostHeaderValue: String // pre-computed "host:port" or "host"
)
