package kyo.internal.transport

import kyo.*

final private[kyo] class InMemoryTransport(
    out: Channel[JsonRpcEnvelope],
    in: Channel[JsonRpcEnvelope]
) extends JsonRpcTransport:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
        out.put(env)

    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
        in.streamUntilClosed()

    def close(using Frame): Unit < Async =
        // The outbound side closes to new sends but keeps what was already sent, so the peer still receives every envelope
        // a send accepted before the close, the same guarantee a socket gives bytes it accepted. Its stream ends once those
        // are read; a send parked on a full channel fails with Closed. The inbound side closes outright.
        // Unsafe: closeAwaitEmpty is started without waiting for the peer to drain; the peer may never read again.
        Sync.Unsafe.defer(discard(out.unsafe.closeAwaitEmpty())).andThen(in.close).unit

end InMemoryTransport
