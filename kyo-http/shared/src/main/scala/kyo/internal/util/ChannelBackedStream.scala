package kyo.internal.util

import kyo.*
import kyo.internal.transport.*

/** Adapts a pair of Channel.Unsafe[Span[Byte]] to the TransportStream interface.
  *
  * Used by UnsafeServerDispatch.dispatchWebSocket to bridge the HTTP/1.1 connection channels into WebSocketCodec's stream-based API. Unlike
  * ConnectionBackedStream (which wraps a full Connection), this class takes inbound and outbound channels directly — useful when the
  * channels are not owned by a Connection (e.g., test channels or after upgrade).
  */
final class ChannelBackedStream(
    inbound: Channel.Unsafe[Span[Byte]],
    outbound: Channel.Unsafe[Span[Byte]]
) extends TransportStream:
    def read(using Frame): Stream[Span[Byte], Async] = inbound.safe.streamUntilClosed()
    def write(data: Span[Byte])(using Frame): Unit < Async =
        // TransportStream.write returns Unit < Async (no Abort[Closed] in the type),
        // so we must handle the Closed error here.
        // Closed is expected during connection shutdown — not logged.
        // Panics indicate bugs and are logged.
        Abort.run[Closed](outbound.safe.put(data)).map {
            case Result.Success(_)         => ()
            case Result.Failure(_: Closed) => ()
            case Result.Panic(t) =>
                Log.error("ChannelBackedStream: write panic", t)
        }
end ChannelBackedStream
