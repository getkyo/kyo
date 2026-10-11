package kyo.internal.transport

import kyo.*

/** Streaming I/O interface over a transport connection.
  *
  * Abstracts over Connection channels so that WebSocketCodec and other protocol layers can work without depending on the transport-level
  * channel types. Concrete implementations: ChannelBackedStream (inbound/outbound Channel.Unsafe pair) and ConnectionBackedStream (wraps a
  * full Connection).
  *
  * Note: write does not expose Abort[Closed] — implementations absorb it silently since connection shutdown is expected and the caller
  * cannot meaningfully recover.
  */
private[kyo] trait TransportStream:
    /** Stream of raw bytes received from the remote end. Completes when the connection closes. */
    def read(using Frame): Stream[Span[Byte], Async]

    /** Write raw bytes to the remote end. Silently ignores Closed, per the type contract above.
      *
      * The bytes of one write reach the peer contiguous, never interleaved with those of a concurrent write. WebSocketCodec writes each
      * frame as one write and relies on this: a session's writer, its auto-Pong and its keep-alive Ping share the stream without a lock.
      */
    def write(data: Span[Byte])(using Frame): Unit < Async
end TransportStream
