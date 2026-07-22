package kyo.internal.transport

import kyo.*

/** Adapts a kyo-net [[kyo.net.Connection]] to the [[JsonRpcWireTransport]] byte seam.
  *
  * `send` copies the caller-owned chunk once into a fresh array and enqueues it on the connection's outbound channel; `incoming` streams the
  * inbound channel until it closes (peer EOF or local close), emitting one chunk per received span; `close` closes the connection (kyo-net flushes
  * queued outbound spans before releasing the socket). This is the single adapter every kyo-net-backed JSON-RPC transport (Unix domain, stdio)
  * sits on.
  */
final private[kyo] class ConnectionWireTransport(conn: kyo.net.Connection) extends JsonRpcWireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        // Chunk.toArray copies the caller-owned chunk; Span.fromUnsafe wraps that fresh array without a second copy. A put on a closed
        // connection aborts Closed, which is exactly send's contract.
        conn.outbound.safe.put(Span.fromUnsafe(bytes.toArray))

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        // chunkSize = 1: every received span is pushed downstream immediately. With the default chunkSize, unfold batches emissions and a
        // held-open connection (an LSP/MCP session that stays open for its lifetime) would stall the framer waiting for a full batch.
        Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]]((), chunkSize = 1) { _ =>
            Abort.run[Closed](conn.inbound.safe.take).map {
                case Result.Success(span) =>
                    // Every kyo-net driver completes a read with a freshly allocated array, so the span's array has a single owner: unwrap and
                    // rewrap with no copy.
                    Maybe.Present((Chunk.fromNoCopy(span.toArrayUnsafe), ()))
                case Result.Failure(_) =>
                    // Inbound channel closed (peer EOF or local close). Buffered spans were already delivered (takes drain a closing channel
                    // until empty), so this is the orderly end of the stream.
                    Maybe.Absent
                case Result.Panic(e) =>
                    Abort.panic(e)
            }
        }

    def close(using Frame): Unit < Async =
        // Unsafe: Connection.close is unsafe-tier; it is synchronous, idempotent, and flushes queued outbound spans before releasing the socket.
        Sync.Unsafe.defer(conn.close())
end ConnectionWireTransport
