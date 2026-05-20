package kyo.internal.transport

import kyo.*

/** Adapts a Connection's raw channels to the TransportStream interface for WebSocketCodec. Converts channel.safe.streamUntilClosed into the
  * Stream-based read API and absorbs Closed errors on writes (connection shutdown is expected during teardown).
  */
final private[kyo] class ConnectionBackedStream[Handle](
    connection: Connection[Handle]
) extends TransportStream:
    def read(using Frame): Stream[Span[Byte], Async] =
        connection.inbound.safe.streamUntilClosed()

    def write(data: Span[Byte])(using Frame): Unit < Async =
        // TransportStream.write returns Unit < Async (no Abort[Closed] in the type),
        // so we must handle the Closed error here.
        // Closed is expected during connection shutdown — not logged.
        // Panics indicate bugs and are logged.
        Abort.run[Closed](connection.outbound.safe.put(data)).map {
            case Result.Success(_)         => ()
            case Result.Failure(_: Closed) => ()
            case Result.Panic(t) =>
                Log.error("ConnectionBackedStream: write panic", t)
        }
end ConnectionBackedStream
