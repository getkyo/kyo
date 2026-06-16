package kyo.internal

import kyo.*

/** The transport SEAM: a text-frame duplex the production path implements over
  * `HttpClient.webSocket` and tests fake in memory. The intersection surface both
  * backends honor (put text / stream text / close / connect), never either
  * backend's extras. Socket Mode is text-only, so binary/peer-close frames do not
  * leak into the seam.
  */
private[kyo] trait SlackTransport:
    /** Run `f` with a connected duplex text channel; mirrors `HttpClient.webSocket`'s
      * body shape.
      */
    private[kyo] def connect[A, S](url: String, config: HttpWebSocket.Config)(
        f: SlackTransport.Conn => A < (S & Async & Abort[SlackException])
    )(using Frame): A < (S & Async & Abort[SlackException])
end SlackTransport

private[kyo] object SlackTransport:

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

    /** Production backend over kyo-http. Translates the kyo-http `HttpException`
      * row into the kyo-slack `Abort[SlackException]` row so the socket and fibers
      * are released on close without leaking transport exceptions into the caller.
      */
    private[kyo] val live: SlackTransport =
        new SlackTransport:
            private[kyo] def connect[A, S](url: String, config: HttpWebSocket.Config)(
                f: SlackTransport.Conn => A < (S & Async & Abort[SlackException])
            )(using Frame): A < (S & Async & Abort[SlackException]) =
                Abort.recover[HttpException] { (ex: HttpException) =>
                    Abort.fail(new SlackTransportException(s"websocket connect/transport failure: ${ex.getMessage}", ex))
                } {
                    HttpClient.webSocket(url, HttpHeaders.empty, config) { ws =>
                        val conn = new Conn:
                            private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) =
                                ws.put(HttpWebSocket.Payload.Text(text))
                            private[kyo] def stream(using Frame): Stream[String, Async] =
                                Stream {
                                    ws.stream.foreach {
                                        case HttpWebSocket.Payload.Text(s) => Emit.value(Chunk(s))
                                        case other => Log.warn(s"SlackTransport.live: ignoring non-Text WS frame: $other")
                                    }
                                }
                            private[kyo] def close(using Frame): Unit < Async =
                                ws.close()
                            private[kyo] def onPeerClose(using Frame): Unit < Async =
                                ws.onPeerClose
                        f(conn)
                    }
                }

    /** The backend the connection entry points open over, defaulting to `live`. The
      * production `run`/`init` path reads this default; cross-platform tests bind an
      * in-memory backend with `transport.let(...)` to drive the full run/receive path
      * without a real socket.
      */
    private[kyo] val transport: Local[SlackTransport] = Local.init(live)

    /** In-memory backend for tests: streams the scripted inbound frames and records
      * every outbound frame on the returned `Channel`, so the test asserts on the
      * recorded acks. Readiness/timing is driven by the Channel and the engine's
      * readiness gate, never a sleep.
      */
    private[kyo] def inMemory(scripted: Chunk[String])(using Frame): (SlackTransport, Channel[String]) < (Sync & Scope) =
        Channel.init[String](64).map { recorded =>
            val transport =
                new SlackTransport:
                    private[kyo] def connect[A, S](url: String, config: HttpWebSocket.Config)(
                        f: SlackTransport.Conn => A < (S & Async & Abort[SlackException])
                    )(using Frame): A < (S & Async & Abort[SlackException]) =
                        // A peer-close gate completed on close, mirroring the live backend's
                        // onPeerClose: the scripted stream is finite, so the receiver leg ends on
                        // its own, but the gate keeps the seam faithful for an explicit close.
                        Fiber.Promise.init[Unit, Any].map { peerClosed =>
                            val conn = new Conn:
                                private[kyo] def put(text: String)(using Frame): Unit < (Async & Abort[Closed]) =
                                    recorded.put(text)
                                private[kyo] def stream(using Frame): Stream[String, Async] =
                                    Stream.init(scripted)
                                private[kyo] def close(using Frame): Unit < Async =
                                    peerClosed.completeUnit.andThen(recorded.close.unit)
                                private[kyo] def onPeerClose(using Frame): Unit < Async =
                                    peerClosed.get
                            f(conn)
                        }
                    end connect
            (transport, recorded)
        }

end SlackTransport
