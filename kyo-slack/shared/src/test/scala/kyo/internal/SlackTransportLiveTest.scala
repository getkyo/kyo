package kyo.internal

import kyo.*

/** Real-WebSocket round-trip test for `SlackTransport.live`.
  * Proves the live socket/HTTP paths against real I/O using an in-process server.
  * `.notNative`-gated because the in-process kyo-http WebSocket server runs on JVM, JS,
  * and Wasm but not Native (kyo-http's own WebSocket suite gates the same way); the
  * cross-platform decode/ack logic is also covered by the in-memory path on all four
  * platforms.
  */
class SlackTransportLiveTest extends kyo.test.Test[Any]:

    // Socket-only opt-out: this suite runs an HttpServer/HttpClient on the NIO transport, whose closed-channel fd
    // close is deferred to the idle selector's next select() (an opaque socket:[inode] no allowlist matches), the
    // same transport-deferred reason as BaseHttpTest. Thread, fiber, and file-descriptor detection stay on.
    override def config = super.config.leakCheckSockets(false)

    // Helper: start an in-process echo WS server on an ephemeral port.
    def withEchoServer[A, S](
        test: (String, Int) => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        val echoHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
            (_, ws) => ws.stream.foreach(ws.put)
        HttpServer.init(0, "localhost")(HttpHandler.webSocket("ws/echo")(echoHandler)).map { server =>
            test("localhost", server.port)
        }
    end withEchoServer

    // Real I/O: live SlackTransport.connect puts a text frame and the stream receives it back.
    // Proves the live OS socket byte-transport path; the cross-platform encode/decode
    // logic is covered by the in-memory path in SlackTransportTest on all four platforms.
    "live connect put and stream round-trip a text frame".notNative in {
        val cfg = HttpWebSocket.Config()
        withEchoServer { (host, port) =>
            val url = s"ws://$host:$port/ws/echo"
            SlackTransport.live.connect(url, cfg) { conn =>
                conn.put("hello-live").andThen {
                    conn.stream.take(1).run.map { frames =>
                        assert(frames == Chunk("hello-live"))
                    }
                }
            }
        }
    }

end SlackTransportLiveTest
