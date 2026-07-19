package kyo

import kyo.*

class HttpWebSocketTest extends BaseHttpTest with internal.UnixSocketTestHelperImpl:

    given CanEqual[Any, Any] = CanEqual.derived

    def echo(req: HttpRequest[Any], ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
        ws.stream.foreach(ws.put)

    def withWsServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(handlers*).map(server =>
            test(HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow)
        )

    // ==================== Basic connectivity ====================

    "basic connectivity" - {

        "text echo".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hello"))))
                    }
                }
            }.unit
        }

        "upgrade request forwards the query string".notNative in {
            // The Slack Socket Mode wss url carries its connection ticket in the query string,
            // so the upgrade GET must send path AND query. The server echoes the received
            // `probe` query param back as the first frame.
            def probe(req: HttpRequest[Any], ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
                ws.put(HttpWebSocket.Payload.Text(req.query("probe").getOrElse("<absent>"))).andThen(ws.stream.foreach(ws.put))
            withWsServer(HttpHandler.webSocket("ws/probe")(probe)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/probe?probe=xyz") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("xyz"))))
                }
            }.unit
        }

        "binary echo".notNative in {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(bytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 5))
                                discard(assert(data(0) == 1.toByte))
                                discard(assert(data(4) == 5.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.unit
        }

        "multiple messages in order".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    Kyo.foreach(1 to 10)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen {
                        Kyo.foreach(1 to 10)(i =>
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"msg$i"))))
                        ).unit
                    }
                }
            }.unit
        }

        "empty text frame".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(""))))
                    }
                }
            }.unit
        }

        "large text frame".notNative in {
            val text = "x" * 65000
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(text))))
                    }
                }
            }.unit
        }

        "large binary frame".notNative in {
            val data = Span.fromUnsafe(Array.tabulate[Byte](65000)(i => (i % 256).toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(data)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) => discard(assert(d.size == 65000))
                            case other                           => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.unit
        }

        "interleaved text and binary".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a"))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1)))))
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b")))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("a")))))
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) => discard(assert(d(0) == 1.toByte))
                            case other                           => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("b")))))
                }
            }.unit
        }

        "unicode text roundtrip".notNative in {
            val text = "Hello \uD83D\uDE00 \u4F60\u597D"
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(text))))
                    }
                }
            }.unit
        }

        "binary all byte values".notNative in {
            val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(_.toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(allBytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 256))
                                var i = 0
                                while i < 256 do
                                    discard(assert(d(i) == i.toByte))
                                    i += 1
                                end while
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.unit
        }

        "single byte binary".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](42)))).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 1))
                                discard(assert(d(0) == 42.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.unit
        }

        "single char text".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("x")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("x"))))
                    }
                }
            }.unit
        }
    }

    // ==================== Close handshake ====================

    "close handshake" - {

        "client initiates close".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hi")).andThen(ws.take()).andThen(ws.close().map(_ =>
                        succeed("close completes without error")
                    ))
                }
            }.unit
        }

        "server initiates close".notNative in {
            withWsServer(HttpHandler.webSocket("ws/close") { (_, ws) =>
                ws.take().andThen(ws.close())
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/close") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                        Abort.run[Closed](ws.take()).map(result =>
                            discard(assert(result.isFailure || result.isPanic))
                        )
                    }
                }
            }.unit
        }

        "close code preserved".notNative in {
            withWsServer(HttpHandler.webSocket("ws/closecode") { (_, ws) =>
                ws.take().andThen(ws.close(4000, "app error"))
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/closecode") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                        Abort.run[Closed](ws.take()).andThen {
                            ws.closeReason.map(reason =>
                                assert(reason == Present((4000, "app error")) || reason.isDefined, s"expected close code 4000, got $reason")
                            )
                        }
                    }
                }
            }.unit
        }

        "take after server close fails with Closed".notNative in {
            withWsServer(HttpHandler.webSocket("ws/close-fast") { (_, ws) =>
                ws.close()
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/close-fast") { ws =>
                    // take blocks until close arrives
                    Abort.run[Closed](ws.take()).map(r =>
                        discard(assert(r.isFailure || r.isPanic))
                    )
                }
            }.unit
        }

        "close after exchange".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take())
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b"))).andThen(ws.take())
                        .andThen(ws.close().map(_ => succeed("close completes after exchange")))
                }
            }.unit
        }
    }

    // ==================== Backpressure ====================

    "backpressure" - {

        "bidirectional concurrent exchange".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    val count = 100
                    Abort.run[Closed] {
                        Async.gather(
                            Kyo.foreach(1 to count)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).unit,
                            Kyo.foreach(1 to count)(i =>
                                ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"msg$i"))))
                            ).unit
                        )
                    }.unit
                }
            }.unit
        }

        "handler that only sends".notNative in {
            withWsServer(HttpHandler.webSocket("ws/send-only") { (_, ws) =>
                Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"server$i"))).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/send-only") { ws =>
                    Kyo.foreach(1 to 5)(i =>
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"server$i"))))
                    ).unit
                }
            }.unit
        }

        "handler that only reads".notNative in {
            withWsServer(HttpHandler.webSocket("ws/read-only") { (_, ws) =>
                // Read 3 messages without sending any response
                Kyo.foreach(1 to 3)(_ => ws.take()).unit
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/read-only") { ws =>
                    Kyo.foreach(1 to 3)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen {
                        // Server consumed all, now it returns and connection closes
                        Abort.run[Closed](ws.take()).map(_ => succeed("server read all messages and closed"))
                    }
                }
            }.unit
        }
    }

    // ==================== Disconnect scenarios ====================

    "disconnect scenarios" - {

        "server handler throws".notNative in {
            withWsServer(HttpHandler.webSocket("ws/bad") { (_, _) =>
                throw new RuntimeException("server boom")
            }) { url =>
                Abort.run[HttpException] {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/bad") { ws =>
                        Abort.run[Closed](ws.take()).map(_ => succeed("connection handled server throw gracefully"))
                    }
                }.map(_ => succeed("server throw propagated as HttpException or connection closed"))
            }.unit
        }

        "server handler returns immediately".notNative in {
            withWsServer(HttpHandler.webSocket("ws/empty") { (_, _) =>
                ()
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/empty") { ws =>
                    Abort.run[Closed](ws.take()).map(r =>
                        discard(assert(r.isFailure || r.isPanic))
                    )
                }
            }.unit
        }

        "server handler Abort.fails with Closed".notNative in {
            withWsServer(HttpHandler.webSocket("ws/abort") { (_, _) =>
                Abort.fail(Closed("test", Frame.internal))
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/abort") { ws =>
                    Abort.run[Closed](ws.take()).map(r =>
                        discard(assert(r.isFailure || r.isPanic))
                    )
                }
            }.unit
        }

        "multiple client connections".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.gather(
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("a")))
                        )
                    },
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("b")))
                        )
                    },
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("c")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("c")))
                        )
                    }
                ).unit
            }.unit
        }

        "5 concurrent clients".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 5) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text(s"client$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text(s"client$i")))
                        )
                    }
                }.unit
            }.unit
        }

        "rapid connect disconnect cycles".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 10) { i =>
                    Abort.run[HttpException] {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text(s"cycle$i")).andThen {
                                ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"cycle$i"))))
                            }
                        }
                    }.unit
                }.unit
            }.unit
        }

        "client disconnects mid-stream".notNative in {
            // Server runs an infinite producer loop until either ws.put raises Closed (outbound
            // closed on wire failure) or ws.onPeerClose fires (client disconnect observed). Either
            // signal terminates the loop and releases serverDone.
            Latch.init(1).map { serverDone =>
                withWsServer(HttpHandler.webSocket("ws/infinite") { (_, ws) =>
                    Sync.ensure(serverDone.release) {
                        val producer: Unit < (Async & Abort[Closed]) =
                            Loop.foreach {
                                ws.put(HttpWebSocket.Payload.Text("tick")).andThen(Loop.continue)
                            }
                        Abort.recover[Closed](_ => ()) {
                            Async.race(producer, ws.onPeerClose).unit
                        }
                    }
                }) { url =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/infinite") { ws =>
                        Kyo.foreach(1 to 3)(_ => ws.take()).unit
                    }.andThen {
                        serverDone.await.map(_ => succeed("server cleanup ran after client disconnect"))
                    }
                }
            }
        }

        "client closes before reading anything".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.close().map(_ => succeed("close without reading any messages completes cleanly"))
                }
            }.unit
        }

        "client sends then immediately closes".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("bye")).andThen(ws.close().map(_ =>
                        succeed("send then close completes cleanly")
                    ))
                }
            }.unit
        }
    }

    // ==================== HTTP integration ====================

    "HTTP integration" - {

        "websocket alongside http handlers".notNative in {
            val httpHandler = HttpHandler.getText("api/hello") { _ => "world" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/hello").map(text =>
                    discard(assert(text == "world"))
                ).andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("test")).andThen {
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("test"))))
                        }
                    }
                }
            }.unit
        }

        "http after websocket on same server".notNative in {
            val httpHandler = HttpHandler.getText("api/ping") { _ => "pong" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).unit
                }.andThen {
                    HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/ping").map(text =>
                        discard(assert(text == "pong"))
                    )
                }
            }.unit
        }

        "multiple http and ws requests interleaved".notNative in {
            val httpHandler = HttpHandler.getText("api/count") { _ => "ok" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/count").andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("1")).andThen(ws.take()).unit
                    }
                }.andThen {
                    HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/count")
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("2")).andThen(ws.take()).map(_ =>
                            succeed("interleaved http+ws requests all complete")
                        )
                    }
                }
            }.unit
        }

        "path routing".notNative in {
            withWsServer(
                HttpHandler.webSocket("ws/echo1")(echo),
                HttpHandler.webSocket("ws/echo2") { (_, ws) =>
                    ws.stream.map {
                        case HttpWebSocket.Payload.Text(data) => HttpWebSocket.Payload.Text(s"echo2:$data")
                        case other                            => other
                    }.foreach(ws.put)
                }
            ) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo1") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("a")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo2") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("echo2:b")))
                        )
                    }
                }
            }.unit
        }

        "three different ws endpoints".notNative in {
            withWsServer(
                HttpHandler.webSocket("ws/a") { (_, ws) =>
                    ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("from-a")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                },
                HttpHandler.webSocket("ws/b") { (_, ws) =>
                    ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("from-b")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                },
                HttpHandler.webSocket("ws/c") { (_, ws) =>
                    ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("from-c")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                }
            ) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/a") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("x")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("from-a")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/b") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("x")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("from-b")))
                        )
                    }
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/c") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("x")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("from-c")))
                        )
                    }
                }
            }.unit
        }

        "request headers accessible".notNative in {
            withWsServer(HttpHandler.webSocket("ws/auth") { (req, ws) =>
                val auth = req.headers.get("Authorization").getOrElse("none")
                ws.put(HttpWebSocket.Payload.Text(auth)).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }) { url =>
                HttpClient.webSocket(
                    s"ws://${url.host}:${url.port}/ws/auth",
                    HttpHeaders.empty.add("Authorization", "Bearer token123"),
                    HttpWebSocket.Config()
                ) { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("Bearer token123"))))
                }
            }.unit
        }
    }

    // ==================== Server handler patterns ====================

    "server handler patterns" - {

        "handler transforms messages".notNative in {
            withWsServer(HttpHandler.webSocket("ws/upper") { (_, ws) =>
                ws.stream.map {
                    case HttpWebSocket.Payload.Text(data) => HttpWebSocket.Payload.Text(data.toUpperCase)
                    case other                            => other
                }.foreach(ws.put)
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/upper") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("HELLO"))))
                    }
                }
            }.unit
        }

        "handler sends greeting then echoes".notNative in {
            withWsServer(HttpHandler.webSocket("ws/greet") { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Text("welcome")).andThen {
                    ws.stream.foreach(ws.put)
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/greet") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("welcome")))).andThen {
                        ws.put(HttpWebSocket.Payload.Text("hi")).andThen {
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hi"))))
                        }
                    }
                }
            }.unit
        }

        "handler responds with count".notNative in {
            withWsServer(HttpHandler.webSocket("ws/count") { (_, ws) =>
                AtomicRef.init(0).map { counter =>
                    ws.stream.foreach { _ =>
                        counter.getAndUpdate(_ + 1).map { n =>
                            ws.put(HttpWebSocket.Payload.Text(s"count:$n"))
                        }
                    }
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/count") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("count:0")))
                    ).andThen {
                        ws.put(HttpWebSocket.Payload.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("count:1")))
                        )
                    }.andThen {
                        ws.put(HttpWebSocket.Payload.Text("c")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("count:2")))
                        )
                    }
                }
            }.unit
        }
    }

    // ==================== Server lifecycle ====================

    "server lifecycle" - {

        "server handles connection after previous ws closed".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("first")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("first")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("second")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("second")))
                        )
                    }
                }
            }.unit
        }

        "three sequential connections".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 3) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text(s"conn$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text(s"conn$i")))
                        )
                    }
                }.unit
            }.unit
        }

        "sequential then concurrent".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                // Sequential first
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("seq")).andThen(ws.take()).unit
                }.andThen {
                    // Then concurrent
                    Async.gather(
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text("par1")).andThen(ws.take()).unit
                        },
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text("par2")).andThen(ws.take()).unit
                        }
                    ).map(_ => succeed("sequential then concurrent connections all complete"))
                }
            }.unit
        }
    }

    // ==================== Edge cases from zio-http/sttp/tapir bugs ====================

    "edge cases" - {

        // zio-http #2737, tapir #3685: server sends before client is ready
        "server sends immediately on connect".notNative in {
            withWsServer(HttpHandler.webSocket("ws/eager") { (_, ws) =>
                // Send immediately without waiting for client message
                ws.put(HttpWebSocket.Payload.Text("eager1"))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("eager2")))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("eager3")))
                    .andThen(Abort.run[Closed](ws.take()).unit) // wait for client
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/eager") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("eager1"))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("eager2")))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("eager3")))))
                }
            }.unit
        }

        // sttp #901: large unicode payload inflates byte count beyond frame limit
        "large unicode payload".notNative in {
            // 3500 emoji chars × 4 bytes each = ~14000 bytes
            val text = "\uD83D\uDE00" * 3500
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(text))))
                    }
                }
            }.unit
        }

        // zio-http #1147: close handler side-effects must execute
        "server cleanup runs on normal close".notNative in {
            Latch.init(1).map { cleanupDone =>
                withWsServer(HttpHandler.webSocket("ws/cleanup") { (_, ws) =>
                    Sync.ensure(cleanupDone.release) {
                        ws.stream.foreach(ws.put)
                    }
                }) { url =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/cleanup") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("hi")).andThen(ws.take()).unit
                    }.andThen {
                        cleanupDone.await.map(_ => succeed("cleanup ran: latch released on normal WS close"))
                    }
                }
            }
        }

        // zio-http #1147: close handler runs even when handler throws
        "server cleanup runs on handler error".notNative in {
            Latch.init(1).map { cleanupDone =>
                withWsServer(HttpHandler.webSocket("ws/cleanup-err") { (_, ws) =>
                    Sync.ensure(cleanupDone.release) {
                        ws.take().andThen {
                            throw new RuntimeException("handler error")
                        }
                    }
                }) { url =>
                    Abort.run[HttpException] {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/cleanup-err") { ws =>
                            ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                                Abort.run[Closed](ws.take()).unit
                            }
                        }
                    }.unit.andThen {
                        cleanupDone.await.map(_ => succeed("cleanup ran: latch released even after handler error"))
                    }
                }
            }
        }

        // tapir #3776: in-flight messages must arrive before close takes effect
        "messages before close are delivered".notNative in {
            withWsServer(HttpHandler.webSocket("ws/send-then-close") { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Text("msg1"))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("msg2")))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("msg3")))
                    .andThen(Abort.run[Closed](ws.take()).unit) // wait for client to read
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/send-then-close") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("msg1"))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("msg2")))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("msg3")))))
                }
            }.unit
        }

        // zio-http #623: many concurrent connections must all succeed
        "10 concurrent connections".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 10) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text(s"c$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text(s"c$i")))
                        )
                    }
                }.unit
            }.unit
        }

        // zio-http #623: many concurrent connections with multi-message exchange
        "10 concurrent connections with multiple messages each".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 10) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        Kyo.foreach(1 to 5) { j =>
                            ws.put(HttpWebSocket.Payload.Text(s"c${i}m$j")).andThen(ws.take()).map(f =>
                                discard(assert(f == HttpWebSocket.Payload.Text(s"c${i}m$j")))
                            )
                        }.unit
                    }
                }.unit
            }.unit
        }

        // zio-http #1004: binary frame data integrity across echo
        "binary echo preserves exact bytes".notNative in {
            // Pattern that could trigger reference counting bugs
            val data1 = Span.fromUnsafe(Array.fill[Byte](100)(0x7f.toByte))
            val data2 = Span.fromUnsafe(Array.fill[Byte](200)(0x80.toByte))
            val data3 = Span.fromUnsafe(Array.fill[Byte](50)(0xff.toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(data1))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(data2)))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(data3)))
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 100))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0x7f.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 200))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0x80.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 50))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0xff.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                }
            }.unit
        }

        // Derived from zio-http #2977: WS and HTTP on same server must not interfere
        "interleaved ws and http requests under load".notNative in {
            val httpHandler = HttpHandler.getText("api/v") { _ => "ok" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 5) { i =>
                    if i % 2 == 0 then
                        HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/v").map(t =>
                            discard(assert(t == "ok"))
                        )
                    else
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text(s"ws$i")).andThen(ws.take()).map(f =>
                                discard(assert(f == HttpWebSocket.Payload.Text(s"ws$i")))
                            )
                        }
                }.unit
            }.unit
        }

        // Regression: rapid open/close without any data exchange
        "rapid open close no data".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 20) { _ =>
                    Abort.run[HttpException] {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { _ =>
                            succeed("connect and immediately return: rapid open/close does not crash")
                        }
                    }.unit
                }.unit
            }.unit
        }

        // Client sends and waits for ack before closing — server must process the message
        "server processes message with client ack".notNative in {
            withWsServer(HttpHandler.webSocket("ws/ack") { (_, ws) =>
                ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("ack")))
                    .andThen(Abort.run[Closed](ws.take()).unit)
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/ack") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("msg")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("ack"))))
                    }
                }
            }.unit
        }
    }

    // ==================== HttpWebSocket.connect (local, no network) ====================

    def echo(ws: HttpWebSocket)(using Frame): Unit < Async =
        ws.stream.foreach(ws.put).handle(Abort.run[Closed]).unit

    def echoWithReq(req: HttpRequest[Any], ws: HttpWebSocket)(using Frame): Unit < Async =
        echo(ws)

    def connectTest(
        p1: HttpWebSocket => Unit < (Async & Abort[Closed]),
        p2: HttpWebSocket => Unit < (Async & Abort[Closed])
    )(using Frame): Unit < (Async & Scope) =
        HttpWebSocket.connect(p1, p2)

    "HttpWebSocket.connect" - {

        "text echo".notNative in {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen(
                        ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text("hello"))))
                    )
            ).unit
        }

        "binary echo".notNative in {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Binary(bytes)).andThen(
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 5))
                                discard(assert(data(0) == 1.toByte))
                                discard(assert(data(4) == 5.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).unit
        }

        "multiple messages in order".notNative in {
            connectTest(
                echo,
                ws =>
                    Kyo.foreach(1 to 10)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen(
                        Kyo.foreach(1 to 10)(i =>
                            ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text(s"msg$i"))))
                        ).unit
                    )
            ).unit
        }

        "empty text".notNative in {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("")).andThen(
                        ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text(""))))
                    )
            ).unit
        }

        "empty binary".notNative in {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Binary(Span.empty[Byte])).andThen(
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) => discard(assert(data.size == 0))
                            case other                              => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).unit
        }

        "interleaved text and binary".notNative in {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("a"))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1)))))
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b")))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("a")))))
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) => discard(assert(d(0) == 1.toByte))
                            case other                           => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("b")))))
            ).unit
        }

        "unicode text".notNative in {
            val text = "Hello \uD83D\uDE00 \u4F60\u597D"
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen(
                        ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text(text))))
                    )
            ).unit
        }

        "binary with all byte values".notNative in {
            val allBytes = Array.tabulate[Byte](256)(i => i.toByte)
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(allBytes))).andThen(
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 256))
                                var i = 0
                                while i < 256 do
                                    discard(assert(data(i) == i.toByte))
                                    i += 1
                                end while
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).unit
        }

        "close propagation".notNative in {
            connectTest(
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    ),
                ws => ws.close().map(_ => succeed("close propagates to peer"))
            ).unit
        }

        "close reason preserved".notNative in {
            // Both sides share the close reason ref via doClose — check it after connect returns
            val closeReasonRef = AtomicRef.init[Maybe[(Int, String)]](Absent)
            closeReasonRef.map { ref =>
                val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                    ref.set(Present((code, reason)))
                Channel.init[HttpWebSocket.Payload](32).map { ch1to2 =>
                    Channel.init[HttpWebSocket.Payload](32).map { ch2to1 =>
                        Fiber.Promise.init[Unit, Any].map { pc1 =>
                            Fiber.Promise.init[Unit, Any].map { pc2 =>
                                val ws1 = new HttpWebSocket(ch2to1, ch1to2, ref, pc1, closeFn)
                                val ws2 = new HttpWebSocket(ch1to2, ch2to1, ref, pc2, closeFn)
                                Sync.ensure(ch1to2.close.unit.andThen(ch2to1.close.unit)) {
                                    Async.raceFirst(
                                        Abort.run[Closed](ws1.take()).unit,
                                        Abort.run[Closed](ws2.close(1001, "going away")).unit
                                    ).unit
                                }.andThen(
                                    ref.get.map(r => discard(assert(r == Present((1001, "going away")))))
                                ).unit
                            }
                        }
                    }
                }
            }
        }

        "close with custom code".notNative in {
            val closeReasonRef = AtomicRef.init[Maybe[(Int, String)]](Absent)
            closeReasonRef.map { ref =>
                val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                    ref.set(Present((code, reason)))
                Channel.init[HttpWebSocket.Payload](32).map { ch1to2 =>
                    Channel.init[HttpWebSocket.Payload](32).map { ch2to1 =>
                        Fiber.Promise.init[Unit, Any].map { pc1 =>
                            Fiber.Promise.init[Unit, Any].map { pc2 =>
                                val ws1 = new HttpWebSocket(ch2to1, ch1to2, ref, pc1, closeFn)
                                val ws2 = new HttpWebSocket(ch1to2, ch2to1, ref, pc2, closeFn)
                                Sync.ensure(ch1to2.close.unit.andThen(ch2to1.close.unit)) {
                                    Async.raceFirst(
                                        Abort.run[Closed](ws1.take()).unit,
                                        Abort.run[Closed](ws2.close(4000, "app error")).unit
                                    ).unit
                                }.andThen(
                                    ref.get.map(r => discard(assert(r == Present((4000, "app error")))))
                                ).unit
                            }
                        }
                    }
                }
            }
        }

        "put after close".notNative in {
            connectTest(
                echo,
                ws =>
                    ws.close().andThen(
                        Abort.run[Closed](ws.put(HttpWebSocket.Payload.Text("fail"))).map(result =>
                            assert(result.isFailure || result.isPanic, s"put after close should fail with Closed, got: $result")
                        )
                    )
            ).map(_ => succeed("connectTest completes after put-after-close interaction"))
        }

        "take after close".notNative in {
            connectTest(
                ws => ws.close().map(_ => succeed("close completes cleanly")),
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    )
            ).unit
        }

        "double close is safe".notNative in {
            connectTest(
                ws => ws.close().andThen(ws.close()).unit,
                ws => Abort.run[Closed](ws.take()).unit
            ).map(_ => succeed("double close is safe: connectTest completes without error"))
        }

        "one party is infinite loop".notNative in {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("hi")).andThen(
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hi"))))
                    )
            ).unit
        }

        "one party throws".notNative in {
            connectTest(
                _ => throw new RuntimeException("boom"),
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    )
            ).map(_ => succeed("one-party throw is handled: other party observes close or panic"))
        }

        "both parties throw".notNative in {
            connectTest(
                _ => throw new RuntimeException("boom1"),
                _ => throw new RuntimeException("boom2")
            ).map(_ => succeed("both parties throwing is handled without propagation"))
        }

        "empty party".notNative in {
            connectTest(
                _ => succeed("empty party: function returns without action"),
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    )
            ).unit
        }

        "concurrent bidirectional".notNative in {
            connectTest(
                ws =>
                    Async.gather(
                        Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"a$i"))).unit,
                        Kyo.foreach(1 to 5)(_ => Abort.run[Closed](ws.take())).unit
                    ).unit,
                ws =>
                    Async.gather(
                        Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"b$i"))).unit,
                        Kyo.foreach(1 to 5)(_ => Abort.run[Closed](ws.take())).unit
                    ).unit
            ).map(_ => succeed("concurrent bidirectional exchange completes"))
        }

        "poll returns Absent when empty".notNative in {
            connectTest(
                _ => succeed("p1 returns immediately"),
                ws =>
                    Abort.run[Closed](ws.poll()).map {
                        case kyo.Result.Success(v) => assert(v == Absent, s"poll on empty ws should return Absent, got $v")
                        case _                     => succeed("Closed is acceptable since p1 returned")
                    }
            ).unit
        }

        "reuse server handler fn".notNative in {
            HttpWebSocket.connect(
                echoWithReq,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("test")).andThen(
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("test"))))
                    )
            ).unit
        }

        "many small messages".notNative in {
            val count = 200
            connectTest(
                echo,
                ws =>
                    // Send and receive concurrently to avoid deadlock on bounded channel
                    val sender = Kyo.foreach(1 to count)(i => ws.put(HttpWebSocket.Payload.Text(i.toString)))
                    val receiver = Kyo.foreach(1 to count)(i =>
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(i.toString))))
                    )
                    Abort.run[Closed](Async.gather(sender.unit, receiver.unit)).unit
            ).unit
        }

        "stream terminates on close".notNative in {
            connectTest(
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("a"))
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b")))
                        .andThen(ws.close().map(_ => succeed("close terminates the stream"))),
                ws =>
                    ws.stream.run.map(frames =>
                        discard(assert(frames.size <= 2))
                    )
            ).unit
        }
    }

    // ==================== WebSocket over Unix socket ====================

    private def withWsUnixServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: String => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        tempSocketPath().map { sockPath =>
            val config = HttpServerConfig.default.unixSocket(sockPath)
            Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                HttpServer.init(config)(handlers*).map { server =>
                    test(sockPath)
                }
            }
        }
    end withWsUnixServer

    private def mkWsUrl(socketPath: String, wsPath: String): String =
        mkUrl(socketPath, wsPath)

    "basic messaging over Unix socket" - {

        "text echo over Unix socket".notNative in {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hello"))))
                    }
                }
            }.unit
        }

        "binary echo over Unix socket".notNative in {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(bytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 5))
                                discard(assert(data(0) == 1.toByte))
                                discard(assert(data(4) == 5.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.unit
        }

        "multiple sequential messages over Unix socket".notNative in {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen {
                        Kyo.foreach(1 to 5)(i =>
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"msg$i"))))
                        ).unit
                    }
                }
            }.unit
        }

        "server pushes message to client over Unix socket".notNative in {
            val serverHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) =>
                    ws.put(HttpWebSocket.Payload.Text("server-hello")).andThen {
                        ws.take().unit // wait for client to respond before handler returns
                    }
            withWsUnixServer(HttpHandler.webSocket("ws/push")(serverHandler)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/push")
                HttpClient.webSocket(url) { ws =>
                    ws.take().map { f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("server-hello")))
                    }.andThen {
                        ws.put(HttpWebSocket.Payload.Text("ack")) // let server handler return
                    }
                }
            }.unit
        }
    }

    "close and lifecycle over Unix socket" - {

        "client close over Unix socket".notNative in {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hi")).andThen {
                        ws.take().andThen {
                            ws.close(1000, "bye").map(_ => succeed("client close over Unix socket completes"))
                        }
                    }
                }
            }.unit
        }

        "server close over Unix socket".notNative in {
            val serverHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) => ws.take().andThen(ws.close())
            withWsUnixServer(HttpHandler.webSocket("ws/srv-close")(serverHandler)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/srv-close")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                        Abort.run[Closed](ws.take()).map(result =>
                            discard(assert(result.isFailure || result.isPanic))
                        )
                    }
                }
            }.unit
        }

        "function return closes connection over Unix socket".notNative in {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("done")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("done"))))
                    }
                }
            }.unit
        }
    }

    "edge cases over Unix socket" - {

        "large text message (64KB) over Unix socket".notNative in {
            val largeText = "x" * 65536
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text(largeText)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(largeText))))
                    }
                }
            }.unit
        }

        "empty text message over Unix socket".notNative in {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(""))))
                    }
                }
            }.unit
        }

        "binary with all byte values over Unix socket".notNative in {
            val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(i => i.toByte))
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(allBytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 256))
                                var i = 0
                                while i < 256 do
                                    discard(assert(data(i) == i.toByte))
                                    i += 1
                                end while
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.unit
        }
    }

    // ==================== Config ====================

    "config" - {

        "webSocket respects baseUrl config".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                // Set baseUrl to the server URL, then connect with a path-only string.
                // The bug: webSocket ignores HttpClientConfig, so baseUrl is never applied
                // and the relative path fails to connect.
                HttpClient.withConfig(_.baseUrl(s"ws://${url.host}:${url.port}")) {
                    HttpClient.webSocket("/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hello"))))
                        }
                    }
                }
            }.unit
        }

        "webSocket applies config and scoped filters to handshake".notNative in {
            val handler = HttpHandler.webSocket("ws/filter") { (req, ws) =>
                val config = req.headers.get("X-Config").getOrElse("missing-config")
                val scoped = req.headers.get("X-Scoped").getOrElse("missing-scoped")
                ws.put(HttpWebSocket.Payload.Text(s"$config/$scoped")).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }
            withWsServer(handler) { url =>
                HttpClient.withConfig(
                    HttpClientConfig()
                        .filter(HttpFilter.client.addHeader("X-Config", "config"))
                ) {
                    HttpClient.withFilter(HttpFilter.client.addHeader("X-Scoped", "scoped")) {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/filter") { ws =>
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("config/scoped"))))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "webSocket preserves user effects through client filters".notNative in {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.withFilter(HttpFilter.client.addHeader("X-Scoped", "scoped")) {
                    Var.run(0) {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            Var.update[Int](_ + 1).andThen {
                                ws.put(HttpWebSocket.Payload.Text("var")).andThen {
                                    ws.take().map { payload =>
                                        discard(assert(payload == HttpWebSocket.Payload.Text("var")))
                                    }
                                }.andThen(Var.get[Int])
                            }
                        }.map { value =>
                            assert(value == 1)
                        }
                    }
                }
            }.unit
        }

        "webSocket preserves query string in handshake path".notNative in {
            val handler = HttpHandler.webSocket("ws/query") { (req, ws) =>
                val token = req.query("token").getOrElse("missing")
                ws.put(HttpWebSocket.Payload.Text(token)).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }
            withWsServer(handler) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/query?token=abc") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("abc"))))
                }
            }.andThen(succeed)
        }

        "webSocket respects connectTimeout config".notNative in {
            // 192.0.2.0/24 is TEST-NET-1 (RFC 5737) — routable but no host responds,
            // so a TCP connect will hang until timeout rather than fail immediately.
            HttpClient.withConfig(_.connectTimeout(100.millis)) {
                Abort.run[HttpException] {
                    HttpClient.webSocket("ws://192.0.2.1/ws") { _ => () }
                }.map { result =>
                    // webSocket must honor HttpClientConfig.connectTimeout — the call fails fast with an
                    // HttpException rather than hanging until the OS TCP timeout.
                    discard(assert(result.isFailure || result.isPanic))
                }
            }.unit
        }
    }

    "close lifecycle and subprotocol negotiation" - {

        "server-side ws.closeReason reflects the code and reason the client sent".notNative in {
            Fiber.Promise.init[Maybe[(Int, String)], Any].map { observed =>
                val handler = HttpHandler.webSocket("ws/srv-close") { (_, ws) =>
                    Abort.recover[Closed](_ => ()) {
                        Loop.foreach {
                            ws.take().andThen(Loop.continue)
                        }
                    }.andThen {
                        ws.closeReason.map(cr => observed.completeDiscard(Result.succeed(cr)))
                    }
                }
                withWsServer(handler) { url =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/srv-close") { ws =>
                        ws.close(4321, "client-bye").andThen(Async.sleep(300.millis))
                    }.andThen(observed.get).map { snapshot =>
                        discard(assert(snapshot.exists(_._1 == 4321), s"expected 4321, got $snapshot"))
                    }
                }
            }.unit
        }

        "ws.onPeerClose fires after a server-initiated close".notNative in {
            // Server immediately closes. The client awaits `ws.onPeerClose` and asserts it completes,
            // then inspects `closeReason` to confirm the close code and reason propagated. This is the
            // canonical pattern for producer-only handlers that need to observe peer-close.
            val handler = HttpHandler.webSocket("ws/srv-fast-close") { (_, ws) =>
                ws.close(4002, "go away")
            }
            withWsServer(handler) { url =>
                Async.timeout(2.seconds) {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/srv-fast-close") { ws =>
                        ws.onPeerClose.andThen {
                            ws.closeReason.map { reason =>
                                discard(assert(reason == Present((4002, "go away")), s"expected (4002, 'go away'), got: $reason"))
                            }
                        }
                    }
                }.unit
            }
        }

        "client emits a single Sec-WebSocket-Protocol when both headers and config.subprotocols are set".notNative in {
            // Caller-supplied header takes precedence over config.subprotocols. The server echoes back what it
            // saw in the upgrade request; we assert that value is the single header value the user provided and
            // not a comma-joined duplicate.
            val srvConfig = HttpWebSocket.Config(subprotocols = Seq("chat"))
            val handler = HttpHandler.webSocket("ws/proto-dedup", srvConfig) { (req, ws) =>
                val seen = req.headers.get("Sec-WebSocket-Protocol").toOption.getOrElse("ABSENT")
                ws.put(HttpWebSocket.Payload.Text(seen))
                    .andThen(Abort.recover[Closed](_ => ()) {
                        Loop.foreach(ws.take().andThen(Loop.continue))
                    }) // hold the WS open until the peer closes (so the client can take)
            }
            withWsServer(handler) { url =>
                HttpClient.webSocket(
                    s"ws://${url.host}:${url.port}/ws/proto-dedup",
                    headers = HttpHeaders.empty.add("Sec-WebSocket-Protocol", "chat"),
                    config = HttpWebSocket.Config(subprotocols = Seq("other"))
                ) { ws =>
                    ws.take().map { f =>
                        f match
                            case HttpWebSocket.Payload.Text(v) =>
                                discard(assert(v == "chat", s"expected exactly one Sec-WebSocket-Protocol value 'chat', got: $v"))
                            case other => fail(s"expected text frame, got $other")
                    }
                }
            }.unit
        }
    }

end HttpWebSocketTest
