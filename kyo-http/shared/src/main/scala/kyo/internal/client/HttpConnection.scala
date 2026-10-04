package kyo.internal.client

import kyo.*
import kyo.internal.http1.*

/** A live HTTP/1.1 connection: the transport-level socket plus the protocol handler.
  *
  * Declared top-level to avoid path-dependent types that would arise from a nested class inside HttpClientBackend. Held by the
  * ConnectionPool and passed to HttpClientBackend methods for send/close/isAlive operations.
  */
final private[kyo] class HttpConnection(
    val transport: kyo.net.Connection,
    val http1: Http1ClientConnection,
    val targetHost: String,
    val targetPort: Int,
    val targetSsl: Boolean,
    val hostHeaderValue: String // pre-computed "host:port" or "host"
):
    // Written before the transport closes, so a reader that sees the inbound stream end also sees why.
    @volatile private var _closedLocally: Boolean = false

    /** Whether the client closed this connection itself, so a body framed by the close ended where the client cut it. */
    def closedLocally: Boolean = _closedLocally

    /** Closes the connection from the client's side. */
    def close()(using AllowUnsafe, Frame): Unit =
        _closedLocally = true
        http1.close()
        transport.close()
    end close
end HttpConnection
