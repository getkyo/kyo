package kyo.internal

import kyo.*

/** The transport a kyo-ui session runs over: text frames out, text frames in, and an end.
  *
  * [[UIServer.serveSession]] speaks this rather than a WebSocket, so one session serves a socket, an MCP Apps view
  * pumped by tool calls, or an in-memory pair in a test, with the same subscription tree and the same event dispatch.
  *
  * A frame is the JSON of one [[HtmlOp]] going out, and of one [[UIEvent]] or [[DragProtocol.ClientMessage]] coming in.
  * The channel carries the encoded text rather than the values: a session that can be resumed on another transport
  * replays exactly the bytes it sent, and a channel that re-encoded on replay could replay something else.
  */
private[kyo] trait UIChannel:

    /** Sends one frame, failing `Closed` once the transport has ended. */
    def send(frame: String)(using Frame): Unit < (Async & Abort[Closed])

    /** The frames the peer sent, in the order it sent them, ending when the peer stops sending. */
    def received(using Frame): Stream[String, Async]

    /** Completes when the peer has gone, whether or not [[received]] has ended. */
    def awaitClose(using Frame): Unit < Async

end UIChannel

private[kyo] object UIChannel:

    /** A WebSocket as a channel.
      *
      * Binary frames are dropped here rather than in the session, which is where the session has always dropped them:
      * the wire is JSON text, and a peer sending bytes is a peer speaking something else.
      */
    def webSocket(ws: HttpWebSocket): UIChannel =
        new UIChannel:
            def send(frame: String)(using Frame): Unit < (Async & Abort[Closed]) =
                ws.put(HttpWebSocket.Payload.Text(frame))

            def received(using Frame): Stream[String, Async] =
                ws.stream.collectPure {
                    case HttpWebSocket.Payload.Text(data) => Present(data)
                    case HttpWebSocket.Payload.Binary(_)  => Absent
                }

            def awaitClose(using Frame): Unit < Async = ws.onPeerClose
    end webSocket

end UIChannel
