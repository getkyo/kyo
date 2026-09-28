package kyo.internal.slack

import kyo.*

class TransportTest extends kyo.test.Test[Any]:

    // Socket-only opt-out: this suite runs an HttpServer/HttpClient on the NIO transport, whose closed-channel fd
    // close is deferred to the idle selector's next select() (an opaque socket:[inode] no allowlist matches), the
    // same transport-deferred reason as BaseHttpTest. Thread, fiber, and file-descriptor detection stay on.
    override def config = super.config.leakCheckSockets(false)

    private val slackConfig = SlackConfig(SlackToken.AppLevel("xapp-transport"), SlackToken.Bot("xoxb-transport"))

    /** The live transport over a client of its own, closed with the test's scope. */
    private def live(using Frame): Transport < (Async & Scope) =
        HttpClient.init().map(http => Transport.live(http, slackConfig))

    private def urlOf(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    "live connect put and stream round-trip a text frame over a local WebSocket server" in {
        val echoHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
            (_, ws) => ws.stream.foreach(ws.put)
        for
            server    <- HttpServer.init(0, "127.0.0.1")(HttpHandler.webSocket("ws/echo")(echoHandler))
            transport <- live
            frames    <- transport.connect(urlOf(s"ws://127.0.0.1:${server.port}/ws/echo"), HttpWebSocket.Config()) { conn =>
                conn.put("hello-live").andThen(conn.stream.take(1).run)
            }
        yield assert(frames == Chunk("hello-live"))
        end for
    }

    "a binary frame is logged by its kind and size, never its bytes, and the stream goes on" in {
        val bytes = s"""{"response_url":"${WireTest.responseUrl}","token":"${WireTest.tokenSecret}","link":"${WireTest.link}"}"""
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val sendBinaryThenText: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
            (_, ws) =>
                ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(bytes)))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("after-binary")))
                    .andThen(ws.stream.foreach(_ => Kyo.unit))
        for
            sink      <- SocketEngineTest.LogSink.init
            server    <- HttpServer.init(0, "127.0.0.1")(HttpHandler.webSocket("ws/binary")(sendBinaryThenText))
            transport <- live
            frames    <- Log.let(Log(sink)) {
                transport.connect(urlOf(s"ws://127.0.0.1:${server.port}/ws/binary"), HttpWebSocket.Config())(_.stream.take(1).run)
            }
            lines <- sink.linesFrom("Transport")
            all   <- sink.everything
        yield
            assert(frames == Chunk("after-binary"))
            assert(lines == Chunk(
                SocketEngineTest.Line("warn", s"Transport.live: ignoring a binary WebSocket frame (${bytes.length} bytes)", Absent)
            ))
            assert(WireTest.secrets.forall(s => !all.contains(s)), "a logged line holds part of a binary frame")
        end for
    }

    "live connect to an unreachable url fails with the socket-connect transport leaf and the kyo-net cause" in {
        live.map { transport =>
            Abort.run[SlackException](transport.connect(urlOf("ws://127.0.0.1:1/ws"), HttpWebSocket.Config())(_ => Kyo.unit)).map {
                result =>
                    assert(result == Result.fail(SlackTransportException(
                        "socket-connect",
                        SlackTransportException.Kind.Connect,
                        "127.0.0.1",
                        1,
                        Absent
                    )(Absent)))
                    val cause = result.failure.collect { case t: SlackTransportException => t.cause.map(_.getClass.getSimpleName) }
                    assert(cause == Present(Present("NetConnectException")), cause.toString)
            }
        }
    }

    "a socket the server refuses to upgrade fails with the WebSocketHandshake kind" in {
        val plain = HttpRoute.getRaw("ws/plain").response(_.bodyText).handler(_ => HttpResponse(HttpStatus.OK).addField("body", "no"))
        for
            server    <- HttpServer.init(0, "127.0.0.1")(plain)
            transport <- live
            result    <- Abort.run[SlackException](
                transport.connect(urlOf(s"ws://127.0.0.1:${server.port}/ws/plain"), HttpWebSocket.Config())(_ => Kyo.unit)
            )
        yield assert(
            result.failure.collect { case t: SlackTransportException => (t.method, t.kind) } ==
                Present(("socket-connect", SlackTransportException.Kind.WebSocketHandshake)),
            s"got: $result"
        )
        end for
    }

    "a peer that closes the socket on accept fails the connect with the ConnectionClosed transport leaf, never a panic" in {
        // Unsafe: the listener and its accepted connection are kyo-net's unsafe tier, bridged here for a test peer.
        import AllowUnsafe.embrace.danger
        val listening = kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16)(conn => conn.close())
        for
            listener  <- listening.safe.get
            _         <- Scope.ensure(Sync.Unsafe.defer(listener.close()))
            transport <- live
            result    <- Abort.run[SlackException](
                transport.connect(urlOf(s"ws://127.0.0.1:${listener.port}/ws"), HttpWebSocket.Config())(_ => Kyo.unit)
            )
        yield assert(
            result == Result.fail(SlackTransportException(
                "socket-connect",
                SlackTransportException.Kind.ConnectionClosed,
                "127.0.0.1",
                listener.port,
                Absent
            )(Absent)),
            s"got: $result"
        )
        end for
    }

    "the caller's kyo-http filters do not reach the socket connect, set through its config or through its client" in {
        val echoHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
            (_, ws) => ws.stream.foreach(ws.put)
        for
            seen <- AtomicInt.init(0)
            spy = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2, S](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                    seen.incrementAndGet.andThen(next(request))
            server    <- HttpServer.init(0, "127.0.0.1")(HttpHandler.webSocket("ws/echo")(echoHandler))
            callers   <- HttpClient.init()
            transport <- live
            url = urlOf(s"ws://127.0.0.1:${server.port}/ws/echo")
            echoed <- HttpClient.let(callers) {
                HttpClient.withConfig(_.filter(spy)) {
                    transport.connect(url, HttpWebSocket.Config())(conn => conn.put("x").andThen(conn.stream.take(1).run))
                }
            }
            count <- seen.get
        yield
            assert(echoed == Chunk("x"))
            assert(count == 0, s"the caller's filter saw $count requests")
        end for
    }

end TransportTest

object TransportTest:
    /** The live transport, dialing a local server's plain `ws` endpoint for the `wss` url the module accepted: the module refuses
      * a `ws` socket url, and a local test server has no certificate the client's default TLS trusts. Everything but the TLS
      * layer is the production path.
      */
    def plainLocal(http: HttpClient, config: SlackConfig): Transport =
        val live = Transport.live(http, config)
        new Transport:
            private[kyo] def connect[A, S](url: HttpUrl, c: HttpWebSocket.Config)(
                f: Transport.Conn => A < (S & Async)
            )(using Frame): A < (S & Async & Abort[SlackTransportException]) =
                live.connect(url.copy(scheme = Present("ws")), c)(f)
        end new
    end plainLocal
end TransportTest
