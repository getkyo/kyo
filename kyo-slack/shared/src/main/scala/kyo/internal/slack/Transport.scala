package kyo.internal.slack

import kyo.*

/** The transport SEAM: a text-frame duplex the production path implements over
  * `HttpClient.webSocket` and tests fake in memory. The intersection surface both
  * backends honor (put text / stream text / close / connect), never either
  * backend's extras. Socket Mode is text-only, so binary/peer-close frames do not
  * leak into the seam. A `Slack` client holds its transport, so a test builds a client
  * over its own through the `private[kyo]` builders.
  */
private[kyo] trait Transport:
    /** Run `f` with a connected duplex text channel; mirrors `HttpClient.webSocket`'s
      * body shape. Failing to connect is the one failure, a `socket-connect` transport leaf.
      */
    private[kyo] def connect[A, S](url: HttpUrl, config: HttpWebSocket.Config)(
        f: Transport.Conn => A < (S & Async)
    )(using Frame): A < (S & Async & Abort[SlackTransportException])
end Transport

private[kyo] object Transport:

    private[kyo] trait Conn:
        private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed])
        private[kyo] def stream(using Frame): Stream[String, Async]
        private[kyo] def close(using Frame): Unit < Async

        /** Completes when the remote peer closes the socket, gracefully or abnormally
          * (transport EOF with no close frame). The engine races this alongside the
          * sender/receiver so the relay resolves promptly on an abnormal drop rather than
          * hanging on a stream that the backend leaves open. Mirrors
          * `HttpWebSocket.onPeerClose`.
          */
        private[kyo] def onPeerClose(using Frame): Unit < Async
    end Conn

    private[kyo] inline val SocketConnect = "socket-connect"

    /** Production backend over kyo-http, on the client's own `HttpClient` under the config that replaces the
      * caller's (`SlackConfig.httpConfig`). A kyo-http failure becomes the `socket-connect` transport leaf.
      */
    private[kyo] def live(http: HttpClient, slackConfig: SlackConfig): Transport =
        new Transport:
            private[kyo] def connect[A, S](url: HttpUrl, config: HttpWebSocket.Config)(
                f: Transport.Conn => A < (S & Async)
            )(using Frame): A < (S & Async & Abort[SlackTransportException]) =
                Abort.recover[HttpException] { (ex: HttpException) =>
                    Abort.fail(WebApi.transportFailure(SocketConnect, url, ex))
                } {
                    HttpClient.let(http) {
                        HttpClient.withConfig(SlackConfig.httpConfig(slackConfig)) {
                            HttpClient.webSocket(url, HttpHeaders.empty, config) { ws =>
                                val conn = new Conn:
                                    private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) =
                                        ws.put(HttpWebSocket.Payload.Text(text))
                                    private[kyo] def stream(using Frame): Stream[String, Async] =
                                        Stream {
                                            ws.stream.foreach {
                                                case HttpWebSocket.Payload.Text(s)      => Emit.value(Chunk(s))
                                                case HttpWebSocket.Payload.Binary(data) =>
                                                    Log.warn(s"Transport.live: ignoring a binary WebSocket frame (${data.size} bytes)")
                                            }
                                        }
                                    private[kyo] def close(using Frame): Unit < Async =
                                        ws.close()
                                    private[kyo] def onPeerClose(using Frame): Unit < Async =
                                        ws.onPeerClose
                                f(conn)
                            }
                        }
                    }
                }

end Transport
