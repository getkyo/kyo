package kyo.internal

import kyo.*

final private[kyo] class InMemoryTransport(
    out: Channel[JsonRpcEnvelope],
    in: Channel[JsonRpcEnvelope]
) extends JsonRpcTransport:

    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
        out.put(env)

    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
        in.streamUntilClosed()

    def close(using Frame): Unit < Async =
        out.close.andThen(in.close).unit

end InMemoryTransport
