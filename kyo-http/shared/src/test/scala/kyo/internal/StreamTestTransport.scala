package kyo.internal

import kyo.*

/** In-memory Transport for testing. Uses Channel pairs to simulate bidirectional streams.
  *
  * Architecture: each `StreamTestConnection` wraps a read channel and a write channel. The `withPair` helper creates two connections with
  * swapped channel pairs so that writing from one end is readable from the other.
  */
class StreamTestConnection(
    readCh: Channel[Span[Byte]],
    writeCh: Channel[Span[Byte]],
    var closed: Boolean = false
) extends TransportStream:

    def read(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            Abort.run[Closed](readCh.take).map {
                case Result.Success(data) =>
                    if data.isEmpty then Maybe.empty
                    else Maybe((data, ()))
                case _ => Maybe.empty // channel closed = EOF
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        Abort.run[Closed](writeCh.put(data)).map {
            case Result.Success(_) => Kyo.unit
            case Result.Error(_)   => Abort.panic(new java.io.IOException("Broken pipe (test transport)"))
        }

    /** Close the write side of this connection, signalling EOF to the remote read. */
    def closeWrite(using Frame): Unit < Async =
        writeCh.close.map(_ => Kyo.unit)

end StreamTestConnection

object StreamTestTransport:

    /** Create a connected pair of test connections for use in tests. `client.write` becomes `server.read` and vice versa. */
    def withPair[A](f: (StreamTestConnection, StreamTestConnection) => A < (Async & Abort[HttpException]))(using
        Frame
    ): A < (Async & Abort[HttpException] & Scope) =
        Channel.init[Span[Byte]](64).map { ch1 =>
            Channel.init[Span[Byte]](64).map { ch2 =>
                val client = new StreamTestConnection(ch2, ch1)
                val server = new StreamTestConnection(ch1, ch2)
                f(client, server)
            }
        }

    /** Simulate a server listener for testing TransportListener.close behavior. */
    def listen()(using Frame): TransportListener[StreamTestConnection] < (Async & Scope) =
        Channel.init[StreamTestConnection](16).map { connCh =>
            Scope.acquireRelease {
                val connStream = Stream.unfold((), chunkSize = 1) { _ =>
                    Abort.run[Closed](connCh.take).map {
                        case Result.Success(conn) => Maybe((conn, ()))
                        case _                    => Maybe.empty
                    }
                }
                new TransportListener(HttpAddress.Tcp("127.0.0.1", 0), connStream, close = connCh.close.unit)
            } { _ => connCh.close.unit }
        }

end StreamTestTransport
