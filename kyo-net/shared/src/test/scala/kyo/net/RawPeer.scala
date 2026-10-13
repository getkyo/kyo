package kyo.net

import kyo.*

/** The far end of a kyo connection, driven at the socket level so a close-contract leaf can act on the wire directly: send bytes, stop
  * reading behind a small receive buffer, half-close, reset with `SO_LINGER {1, 0}`, and observe how the kyo side ended the stream (a FIN
  * or a reset). No kyo connection can do these on cue.
  *
  * It is a plain TCP socket from the platform's own stack, never a kyo transport, so what it observes is what any peer would: a
  * `java.nio` socket channel on the JVM, the posix socket bindings on Native, and a Node `net.Socket` on JS. [[RawPeer.connect]] dials a kyo
  * listener; [[RawPeer.listen]] accepts a kyo client. Every socket is released when the enclosing scope closes.
  */
abstract class RawPeer:

    /** Writes every byte of `bytes`, completing once the platform accepted them. */
    def send(bytes: Span[Byte])(using Frame): Unit < Async

    /** The next read: bytes, the kyo side's FIN, or a reset. Reading past the end keeps returning the end. */
    def recv()(using Frame): RawPeer.Read < Async

    /** Half-closes: the kyo side reads its stream's end while this peer can still read. */
    def shutdownWr()(using Frame): Unit < Async

    /** Closes with `SO_LINGER {1, 0}`, so the kyo side receives an RST and no FIN. */
    def reset()(using Frame): Unit < Async

    /** Closes orderly: a FIN, or an RST when the kernel still holds unread bytes from the kyo side. */
    def close()(using Frame): Unit < Async

    /** Reads until `n` bytes arrived or the stream ended, whichever comes first. */
    final def recvExactly(n: Int)(using Frame): Array[Byte] < Async =
        Loop(Chunk.empty[Array[Byte]], 0) { (acc, got) =>
            if got >= n then Loop.done(acc.toArray.flatten)
            else
                recv().map {
                    case RawPeer.Read.Bytes(span) => Loop.continue(acc.append(span.toArray), got + span.size)
                    case _                        => Loop.done(acc.toArray.flatten)
                }
        }

    /** Reads until the stream ends, returning every byte and how it ended. */
    final def recvToEnd()(using Frame): (Array[Byte], RawPeer.Read) < Async =
        Loop(Chunk.empty[Array[Byte]]) { acc =>
            recv().map {
                case RawPeer.Read.Bytes(span) => Loop.continue(acc.append(span.toArray))
                case end                      => Loop.done((acc.toArray.flatten, end))
            }
        }

end RawPeer

object RawPeer:

    /** One read's outcome. `Reset` carries the platform's own description of the error, for the failure message. */
    enum Read derives CanEqual:
        case Bytes(span: Span[Byte])
        case Fin
        case Reset(detail: String)
    end Read

    /** A listening socket that accepts one kyo client. */
    abstract class Listener:
        def port: Int
        def accept()(using Frame): RawPeer < (Async & Scope)
    end Listener

    /** Connects to a kyo listener on 127.0.0.1:`port`. `rcvBuf` sets `SO_RCVBUF` before the connect, so a peer that stops reading
      * backpressures the kyo writer after a known amount.
      */
    def connect(port: Int, rcvBuf: Maybe[Int] = Absent)(using Frame): RawPeer < (Async & Scope) =
        RawPeerPlatform.connect(port, rcvBuf)

    /** Listens on an ephemeral port of 127.0.0.1; `rcvBuf` applies to every accepted socket. */
    def listen(rcvBuf: Maybe[Int] = Absent)(using Frame): Listener < (Async & Scope) =
        RawPeerPlatform.listen(rcvBuf)

    /** The other side of an in-memory pair as a peer. A closed channel reads as the stream's end. There is no half-close and no reset
      * between in-memory connections, so those fail the leaf that asks for them.
      */
    def overConnection(conn: Connection): RawPeer =
        new RawPeer:
            import AllowUnsafe.embrace.danger

            def send(bytes: Span[Byte])(using Frame): Unit < Async =
                Abort.run[Closed](conn.outbound.safe.put(bytes)).map(_.getOrThrow)

            def recv()(using Frame): Read < Async =
                Abort.run[Closed](conn.inbound.safe.take).map {
                    case Result.Success(span) if !span.isEmpty => Read.Bytes(span)
                    case _                                     => Read.Fin
                }

            def shutdownWr()(using Frame): Unit < Async =
                Abort.panic(new UnsupportedOperationException("an in-memory pair has no half-close"))

            def reset()(using Frame): Unit < Async = Abort.panic(new UnsupportedOperationException("an in-memory pair has no reset"))

            def close()(using Frame): Unit < Async = Sync.defer(conn.close())
    end overConnection

end RawPeer
