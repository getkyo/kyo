package kyo

class JsonRpcHttpTransportTest extends kyo.test.Test[Any]:

    // Linux Native CI HTTP server bring-up + per-request latency can exceed the production 5-second HttpClient
    // default. Wrap every leaf so test requests get a 60s client request timeout (mirrors BaseHttpTest).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    // ==================== Test helpers ====================

    private def withEchoWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(
            HttpHandler.webSocket("ws/echo") { (_, ws) =>
                ws.stream.foreach(ws.put)
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow)
        )

    private def withBinaryWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(
            HttpHandler.webSocket("ws/binary") { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1, 2, 3))))
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow)
        )

    private def withCloseTrackingWsServer[A, S](
        test: (HttpUrl, () => Int) => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        val closeCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        HttpServer.init(0, "localhost")(
            HttpHandler.webSocket("ws/close") { (_, ws) =>
                ws.stream.run.andThen(
                    Sync.defer(discard(closeCount.incrementAndGet()(using AllowUnsafe.embrace.danger)))
                )
            }
        ).map(server =>
            test(
                HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow,
                () => closeCount.get()(using AllowUnsafe.embrace.danger)
            )
        )
    end withCloseTrackingWsServer

    private def withGarbageWsServer[A, S](
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(
            HttpHandler.webSocket("ws/garbage") { (_, ws) =>
                // Send "not json" then sleep briefly so our bridge has time to read it
                // before the server closes the connection.
                ws.put(HttpWebSocket.Payload.Text("not json")).andThen(Async.sleep(500.millis))
            }
        ).map(server =>
            test(HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow)
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
                Abort.run[Timeout](Async.timeout(2.seconds)(untilClosed)).map {
                    case Result.Success(_) => succeed
                    case Result.Failure(_) => fail("server did not observe WS close within 2s")
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
