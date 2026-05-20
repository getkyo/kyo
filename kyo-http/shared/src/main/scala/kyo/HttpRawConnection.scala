package kyo

import kyo.*

/** Bidirectional raw byte connection obtained by upgrading an HTTP request.
  *
  * The HTTP connection is detached from the pool and becomes a raw byte stream. Closed automatically when the enclosing Scope exits.
  *
  * Used for protocols that upgrade HTTP connections to raw streaming (e.g. Docker exec/attach, CONNECT proxies).
  */
final class HttpRawConnection private[kyo] (
    val read: Stream[Span[Byte], Async],
    val write: Span[Byte] => Unit < Async
)
