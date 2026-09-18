package kyo

class JsonRpcHttpTransportTest extends kyo.test.Test[Any]:

    // Only the socket category is disabled. These tests run an HttpServer and HttpClient over the NIO transport, which
    // defers a closed channel's real fd close to its idle selector's next select(), which nothing wakes, so the
    // listener/connection fd outlives the run. That fix belongs to the transport (frozen for the kyo-net rewrite); the
    // socket is an opaque socket:[inode] no allowlist can match. File-descriptor, thread, and fiber detection stay on.
    // Same opt-out and reason as BaseHttpTest.
    override def config = super.config.leakCheckSockets(false)

    // Linux Native CI HTTP server bring-up + per-request latency can exceed the production 5-second HttpClient
    // default. Wrap every leaf so test requests get a 60s client request timeout (mirrors BaseHttpTest).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    // ==================== Test helpers ====================

    private def withEchoWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "127.0.0.1")(
            HttpHandler.webSocket("ws/echo") { (_, ws) =>
                ws.stream.foreach(ws.put)
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow)
        )

    private def withBinaryWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "127.0.0.1")(
            HttpHandler.webSocket("ws/binary") { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1, 2, 3))))
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow)
        )

    private def withCloseTrackingWsServer[A, S](
        test: (HttpUrl, () => Int) => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        val closeCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        HttpServer.init(0, "127.0.0.1")(
            HttpHandler.webSocket("ws/close") { (_, ws) =>
                ws.stream.run.andThen(
                    Sync.defer(discard(closeCount.incrementAndGet()(using AllowUnsafe.embrace.danger)))
                )
            }
        ).map(server =>
            test(
                HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow,
                () => closeCount.get()(using AllowUnsafe.embrace.danger)
            )
        )
    end withCloseTrackingWsServer

    private def withClosingWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "127.0.0.1")(
            // Accepts the upgrade and closes straight away, so the client sees a session that ends
            // after a successful connect rather than a connect that never succeeded.
            HttpHandler.webSocket("ws/bye") { (_, ws) =>
                ws.close()
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow)
        )

    private def withGarbageWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "127.0.0.1")(
            HttpHandler.webSocket("ws/garbage") { (_, ws) =>
                // Send a malformed text frame, then hold the connection open by draining inbound until the client closes it. The client's read of
                // the frame therefore always precedes the server tearing the socket down, with no timer racing the read: ws.stream.run completes
                // exactly when the client-side scope closes the transport.
                ws.put(HttpWebSocket.Payload.Text("not json")).andThen(ws.stream.run.unit)
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow)
        )

    // ==================== Tests ====================

    "webSocket connects to a local kyo-http server".notNative in {
        withEchoWsServer { url =>
            Scope.run {
                val wsUrl = HttpUrl.parse(s"ws://${url.host}:${url.port}/ws/echo").getOrThrow
                Abort.run[HttpException](JsonRpcHttpTransport.webSocket(wsUrl)).map {
                    case Result.Success(t) =>
                        val req = JsonRpcRequest(JsonRpcId.Num(1), "ping", Absent, Absent)
                        t.send(req).andThen {
                            t.incoming.take(1).run.map { frames =>
                                assert(frames.size == 1)
                                frames.head match
                                    case JsonRpcRequest(JsonRpcId.Num(1), "ping", _, _) => succeed
                                    case other                                          => fail(s"unexpected $other")
                            }
                        }
                    case other => fail(s"unexpected $other")
                }
            }
        }
    }

    "webSocket aborts HttpException when the connection cannot be established".notNative in {
        withEchoWsServer { url =>
            Scope.run {
                // The server answers on this port but has no WebSocket handler at this path, so the
                // upgrade is refused and the factory must surface that as the HttpException its row declares.
                val wsUrl = HttpUrl.parse(s"ws://${url.host}:${url.port}/ws/absent").getOrThrow
                Abort.run[Timeout](Async.timeout(30.seconds)(Abort.run[HttpException](JsonRpcHttpTransport.webSocket(wsUrl)))).map {
                    case Result.Success(Result.Failure(_: HttpException)) => succeed
                    case Result.Success(Result.Success(_)) =>
                        fail("a refused upgrade returned a transport instead of aborting HttpException")
                    case Result.Failure(_: Timeout) => fail("webSocket neither returned nor aborted within 30s")
                    case other                      => fail(s"unexpected $other")
                }
            }
        }
    }

    "send aborts Closed once the peer has closed the connection".notNative in {
        withClosingWsServer { url =>
            Scope.run {
                val wsUrl = HttpUrl.parse(s"ws://${url.host}:${url.port}/ws/bye").getOrThrow
                Abort.run[HttpException](JsonRpcHttpTransport.webSocket(wsUrl)).map {
                    case Result.Success(t) =>
                        val req = JsonRpcRequest(JsonRpcId.Num(1), "ping", Absent, Absent)
                        // incoming completing is the signal that the session is over; from that point a send
                        // has nowhere to go and must abort rather than buffer into the outbound channel.
                        Abort.run[Timeout](
                            Async.timeout(30.seconds)(t.incoming.run.andThen(Abort.run[Closed](t.send(req))))
                        ).map {
                            case Result.Success(Result.Failure(_: Closed)) => succeed
                            case Result.Success(Result.Success(_)) => fail("send reported success after the peer closed the connection")
                            case Result.Failure(_: Timeout)        => fail("send neither completed nor aborted within 30s")
                            case other                             => fail(s"unexpected $other")
                        }
                    case other => fail(s"unexpected $other")
                }
            }
        }
    }

    "webSocket drops binary frames with warn".notNative in {
        withBinaryWsServer { url =>
            Scope.run {
                val wsUrl = HttpUrl.parse(s"ws://${url.host}:${url.port}/ws/binary").getOrThrow
                Abort.run[HttpException](JsonRpcHttpTransport.webSocket(wsUrl)).map {
                    case Result.Success(t) =>
                        // Binary frames must not appear in incoming; a 500ms window with no envelope is the signal.
                        Abort.run[Timeout](Async.timeout(500.millis)(t.incoming.take(1).run)).map {
                            case Result.Failure(_: Timeout) =>
                                succeed
                            case Result.Success(c) if c.isEmpty =>
                                succeed
                            case Result.Success(c) =>
                                fail(s"expected no envelope from binary frame, got ${c.head}")
                            case other => fail(s"unexpected $other")
                        }
                    case other => fail(s"unexpected $other")
                }
            }
        }
    }

    "Scope.ensure closes the WS on scope exit".notNative in {
        withCloseTrackingWsServer { (url, closes) =>
            val wsUrl = HttpUrl.parse(s"ws://${url.host}:${url.port}/ws/close").getOrThrow
            Scope.run {
                Abort.run[HttpException](JsonRpcHttpTransport.webSocket(wsUrl)).map(_ => ())
            }.andThen {
                def untilClosed: Unit < Async =
                    Async.sleep(50.millis).andThen(
                        if closes() >= 1 then ()
                        else untilClosed
                    )
                // Converging poll: closes() rises to 1 the moment the server's handler observes the client close, normally within a few
                // milliseconds of scope exit. The ceiling is a hang-guard, so it only expires if the close is never observed (a real leak).
                Abort.run[Timeout](Async.timeout(30.seconds)(untilClosed)).map {
                    case Result.Success(_) => succeed
                    case Result.Failure(_) => fail("server did not observe WS close within the hang-guard")
                    case Result.Panic(t)   => fail(s"panic: ${t.getMessage}")
                }
            }
        }
    }

    "codec failure on malformed text frame surfaces as Malformed envelope".notNative in {
        withGarbageWsServer { url =>
            Scope.run {
                val wsUrl = HttpUrl.parse(s"ws://${url.host}:${url.port}/ws/garbage").getOrThrow
                Abort.run[HttpException](JsonRpcHttpTransport.webSocket(wsUrl)).map {
                    case Result.Success(t) =>
                        Abort.run[Timeout](Async.timeout(5.seconds)(t.incoming.take(1).run)).map {
                            case Result.Success(frames) =>
                                assert(frames.size == 1, s"expected 1 frame, got ${frames.size}: $frames")
                                frames.head match
                                    case JsonRpcMalformedMessage(Absent, reason, _) =>
                                        assert(reason.contains("json parse"), s"reason was: $reason")
                                    case other => fail(s"unexpected $other")
                                end match
                            case Result.Failure(_: Timeout) =>
                                fail("timed out waiting for Malformed envelope after 5s")
                            case other => fail(s"unexpected $other")
                        }
                    case other => fail(s"unexpected $other")
                }
            }
        }
    }

end JsonRpcHttpTransportTest
