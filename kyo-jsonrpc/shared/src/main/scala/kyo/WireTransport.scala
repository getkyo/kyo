// PUBLIC byte-level user-facing transport seam consumed by JsonRpcTransport.fromWire and the byte-stream adapter set
package kyo

import kyo.Stream

trait WireTransport:
    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end WireTransport

object WireTransport:
    /** No-op wire transport for tests: send drops bytes, incoming is empty, close is no-op. */
    val empty: WireTransport = new WireTransport:
        def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) = Kyo.unit
        def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]     = Stream.empty
        def close(using Frame): Unit < Async                                      = Kyo.unit
end WireTransport
