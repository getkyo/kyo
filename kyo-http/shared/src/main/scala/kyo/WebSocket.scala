package kyo

import kyo.*

/** A bidirectional WebSocket connection handle.
  *
  * Uses Channel vocabulary — `put`/`take`/`offer`/`poll`/`stream` — so users familiar with kyo's Channel API can use WebSocket immediately.
  * Internally backed by two channels: one for inbound messages (received from the remote peer) and one for outbound messages (sent to the
  * remote peer).
  *
  * Backpressure is built in: when the outbound channel is full, `put` suspends until space is available. When the inbound channel is full,
  * the backend pauses reading from the network.
  *
  * When the connection closes (either side), channel operations fail with `Abort[Closed]`. The close code and reason are available via
  * `closeReason` after catching the `Closed` error.
  *
  * @see
  *   [[kyo.WebSocket.connect]] Local testing without network
  * @see
  *   [[kyo.HttpHandler.webSocket]] Server-side WebSocket endpoint
  * @see
  *   [[kyo.HttpClient.webSocket]] Client-side WebSocket connection
  */
final class WebSocket private[kyo] (
    private[kyo] val inbound: Channel[WebSocket.Payload],
    private[kyo] val outbound: Channel[WebSocket.Payload],
    private[kyo] val closeReasonRef: AtomicRef[Maybe[(Int, String)]],
    private[kyo] val closeFn: (Int, String) => Unit < Async
):

    /** Sends a frame to the remote peer. Suspends if the outbound buffer is full. */
    def put(frame: WebSocket.Payload)(using Frame): Unit < (Async & Abort[Closed]) =
        outbound.put(frame)

    /** Receives the next frame from the remote peer. Suspends until a frame arrives. */
    def take()(using Frame): WebSocket.Payload < (Async & Abort[Closed]) =
        inbound.take

    /** Attempts to send a frame without suspending. Returns false if the outbound buffer is full. */
    def offer(frame: WebSocket.Payload)(using Frame): Boolean < (Abort[Closed] & Sync) =
        outbound.offer(frame)

    /** Attempts to receive a frame without suspending. Returns Absent if no frame is available. */
    def poll()(using Frame): Maybe[WebSocket.Payload] < (Abort[Closed] & Sync) =
        inbound.poll

    /** Returns a stream of all inbound frames. Terminates when the connection closes. */
    def stream(using Tag[Emit[Chunk[WebSocket.Payload]]], Frame): Stream[WebSocket.Payload, Async] =
        inbound.streamUntilClosed()

    /** Initiates a close handshake with the given code and reason. */
    def close(code: Int = 1000, reason: String = "")(using Frame): Unit < Async =
        closeFn(code, reason)

    /** Returns the close code and reason if the connection has been closed. */
    def closeReason(using Frame): Maybe[(Int, String)] < Sync =
        closeReasonRef.get

end WebSocket

object WebSocket:

    /** A WebSocket message frame — either text (UTF-8) or binary.
      *
      * Protocol-level frames (ping, pong, close) are handled internally by backends and are not exposed to user code.
      */
    enum Payload derives CanEqual:
        case Text(data: String)
        case Binary(data: Span[Byte])

    /** Configuration for a WebSocket endpoint or connection.
      *
      * @param bufferSize
      *   Channel capacity for inbound and outbound message queues. Controls backpressure — when a channel is full, the sender suspends.
      * @param maxFrameSize
      *   Maximum size in bytes of a single WebSocket frame. Frames exceeding this limit cause the connection to close.
      * @param autoPingInterval
      *   If set, the backend sends ping frames at this interval to keep the connection alive through proxies.
      * @param closeTimeout
      *   Maximum time to wait for a clean close handshake before forcibly closing the connection.
      * @param subprotocols
      *   WebSocket subprotocols to advertise during the handshake (e.g. "graphql-transport-ws").
      */
    case class Config(
        bufferSize: Int = 32,
        maxFrameSize: Int = 65536,
        autoPingInterval: Maybe[Duration] = Absent,
        closeTimeout: Duration = 5.seconds,
        subprotocols: Seq[String] = Seq.empty
    ) derives CanEqual

    /** Connects two WebSocket participants locally without any network.
      *
      * Creates cross-wired channels: what one side puts, the other side takes. Both participants run concurrently. The connection closes
      * when either participant completes or fails.
      *
      * Useful for testing WebSocket handlers without starting a server.
      */
    def connect(
        p1: WebSocket => Unit < (Async & Abort[Closed]),
        p2: WebSocket => Unit < (Async & Abort[Closed])
    )(using Frame): Unit < (Async & Scope) =
        Channel.initWith[Payload](32) { ch1to2 =>
            Channel.initWith[Payload](32) { ch2to1 =>
                AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeRef1 =>
                    AtomicRef.initWith(Absent: Maybe[(Int, String)]) { closeRef2 =>
                        val doClose: (Int, String) => Unit < Async = (code, reason) =>
                            closeRef1.set(Present((code, reason)))
                                .andThen(closeRef2.set(Present((code, reason))))
                                .andThen(ch1to2.close.unit)
                                .andThen(ch2to1.close.unit)
                        val ws1 = new WebSocket(ch2to1, ch1to2, closeRef1, doClose)
                        val ws2 = new WebSocket(ch1to2, ch2to1, closeRef2, doClose)
                        Sync.ensure {
                            ch1to2.close.unit.andThen(ch2to1.close.unit)
                        } {
                            // Race: when either party completes, close channels to unblock the other
                            Async.raceFirst(
                                Abort.run[Closed](p1(ws1)).unit,
                                Abort.run[Closed](p2(ws2)).unit
                            ).unit
                        }
                    }
                }
            }
        }

    /** Convenience overload for reusing server handler functions that take `(HttpRequest[Any], WebSocket)`. */
    def connect(
        p1: (HttpRequest[Any], WebSocket) => Unit < (Async & Abort[Closed]),
        p2: WebSocket => Unit < (Async & Abort[Closed])
    )(using Frame): Unit < (Async & Scope) =
        val dummyReq = HttpRequest(HttpMethod.GET, HttpUrl(Absent, "", 0, "/", Absent), HttpHeaders.empty, Record.empty)
        connect(ws => p1(dummyReq, ws), p2)
    end connect

end WebSocket
