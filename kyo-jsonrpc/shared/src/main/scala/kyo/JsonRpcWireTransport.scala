package kyo

import kyo.Stream

/** Byte-level transport abstraction for carrying framed JSON-RPC messages over a byte stream.
  *
  * `JsonRpcWireTransport` is the lower-level seam below [[JsonRpcTransport]]. Implement this trait to
  * integrate a custom byte-stream connection (e.g., a TCP socket, a pipe, or a test double).
  * Pass the implementation to [[JsonRpcTransport.fromWire]] together with a [[JsonRpcFramer]] to
  * produce an envelope-level `JsonRpcTransport`.
  *
  * A no-op [[JsonRpcWireTransport.empty]] is provided for tests that do not need real I/O.
  *
  * @see [[JsonRpcTransport.fromWire]]
  * @see [[JsonRpcFramer]]
  */
trait JsonRpcWireTransport:
    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end JsonRpcWireTransport

object JsonRpcWireTransport:
    /** No-op wire transport for tests: send drops bytes, incoming is empty, close is no-op. */
    val empty: JsonRpcWireTransport = new JsonRpcWireTransport:
        def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) = Kyo.unit
        def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]     = Stream.empty
        def close(using Frame): Unit < Async                                      = Kyo.unit
end JsonRpcWireTransport
